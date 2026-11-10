import { Inject, Injectable } from '@nestjs/common';
import { randomUUID } from 'node:crypto';
import { and, desc, eq, isNull } from 'drizzle-orm';
import { DatabaseService } from '../database/database.service.js';
import {
  jobDocuments,
  jobActivityEdits,
  jobQuoteHistory,
  jobQuoteWorkflows,
  jobs,
  visits,
} from '../database/schema.js';
import {
  OBJECT_STORAGE,
  ObjectNotFoundError,
  jobDocumentObjectKey,
  type ObjectStorage,
  type StoredObject,
} from '../storage/object-storage.js';
import type { OrganizationScope } from '../tenancy/tenant-scope.js';
import { readJobActivity, type JobActivityEventDto } from './job-activity.js';
import {
  quoteWorkflowStateAfterQuoteDocumentReplacement,
  type CreateJobDocumentDto,
  type JobDocumentDto,
  type ValidatedJobDocument,
} from './job-document.dto.js';
import type { JobQuoteWorkflowState } from './job.types.js';
import {
  JobNotFoundError,
  JobQuoteWorkflowNotFoundError,
} from './jobs.service.js';

export class JobDocumentNotFoundError extends Error {
  constructor() {
    super('The job document was not found.');
    this.name = 'JobDocumentNotFoundError';
  }
}

@Injectable()
export class JobDocumentsService {
  constructor(
    private readonly database: DatabaseService,
    @Inject(OBJECT_STORAGE) private readonly storage: ObjectStorage,
  ) {}

  private get db() {
    return this.database.db;
  }

  async attachmentVisitId(scope: OrganizationScope, jobId: string, documentId: string): Promise<string | null> {
    const [row] = await this.db.select({ visitId: jobDocuments.visitId }).from(jobDocuments).where(and(
      eq(jobDocuments.organizationId, scope.organizationId),
      eq(jobDocuments.jobId, jobId),
      eq(jobDocuments.id, documentId),
    )).limit(1);
    return row?.visitId ?? null;
  }

  async addJobDocument(
    scope: OrganizationScope,
    jobId: string,
    uploaderMembershipId: string,
    input: CreateJobDocumentDto,
    document: ValidatedJobDocument,
  ): Promise<JobDocumentDto> {
    if (input.visitId) {
      const [visit] = await this.db.select({ id: visits.id }).from(visits).where(and(
        eq(visits.organizationId, scope.organizationId),
        eq(visits.jobId, jobId),
        eq(visits.id, input.visitId),
      )).limit(1);
      if (!visit) throw new JobNotFoundError();
    }
    const documentId = input.clientOperationId ?? randomUUID();
    if (input.clientOperationId) {
      const [existing] = await this.db.select().from(jobDocuments).where(and(
        eq(jobDocuments.organizationId, scope.organizationId),
        eq(jobDocuments.clientOperationId, input.clientOperationId),
      )).limit(1);
      if (existing) {
        if (existing.jobId !== jobId) throw new JobNotFoundError();
        return toJobDocumentDto(existing);
      }
    }
    const objectKey = jobDocumentObjectKey({
      organizationId: scope.organizationId,
      jobId,
      documentId,
      contentType: document.contentType,
    });

    await this.storage.putObject({
      key: objectKey,
      body: document.body,
      contentType: document.contentType,
    });

    const row = await this.db.transaction(async (tx) => {
      const [job] = await tx
        .select({ id: jobs.id })
        .from(jobs)
        .where(
          and(
            eq(jobs.organizationId, scope.organizationId),
            eq(jobs.id, jobId),
          ),
        )
        .for('update')
        .limit(1);
      if (job === undefined) {
        throw new JobNotFoundError();
      }

      if (input.clientOperationId) {
        const [replay] = await tx.select().from(jobDocuments).where(and(
          eq(jobDocuments.organizationId, scope.organizationId),
          eq(jobDocuments.clientOperationId, input.clientOperationId),
        )).for('update').limit(1);
        if (replay) {
          if (replay.jobId !== jobId) throw new JobNotFoundError();
          return replay;
        }
      }

      if (input.documentType === 'ATTACHMENT') {
        const [attachment] = await tx.insert(jobDocuments).values({
          id: documentId,
          organizationId: scope.organizationId,
          jobId,
          visitId: input.visitId,
          documentType: input.documentType,
          clientOperationId: input.clientOperationId,
          displayName: input.displayName,
          documentVersion: 1,
          isCurrent: true,
          objectKey,
          contentType: document.contentType,
          byteSize: document.body.byteLength,
          uploadedByMembershipId: uploaderMembershipId,
          recordedAt: new Date(),
        }).returning();
        if (!attachment) throw new Error('The job attachment write returned no row.');
        return attachment;
      }

      const [workflow] = await tx
        .select()
        .from(jobQuoteWorkflows)
        .where(
          and(
            eq(jobQuoteWorkflows.organizationId, scope.organizationId),
            eq(jobQuoteWorkflows.jobId, jobId),
          ),
        )
        .for('update')
        .limit(1);
      if (workflow === undefined) {
        throw new JobQuoteWorkflowNotFoundError();
      }

      const [current] = await tx
        .select({
          id: jobDocuments.id,
          documentVersion: jobDocuments.documentVersion,
        })
        .from(jobDocuments)
        .where(
          and(
            eq(jobDocuments.organizationId, scope.organizationId),
            eq(jobDocuments.jobId, jobId),
            eq(jobDocuments.documentType, input.documentType),
            eq(jobDocuments.isCurrent, true),
          ),
        )
        .for('update')
        .limit(1);

      const recordedAt = new Date();
      const documentVersion =
        current === undefined ? 1 : current.documentVersion + 1;

      if (current !== undefined) {
        await tx
          .update(jobDocuments)
          .set({
            isCurrent: false,
            supersededAt: recordedAt,
            supersededByJobDocumentId: current.id,
          })
          .where(eq(jobDocuments.id, current.id));
      }

      const [inserted] = await tx
        .insert(jobDocuments)
        .values({
          id: documentId,
          organizationId: scope.organizationId,
          jobId,
          visitId: input.visitId,
          documentType: input.documentType,
          clientOperationId: input.clientOperationId,
          displayName: input.displayName,
          documentVersion,
          isCurrent: true,
          objectKey,
          contentType: document.contentType,
          byteSize: document.body.byteLength,
          uploadedByMembershipId: uploaderMembershipId,
          recordedAt,
        })
        .returning();
      if (inserted === undefined) {
        throw new Error('The job document write returned no row.');
      }

      if (current !== undefined) {
        await tx
          .update(jobDocuments)
          .set({ supersededByJobDocumentId: documentId })
          .where(eq(jobDocuments.id, current.id));
      }

      const fromState = workflow.state as JobQuoteWorkflowState;
      const toState =
        quoteWorkflowStateAfterQuoteDocumentReplacement(fromState);
      if (current !== undefined && toState !== fromState) {
        await tx
          .update(jobQuoteWorkflows)
          .set({
            state: toState,
            sentAt: null,
            sentByMembershipId: null,
            sentNote: null,
            externalReference: null,
            version: workflow.version + 1,
            updatedAt: recordedAt,
          })
          .where(eq(jobQuoteWorkflows.id, workflow.id));
        await tx.insert(jobQuoteHistory).values({
          organizationId: scope.organizationId,
          quoteWorkflowId: workflow.id,
          jobId,
          sourceVisitId: workflow.sourceVisitId,
          event: 'REQUIRED',
          fromState,
          toState,
          actorMembershipId: uploaderMembershipId,
          recordedAt,
        });
      }

      return inserted;
    });

    return toJobDocumentDto(row);
  }

  async editJobAttachment(
    scope: OrganizationScope,
    jobId: string,
    documentId: string,
    actorMembershipId: string,
    displayName: string | null,
    document: ValidatedJobDocument | null,
    canEditAny: boolean,
  ): Promise<JobActivityEventDto[]> {
    const [current] = await this.db.select().from(jobDocuments).where(and(
      eq(jobDocuments.organizationId, scope.organizationId), eq(jobDocuments.jobId, jobId), eq(jobDocuments.id, documentId),
    )).limit(1);
    if (!current || current.documentType !== 'ATTACHMENT' || current.removedAt !== null) throw new JobDocumentNotFoundError();
    if (!canEditAny && current.uploadedByMembershipId !== actorMembershipId) throw new JobDocumentNotFoundError();
    const recordedAt = new Date();
    await this.db.insert(jobActivityEdits).values({
      organizationId: scope.organizationId, jobId, activityKind: 'JOB_DOCUMENT_ATTACHED', activityId: documentId,
      editorMembershipId: actorMembershipId,
      previousValue: { displayName: current.displayName, byteSize: current.byteSize, documentVersion: current.documentVersion },
      recordedAt,
    });
    const changes: Partial<typeof jobDocuments.$inferInsert> = {
      displayName, editedAt: recordedAt, editedByMembershipId: actorMembershipId,
    };
    if (document) {
      const objectKey = jobDocumentObjectKey({ organizationId: scope.organizationId, jobId, documentId: randomUUID(), contentType: document.contentType });
      await this.storage.putObject({ key: objectKey, body: document.body, contentType: document.contentType });
      changes.objectKey = objectKey; changes.contentType = document.contentType; changes.byteSize = document.body.byteLength;
    }
    await this.db.update(jobDocuments).set(changes).where(and(
      eq(jobDocuments.organizationId, scope.organizationId), eq(jobDocuments.jobId, jobId), eq(jobDocuments.id, documentId),
    ));
    return readJobActivity(this.db, scope, jobId);
  }

  async removeJobAttachment(scope: OrganizationScope, jobId: string, documentId: string, actorMembershipId: string, canRemoveAny: boolean): Promise<JobActivityEventDto[]> {
    const [document] = await this.db.select().from(jobDocuments).where(and(
      eq(jobDocuments.organizationId, scope.organizationId), eq(jobDocuments.jobId, jobId), eq(jobDocuments.id, documentId),
    )).limit(1);
    if (!document || document.documentType !== 'ATTACHMENT' || document.removedAt !== null) throw new JobDocumentNotFoundError();
    if (!canRemoveAny && document.uploadedByMembershipId !== actorMembershipId) throw new JobDocumentNotFoundError();
    await this.db.update(jobDocuments).set({ removedAt: new Date(), removedByMembershipId: actorMembershipId }).where(and(
      eq(jobDocuments.organizationId, scope.organizationId), eq(jobDocuments.jobId, jobId), eq(jobDocuments.id, documentId),
    ));
    return readJobActivity(this.db, scope, jobId);
  }

  async readCurrentQuoteDocumentContent(
    scope: OrganizationScope,
    jobId: string,
  ): Promise<StoredObject> {
    const [row] = await this.db
      .select({ objectKey: jobDocuments.objectKey })
      .from(jobDocuments)
      .where(
        and(
          eq(jobDocuments.organizationId, scope.organizationId),
          eq(jobDocuments.jobId, jobId),
          eq(jobDocuments.documentType, 'QUOTE'),
          eq(jobDocuments.isCurrent, true),
        ),
      )
      .orderBy(desc(jobDocuments.recordedAt))
      .limit(1);
    return this.readObject(row?.objectKey);
  }

  async readJobDocumentContent(
    scope: OrganizationScope,
    jobId: string,
    documentId: string,
  ): Promise<StoredObject> {
    const [row] = await this.db
      .select({ objectKey: jobDocuments.objectKey })
      .from(jobDocuments)
      .where(
        and(
          eq(jobDocuments.organizationId, scope.organizationId),
          eq(jobDocuments.jobId, jobId),
          eq(jobDocuments.id, documentId),
          isNull(jobDocuments.removedAt),
        ),
      )
      .limit(1);
    return this.readObject(row?.objectKey);
  }

  private async readObject(
    objectKey: string | undefined,
  ): Promise<StoredObject> {
    if (objectKey === undefined) {
      throw new JobDocumentNotFoundError();
    }
    try {
      return await this.storage.getObject(objectKey);
    } catch (error) {
      if (error instanceof ObjectNotFoundError) {
        throw new JobDocumentNotFoundError();
      }
      throw error;
    }
  }
}

function toJobDocumentDto(
  row: typeof jobDocuments.$inferSelect,
): JobDocumentDto {
  return {
    id: row.id,
    jobId: row.jobId,
    documentType: row.documentType as JobDocumentDto['documentType'],
    displayName: row.displayName,
    documentVersion: row.documentVersion,
    isCurrent: row.isCurrent,
    contentType: row.contentType as JobDocumentDto['contentType'],
    byteSize: row.byteSize,
    uploadedByMembershipId: row.uploadedByMembershipId,
    supersededAt: row.supersededAt?.toISOString() ?? null,
    supersededByJobDocumentId: row.supersededByJobDocumentId,
    recordedAt: row.recordedAt.toISOString(),
  };
}
