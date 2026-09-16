import { and, asc, eq, inArray } from 'drizzle-orm';
import type { DatabaseService } from '../database/database.service.js';
import {
  jobAudioNoteRemovals,
  jobAudioNotes,
  jobCustomerHistory,
  jobPhotoRemovals,
  jobPhotos,
  jobPropertyHistory,
  jobStatusHistory,
  organizationMembers,
  userProfiles,
  visitNotes,
  visitOutcomeHistory,
  visits,
  visitScheduleHistory,
  visitStatusHistory,
  visitTechnicianHistory,
} from '../database/schema.js';
import { memberName } from '../members/member-name.js';
import type { OrganizationScope } from '../tenancy/tenant-scope.js';
import type { AssignmentRoleCode } from './job.types.js';

/**
 * The Job Activity read (`BR-080`).
 *
 * It is a **projection**: it derives one Job's chronological account from the append-only histories
 * the domain already keeps — the Job's status, Property and Customer changes, and each Visit's status,
 * schedule, assignment, outcome and notes — and stores nothing (`BR-001`, `BR-067`, `BR-080`). Nothing
 * here duplicates an authoritative record or introduces a second source of truth.
 *
 * The event **vocabulary** is the stable, machine-readable contract that crosses the application
 * boundary, defined once here and re-exported (`BR-041`); clients present a localized label for a code
 * and must never invent a parallel vocabulary (`BR-028`, `BR-041`, `BR-042`). The vocabulary and the
 * derived `Visit N` sequence are recorded as a decision in `docs/api/job-activity.md` and
 * `docs/tracker/022-android-job-activity-timeline.md`.
 *
 * Ordering is newest-first (`BR-080`). A Job-level event carries no Visit; a Visit-level event carries
 * the Visit's stable, human-readable sequence (`Visit 1`, `Visit 2`, …), never a database id
 * (`docs/domain/job-visit-domain-model.md` §6).
 */

export const JOB_ACTIVITY_KINDS = [
  'JOB_STATUS_CHANGED',
  'JOB_PROPERTY_CHANGED',
  'JOB_CUSTOMER_CHANGED',
  'JOB_PHOTO_ADDED',
  'JOB_PHOTO_REMOVED',
  'JOB_AUDIO_ADDED',
  'JOB_AUDIO_REMOVED',
  'VISIT_STATUS_CHANGED',
  'VISIT_SCHEDULED',
  'VISIT_RESCHEDULED',
  'VISIT_TECHNICIAN_ASSIGNED',
  'VISIT_TECHNICIAN_REMOVED',
  'VISIT_TECHNICIAN_ROLE_CHANGED',
  'VISIT_OUTCOME_RECORDED',
  'VISIT_NOTE_ADDED',
] as const;

/** The stable machine-readable event kinds the Job Activity read emits (`BR-041`). */
export type JobActivityKind = (typeof JOB_ACTIVITY_KINDS)[number];

/** One Job Activity event, as the client renders it. */
export interface JobActivityEventDto {
  /** The event's own stable id — the history row it projects (`BR-001`). */
  id: string;
  /** The stable event kind; the client resolves a localized label (`BR-028`, `BR-041`). */
  kind: JobActivityKind;
  /** ISO-8601 UTC instant the event was recorded. */
  recordedAt: string;
  /**
   * The member who performed the event, resolved from their profile; `null` when they have none yet
   * (`BR-020`).
   */
  actorName: string | null;
  /**
   * The Visit's human-readable sequence, `Visit 1`, `Visit 2`, … — `null` for a Job-level event
   * (`docs/domain/job-visit-domain-model.md` §6).
   */
  visitSequence: number | null;
  /** The previous status (`JOB_STATUS_CHANGED`, `VISIT_STATUS_CHANGED`); `null` when it is the first. */
  fromStatus: string | null;
  /** The new status (`JOB_STATUS_CHANGED`, `VISIT_STATUS_CHANGED`); the kind names the vocabulary. */
  toStatus: string | null;
  /** The technician the event is about (`VISIT_TECHNICIAN_*`). */
  technicianName: string | null;
  /** The new assignment role (`VISIT_TECHNICIAN_ASSIGNED`, `VISIT_TECHNICIAN_ROLE_CHANGED`). */
  roleCode: AssignmentRoleCode | null;
  /** The role left behind (`VISIT_TECHNICIAN_ROLE_CHANGED`). */
  previousRoleCode: AssignmentRoleCode | null;
  /** The outcome recorded (`VISIT_OUTCOME_RECORDED`, `BR-078`). */
  outcomeCode: string | null;
  /** The outcome's summary (`VISIT_OUTCOME_RECORDED`, `BR-077`). */
  outcomeSummary: string | null;
  /** The note's text (`VISIT_NOTE_ADDED`, or a photo's optional note for `JOB_PHOTO_ADDED`). */
  body: string | null;
  /**
   * The photo's identifier (`JOB_PHOTO_ADDED`, `JOB_PHOTO_REMOVED`), which is also the idempotency
   * key the device generated. A client asks `GET /jobs/:id/photos/:photoId/content` for its bytes
   * while the photo is in ordinary use.
   */
  photoId: string | null;
  /** The field-work phase a photo was taken in (`JOB_PHOTO_ADDED`, `BR-027`). */
  photoPhase: string | null;
  /**
   * Why a photo was removed (`JOB_PHOTO_REMOVED`, `BR-089`); `null` on every other kind.
   *
   * It is separate from [body] rather than sharing it, because the two say different things: `body` is
   * the author's own text recorded with the evidence, while this is the reason a Manager gave for
   * taking that evidence out of ordinary use, and a client must be able to present one without the
   * other (`BR-028`, `BR-089`).
   */
  photoRemovalReason: string | null;
  /**
   * The audio note's identifier (`JOB_AUDIO_ADDED`, `JOB_AUDIO_REMOVED`), which is also the idempotency
   * key the device generated. A client asks `GET /jobs/:id/audio-notes/:audioNoteId/content` for its
   * bytes while the recording is in ordinary use (`ADR-018` A6).
   */
  audioNoteId: string | null;
  /** The field-work phase an audio note was recorded in (`JOB_AUDIO_ADDED`, `BR-091`). */
  audioPhase: string | null;
  /**
   * The recording's length in whole seconds (`JOB_AUDIO_ADDED`), derived from its own container by the
   * API rather than declared by a client (`ADR-018` A3). It is what a client draws as the recording's
   * duration without opening the file.
   */
  audioDurationSeconds: number | null;
  /**
   * Why an audio note was removed (`JOB_AUDIO_REMOVED`, `BR-089`); `null` on every other kind.
   *
   * It is its own field for the same reason a photo's removal reason is: the reason a Manager gave for
   * taking evidence out of ordinary use is not the author's own text recorded with the evidence, and a
   * client must be able to present one without the other (`BR-028`, `BR-089`).
   */
  audioRemovalReason: string | null;
}

/** The Job Activity read's response body. */
export interface JobActivityDto {
  jobId: string;
  events: JobActivityEventDto[];
}

/** The shape the projection resolves before it is serialized. */
interface RawEvent {
  id: string;
  kind: JobActivityKind;
  recordedAt: Date;
  actorMembershipId: string;
  visitId: string | null;
  fromStatus: string | null;
  toStatus: string | null;
  technicianMembershipId: string | null;
  roleCode: AssignmentRoleCode | null;
  previousRoleCode: AssignmentRoleCode | null;
  outcomeCode: string | null;
  outcomeSummary: string | null;
  body: string | null;
  /**
   * The photo's id and phase, carried only by `JOB_PHOTO_ADDED`.
   *
   * They are optional here because only one kind carries them, and every event is mapped onto the
   * response with them normalized to `null` — the response contract still states every field on
   * every event (`docs/api/job-activity.md` §3.1).
   */
  photoId?: string | null;
  photoPhase?: string | null;
  /** Set only by `JOB_PHOTO_REMOVED`; normalized to `null` on every other event. */
  photoRemovalReason?: string | null;
  /**
   * The audio note's id, phase and length, carried only by `JOB_AUDIO_ADDED` (`ADR-018` A6).
   *
   * They are optional here for the same reason the photo fields are: only one kind carries them, and
   * every event is mapped onto the response with them normalized to `null` — the response contract still
   * states every field on every event (`docs/api/job-activity.md` §3.1).
   */
  audioNoteId?: string | null;
  audioPhase?: string | null;
  audioDurationSeconds?: number | null;
  /** Set only by `JOB_AUDIO_REMOVED`; normalized to `null` on every other event. */
  audioRemovalReason?: string | null;
}

/** Wraps the read's events in its response body (`docs/api/job-activity.md`). */
export function toJobActivityDto(
  jobId: string,
  events: JobActivityEventDto[],
): JobActivityDto {
  return { jobId, events };
}
/**
 * What a Job Activity read includes beyond the ordinary account (`BR-089`, tracker 029 D6d).
 *
 * A removal takes evidence out of **ordinary** use, and the audit/history context is the other half
 * of that rule: an authorized Manager can still see the removed record and what the removal recorded.
 * The caller decides, so no read silently answers with a different set of records than it was asked
 * for.
 */
export interface JobActivityOptions {
  /**
   * Include the evidence entries of evidence that has been removed, of **every** kind.
   *
   * Defaults to `false`: an ordinary read — the technician's field views in particular — excludes removed
   * evidence. The removal event itself is part of both reads, because the removal is history and Activity
   * must not silently drop it (`BR-080`). It is one flag for one question ("show me removed evidence"),
   * not one flag per kind (`ADR-018` A6).
   */
  readonly includeRemovedEvidence?: boolean;
}

/**
 * Reads one Job's chronological activity, newest first (`BR-080`).
 *
 * Every query is scoped by `organization_id` (`BR-001`); nothing here fetches a record by primary key
 * alone. Visit-level events are read through the Visit ids the Job owns, so a Visit of another Job is
 * never projected. The `Visit N` sequence is derived from the Visit's creation order within the Job
 * and is a presentation label, never an identifier and never a stored field
 * (`docs/domain/job-visit-domain-model.md` §6).
 *
 * **Removed evidence** (`BR-088`, `BR-089`) is a projection rule rather than a stored flag: a photo is
 * out of ordinary use when a removal row records it, the photo's own row stays untouched and
 * append-only, and the removal appears as its own event beside it. Every read therefore sees the
 * removal; only [JobActivityOptions.includeRemovedEvidence] additionally shows the evidence it
 * removed.
 */
export async function readJobActivity(
  db: DatabaseService['db'],
  scope: OrganizationScope,
  jobId: string,
  options: JobActivityOptions = {},
): Promise<JobActivityEventDto[]> {
  const visitRows = await db
    .select({ id: visits.id, createdAt: visits.createdAt })
    .from(visits)
    .where(
      and(
        eq(visits.organizationId, scope.organizationId),
        eq(visits.jobId, jobId),
      ),
    )
    .orderBy(asc(visits.createdAt), asc(visits.id));

  const sequenceByVisit = new Map<string, number>();
  visitRows.forEach((visit, index) => sequenceByVisit.set(visit.id, index + 1));
  const visitIds = [...sequenceByVisit.keys()];

  const [
    jobStatusRows,
    jobPropertyRows,
    jobCustomerRows,
    visitStatusRows,
    scheduleRows,
    technicianRows,
    outcomeRows,
    noteRows,
    photoRows,
    removalRows,
    audioNoteRows,
    audioRemovalRows,
  ] = await Promise.all([
    db
      .select({
        id: jobStatusHistory.id,
        fromStatus: jobStatusHistory.fromStatus,
        toStatus: jobStatusHistory.toStatus,
        actorMembershipId: jobStatusHistory.actorMembershipId,
        recordedAt: jobStatusHistory.recordedAt,
      })
      .from(jobStatusHistory)
      .where(
        and(
          eq(jobStatusHistory.organizationId, scope.organizationId),
          eq(jobStatusHistory.jobId, jobId),
        ),
      ),
    db
      .select({
        id: jobPropertyHistory.id,
        actorMembershipId: jobPropertyHistory.actorMembershipId,
        recordedAt: jobPropertyHistory.recordedAt,
      })
      .from(jobPropertyHistory)
      .where(
        and(
          eq(jobPropertyHistory.organizationId, scope.organizationId),
          eq(jobPropertyHistory.jobId, jobId),
        ),
      ),
    db
      .select({
        id: jobCustomerHistory.id,
        actorMembershipId: jobCustomerHistory.actorMembershipId,
        recordedAt: jobCustomerHistory.recordedAt,
      })
      .from(jobCustomerHistory)
      .where(
        and(
          eq(jobCustomerHistory.organizationId, scope.organizationId),
          eq(jobCustomerHistory.jobId, jobId),
        ),
      ),
    selectVisitHistory(db, scope, visitIds, visitStatusHistory, {
      id: visitStatusHistory.id,
      visitId: visitStatusHistory.visitId,
      fromStatus: visitStatusHistory.fromStatus,
      toStatus: visitStatusHistory.toStatus,
      actorMembershipId: visitStatusHistory.actorMembershipId,
      recordedAt: visitStatusHistory.recordedAt,
    }),
    selectVisitHistory(db, scope, visitIds, visitScheduleHistory, {
      id: visitScheduleHistory.id,
      visitId: visitScheduleHistory.visitId,
      previousScheduledStart: visitScheduleHistory.previousScheduledStart,
      actorMembershipId: visitScheduleHistory.actorMembershipId,
      recordedAt: visitScheduleHistory.recordedAt,
    }),
    selectVisitHistory(db, scope, visitIds, visitTechnicianHistory, {
      id: visitTechnicianHistory.id,
      visitId: visitTechnicianHistory.visitId,
      event: visitTechnicianHistory.event,
      technicianMembershipId: visitTechnicianHistory.technicianMembershipId,
      roleCode: visitTechnicianHistory.roleCode,
      previousRoleCode: visitTechnicianHistory.previousRoleCode,
      actorMembershipId: visitTechnicianHistory.actorMembershipId,
      recordedAt: visitTechnicianHistory.recordedAt,
    }),
    selectVisitHistory(db, scope, visitIds, visitOutcomeHistory, {
      id: visitOutcomeHistory.id,
      visitId: visitOutcomeHistory.visitId,
      outcomeCode: visitOutcomeHistory.outcomeCode,
      outcomeSummary: visitOutcomeHistory.outcomeSummary,
      actorMembershipId: visitOutcomeHistory.actorMembershipId,
      recordedAt: visitOutcomeHistory.recordedAt,
    }),
    selectVisitHistory(db, scope, visitIds, visitNotes, {
      id: visitNotes.id,
      visitId: visitNotes.visitId,
      authorMembershipId: visitNotes.authorMembershipId,
      body: visitNotes.body,
      recordedAt: visitNotes.recordedAt,
    }),
    db
      .select({
        id: jobPhotos.id,
        uploaderMembershipId: jobPhotos.uploaderMembershipId,
        phase: jobPhotos.phase,
        note: jobPhotos.note,
        recordedAt: jobPhotos.recordedAt,
      })
      .from(jobPhotos)
      .where(
        and(
          eq(jobPhotos.organizationId, scope.organizationId),
          eq(jobPhotos.jobId, jobId),
        ),
      ),
    // The removals of this Job's photos. The join is what bounds them to the Job: a removal names its
    // photo, and the photo names the Job (`BR-001`, `BR-089`).
    db
      .select({
        id: jobPhotoRemovals.id,
        jobPhotoId: jobPhotoRemovals.jobPhotoId,
        actorMembershipId: jobPhotoRemovals.actorMembershipId,
        reason: jobPhotoRemovals.reason,
        recordedAt: jobPhotoRemovals.recordedAt,
      })
      .from(jobPhotoRemovals)
      .innerJoin(jobPhotos, eq(jobPhotos.id, jobPhotoRemovals.jobPhotoId))
      .where(
        and(
          eq(jobPhotoRemovals.organizationId, scope.organizationId),
          eq(jobPhotos.jobId, jobId),
        ),
      ),
    // The audio notes of this Job, and their removals — the same two reads the photo kind has, because
    // an audio note is evidence of its own kind with the same projection rules (`BR-091`, `ADR-018` A1).
    db
      .select({
        id: jobAudioNotes.id,
        uploaderMembershipId: jobAudioNotes.uploaderMembershipId,
        phase: jobAudioNotes.phase,
        note: jobAudioNotes.note,
        durationSeconds: jobAudioNotes.durationSeconds,
        recordedAt: jobAudioNotes.recordedAt,
      })
      .from(jobAudioNotes)
      .where(
        and(
          eq(jobAudioNotes.organizationId, scope.organizationId),
          eq(jobAudioNotes.jobId, jobId),
        ),
      ),
    db
      .select({
        id: jobAudioNoteRemovals.id,
        jobAudioNoteId: jobAudioNoteRemovals.jobAudioNoteId,
        actorMembershipId: jobAudioNoteRemovals.actorMembershipId,
        reason: jobAudioNoteRemovals.reason,
        recordedAt: jobAudioNoteRemovals.recordedAt,
      })
      .from(jobAudioNoteRemovals)
      .innerJoin(
        jobAudioNotes,
        eq(jobAudioNotes.id, jobAudioNoteRemovals.jobAudioNoteId),
      )
      .where(
        and(
          eq(jobAudioNoteRemovals.organizationId, scope.organizationId),
          eq(jobAudioNotes.jobId, jobId),
        ),
      ),
  ]);

  const removedPhotoIds = new Set(removalRows.map((row) => row.jobPhotoId));
  const removedAudioNoteIds = new Set(
    audioRemovalRows.map((row) => row.jobAudioNoteId),
  );

  const rawEvents: RawEvent[] = [
    ...jobStatusRows.map((row): RawEvent => ({
      id: row.id,
      kind: 'JOB_STATUS_CHANGED',
      recordedAt: row.recordedAt as Date,
      actorMembershipId: row.actorMembershipId,
      visitId: null,
      fromStatus: row.fromStatus,
      toStatus: row.toStatus,
      technicianMembershipId: null,
      roleCode: null,
      previousRoleCode: null,
      outcomeCode: null,
      outcomeSummary: null,
      body: null,
    })),
    ...jobPropertyRows.map((row): RawEvent => ({
      id: row.id,
      kind: 'JOB_PROPERTY_CHANGED',
      recordedAt: row.recordedAt as Date,
      actorMembershipId: row.actorMembershipId,
      visitId: null,
      fromStatus: null,
      toStatus: null,
      technicianMembershipId: null,
      roleCode: null,
      previousRoleCode: null,
      outcomeCode: null,
      outcomeSummary: null,
      body: null,
    })),
    ...jobCustomerRows.map((row): RawEvent => ({
      id: row.id,
      kind: 'JOB_CUSTOMER_CHANGED',
      recordedAt: row.recordedAt as Date,
      actorMembershipId: row.actorMembershipId,
      visitId: null,
      fromStatus: null,
      toStatus: null,
      technicianMembershipId: null,
      roleCode: null,
      previousRoleCode: null,
      outcomeCode: null,
      outcomeSummary: null,
      body: null,
    })),
    ...visitStatusRows.map((row): RawEvent => ({
      id: row.id,
      kind: 'VISIT_STATUS_CHANGED',
      recordedAt: row.recordedAt as Date,
      actorMembershipId: row.actorMembershipId,
      visitId: row.visitId,
      fromStatus: row.fromStatus,
      toStatus: row.toStatus,
      technicianMembershipId: null,
      roleCode: null,
      previousRoleCode: null,
      outcomeCode: null,
      outcomeSummary: null,
      body: null,
    })),
    ...scheduleRows.map((row): RawEvent => ({
      id: row.id,
      kind:
        row.previousScheduledStart === null
          ? 'VISIT_SCHEDULED'
          : 'VISIT_RESCHEDULED',
      recordedAt: row.recordedAt as Date,
      actorMembershipId: row.actorMembershipId,
      visitId: row.visitId,
      fromStatus: null,
      toStatus: null,
      technicianMembershipId: null,
      roleCode: null,
      previousRoleCode: null,
      outcomeCode: null,
      outcomeSummary: null,
      body: null,
    })),
    ...technicianRows.map((row): RawEvent => ({
      id: row.id,
      kind: technicianEventKind(row.event),
      recordedAt: row.recordedAt as Date,
      actorMembershipId: row.actorMembershipId,
      visitId: row.visitId,
      fromStatus: null,
      toStatus: null,
      technicianMembershipId: row.technicianMembershipId,
      roleCode: row.roleCode as AssignmentRoleCode | null,
      previousRoleCode: row.previousRoleCode as AssignmentRoleCode | null,
      outcomeCode: null,
      outcomeSummary: null,
      body: null,
    })),
    ...outcomeRows.map((row): RawEvent => ({
      id: row.id,
      kind: 'VISIT_OUTCOME_RECORDED',
      recordedAt: row.recordedAt as Date,
      actorMembershipId: row.actorMembershipId,
      visitId: row.visitId,
      fromStatus: null,
      toStatus: null,
      technicianMembershipId: null,
      roleCode: null,
      previousRoleCode: null,
      outcomeCode: row.outcomeCode,
      outcomeSummary: row.outcomeSummary,
      body: null,
    })),
    ...noteRows.map((row): RawEvent => ({
      id: row.id,
      kind: 'VISIT_NOTE_ADDED',
      recordedAt: row.recordedAt as Date,
      actorMembershipId: row.authorMembershipId,
      visitId: row.visitId,
      fromStatus: null,
      toStatus: null,
      technicianMembershipId: null,
      roleCode: null,
      previousRoleCode: null,
      outcomeCode: null,
      outcomeSummary: null,
      body: row.body,
    })),
    // A photo is Job-level evidence (`BR-015`, `BR-027`): it carries no Visit, its actor is the
    // member who uploaded it, and its optional note travels in `body`, which is the field a client
    // already renders as an entry's own text (`BR-080`). A photo that has been removed is left out of
    // an ordinary read — the evidence is out of use (`BR-089`) — and included when the caller asks for
    // the audit/history context (`D6d`).
    ...photoRows
      .filter(
        (row) =>
          options.includeRemovedEvidence === true ||
          !removedPhotoIds.has(row.id),
      )
      .map((row): RawEvent => ({
        id: row.id,
        kind: 'JOB_PHOTO_ADDED',
        recordedAt: row.recordedAt as Date,
        actorMembershipId: row.uploaderMembershipId,
        visitId: null,
        fromStatus: null,
        toStatus: null,
        technicianMembershipId: null,
        roleCode: null,
        previousRoleCode: null,
        outcomeCode: null,
        outcomeSummary: null,
        body: row.note,
        photoId: row.id,
        photoPhase: row.phase,
      })),
    // The removal is history in its own right (`BR-067`, `BR-089`) and is part of **every** read, so
    // Activity never silently drops the fact that evidence was taken out of use; the reason it was
    // removed travels in its own field rather than in `body` (`BR-080`, `docs/api/job-activity.md`).
    ...removalRows.map((row): RawEvent => ({
      id: row.id,
      kind: 'JOB_PHOTO_REMOVED',
      recordedAt: row.recordedAt as Date,
      actorMembershipId: row.actorMembershipId,
      visitId: null,
      fromStatus: null,
      toStatus: null,
      technicianMembershipId: null,
      roleCode: null,
      previousRoleCode: null,
      outcomeCode: null,
      outcomeSummary: null,
      body: null,
      photoId: row.jobPhotoId,
      photoRemovalReason: row.reason,
    })),
    // An audio note is Job-level evidence of its own kind, projected exactly as a photo is: its actor is
    // the member who recorded it, its optional note travels in `body`, and its length is carried so a
    // client can draw the recording without opening it (`BR-080`, `BR-091`, `ADR-018` A6). A recording
    // that has been removed is left out of an ordinary read — it is out of use (`BR-089`) — and included
    // when the caller asks for the audit/history context (`D6d`).
    ...audioNoteRows
      .filter(
        (row) =>
          options.includeRemovedEvidence === true ||
          !removedAudioNoteIds.has(row.id),
      )
      .map((row): RawEvent => ({
        id: row.id,
        kind: 'JOB_AUDIO_ADDED',
        recordedAt: row.recordedAt as Date,
        actorMembershipId: row.uploaderMembershipId,
        visitId: null,
        fromStatus: null,
        toStatus: null,
        technicianMembershipId: null,
        roleCode: null,
        previousRoleCode: null,
        outcomeCode: null,
        outcomeSummary: null,
        body: row.note,
        audioNoteId: row.id,
        audioPhase: row.phase,
        audioDurationSeconds: row.durationSeconds,
      })),
    // The removal is history in its own right (`BR-067`, `BR-089`) and is part of every read, exactly as
    // a photo's removal is, so Activity never silently drops the fact that evidence was taken out of use.
    ...audioRemovalRows.map((row): RawEvent => ({
      id: row.id,
      kind: 'JOB_AUDIO_REMOVED',
      recordedAt: row.recordedAt as Date,
      actorMembershipId: row.actorMembershipId,
      visitId: null,
      fromStatus: null,
      toStatus: null,
      technicianMembershipId: null,
      roleCode: null,
      previousRoleCode: null,
      outcomeCode: null,
      outcomeSummary: null,
      body: null,
      audioNoteId: row.jobAudioNoteId,
      audioRemovalReason: row.reason,
    })),
  ];

  const membershipIds = new Set<string>();
  for (const event of rawEvents) {
    membershipIds.add(event.actorMembershipId);
    if (event.technicianMembershipId !== null) {
      membershipIds.add(event.technicianMembershipId);
    }
  }

  const nameByMember = await resolveMemberNames(db, scope, [...membershipIds]);

  rawEvents.sort(
    (left, right) =>
      right.recordedAt.getTime() - left.recordedAt.getTime() ||
      left.id.localeCompare(right.id),
  );

  return rawEvents.map((event) => ({
    id: event.id,
    kind: event.kind,
    recordedAt: event.recordedAt.toISOString(),
    actorName: nameByMember.get(event.actorMembershipId) ?? null,
    visitSequence:
      event.visitId === null
        ? null
        : (sequenceByVisit.get(event.visitId) ?? null),
    fromStatus: event.fromStatus,
    toStatus: event.toStatus,
    technicianName:
      event.technicianMembershipId === null
        ? null
        : (nameByMember.get(event.technicianMembershipId) ?? null),
    roleCode: event.roleCode,
    previousRoleCode: event.previousRoleCode,
    outcomeCode: event.outcomeCode,
    outcomeSummary: event.outcomeSummary,
    body: event.body,
    photoId: event.photoId ?? null,
    photoPhase: event.photoPhase ?? null,
    photoRemovalReason: event.photoRemovalReason ?? null,
    audioNoteId: event.audioNoteId ?? null,
    audioPhase: event.audioPhase ?? null,
    audioDurationSeconds: event.audioDurationSeconds ?? null,
    audioRemovalReason: event.audioRemovalReason ?? null,
  }));
}

/** A Visit-scoped history read, so the visitIds a Job owns bound every Visit-level projection. */
// eslint-disable-next-line @typescript-eslint/no-explicit-any
function selectVisitHistory(
  db: DatabaseService['db'],
  scope: OrganizationScope,
  visitIds: readonly string[],
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  table: any,
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  selection: any,
): Promise<any[]> {
  if (visitIds.length === 0) {
    return Promise.resolve([]);
  }
  return db
    .select(selection)
    .from(table)
    .where(
      and(
        eq(table.organizationId, scope.organizationId),
        inArray(table.visitId, [...visitIds]),
      ),
    );
}

/** Maps the `visit_technician_history.event` code onto the activity kind (`BR-041`). */
function technicianEventKind(event: string): JobActivityKind {
  switch (event) {
    case 'ASSIGNED':
      return 'VISIT_TECHNICIAN_ASSIGNED';
    case 'REMOVED':
      return 'VISIT_TECHNICIAN_REMOVED';
    case 'ROLE_CHANGED':
      return 'VISIT_TECHNICIAN_ROLE_CHANGED';
    default:
      // The schema CHECK closes the vocabulary, so an unknown event is a programming error rather
      // than a value the read must present (`BR-042`).
      throw new Error(`Unknown visit_technician_history event: ${event}`);
  }
}

/** Resolves one name for each referenced member, the one way the whole API resolves names (`BR-041`). */
async function resolveMemberNames(
  db: DatabaseService['db'],
  scope: OrganizationScope,
  membershipIds: readonly string[],
): Promise<Map<string, string | null>> {
  if (membershipIds.length === 0) {
    return new Map();
  }
  const rows = await db
    .select({
      id: organizationMembers.id,
      displayName: userProfiles.displayName,
      firstName: userProfiles.firstName,
      lastName: userProfiles.lastName,
    })
    .from(organizationMembers)
    .leftJoin(userProfiles, eq(userProfiles.userId, organizationMembers.userId))
    .where(
      and(
        eq(organizationMembers.organizationId, scope.organizationId),
        inArray(organizationMembers.id, [...membershipIds]),
      ),
    );
  return new Map(rows.map((row) => [row.id, memberName(row)]));
}
