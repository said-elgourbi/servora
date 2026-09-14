package com.servora.android.domain.model

/**
 * The stable Job Activity event kinds the API exchanges (`BR-080`, `BR-041`).
 *
 * The code is the machine-readable contract; the client resolves a localized label for it rather than
 * inventing a parallel vocabulary (`BR-028`, `BR-041`, `BR-042`). One Job Activity read emits a single
 * chronological list over both Job-level and Visit-level events, newest first (`BR-080`).
 */
enum class JobActivityKind {
    /** The Job's lifecycle status changed (`BR-058`). */
    JOB_STATUS_CHANGED,

    /** The Job's Property changed (`BR-056`). */
    JOB_PROPERTY_CHANGED,

    /** The Job's Customer changed (`BR-048`). */
    JOB_CUSTOMER_CHANGED,

    /** A Visit's field status changed (`BR-074`). */
    VISIT_STATUS_CHANGED,

    /** A Visit received its first schedule (`BR-072`). */
    VISIT_SCHEDULED,

    /** A Visit's schedule was edited (`BR-073`). */
    VISIT_RESCHEDULED,

    /** A technician was assigned to a Visit (`BR-068`). */
    VISIT_TECHNICIAN_ASSIGNED,

    /** A technician was removed from a Visit (`BR-069`). */
    VISIT_TECHNICIAN_REMOVED,

    /** A technician's assignment role changed (`BR-069`). */
    VISIT_TECHNICIAN_ROLE_CHANGED,

    /** A Visit's outcome was recorded (`BR-077`, `BR-078`). */
    VISIT_OUTCOME_RECORDED,

    /** A note was added to a Visit (`BR-027`). */
    VISIT_NOTE_ADDED,
}

/**
 * One Job Activity event, projected by the backend (`BR-080`).
 *
 * Every field is a projection of an authoritative history row (`BR-001`). [visitSequence] is `null` for
 * a Job-level event and otherwise the Visit's stable, human-readable sequence — `Visit 1`, `Visit 2`,
 * … — never a database id (`docs/domain/job-visit-domain-model.md` §6). Status and role values are the
 * stable codes the API exchanges; the screen resolves a localized label for them.
 */
data class JobActivityEvent(
    val id: String,
    val kind: JobActivityKind,
    /** ISO-8601 UTC instant the event was recorded. */
    val recordedAt: String,
    /** The member who performed the event; `null` when they have no profile yet (`BR-020`). */
    val actorName: String?,
    /** The Visit's sequence, or `null` for a Job-level event. */
    val visitSequence: Int?,
    /** The previous status (`JOB_STATUS_CHANGED`, `VISIT_STATUS_CHANGED`). */
    val fromStatus: String?,
    /** The new status (`JOB_STATUS_CHANGED`, `VISIT_STATUS_CHANGED`). */
    val toStatus: String?,
    /** The technician the event is about (`VISIT_TECHNICIAN_*`). */
    val technicianName: String?,
    /** The new assignment role (`VISIT_TECHNICIAN_ASSIGNED`, `VISIT_TECHNICIAN_ROLE_CHANGED`). */
    val roleCode: String?,
    /** The role left behind (`VISIT_TECHNICIAN_ROLE_CHANGED`). */
    val previousRoleCode: String?,
    /** The outcome recorded (`VISIT_OUTCOME_RECORDED`). */
    val outcomeCode: String?,
    /** The outcome's summary (`VISIT_OUTCOME_RECORDED`). */
    val outcomeSummary: String?,
    /** The note's text (`VISIT_NOTE_ADDED`). */
    val body: String?,
)
