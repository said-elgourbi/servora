import { Inject, Injectable } from '@nestjs/common';
import { and, eq, isNull } from 'drizzle-orm';
import { DatabaseService } from '../database/database.service.js';
import {
  customers,
  jobAudioNoteRemovals,
  jobAudioNotes,
  jobs,
} from '../database/schema.js';
import { isUniqueViolation } from '../database/unique-violation.js';
import {
  OBJECT_STORAGE,
  ObjectNotFoundError,
  jobAudioNoteObjectKey,
  type ObjectStorage,
  type StoredObject,
} from '../storage/object-storage.js';
import type { OrganizationScope } from '../tenancy/tenant-scope.js';
import { readJobActivity, type JobActivityEventDto } from './job-activity.js';
import type {
  CreateJobAudioNoteDto,
  RemoveJobAudioNoteDto,
  ValidatedJobAudioNote,
} from './job-audio.dto.js';
import { JobNotFoundError } from './jobs.service.js';

/**
 * The Job audio note evidence write and read (`BR-027`, `BR-091`, `ADR-018`).
 *
 * An audio note is **evidence**, exactly as a photo is: it is appended, never edited, and the backend is
 * the only authority that it exists (`BR-001`). The service therefore does the same three things its
 * photo sibling does — store the bytes through the provider-neutral `ObjectStorage` port, record the
 * append-only row that makes the recording Servora's evidence, and answer with the Job Activity
 * projection the client already renders (`BR-080`) — and decides nothing else.
 *
 * **Idempotency** (`BR-031`, offline standard §5). The device generates the operation id once, before its
 * first attempt, and reuses it for every retry. That id is the audio note's primary key *and* the object
 * key's last segment, so a replay finds the row that already exists and a retry after a timeout writes
 * the same key instead of leaving an orphan object behind (`ADR-018` A10).
 *
 * A recording is recorded on the **Job**, so it can be made whether or not the Job has a Visit: a Job may
 * exist with no Visit at all (`BR-051`).
 */
@Injectable()
export class JobAudioNotesService {
  constructor(
    private readonly database: DatabaseService,
    @Inject(OBJECT_STORAGE) private readonly storage: ObjectStorage,
  ) {}

  private get db() {
    return this.database.db;
  }

  /**
   * Records one audio note against a Job and answers with the refreshed activity.
   *
   * The Job is resolved first, so a recording is never stored for a Job the caller's organization does
   * not own (`BR-001`), and an idempotent replay is answered before anything is written (`BR-031`).
   */
  async addJobAudioNote(
    scope: OrganizationScope,
    jobId: string,
    uploaderMembershipId: string,
    input: CreateJobAudioNoteDto,
    audio: ValidatedJobAudioNote,
  ): Promise<JobActivityEventDto[]> {
    await this.requireJob(scope, jobId);

    const existing = await this.findByOperationId(scope, input.clientOperationId);
    if (existing !== null) {
      this.assertSameJob(existing.jobId, jobId);
      return readJobActivity(this.db, scope, jobId);
    }

    const objectKey = jobAudioNoteObjectKey({
      organizationId: scope.organizationId,
      jobId,
      audioNoteId: input.clientOperationId,
      contentType: audio.contentType,
    });

    await this.storage.putObject({
      key: objectKey,
      body: audio.body,
      contentType: audio.contentType,
    });

    try {
      await this.db.insert(jobAudioNotes).values({
        id: input.clientOperationId,
        organizationId: scope.organizationId,
        jobId,
        uploaderMembershipId,
        phase: input.phase,
        note: input.note,
        objectKey,
        contentType: audio.contentType,
        byteSize: audio.body.byteLength,
        durationSeconds: audio.durationSeconds,
        capturedAt: input.capturedAt,
        clientOperationId: input.clientOperationId,
      });
    } catch (error) {
      // A concurrent replay of the same operation won the race. It wrote the same object key, so the
      // evidence it recorded is the evidence this request carried; the caller is answered with that
      // record rather than with a failure it would retry forever (`BR-031`).
      if (isUniqueViolation(error)) {
        return readJobActivity(this.db, scope, jobId);
      }
      throw error;
    }

    return readJobActivity(this.db, scope, jobId);
  }

  /**
   * Takes one audio note out of ordinary use, recording the actor, the instant and the reason
   * (`BR-089`).
   *
   * Evidence is immutable once this API has accepted it (`BR-088`), so nothing here edits or deletes:
   * the recording's own row is left exactly as it was and a **removal row** is appended beside it, which
   * is what makes the evidence stop appearing in ordinary reads. The bytes stay in the store — a
   * physical purge is a retention concern, not this operation (`BR-090`).
   *
   * The route is authorized by `evidence.audio.remove`, a Manager-level capability (`ADR-018` A7), and
   * the removal is online-only: it takes no client-generated idempotency key and no conflict policy is
   * decided for it, which is the offline standard's own condition for staying out of the outbox
   * (§5, §8, §13.2).
   */
  async removeJobAudioNote(
    scope: OrganizationScope,
    jobId: string,
    audioNoteId: string,
    actorMembershipId: string,
    input: RemoveJobAudioNoteDto,
  ): Promise<JobActivityEventDto[]> {
    await this.requireJob(scope, jobId);

    const audioNote = await this.findAudioNote(scope, jobId, audioNoteId);
    if (audioNote === null) {
      throw new JobAudioNoteNotFoundError();
    }
    if (audioNote.removed) {
      throw new JobAudioNoteAlreadyRemovedError();
    }

    try {
      await this.db.insert(jobAudioNoteRemovals).values({
        organizationId: scope.organizationId,
        jobAudioNoteId: audioNoteId,
        actorMembershipId,
        reason: input.reason,
      });
    } catch (error) {
      // A concurrent removal of the same recording won the race. One recording has one removal (no
      // restore is defined), so the second attempt is answered with that rather than recording a second
      // removal of evidence that is already out of ordinary use (`BR-089`).
      if (isUniqueViolation(error)) {
        throw new JobAudioNoteAlreadyRemovedError();
      }
      throw error;
    }

    return readJobActivity(this.db, scope, jobId);
  }

  /**
   * Returns one recording's bytes, scoped to the caller's organization and Job.
   *
   * The row is the authority for whether the evidence exists; the object store is reached only after it
   * is found, and only by the key the row carries — the API derives the key, a client never supplies one
   * (`ADR-013` D6.5).
   *
   * A recording that has been **removed** is not readable here: this is an ordinary read of evidence, and
   * removed evidence is out of ordinary use (`BR-089`). It is answered exactly like a recording that
   * does not exist, so an ordinary read never discloses that there is evidence behind the id; the
   * audit/history context is the Job Activity read.
   */
  async readJobAudioNoteContent(
    scope: OrganizationScope,
    jobId: string,
    audioNoteId: string,
  ): Promise<StoredObject> {
    const [row] = await this.db
      .select({ objectKey: jobAudioNotes.objectKey })
      .from(jobAudioNotes)
      .leftJoin(
        jobAudioNoteRemovals,
        and(
          eq(jobAudioNoteRemovals.jobAudioNoteId, jobAudioNotes.id),
          eq(jobAudioNoteRemovals.organizationId, scope.organizationId),
        ),
      )
      .where(
        and(
          eq(jobAudioNotes.organizationId, scope.organizationId),
          eq(jobAudioNotes.jobId, jobId),
          eq(jobAudioNotes.id, audioNoteId),
          isNull(jobAudioNoteRemovals.id),
        ),
      )
      .limit(1);

    if (row === undefined) {
      throw new JobAudioNoteNotFoundError();
    }

    try {
      return await this.storage.getObject(row.objectKey);
    } catch (error) {
      // The record exists but the store does not hold its bytes. That is an integrity failure rather
      // than a client mistake, so it is reported as the recording being unavailable instead of as a
      // success with nothing in it (`dev.md` §7).
      if (error instanceof ObjectNotFoundError) {
        throw new JobAudioNoteNotFoundError();
      }
      throw error;
    }
  }

  /**
   * One audio note of the caller's organization and Job, and whether it has been removed.
   *
   * Whether evidence is out of ordinary use is **derived** from the removal row rather than stored on the
   * recording (`BR-089`): the note's row is append-only, so the removal is the only record of it, and one
   * query answers both questions the removal route asks.
   */
  private async findAudioNote(
    scope: OrganizationScope,
    jobId: string,
    audioNoteId: string,
  ): Promise<{ removed: boolean } | null> {
    const [row] = await this.db
      .select({ removalId: jobAudioNoteRemovals.id })
      .from(jobAudioNotes)
      .leftJoin(
        jobAudioNoteRemovals,
        and(
          eq(jobAudioNoteRemovals.jobAudioNoteId, jobAudioNotes.id),
          eq(jobAudioNoteRemovals.organizationId, scope.organizationId),
        ),
      )
      .where(
        and(
          eq(jobAudioNotes.organizationId, scope.organizationId),
          eq(jobAudioNotes.jobId, jobId),
          eq(jobAudioNotes.id, audioNoteId),
        ),
      )
      .limit(1);
    return row === undefined ? null : { removed: row.removalId !== null };
  }

  /** One audio note row of the caller's organization, or `null`. */
  private async findByOperationId(
    scope: OrganizationScope,
    clientOperationId: string,
  ): Promise<{ jobId: string } | null> {
    const [row] = await this.db
      .select({ jobId: jobAudioNotes.jobId })
      .from(jobAudioNotes)
      .where(
        and(
          eq(jobAudioNotes.organizationId, scope.organizationId),
          eq(jobAudioNotes.clientOperationId, clientOperationId),
        ),
      )
      .limit(1);
    return row ?? null;
  }

  /**
   * A key already used for another Job is refused rather than answered.
   *
   * The idempotency key belongs to the operation the device performed, so it can only ever replay for the
   * Job that operation addressed. Answering with another Job's activity would disclose a record the
   * caller did not address (`BR-001`).
   */
  private assertSameJob(existingJobId: string, jobId: string): void {
    if (existingJobId !== jobId) {
      throw new JobAudioNoteOperationReusedError();
    }
  }

  /**
   * One Job of the caller's organization, or a failure the route maps (`BR-001`, `BR-023`).
   *
   * A Job whose Customer the organization has deleted is not addressable, exactly as the Job read and its
   * actions treat it (`BR-023`).
   */
  private async requireJob(scope: OrganizationScope, jobId: string): Promise<void> {
    const [row] = await this.db
      .select({ id: jobs.id })
      .from(jobs)
      .innerJoin(customers, eq(customers.id, jobs.customerId))
      .where(
        and(
          eq(jobs.organizationId, scope.organizationId),
          eq(jobs.id, jobId),
          isNull(customers.deletedAt),
        ),
      )
      .limit(1);
    if (row === undefined) {
      throw new JobNotFoundError();
    }
  }
}

/** The audio note does not exist in the caller's organization and Job (`BR-001`). */
export class JobAudioNoteNotFoundError extends Error {
  constructor() {
    super('Job audio note was not found.');
    this.name = 'JobAudioNoteNotFoundError';
  }
}

/**
 * The recording exists but has already been removed from ordinary use (`BR-089`).
 *
 * Removal is not repeatable: the evidence is already out of use, no restore is defined, and a second
 * removal would record a state change that did not happen. It is reported rather than absorbed, so a
 * Manager who attempts it learns that the evidence is already removed (`BR-067`).
 */
export class JobAudioNoteAlreadyRemovedError extends Error {
  constructor() {
    super('That audio note has already been removed.');
    this.name = 'JobAudioNoteAlreadyRemovedError';
  }
}

/**
 * The idempotency key was already used for a different Job.
 *
 * It is a client defect rather than a conflict with business state: one operation id names one operation,
 * and that operation addressed one Job.
 */
export class JobAudioNoteOperationReusedError extends Error {
  constructor() {
    super('That operation id was already used for another job.');
    this.name = 'JobAudioNoteOperationReusedError';
  }
}
