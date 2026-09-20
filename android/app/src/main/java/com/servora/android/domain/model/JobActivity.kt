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

    /** A photo was added to the Job as field evidence (`BR-015`, `BR-027`). */
    JOB_PHOTO_ADDED,

    /**
     * Accepted photo evidence was taken out of ordinary use (`BR-088`, `BR-089`).
     *
     * It is history in its own right: the entry states that evidence was removed, and the reason
     * travels in [JobActivityEvent.photoRemovalReason]. The photo itself leaves the ordinary timeline,
     * so a client draws no photo for this kind.
     */
    JOB_PHOTO_REMOVED,

    /** A recording was added to the Job as field evidence (`BR-091`, `BR-027`). */
    JOB_AUDIO_ADDED,

    /**
     * Accepted audio evidence was taken out of ordinary use (`BR-088`, `BR-089`).
     *
     * It mirrors [JOB_PHOTO_REMOVED] for the audio kind (`ADR-018` A6): the entry states that the
     * recording was removed, the reason travels in [JobActivityEvent.audioRemovalReason], and the
     * recording itself leaves the ordinary timeline, so a client draws no player for this kind.
     */
    JOB_AUDIO_REMOVED,
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
    /** The note's text (`VISIT_NOTE_ADDED`), or a photo's optional note (`JOB_PHOTO_ADDED`). */
    val body: String?,
    /**
     * The photo's identifier (`JOB_PHOTO_ADDED`), which is the value the bytes are asked for with.
     *
     * It is absent on every other kind, and it is `null` for a photo whose id this build cannot read,
     * which is why the screen checks both it and [photoPhase] before it draws a photo (`BR-042`).
     */
    val photoId: String? = null,
    /** The field-work phase a photo was taken in (`JOB_PHOTO_ADDED`), as the API's stable code. */
    val photoPhase: String? = null,
    /**
     * Why accepted photo evidence was removed (`JOB_PHOTO_REMOVED`, `BR-089`), or `null`.
     *
     * It is its own field rather than [body], because the two say different things: `body` is the text
     * an author recorded with a record, while this is the reason a Manager gave for taking evidence out
     * of ordinary use (`BR-028`).
     */
    val photoRemovalReason: String? = null,
    /**
     * The recording's identifier (`JOB_AUDIO_ADDED`, `JOB_AUDIO_REMOVED`), which is the value its
     * bytes are asked for with (`docs/api/job-audio.md` §4.1).
     *
     * It is its own field rather than [photoId], because the two name evidence of different kinds
     * whose bytes are read on their own routes (`BR-041`, `ADR-018` A1).
     */
    val audioNoteId: String? = null,
    /** The field-work phase a recording was captured in (`JOB_AUDIO_ADDED`), as the API's stable code. */
    val audioPhase: String? = null,
    /**
     * The recording's length in whole seconds (`JOB_AUDIO_ADDED`).
     *
     * It is the length the **API read from the recording's own container** (`ADR-018` A3) and is what
     * the timeline states, so a client draws a recording it has never opened. A recording whose length
     * this build cannot read is drawn without one rather than with a guess (`BR-042`).
     */
    val audioDurationSeconds: Int? = null,
    /**
     * Why accepted audio evidence was removed (`JOB_AUDIO_REMOVED`, `BR-089`), or `null`.
     *
     * It is its own field for the same reason [photoRemovalReason] is: it is the reason a removal was
     * performed, not text an author recorded with the evidence (`BR-028`).
     */
    val audioRemovalReason: String? = null,
)
