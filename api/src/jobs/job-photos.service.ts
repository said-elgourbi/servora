import { Inject, Injectable } from '@nestjs/common';
import { and, eq, isNull } from 'drizzle-orm';
import { DatabaseService } from '../database/database.service.js';
import { isUniqueViolation } from '../database/unique-violation.js';
import { customers, jobPhotoRemovals, jobPhotos, jobs } from '../database/schema.js';
import {
  OBJECT_STORAGE,
  ObjectNotFoundError,
  jobPhotoObjectKey,
  type ObjectStorage,
  type StoredObject,
} from '../storage/object-storage.js';
import type { OrganizationScope } from '../tenancy/tenant-scope.js';
import { readJobActivity, type JobActivityEventDto } from './job-activity.js';
import type {
  CreateJobPhotoDto,
  RemoveJobPhotoDto,
  ValidatedJobPhoto,
} from './job-photo.dto.js';
import { JobNotFoundError } from './jobs.service.js';

/**
 * The Job photo evidence write and read (`BR-015`, `BR-027`).
 *
 * A photo is **evidence**, not a Job field: it is appended, never edited, and the backend is the only
 * authority that it exists (`BR-001`). The service therefore does three things and decides nothing
 * else: it stores the bytes through the provider-neutral `ObjectStorage` port, it records the
 * append-only row that makes the photo Servora's evidence, and it answers with the Job Activity
 * projection the client already renders (`BR-080`).
 *
 * **Idempotency** (`BR-031`, offline standard §5). The device generates the operation id once, before
 * its first attempt, and reuses it for every retry. That id is the photo's primary key *and* the
 * object key's last segment, so:
 *
 * - a replay finds the row that already exists and returns the same activity instead of storing the
 *   evidence twice;
 * - a retry after a timeout writes the **same** object key, so a failed attempt cannot leave an
 *   orphan object behind.
 *
 * A photo is recorded on the **Job**, so it can be captured whether or not the Job has a Visit: a Job
 * may exist with no Visit at all (`BR-051`).
 */
@Injectable()
export class JobPhotosService {
  constructor(
    private readonly database: DatabaseService,
    @Inject(OBJECT_STORAGE) private readonly storage: ObjectStorage,
  ) {}

  private get db() {
    return this.database.db;
  }

  /**
   * Records one photo against a Job and answers with the refreshed activity.
   *
   * The Job is resolved first, so a photo is never stored for a Job the caller's organization does
   * not own (`BR-001`), and an idempotent replay is answered before anything is written (`BR-031`).
   */
  async addJobPhoto(
    scope: OrganizationScope,
    jobId: string,
    uploaderMembershipId: string,
    input: CreateJobPhotoDto,
    photo: ValidatedJobPhoto,
  ): Promise<JobActivityEventDto[]> {
    await this.requireJob(scope, jobId);

    const existing = await this.findByOperationId(scope, input.clientOperationId);
    if (existing !== null) {
      this.assertSameJob(existing.jobId, jobId);
      return readJobActivity(this.db, scope, jobId);
    }

    const objectKey = jobPhotoObjectKey({
      organizationId: scope.organizationId,
      jobId,
      photoId: input.clientOperationId,
      contentType: photo.contentType,
    });

    await this.storage.putObject({
      key: objectKey,
      body: photo.body,
      contentType: photo.contentType,
    });

    try {
      await this.db.insert(jobPhotos).values({
        id: input.clientOperationId,
        organizationId: scope.organizationId,
        jobId,
        uploaderMembershipId,
        phase: input.phase,
        note: input.note,
        objectKey,
        contentType: photo.contentType,
        byteSize: photo.body.byteLength,
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
   * Takes one photo out of ordinary use, recording the actor, the instant and the reason (`BR-089`).
   *
   * Evidence is immutable once this API has accepted it (`BR-088`), so nothing here edits or deletes:
   * the photo's own row is left exactly as it was and a **removal row** is appended beside it, which
   * is what makes the evidence stop appearing in ordinary reads. The bytes stay in the store — a
   * physical purge is a retention concern, not this operation (`BR-090`) — so nothing here destroys
   * what a later retention decision may still need.
   *
   * The route is authorized by `evidence.photo.remove`, a Manager-level capability (`BR-089`), and the
   * removal is online-only: it takes no client-generated idempotency key and no conflict policy is
   * decided for it, which is the offline standard's own condition for staying out of the outbox
   * (§5, §8, §13.2).
   */
  async removeJobPhoto(
    scope: OrganizationScope,
    jobId: string,
    photoId: string,
    actorMembershipId: string,
    input: RemoveJobPhotoDto,
  ): Promise<JobActivityEventDto[]> {
    await this.requireJob(scope, jobId);

    const photo = await this.findPhoto(scope, jobId, photoId);
    if (photo === null) {
      throw new JobPhotoNotFoundError();
    }
    if (photo.removed) {
      throw new JobPhotoAlreadyRemovedError();
    }

    try {
      await this.db.insert(jobPhotoRemovals).values({
        organizationId: scope.organizationId,
        jobPhotoId: photoId,
        actorMembershipId,
        reason: input.reason,
      });
    } catch (error) {
      // A concurrent removal of the same photo won the race. One photo has one removal (no restore is
      // defined), so the second attempt is answered with that rather than recording a second removal
      // of evidence that is already out of ordinary use (`BR-089`).
      if (isUniqueViolation(error)) {
        throw new JobPhotoAlreadyRemovedError();
      }
      throw error;
    }

    return readJobActivity(this.db, scope, jobId);
  }

  /**
   * Returns one photo's bytes, scoped to the caller's organization and Job.
   *
   * The row is the authority for whether the evidence exists; the object store is reached only after
   * it is found, and only by the key the row carries — the API derives the key, a client never
   * supplies one (`ADR-013` D6.5).
   *
   * A photo that has been **removed** is not readable here: this is an ordinary read of evidence, and
   * removed evidence is out of ordinary use (`BR-089`). It is answered exactly like a photo that does
   * not exist, so an ordinary read never discloses that there is evidence behind the id; the
   * audit/history context is the Job Activity read, which states that it was removed, by whom, when
   * and why (tracker 029 D6d).
   */
  async readJobPhotoContent(
    scope: OrganizationScope,
    jobId: string,
    photoId: string,
  ): Promise<StoredObject> {
    const [row] = await this.db
      .select({ objectKey: jobPhotos.objectKey })
      .from(jobPhotos)
      .leftJoin(
        jobPhotoRemovals,
        and(
          eq(jobPhotoRemovals.jobPhotoId, jobPhotos.id),
          eq(jobPhotoRemovals.organizationId, scope.organizationId),
        ),
      )
      .where(
        and(
          eq(jobPhotos.organizationId, scope.organizationId),
          eq(jobPhotos.jobId, jobId),
          eq(jobPhotos.id, photoId),
          isNull(jobPhotoRemovals.id),
        ),
      )
      .limit(1);

    if (row === undefined) {
      throw new JobPhotoNotFoundError();
    }

    try {
      return await this.storage.getObject(row.objectKey);
    } catch (error) {
      // The record exists but the store does not hold its bytes. That is an integrity failure rather
      // than a client mistake, so it is reported as the photo being unavailable instead of as a
      // success with nothing in it (`dev.md` §7).
      if (error instanceof ObjectNotFoundError) {
        throw new JobPhotoNotFoundError();
      }
      throw error;
    }
  }

  /**
   * One photo of the caller's organization and Job, and whether it has been removed.
   *
   * Whether evidence is out of ordinary use is **derived** from the removal row rather than stored on
   * the photo (`BR-089`): the photo row is append-only, so the removal is the only record of it, and
   * one query answers both questions the removal route asks.
   */
  private async findPhoto(
    scope: OrganizationScope,
    jobId: string,
    photoId: string,
  ): Promise<{ removed: boolean } | null> {
    const [row] = await this.db
      .select({ removalId: jobPhotoRemovals.id })
      .from(jobPhotos)
      .leftJoin(
        jobPhotoRemovals,
        and(
          eq(jobPhotoRemovals.jobPhotoId, jobPhotos.id),
          eq(jobPhotoRemovals.organizationId, scope.organizationId),
        ),
      )
      .where(
        and(
          eq(jobPhotos.organizationId, scope.organizationId),
          eq(jobPhotos.jobId, jobId),
          eq(jobPhotos.id, photoId),
        ),
      )
      .limit(1);
    return row === undefined ? null : { removed: row.removalId !== null };
  }

  /** One photo row of the caller's organization, or `null`. */
  private async findByOperationId(
    scope: OrganizationScope,
    clientOperationId: string,
  ): Promise<{ jobId: string } | null> {
    const [row] = await this.db
      .select({ jobId: jobPhotos.jobId })
      .from(jobPhotos)
      .where(
        and(
          eq(jobPhotos.organizationId, scope.organizationId),
          eq(jobPhotos.clientOperationId, clientOperationId),
        ),
      )
      .limit(1);
    return row ?? null;
  }

  /**
   * A key already used for another Job is refused rather than answered.
   *
   * The idempotency key belongs to the operation the device performed, so it can only ever replay for
   * the Job that operation addressed. Answering with another Job's activity would disclose a record
   * the caller did not address (`BR-001`).
   */
  private assertSameJob(existingJobId: string, jobId: string): void {
    if (existingJobId !== jobId) {
      throw new JobPhotoOperationReusedError();
    }
  }

  /**
   * One Job of the caller's organization, or a failure the route maps (`BR-001`, `BR-023`).
   *
   * A Job whose Customer the organization has deleted is not addressable, exactly as the Job read and
   * its actions treat it (`BR-023`).
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

/** The photo does not exist in the caller's organization and Job (`BR-001`). */
export class JobPhotoNotFoundError extends Error {
  constructor() {
    super('Job photo was not found.');
    this.name = 'JobPhotoNotFoundError';
  }
}

/**
 * The photo exists but has already been removed from ordinary use (`BR-089`).
 *
 * Removal is not repeatable: the evidence is already out of use, no restore is defined, and a second
 * removal would record a state change that did not happen. It is reported rather than absorbed, so a
 * Manager who attempts it learns that the evidence is already removed (`BR-067`) instead of being
 * shown a success for an action the API did not perform.
 */
export class JobPhotoAlreadyRemovedError extends Error {
  constructor() {
    super('That photo has already been removed.');
    this.name = 'JobPhotoAlreadyRemovedError';
  }
}

/**
 * The idempotency key was already used for a different Job.
 *
 * It is a client defect rather than a conflict with business state: one operation id names one
 * operation, and that operation addressed one Job.
 */
export class JobPhotoOperationReusedError extends Error {
  constructor() {
    super('That operation id was already used for another job.');
    this.name = 'JobPhotoOperationReusedError';
  }
}

