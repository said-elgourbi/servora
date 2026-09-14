package com.servora.android.domain.model

/**
 * Stable, machine-readable Visit status codes (`BR-074`).
 *
 * The backend's vocabulary is the only one (`BR-041`): the UI resolves a localized label for a code
 * and never invents one of its own.
 */
enum class VisitStatus {
    DRAFT,
    SCHEDULED,
    EN_ROUTE,
    ON_SITE,
    IN_PROGRESS,
    COMPLETED,
    CANCELED,
    NO_SHOW,
}

/**
 * Why a Job or Visit is on the manager home's "Needs attention" list.
 *
 * Each kind is a condition the backend derived from authoritative records (`BR-060`, `BR-061`,
 * `BR-072`, `BR-074`). The client presents the condition; it never decides one (`BR-001`).
 */
enum class ManagerAttentionKind {
    /** A Visit still `SCHEDULED` after its scheduled window ended. */
    VISIT_OVERDUE,

    /** A Job awaiting the office review only an authorized user performs. */
    JOB_PENDING_REVIEW,

    /** A Job with no active Visit, so it needs scheduling. */
    JOB_NEEDS_SCHEDULING,
}

/** One item of the manager home's "Needs attention" list. */
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
