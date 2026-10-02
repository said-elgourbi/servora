package com.servora.android.domain.model

/**
 * Stable, machine-readable Visit status codes (`BR-074`).
 *
 * The backend's vocabulary is the only one (`BR-041`): the UI resolves a localized label for a code
 * and never invents one of its own. `NO_SHOW` is not a Visit status in the canonical lifecycle — an
 * attempt that did not happen is a cancellation with its own reason — so it is absent rather than
 * kept as a state nothing can produce.
 */
enum class VisitStatus {
    DRAFT,
    SCHEDULED,
    EN_ROUTE,
    ON_SITE,
    IN_PROGRESS,
    COMPLETED,
    CANCELED,
    ;

    companion object {
        /**
         * The four states a technician selects between directly (`BR-074`).
         *
         * They are the selector's vocabulary and the order it presents them in, which is the order a
         * successful field attempt usually follows — the order is a presentation, never a required
         * path: the API's own `allowedStatusTransitions` decides which of them the Visit may be moved
         * to right now, and each one is a destination of its own (`BR-074`).
         *
         * `DRAFT` is not one of them (an unscheduled Visit is not work a technician is doing),
         * `COMPLETED` is not (finishing a Visit is the explicit completion action, `BR-077`) and
         * `CANCELED` is not (cancelling is a dispatch action, `BR-066`, `BR-076`).
         */
        val technicianWorkingStates: List<VisitStatus> = listOf(
            SCHEDULED,
            EN_ROUTE,
            ON_SITE,
            IN_PROGRESS,
        )
    }
}

/**
 * Why a Job or Visit is on the manager home's "Needs attention" list.
 *
 * Each kind is a condition the backend derived from authoritative records (`BR-060`, `BR-072`,
 * `BR-074`, `BR-078`). The client presents the condition; it never decides one (`BR-001`), and it holds
 * no second copy of the vocabulary: a kind this build does not know fails the whole read rather than
 * being dropped, because a screen that dropped one would describe an operation that is not the one the
 * backend reported (`BR-042`).
 */
enum class ManagerAttentionKind {
    /** A Visit still `SCHEDULED` after its scheduled window ended (`BR-072`, `BR-074`). */
    VISIT_OVERDUE,

    /** A Job with no active Visit, so it needs scheduling (`BR-060`). */
    JOB_NEEDS_SCHEDULING,

    /**
     * The Job's latest completed Visit reported `NEEDS_FOLLOW_UP` and no later Visit is actionable, so
     * another field attempt has to be arranged (`BR-078`).
     */
    FOLLOW_UP_NEEDS_SCHEDULING,

    /**
     * The Job's latest completed Visit reported `NEEDS_PARTS`: the work waits on parts, and the absence
     * of a further Visit is deliberate rather than an oversight (`BR-078`).
     */
    PARTS_REQUIRED,

    /** The Job's latest completed Visit reported `UNABLE_TO_COMPLETE` (`BR-078`). */
    UNABLE_TO_COMPLETE,
}

/**
 * One item of the manager home's "Needs attention" list.
 *
 * The API also reports a `reasonCode` beside the kind. It is `null` for every condition the API derives
 * today, because no structured reason catalogue is approved, so the client models no field for it and
 * invents no reason vocabulary of its own (`BR-042`).
 */
data class ManagerAttentionItem(
    val kind: ManagerAttentionKind,
    val jobId: String,
    val jobNumber: Int,
    val jobTitle: String,
    val jobStatus: JobStatus,
    val customerId: String,
    val customerName: String,
    /** The Visit the condition is about; `null` for a Job-level condition. */
    val visitId: String?,
    /** ISO-8601 UTC instant of the condition Visit's scheduled start; `null` when it has none. */
    val scheduledStart: String?,
    val scheduledEnd: String?,
)

/** One technician assigned to a Visit (`BR-068`). [name] is absent without a member profile. */
data class ManagerHomeTechnician(
    val membershipId: String,
    val name: String?,
    val roleCode: String,
)

/** The address a Visit row shows (`BR-056`). Every part may be absent. */
data class ManagerHomeAddress(
    val propertyName: String?,
    val addressLine1: String?,
    val addressLine2: String?,
    val city: String?,
    val province: String?,
    val postalCode: String?,
    val country: String?,
)

/** One Visit of today's schedule, with the Job and Customer context its row shows. */
data class ManagerHomeVisit(
    val visitId: String,
    val visitStatus: VisitStatus,
    /** ISO-8601 UTC instant the Visit is scheduled to start. */
    val scheduledStart: String,
    val scheduledEnd: String,
    val jobId: String,
    val jobNumber: Int,
    val jobTitle: String,
    val jobStatus: JobStatus,
    val customerId: String,
    val customerName: String,
    val address: ManagerHomeAddress?,
    /** Assigned technicians, Lead first (`BR-068`); empty when the Visit has none. */
    val technicians: List<ManagerHomeTechnician>,
    /**
     * Whether the backend reports the Visit as overdue.
     *
     * It is derived from the server clock, so the row presents the condition without deciding it
     * from the device clock (`BR-001`).
     */
    val isOverdue: Boolean,
)

/**
 * The derived counts today's summary shows.
 *
 * The three named counts partition [total]; exception counts belong to "Needs attention" and are
 * deliberately not repeated here.
 */
data class ManagerHomeTodaySummary(
    val total: Int,
    val completed: Int,
    val inProgress: Int,
    val upcoming: Int,
)

/**
 * Everything the manager home renders, as the backend reported it (`BR-010`).
 *
 * Nothing is stored on the client as authoritative: the manager home is a read over backend records
 * and is refreshed from the API (`BR-001`).
 */
data class ManagerHome(
    /** The signed-in member's name, or `null` when they have no profile yet. */
    val displayName: String?,
    val attention: List<ManagerAttentionItem>,
    /** Every matching condition, including any beyond [attention]'s size. */
    val attentionTotal: Int,
    val today: ManagerHomeTodaySummary,
    val visits: List<ManagerHomeVisit>,
)
