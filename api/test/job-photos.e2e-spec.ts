import { INestApplication } from '@nestjs/common';
import { Test } from '@nestjs/testing';
import { eq } from 'drizzle-orm';
import { randomUUID } from 'node:crypto';
import request from 'supertest';
import { afterAll, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { AppModule } from '../src/app.module.js';
import {
  EVIDENCE_PERMISSIONS,
  type PermissionCode,
} from '../src/auth/permissions.js';
import {
  customers,
  jobPhotos,
  jobs,
  organizationMemberPermissions,
  organizationMembers,
  permissions,
  rolePermissions,
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

/** A minimal but real JPEG header: the API sniffs the bytes, so the fixture must be real enough. */
const JPEG = Buffer.concat([
  Buffer.from([0xff, 0xd8, 0xff, 0xe0]),
  Buffer.alloc(64, 0x11),
]);

/**
 * `POST /jobs/:id/photos` and `GET /jobs/:id/photos/:photoId/content` — Job photo evidence
 * (`BR-015`, `BR-027`, `BR-031`).
 *
 * The object store is replaced by an in-memory fake, because what is under test is the API's own
 * behaviour: which key it derives, that the bytes are validated before storage, that the tenant
 * boundary holds, and that a replay of the same operation id records the evidence once. The fake is
 * also what makes the failure paths assertable — a deployment whose provider is unreachable must
 * refuse the upload rather than report a success it cannot back (`BR-014`, `BR-015`).
 *
 * The routes are guarded by the evidence capabilities `EVIDENCE_PERMISSIONS` (`BR-006`, `BR-009`;
 * `docs/decisions/015-evidence-capabilities.md`), so the authorization cases below are written
 * against those codes: neither route needs `JOB_UPDATE` or `customers.view` any more.
 */
describe('job photos (e2e)', () => {
  let app: INestApplication;
  let database: FoundationTestDatabase;
  let storage: FakeObjectStorage;
  let organizationId: string;
  let deviceSequence = 0;
  let jobNumberSequence = 0;

  beforeAll(async () => {
    database = await createFoundationTestDatabase();
    const organization = await createTestOrganization(database.db, {
      name: 'Job Photo Org',
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
    const email = uniqueEmail('job-photo');
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
          deviceId: `job-photo-device-${deviceSequence}`,
        },
      })
      .expect(200);

    return {
      membershipId: member.id,
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
   * A member of a default system role is authorized by the role's grants (`BR-006`), so a test that
   * means "the default Technician" must not hand the member the capability directly.
   */
  async function signInWithRole(roleId: string) {
    const email = uniqueEmail('job-photo-role');
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
          deviceId: `job-photo-role-device-${deviceSequence}`,
        },
      })
      .expect(200);

    return {
      membershipId: member.id,
      accessToken: response.body.accessToken as string,
    };
  }

  /** A Job owned by the caller's organization, with no Visit: photos do not require one. */
  async function newJobInOrganization(
    targetOrganizationId: string = organizationId,
  ) {
    const [customer] = await database.db
      .insert(customers)
      .values({
        organizationId: targetOrganizationId,
        type: 'COMPANY',
        displayName: 'Riverside Mechanical',
      })
      .returning();
    jobNumberSequence += 1;
    const [job] = await database.db
      .insert(jobs)
      .values({
        organizationId: targetOrganizationId,
        jobNumber: jobNumberSequence,
        customerId: customer.id,
        title: `Photo job ${jobNumberSequence}`,
      })
      .returning();
    return job;
  }

  /** Uploads one photo, as the Android client does: multipart with the metadata as fields. */
  function uploadPhoto(
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
      .post(`/jobs/${jobId}/photos`)
      .set('Authorization', `Bearer ${accessToken}`)
      .field('clientOperationId', fields.clientOperationId ?? randomUUID())
      .field('phase', fields.phase ?? 'DURING_WORK');

    if (fields.note !== undefined) {
      call.field('note', fields.note);
    }
    if (fields.capturedAt !== undefined) {
      call.field('capturedAt', fields.capturedAt);
    }
    return call.attach('file', fields.bytes ?? JPEG, {
      filename: 'photo.jpg',
      contentType: fields.contentType ?? 'image/jpeg',
    });
  }

  it('refuses an unauthenticated caller with 401', async () => {
    const job = await newJobInOrganization();

    await request(app.getHttpServer())
      .post(`/jobs/${job.id}/photos`)
      .field('phase', 'DURING_WORK')
      .field('clientOperationId', randomUUID())
      .attach('file', JPEG, {
        filename: 'photo.jpg',
        contentType: 'image/jpeg',
      })
      .expect(401);

    await request(app.getHttpServer())
      .get(`/jobs/${job.id}/photos/${randomUUID()}/content`)
      .expect(401);
  });

  it('refuses a caller without the write capability with 403, and stores nothing', async () => {
    // Reading evidence does not grant adding it: the capability is per kind (`BR-006`, D1b).
    const session = await signInFor([EVIDENCE_PERMISSIONS.VIEW]);
    const job = await newJobInOrganization();

    await uploadPhoto(job.id, session.accessToken).expect(403);

    expect(storage.puts).toHaveLength(0);
  });

  it('refuses a caller without the read capability with 403', async () => {
    // Adding evidence does not grant reading it back, so a photo can be recorded by a member who
    // may not retrieve the bytes.
    const writer = await signInFor([EVIDENCE_PERMISSIONS.PHOTO_ADD]);
    const job = await newJobInOrganization();
    await uploadPhoto(job.id, writer.accessToken).expect(201);

    const reader = await signInFor([EVIDENCE_PERMISSIONS.PHOTO_ADD]);
    await request(app.getHttpServer())
      .get(`/jobs/${job.id}/photos/${randomUUID()}/content`)
      .set('Authorization', `Bearer ${reader.accessToken}`)
      .expect(403);
  });

  it('lets a member of the default Technician role add a photo and read it back', async () => {
    // `BR-015` names the technician as the evidence author, so the default field role is the path
    // that matters. A test organization is created after the migrations, so the role's grants are
    // written here the way `0010_evidence_permissions.sql` and the development seed write them for
    // a real organization: through the role, never through direct member permissions (`BR-006`).
    const role = await createTestOrganizationRole(database.db, organizationId, {
      systemCode: 'TECHNICIAN',
    });
    for (const code of [
      EVIDENCE_PERMISSIONS.VIEW,
      EVIDENCE_PERMISSIONS.PHOTO_ADD,
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

    await uploadPhoto(job.id, session.accessToken, {
      clientOperationId,
    }).expect(201);

    await request(app.getHttpServer())
      .get(`/jobs/${job.id}/photos/${clientOperationId}/content`)
      .set('Authorization', `Bearer ${session.accessToken}`)
      .expect(200);
  });

  it('stores the photo under an API-derived key and projects it into the activity', async () => {
    const session = await signInFor([
      EVIDENCE_PERMISSIONS.PHOTO_ADD,
      EVIDENCE_PERMISSIONS.VIEW,
    ]);
    const job = await newJobInOrganization();
    const clientOperationId = randomUUID();

    const response = await uploadPhoto(job.id, session.accessToken, {
      clientOperationId,
      phase: 'BEFORE_WORK',
      note: 'Panel before the repair',
      capturedAt: '2026-09-15T13:04:05.000Z',
    }).expect(201);

    expect(response.body.jobId).toBe(job.id);
    const events = response.body.events as Array<Record<string, unknown>>;
    expect(events).toHaveLength(1);
    expect(events[0]).toMatchObject({
      id: clientOperationId,
      kind: 'JOB_PHOTO_ADDED',
      photoId: clientOperationId,
      photoPhase: 'BEFORE_WORK',
      body: 'Panel before the repair',
      visitSequence: null,
    });

    // The key is derived by the API, provider-agnostic, and names the row's own operation id.
    expect(storage.puts).toHaveLength(1);
    expect(storage.puts[0]?.key).toBe(
      `evidence/job-photos/${organizationId}/${job.id}/${clientOperationId}.jpg`,
    );
    expect(storage.puts[0]?.contentType).toBe('image/jpeg');

    const [row] = await database.db
      .select()
      .from(jobPhotos)
      .where(eq(jobPhotos.id, clientOperationId));
    expect(row?.phase).toBe('BEFORE_WORK');
    expect(row?.note).toBe('Panel before the repair');
    expect(row?.uploaderMembershipId).toBe(session.membershipId);
    expect(row?.capturedAt?.toISOString()).toBe('2026-09-15T13:04:05.000Z');
  });

  it('records a replay of the same operation id once and does not upload it twice', async () => {
    const session = await signInFor([
      EVIDENCE_PERMISSIONS.PHOTO_ADD,
      EVIDENCE_PERMISSIONS.VIEW,
    ]);
    const job = await newJobInOrganization();
    const clientOperationId = randomUUID();

    await uploadPhoto(job.id, session.accessToken, {
      clientOperationId,
      phase: 'AFTER_WORK',
    }).expect(201);

    const replay = await uploadPhoto(job.id, session.accessToken, {
      clientOperationId,
      phase: 'AFTER_WORK',
    }).expect(201);

    const events = replay.body.events as Array<{
      kind: string;
      photoId: string;
    }>;
    expect(
      events.filter((event) => event.kind === 'JOB_PHOTO_ADDED'),
    ).toHaveLength(1);
    // The evidence was written once: a replay is answered, not re-stored (`BR-031`).
    expect(storage.puts).toHaveLength(1);

    const rows = await database.db
      .select()
      .from(jobPhotos)
      .where(eq(jobPhotos.jobId, job.id));
    expect(rows).toHaveLength(1);
  });

  it('refuses an operation id already used for another job', async () => {
    const session = await signInFor([EVIDENCE_PERMISSIONS.PHOTO_ADD]);
    const firstJob = await newJobInOrganization();
    const secondJob = await newJobInOrganization();
    const clientOperationId = randomUUID();

    await uploadPhoto(firstJob.id, session.accessToken, {
      clientOperationId,
    }).expect(201);

    await uploadPhoto(secondJob.id, session.accessToken, { clientOperationId })
      .expect(409)
      .expect((response) => {
        expect(response.body.code).toBe('PHOTO_OPERATION_REUSED');
      });
  });

  it('refuses bytes that are not an image, and a phase Servora does not have', async () => {
    const session = await signInFor([EVIDENCE_PERMISSIONS.PHOTO_ADD]);
    const job = await newJobInOrganization();

    await uploadPhoto(job.id, session.accessToken, {
      bytes: Buffer.from('this is not a photo'),
      contentType: 'image/jpeg',
    })
      .expect(400)
      .expect((response) => {
        expect(response.body.code).toBe('VALIDATION_FAILED');
      });

    await uploadPhoto(job.id, session.accessToken, { phase: 'SOMETIME' })
      .expect(400)
      .expect((response) => {
        expect(response.body.code).toBe('VALIDATION_FAILED');
      });

    expect(storage.puts).toHaveLength(0);
  });

  it('answers not found for a Job the caller organization does not own', async () => {
    const session = await signInFor([
      EVIDENCE_PERMISSIONS.PHOTO_ADD,
      EVIDENCE_PERMISSIONS.VIEW,
    ]);
    const otherOrganization = await createTestOrganization(database.db, {
      name: 'Other Job Photo Org',
    });
    database.cleanup.trackOrganization(otherOrganization.id);
    const foreignJob = await newJobInOrganization(otherOrganization.id);

    await uploadPhoto(foreignJob.id, session.accessToken)
      .expect(404)
      .expect((response) => {
        expect(response.body.code).toBe('JOB_NOT_FOUND');
      });
  });

  it('serves the stored bytes through the API on the API port', async () => {
    const session = await signInFor([
      EVIDENCE_PERMISSIONS.PHOTO_ADD,
      EVIDENCE_PERMISSIONS.VIEW,
    ]);
    const job = await newJobInOrganization();
    const clientOperationId = randomUUID();
    await uploadPhoto(job.id, session.accessToken, {
      clientOperationId,
    }).expect(201);

    const content = await request(app.getHttpServer())
      .get(`/jobs/${job.id}/photos/${clientOperationId}/content`)
      .set('Authorization', `Bearer ${session.accessToken}`)
      .expect(200);

    expect(content.headers['content-type']).toContain('image/jpeg');
    expect(Buffer.compare(content.body as Buffer, JPEG)).toBe(0);
  });

  it('answers not found for a photo that does not exist', async () => {
    const session = await signInFor([
      EVIDENCE_PERMISSIONS.PHOTO_ADD,
      EVIDENCE_PERMISSIONS.VIEW,
    ]);
    const job = await newJobInOrganization();
    await uploadPhoto(job.id, session.accessToken).expect(201);

    await request(app.getHttpServer())
      .get(`/jobs/${job.id}/photos/${randomUUID()}/content`)
      .set('Authorization', `Bearer ${session.accessToken}`)
      .expect(404)
      .expect((response) => {
        expect(response.body.code).toBe('JOB_PHOTO_NOT_FOUND');
      });
  });

  it('refuses the upload when the object store cannot store it, and records nothing', async () => {
    const session = await signInFor([
      EVIDENCE_PERMISSIONS.PHOTO_ADD,
      EVIDENCE_PERMISSIONS.VIEW,
    ]);
    const job = await newJobInOrganization();

    storage.failWith = FakeObjectStorage.unavailable();
    try {
      const response = await uploadPhoto(job.id, session.accessToken).expect(
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
      .from(jobPhotos)
      .where(eq(jobPhotos.jobId, job.id));
    expect(rows).toHaveLength(0);
  });

  it('answers not found when the record exists but its bytes do not', async () => {
    const session = await signInFor([
      EVIDENCE_PERMISSIONS.PHOTO_ADD,
      EVIDENCE_PERMISSIONS.VIEW,
    ]);
    const job = await newJobInOrganization();
    const clientOperationId = randomUUID();
    await uploadPhoto(job.id, session.accessToken, {
      clientOperationId,
    }).expect(201);

    storage.clear();

    await request(app.getHttpServer())
      .get(`/jobs/${job.id}/photos/${clientOperationId}/content`)
      .set('Authorization', `Bearer ${session.accessToken}`)
      .expect(404)
      .expect((response) => {
        expect(response.body.code).toBe('JOB_PHOTO_NOT_FOUND');
      });
  });
});
