import { INestApplication } from '@nestjs/common';
import { Test } from '@nestjs/testing';
import { eq } from 'drizzle-orm';
import { randomUUID } from 'node:crypto';
import request from 'supertest';
import { afterAll, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { AppModule } from '../src/app.module.js';
import {
  CUSTOMER_PERMISSIONS,
  EVIDENCE_PERMISSIONS,
  type PermissionCode,
} from '../src/auth/permissions.js';
import {
  customers,
  jobAudioNoteRemovals,
  jobAudioNotes,
  jobs,
  organizationMemberPermissions,
  organizationMembers,
  permissions,
  rolePermissions,
  userProfiles,
} from '../src/database/schema.js';
import { hashPassword } from '../src/users/password-hasher.js';
import { FakeObjectStorage } from './support/fake-object-storage.js';
import {
  createFoundationTestDatabase,
  createTestOrganization,
  createTestOrganizationRole,
  createTestUser,
  uniqueEmail,
  type FoundationTestDatabase,
} from './support/foundation-database.js';

const PASSWORD = 'servora-e2e-password';

/** One ISO-BMFF box: its size, its type and its payload. */
function box(type: string, payload: Buffer): Buffer {
  const header = Buffer.alloc(8);
  header.writeUInt32BE(payload.length + 8, 0);
  header.write(type, 4, 'latin1');
  return Buffer.concat([header, payload]);
}

/**
 * An audio-only MP4 of [seconds], which is the container the API accepts (`ADR-018` A2/A3).
 *
 * The fixture is a real container rather than a stub, because what is under test is the API reading the
 * recording: the length the limits apply to comes from the `mvhd` this builds.
 */
function audioMp4(seconds = 18): Buffer {
  const mvhd = Buffer.alloc(100);
  mvhd.writeUInt32BE(1000, 12);
  mvhd.writeUInt32BE(Math.round(seconds * 1000), 16);
  const hdlr = Buffer.alloc(24);
  hdlr.write('soun', 8, 'latin1');
  return Buffer.concat([
    box(
      'ftyp',
      Buffer.concat([
        Buffer.from('isom', 'latin1'),
        Buffer.from([0x00, 0x00, 0x02, 0x00]),
      ]),
    ),
    box(
      'moov',
      Buffer.concat([box('mvhd', mvhd), box('trak', box('mdia', box('hdlr', hdlr)))]),
    ),
  ]);
}

const AUDIO = audioMp4(18);

/**
 * `POST /jobs/:id/audio-notes`, `GET /jobs/:id/audio-notes/:audioNoteId/content` and
 * `POST /jobs/:id/audio-notes/:audioNoteId/removal` — Job audio evidence (`BR-091`, `ADR-018`).
 *
 * The object store is replaced by an in-memory fake, because what is under test is the API's own
 * behaviour: which key it derives, that the bytes and the length are validated before storage, that the
 * tenant boundary holds, and that a replay of the same operation id records the evidence once. The fake
 * is also what makes the failure paths assertable — a deployment whose provider is unreachable must
 * refuse the upload rather than report a success it cannot back (`BR-014`, `BR-015`).
 *
 * The routes are guarded by the evidence capabilities (`ADR-018` A7), so the authorization cases below
 * are written against those codes: audio needs neither `JOB_UPDATE` nor `customers.view`, and removing a
 * recording is a capability of its own that the default field role does not hold.
 */
describe('job audio notes (e2e)', () => {
  let app: INestApplication;
  let database: FoundationTestDatabase;
  let storage: FakeObjectStorage;
  let organizationId: string;
  let deviceSequence = 0;
  let jobNumberSequence = 0;

  beforeAll(async () => {
    database = await createFoundationTestDatabase();
    const organization = await createTestOrganization(database.db, {
      name: 'Job Audio Org',
    });
    database.cleanup.trackOrganization(organization.id);
    organizationId = organization.id;

    storage = new FakeObjectStorage();
    const moduleFixture = await Test.createTestingModule({
      imports: [AppModule],
    })
      .overrideProvider(FakeObjectStorage.token)
      .useValue(storage)
      .compile();
    app = moduleFixture.createNestApplication({ logger: false });
    await app.init();
  });

  afterAll(async () => {
    await app.close();
    await database.dispose();
  });

  // Each test starts from an empty store, so what was stored is asserted per test rather than as a
  // running total.
  beforeEach(() => {
    storage.clear();
  });

  async function signInFor(granted: readonly PermissionCode[]) {
    const email = uniqueEmail('job-audio');
    const user = await createTestUser(database.db, {
      email,
      passwordHash: await hashPassword(PASSWORD),
    });
    database.cleanup.trackUser(user.id);
    const role = await createTestOrganizationRole(database.db, organizationId);
    const [member] = await database.db
      .insert(organizationMembers)
      .values({ organizationId, userId: user.id, roleId: role.id })
      .returning();

    for (const code of granted) {
      await database.db.insert(organizationMemberPermissions).values({
        organizationId,
        memberId: member.id,
        permissionId: await permissionIdFor(code),
      });
    }

    deviceSequence += 1;
    const response = await request(app.getHttpServer())
      .post('/auth/sign-in')
      .send({
        email,
        password: PASSWORD,
        device: {
          platform: 'ANDROID',
          deviceId: `job-audio-device-${deviceSequence}`,
        },
      })
      .expect(200);

    return {
      membershipId: member.id,
      userId: user.id,
      accessToken: response.body.accessToken as string,
    };
  }

  /** Resolves a seeded capability, so a test can grant it to a role or directly to a member. */
  async function permissionIdFor(code: PermissionCode): Promise<string> {
    const [permission] = await database.db
      .select({ id: permissions.id })
      .from(permissions)
      .where(eq(permissions.code, code))
      .limit(1);
    if (permission === undefined) {
      throw new Error(`Missing permission seed: ${code}`);
    }
    return permission.id;
  }

  /**
   * Signs in a member whose capabilities come from a role rather than from direct grants.
   *
   * A member of a default system role is authorized by the role's grants (`BR-006`), so a test that means
   * "the default Technician" must not hand the member the capability directly.
   */
  async function signInWithRole(roleId: string) {
    const email = uniqueEmail('job-audio-role');
    const user = await createTestUser(database.db, {
      email,
      passwordHash: await hashPassword(PASSWORD),
    });
    database.cleanup.trackUser(user.id);
    const [member] = await database.db
      .insert(organizationMembers)
      .values({ organizationId, userId: user.id, roleId })
      .returning();

    deviceSequence += 1;
    const response = await request(app.getHttpServer())
      .post('/auth/sign-in')
      .send({
        email,
        password: PASSWORD,
        device: {
          platform: 'ANDROID',
          deviceId: `job-audio-role-device-${deviceSequence}`,
        },
      })
      .expect(200);

    return {
      membershipId: member.id,
      accessToken: response.body.accessToken as string,
    };
  }

  /** A Job owned by the caller's organization, with no Visit: a recording does not require one. */
  async function newJobInOrganization(
    targetOrganizationId: string = organizationId,
  ) {
    const [customer] = await database.db
      .insert(customers)
      .values({
        organizationId: targetOrganizationId,
        type: 'COMPANY',
        displayName: 'Northside Heating',
      })
      .returning();
    jobNumberSequence += 1;
    const [job] = await database.db
      .insert(jobs)
      .values({
        organizationId: targetOrganizationId,
        jobNumber: jobNumberSequence,
        customerId: customer.id,
        title: `Audio job ${jobNumberSequence}`,
      })
      .returning();
    return job;
  }

  /** Uploads one recording, as the Android client does: multipart with the metadata as fields. */
  function uploadAudio(
    jobId: string,
    accessToken: string,
    fields: {
      clientOperationId?: string;
      phase?: string;
      note?: string;
      capturedAt?: string;
      bytes?: Buffer;
      contentType?: string;
    } = {},
  ) {
    const call = request(app.getHttpServer())
      .post(`/jobs/${jobId}/audio-notes`)
      .set('Authorization', `Bearer ${accessToken}`)
      .field('clientOperationId', fields.clientOperationId ?? randomUUID())
      .field('phase', fields.phase ?? 'DURING_WORK');

    if (fields.note !== undefined) {
      call.field('note', fields.note);
    }
    if (fields.capturedAt !== undefined) {
      call.field('capturedAt', fields.capturedAt);
    }
    return call.attach('file', fields.bytes ?? AUDIO, {
      filename: 'note.m4a',
      contentType: fields.contentType ?? 'audio/mp4',
    });
  }

  /** Removes one recording, as the Manager surface does (`BR-089`). */
  function removeAudio(
    jobId: string,
    audioNoteId: string,
    accessToken: string,
    body: object = { reason: 'Recorded the wrong job' },
  ) {
    return request(app.getHttpServer())
      .post(`/jobs/${jobId}/audio-notes/${audioNoteId}/removal`)
      .set('Authorization', `Bearer ${accessToken}`)
      .send(body);
  }

  /** The activity as the ordinary read answers it (`BR-080`). */
  async function readActivity(jobId: string, accessToken: string, query = '') {
    const response = await request(app.getHttpServer())
      .get(`/jobs/${jobId}/activity${query}`)
      .set('Authorization', `Bearer ${accessToken}`)
      .expect(200);
    return response.body.events as Array<Record<string, unknown>>;
  }

  it('refuses an unauthenticated caller with 401', async () => {
    const job = await newJobInOrganization();

    await request(app.getHttpServer())
      .post(`/jobs/${job.id}/audio-notes`)
      .field('phase', 'DURING_WORK')
      .field('clientOperationId', randomUUID())
      .attach('file', AUDIO, {
        filename: 'note.m4a',
        contentType: 'audio/mp4',
      })
      .expect(401);

    await request(app.getHttpServer())
      .get(`/jobs/${job.id}/audio-notes/${randomUUID()}/content`)
      .expect(401);

    await request(app.getHttpServer())
      .post(`/jobs/${job.id}/audio-notes/${randomUUID()}/removal`)
      .send({ reason: 'Wrong job' })
      .expect(401);
  });

  it('refuses a caller without the audio capability with 403, and stores nothing', async () => {
    // A recording is added on `evidence.audio.add` and not on the photo kind's capability, and not on
    // `JOB_UPDATE` either: the catalogue is per kind (`ADR-018` A7).
    const session = await signInFor([EVIDENCE_PERMISSIONS.PHOTO_ADD]);
    const job = await newJobInOrganization();

    await uploadAudio(job.id, session.accessToken)
      .expect(403)
      .expect((response) => {
        expect(response.body.code).toBe('FORBIDDEN');
      });

    expect(storage.puts).toHaveLength(0);
  });

  it('refuses a caller without the read capability with 403', async () => {
    // Adding evidence does not grant reading it back (`ADR-015` D2), so the same per-kind split applies
    // to the read: a session with the audio capability alone cannot fetch the bytes.
    const session = await signInFor([EVIDENCE_PERMISSIONS.AUDIO_ADD]);
    const job = await newJobInOrganization();
    const clientOperationId = randomUUID();
    await uploadAudio(job.id, session.accessToken, {
      clientOperationId,
    }).expect(201);

    await request(app.getHttpServer())
      .get(`/jobs/${job.id}/audio-notes/${clientOperationId}/content`)
      .set('Authorization', `Bearer ${session.accessToken}`)
      .expect(403);
  });

  it('lets a member of the default Technician role record a note and read it back, but not remove it', async () => {
    // `BR-091` names the technician as the author of an audio note, so the default field role is the path
    // that matters. A test organization is created after the migrations, so the role's grants are written
    // here the way `0012_job_audio_notes.sql` and the development seed write them for a real
    // organization: through the role, never through direct member permissions (`BR-006`).
    const role = await createTestOrganizationRole(database.db, organizationId, {
      systemCode: 'TECHNICIAN',
    });
    for (const code of [
      EVIDENCE_PERMISSIONS.VIEW,
      EVIDENCE_PERMISSIONS.AUDIO_ADD,
    ] as const) {
      await database.db.insert(rolePermissions).values({
        organizationId,
        roleId: role.id,
        permissionId: await permissionIdFor(code),
      });
    }

    const session = await signInWithRole(role.id);
    const job = await newJobInOrganization();
    const clientOperationId = randomUUID();

    await uploadAudio(job.id, session.accessToken, {
      clientOperationId,
    }).expect(201);

    await request(app.getHttpServer())
      .get(`/jobs/${job.id}/audio-notes/${clientOperationId}/content`)
      .set('Authorization', `Bearer ${session.accessToken}`)
      .expect(200);

    // Taking accepted evidence out of ordinary use is a Manager capability (`BR-089`, `ADR-018` A7).
    await removeAudio(job.id, clientOperationId, session.accessToken).expect(403);
  });

  it('stores the recording under an API-derived key and projects it into the activity', async () => {
    const session = await signInFor([
      EVIDENCE_PERMISSIONS.AUDIO_ADD,
      EVIDENCE_PERMISSIONS.VIEW,
    ]);
    const job = await newJobInOrganization();
    const clientOperationId = randomUUID();

    const response = await uploadAudio(job.id, session.accessToken, {
      clientOperationId,
      phase: 'BEFORE_WORK',
      note: 'Customer described the noise',
      capturedAt: '2026-09-16T09:12:00.000Z',
    }).expect(201);

    expect(response.body.jobId).toBe(job.id);
    const events = response.body.events as Array<Record<string, unknown>>;
    expect(events).toHaveLength(1);
    expect(events[0]).toMatchObject({
      id: clientOperationId,
      kind: 'JOB_AUDIO_ADDED',
      audioNoteId: clientOperationId,
      audioPhase: 'BEFORE_WORK',
      // The length is the container's own, not something the client said (`ADR-018` A3).
      audioDurationSeconds: 18,
      body: 'Customer described the noise',
      visitSequence: null,
      audioRemovalReason: null,
    });

    // The key is derived by the API, provider-agnostic, and names the row's own operation id.
    expect(storage.puts).toHaveLength(1);
    expect(storage.puts[0]?.key).toBe(
      `evidence/job-audio-notes/${organizationId}/${job.id}/${clientOperationId}.m4a`,
    );
    expect(storage.puts[0]?.contentType).toBe('audio/mp4');

    const [row] = await database.db
      .select()
      .from(jobAudioNotes)
      .where(eq(jobAudioNotes.id, clientOperationId));
    expect(row?.phase).toBe('BEFORE_WORK');
    expect(row?.note).toBe('Customer described the noise');
    expect(row?.durationSeconds).toBe(18);
    expect(row?.byteSize).toBe(AUDIO.length);
    expect(row?.uploaderMembershipId).toBe(session.membershipId);
    expect(row?.capturedAt?.toISOString()).toBe('2026-09-16T09:12:00.000Z');
  });

  it('records a replay of the same operation id once and does not upload it twice', async () => {
    const session = await signInFor([
      EVIDENCE_PERMISSIONS.AUDIO_ADD,
      EVIDENCE_PERMISSIONS.VIEW,
    ]);
    const job = await newJobInOrganization();
    const clientOperationId = randomUUID();

    await uploadAudio(job.id, session.accessToken, {
      clientOperationId,
      phase: 'AFTER_WORK',
    }).expect(201);

    const replay = await uploadAudio(job.id, session.accessToken, {
      clientOperationId,
      phase: 'AFTER_WORK',
    }).expect(201);

    const events = replay.body.events as Array<{
      kind: string;
      audioNoteId: string;
    }>;
    expect(
      events.filter((event) => event.kind === 'JOB_AUDIO_ADDED'),
    ).toHaveLength(1);
    // The evidence was written once: a replay is answered, not re-stored (`BR-031`).
    expect(storage.puts).toHaveLength(1);

    const rows = await database.db
      .select()
      .from(jobAudioNotes)
      .where(eq(jobAudioNotes.jobId, job.id));
    expect(rows).toHaveLength(1);
  });

  it('refuses an operation id already used for another job', async () => {
    const session = await signInFor([EVIDENCE_PERMISSIONS.AUDIO_ADD]);
    const firstJob = await newJobInOrganization();
    const secondJob = await newJobInOrganization();
    const clientOperationId = randomUUID();

    await uploadAudio(firstJob.id, session.accessToken, {
      clientOperationId,
    }).expect(201);

    await uploadAudio(secondJob.id, session.accessToken, {
      clientOperationId,
    })
      .expect(409)
      .expect((response) => {
        expect(response.body.code).toBe('AUDIO_NOTE_OPERATION_REUSED');
      });
  });

  it('refuses a file that is not an audio-only MP4, an unknown phase, and a recording that is too long', async () => {
    const session = await signInFor([EVIDENCE_PERMISSIONS.AUDIO_ADD]);
    const job = await newJobInOrganization();

    // Bytes that are not a recording at all.
    await uploadAudio(job.id, session.accessToken, {
      bytes: Buffer.from('not a recording'),
      contentType: 'audio/mp4',
    })
      .expect(400)
      .expect((response) => {
        expect(response.body.code).toBe('VALIDATION_FAILED');
      });

    // A phase Servora does not have.
    await uploadAudio(job.id, session.accessToken, { phase: 'SOMETIME' })
      .expect(400)
      .expect((response) => {
        expect(response.body.code).toBe('VALIDATION_FAILED');
      });

    // Longer than the product limit, which is applied to the length the container declares (`ADR-018` A3).
    await uploadAudio(job.id, session.accessToken, { bytes: audioMp4(301) })
      .expect(400)
      .expect((response) => {
        expect(response.body.code).toBe('VALIDATION_FAILED');
      });

    expect(storage.puts).toHaveLength(0);
  });

  it('answers not found for a Job the caller organization does not own', async () => {
    const session = await signInFor([
      EVIDENCE_PERMISSIONS.AUDIO_ADD,
      EVIDENCE_PERMISSIONS.VIEW,
    ]);
    const otherOrganization = await createTestOrganization(database.db, {
      name: 'Other Job Audio Org',
    });
    database.cleanup.trackOrganization(otherOrganization.id);
    const foreignJob = await newJobInOrganization(otherOrganization.id);

    await uploadAudio(foreignJob.id, session.accessToken)
      .expect(404)
      .expect((response) => {
        expect(response.body.code).toBe('JOB_NOT_FOUND');
      });
  });

  it('serves the stored bytes through the API on the API port', async () => {
    const session = await signInFor([
      EVIDENCE_PERMISSIONS.AUDIO_ADD,
      EVIDENCE_PERMISSIONS.VIEW,
    ]);
    const job = await newJobInOrganization();
    const clientOperationId = randomUUID();
    await uploadAudio(job.id, session.accessToken, {
      clientOperationId,
    }).expect(201);

    const content = await request(app.getHttpServer())
      .get(`/jobs/${job.id}/audio-notes/${clientOperationId}/content`)
      .set('Authorization', `Bearer ${session.accessToken}`)
      .expect(200);

    expect(content.headers['content-type']).toContain('audio/mp4');
    expect(Buffer.compare(content.body as Buffer, AUDIO)).toBe(0);
  });

  it('answers not found for a recording that does not exist, and for one the Job does not hold', async () => {
    const session = await signInFor([
      EVIDENCE_PERMISSIONS.AUDIO_ADD,
      EVIDENCE_PERMISSIONS.VIEW,
    ]);
    const job = await newJobInOrganization();
    const otherJob = await newJobInOrganization();
    const clientOperationId = randomUUID();
    await uploadAudio(job.id, session.accessToken, {
      clientOperationId,
    }).expect(201);

    await request(app.getHttpServer())
      .get(`/jobs/${job.id}/audio-notes/${randomUUID()}/content`)
      .set('Authorization', `Bearer ${session.accessToken}`)
      .expect(404)
      .expect((response) => {
        expect(response.body.code).toBe('JOB_AUDIO_NOTE_NOT_FOUND');
      });

    await request(app.getHttpServer())
      .get(`/jobs/${otherJob.id}/audio-notes/${clientOperationId}/content`)
      .set('Authorization', `Bearer ${session.accessToken}`)
      .expect(404)
      .expect((response) => {
        expect(response.body.code).toBe('JOB_AUDIO_NOTE_NOT_FOUND');
      });
  });

  it('refuses the upload when the object store cannot store it, and records nothing', async () => {
    const session = await signInFor([
      EVIDENCE_PERMISSIONS.AUDIO_ADD,
      EVIDENCE_PERMISSIONS.VIEW,
    ]);
    const job = await newJobInOrganization();

    storage.failWith = FakeObjectStorage.unavailable();
    try {
      const response = await uploadAudio(job.id, session.accessToken).expect(
        503,
      );
      expect(response.body.code).toBe('STORAGE_UNAVAILABLE');
      // No provider vocabulary reaches the client (`dev.md` §7).
      expect(JSON.stringify(response.body)).not.toContain('ObjectStorageError');
    } finally {
      storage.failWith = null;
    }

    const rows = await database.db
      .select()
      .from(jobAudioNotes)
      .where(eq(jobAudioNotes.jobId, job.id));
    expect(rows).toHaveLength(0);
  });

  it('answers not found when the record exists but its bytes do not', async () => {
    const session = await signInFor([
      EVIDENCE_PERMISSIONS.AUDIO_ADD,
      EVIDENCE_PERMISSIONS.VIEW,
    ]);
    const job = await newJobInOrganization();
    const clientOperationId = randomUUID();
    await uploadAudio(job.id, session.accessToken, {
      clientOperationId,
    }).expect(201);

    storage.clear();

    await request(app.getHttpServer())
      .get(`/jobs/${job.id}/audio-notes/${clientOperationId}/content`)
      .set('Authorization', `Bearer ${session.accessToken}`)
      .expect(404)
      .expect((response) => {
        expect(response.body.code).toBe('JOB_AUDIO_NOTE_NOT_FOUND');
      });
  });

  /*
   * Taking accepted audio evidence out of ordinary use (`BR-088`, `BR-089`; `ADR-018` A7).
   *
   * The removal is a recorded business action, not a mutation: the recording's own row is append-only and
   * the removal is added beside it, so these cases assert both halves — that ordinary reads stop showing
   * the evidence, and that the record, the bytes and the history are still exactly what they were.
   */

  it('refuses a caller without the removal capability with 403, and records nothing', async () => {
    // Recording and reading evidence does not grant removing it (`BR-089`), and the photo kind's removal
    // capability is not the audio kind's (`ADR-018` A7).
    const session = await signInFor([
      CUSTOMER_PERMISSIONS.VIEW,
      EVIDENCE_PERMISSIONS.AUDIO_ADD,
      EVIDENCE_PERMISSIONS.VIEW,
      EVIDENCE_PERMISSIONS.PHOTO_REMOVE,
    ]);
    const job = await newJobInOrganization();
    const clientOperationId = randomUUID();
    await uploadAudio(job.id, session.accessToken, {
      clientOperationId,
    }).expect(201);

    await removeAudio(job.id, clientOperationId, session.accessToken).expect(403);

    const removals = await database.db
      .select()
      .from(jobAudioNoteRemovals)
      .where(eq(jobAudioNoteRemovals.jobAudioNoteId, clientOperationId));
    expect(removals).toHaveLength(0);
    // The evidence is untouched: it is still readable and still in the timeline.
    expect(await readActivity(job.id, session.accessToken)).toHaveLength(1);
  });

  it('records the removal and excludes the recording from the ordinary reads', async () => {
    const session = await signInFor([
      CUSTOMER_PERMISSIONS.VIEW,
      EVIDENCE_PERMISSIONS.AUDIO_ADD,
      EVIDENCE_PERMISSIONS.VIEW,
      EVIDENCE_PERMISSIONS.AUDIO_REMOVE,
    ]);
    await database.db.insert(userProfiles).values({
      userId: session.userId,
      firstName: 'Danielle',
      lastName: 'Manager',
      displayName: 'Danielle Manager',
    });
    const job = await newJobInOrganization();
    const clientOperationId = randomUUID();
    await uploadAudio(job.id, session.accessToken, {
      clientOperationId,
      note: 'Customer described the noise',
    }).expect(201);

    const response = await removeAudio(
      job.id,
      clientOperationId,
      session.accessToken,
      { reason: 'Recorded the wrong job' },
    ).expect(201);
    expect(response.body.jobId).toBe(job.id);

    // The removal is history, and Activity does not silently drop it (`BR-080`, `BR-089`).
    const events = response.body.events as Array<Record<string, unknown>>;
    expect(events.some((event) => event.kind === 'JOB_AUDIO_ADDED')).toBe(false);
    expect(events[0]).toMatchObject({
      kind: 'JOB_AUDIO_REMOVED',
      audioNoteId: clientOperationId,
      audioRemovalReason: 'Recorded the wrong job',
      actorName: 'Danielle Manager',
    });

    // Ordinary reads exclude it, and the bytes are refused (`BR-089`).
    const ordinary = await readActivity(job.id, session.accessToken);
    expect(ordinary.some((event) => event.kind === 'JOB_AUDIO_ADDED')).toBe(false);
    expect(ordinary.some((event) => event.kind === 'JOB_AUDIO_REMOVED')).toBe(true);

    await request(app.getHttpServer())
      .get(`/jobs/${job.id}/audio-notes/${clientOperationId}/content`)
      .set('Authorization', `Bearer ${session.accessToken}`)
      .expect(404)
      .expect((notFound) => {
        expect(notFound.body.code).toBe('JOB_AUDIO_NOTE_NOT_FOUND');
      });

    // The record itself is untouched: the removal is added history, never a rewrite (`BR-088`).
    const [row] = await database.db
      .select()
      .from(jobAudioNotes)
      .where(eq(jobAudioNotes.id, clientOperationId));
    expect(row?.note).toBe('Customer described the noise');
    expect(row?.durationSeconds).toBe(18);
  });

  it('keeps the removed recording in the audit/history context, with its own fields', async () => {
    const session = await signInFor([
      CUSTOMER_PERMISSIONS.VIEW,
      EVIDENCE_PERMISSIONS.AUDIO_ADD,
      EVIDENCE_PERMISSIONS.VIEW,
      EVIDENCE_PERMISSIONS.AUDIO_REMOVE,
    ]);
    const job = await newJobInOrganization();
    const clientOperationId = randomUUID();
    await uploadAudio(job.id, session.accessToken, {
      clientOperationId,
      phase: 'AFTER_WORK',
      note: 'Customer described the noise',
    }).expect(201);
    await removeAudio(job.id, clientOperationId, session.accessToken, {
      reason: 'Duplicate recording',
    }).expect(201);

    const events = await readActivity(
      job.id,
      session.accessToken,
      '?includeRemovedEvidence=true',
    );
    expect(events.find((event) => event.kind === 'JOB_AUDIO_ADDED')).toMatchObject({
      audioNoteId: clientOperationId,
      audioPhase: 'AFTER_WORK',
      audioDurationSeconds: 18,
      body: 'Customer described the noise',
    });
  });

  it('lets a member who may remove only audio ask for the audit/history context', async () => {
    // One flag for one question ("show me removed evidence"), so either kind's removal capability
    // authorizes it (`ADR-018` A7).
    const session = await signInFor([
      CUSTOMER_PERMISSIONS.VIEW,
      EVIDENCE_PERMISSIONS.AUDIO_REMOVE,
    ]);
    const job = await newJobInOrganization();

    await request(app.getHttpServer())
      .get(`/jobs/${job.id}/activity?includeRemovedEvidence=true`)
      .set('Authorization', `Bearer ${session.accessToken}`)
      .expect(200);
  });

  it('refuses the audit/history context to a caller who may not remove evidence', async () => {
    const session = await signInFor([
      CUSTOMER_PERMISSIONS.VIEW,
      EVIDENCE_PERMISSIONS.AUDIO_ADD,
      EVIDENCE_PERMISSIONS.VIEW,
    ]);
    const job = await newJobInOrganization();

    await request(app.getHttpServer())
      .get(`/jobs/${job.id}/activity?includeRemovedEvidence=true`)
      .set('Authorization', `Bearer ${session.accessToken}`)
      .expect(403);
  });

  it('refuses a second removal of the same recording', async () => {
    const session = await signInFor([
      CUSTOMER_PERMISSIONS.VIEW,
      EVIDENCE_PERMISSIONS.AUDIO_ADD,
      EVIDENCE_PERMISSIONS.VIEW,
      EVIDENCE_PERMISSIONS.AUDIO_REMOVE,
    ]);
    const job = await newJobInOrganization();
    const clientOperationId = randomUUID();
    await uploadAudio(job.id, session.accessToken, {
      clientOperationId,
    }).expect(201);
    await removeAudio(job.id, clientOperationId, session.accessToken).expect(201);

    // One recording has one removal and no restore is defined, so the repeat is a conflict with the
    // evidence's own state rather than a second removal (`BR-089`).
    await removeAudio(job.id, clientOperationId, session.accessToken)
      .expect(409)
      .expect((response) => {
        expect(response.body.code).toBe('JOB_AUDIO_NOTE_ALREADY_REMOVED');
      });
  });

  it('refuses a removal with no reason, and answers not found for a recording that does not exist', async () => {
    const session = await signInFor([
      CUSTOMER_PERMISSIONS.VIEW,
      EVIDENCE_PERMISSIONS.AUDIO_ADD,
      EVIDENCE_PERMISSIONS.AUDIO_REMOVE,
    ]);
    const job = await newJobInOrganization();
    const clientOperationId = randomUUID();
    await uploadAudio(job.id, session.accessToken, {
      clientOperationId,
    }).expect(201);

    await removeAudio(job.id, clientOperationId, session.accessToken, {})
      .expect(400)
      .expect((response) => {
        expect(response.body.code).toBe('VALIDATION_FAILED');
      });

    await removeAudio(job.id, randomUUID(), session.accessToken)
      .expect(404)
      .expect((response) => {
        expect(response.body.code).toBe('JOB_AUDIO_NOTE_NOT_FOUND');
      });
  });

  it('seeds the audio capabilities with their bilingual catalogue entries', async () => {
    const [add] = await database.db
      .select()
      .from(permissions)
      .where(eq(permissions.code, EVIDENCE_PERMISSIONS.AUDIO_ADD));
    expect(add?.nameEn).toBe('Add audio evidence');
    expect(add?.nameFr).toBe('Ajouter des preuves audio');
    expect(add?.descriptionEn).not.toBe('');
    expect(add?.descriptionFr).not.toBe('');

    const [remove] = await database.db
      .select()
      .from(permissions)
      .where(eq(permissions.code, EVIDENCE_PERMISSIONS.AUDIO_REMOVE));
    expect(remove?.nameEn).toBe('Remove audio evidence');
    expect(remove?.nameFr).toBe('Retirer des preuves audio');
  });
});
