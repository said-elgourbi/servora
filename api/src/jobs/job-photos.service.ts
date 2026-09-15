import { Inject, Injectable } from '@nestjs/common';
import { and, eq, isNull } from 'drizzle-orm';
import { DatabaseService } from '../database/database.service.js';
import { customers, jobPhotos, jobs } from '../database/schema.js';
import {
  OBJECT_STORAGE,
  ObjectNotFoundError,
  jobPhotoObjectKey,
  type ObjectStorage,
  type StoredObject,
} from '../storage/object-storage.js';
import type { OrganizationScope } from '../tenancy/tenant-scope.js';
import { readJobActivity, type JobActivityEventDto } from './job-activity.js';
import type { CreateJobPhotoDto, ValidatedJobPhoto } from './job-photo.dto.js';
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
   * Returns one photo's bytes, scoped to the caller's organization and Job.
   *
   * The row is the authority for whether the evidence exists; the object store is reached only after
   * it is found, and only by the key the row carries — the API derives the key, a client never
   * supplies one (`ADR-013` D6.5).
   */
  async readJobPhotoContent(
    scope: OrganizationScope,
    jobId: string,
    photoId: string,
  ): Promise<StoredObject> {
    const [row] = await this.db
      .select({ objectKey: jobPhotos.objectKey })
      .from(jobPhotos)
      .where(
        and(
          eq(jobPhotos.organizationId, scope.organizationId),
          eq(jobPhotos.jobId, jobId),
          eq(jobPhotos.id, photoId),
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

/** Whether a write failed because the row (or its unique key) already exists. */
function isUniqueViolation(error: unknown): boolean {
  if (typeof error !== 'object' || error === null) {
    return false;
  }
  const direct = (error as { code?: unknown }).code;
  if (direct === '23505') {
    return true;
  }
  const cause = (error as { cause?: unknown }).cause;
  return (
    typeof cause === 'object' &&
    cause !== null &&
    (cause as { code?: unknown }).code === '23505'
  );
}

