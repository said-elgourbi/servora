package com.servora.android.domain.model

/**
 * Stable, machine-readable Job status codes (`BR-058`).
 *
 * The backend's vocabulary is the only one (`BR-041`): the UI resolves a localized label for a code
 * and never invents one of its own. The canonical lifecycle has four statuses — a request is `NEW`,
 * `ACTIVE`, `COMPLETED` or `CANCELED` — and the states it no longer has (`SCHEDULED`,
 * `IN_PROGRESS`, `PENDING_REVIEW`) are absent here rather than kept as labels nothing can produce,
 * because a Job is never *in progress*: its Visit is, and the two are separate state machines
 * (`BR-059`). Office attention is a derived read (`BR-060`), never a status.
 */
enum class JobStatus {
    /** The Job exists but has not entered execution or scheduling yet. */
    NEW,

    /** The request is open and has entered execution; it does not mean work is happening right now. */
    ACTIVE,

    /** The request has been resolved. */
    COMPLETED,

    /** The request was canceled rather than resolved. */
    CANCELED,
}

/**
 * Why a Job's field-facing actions are read-only, as the API reports it (`BR-062`, `BR-079`).
 *
 * It is the API's own answer, projected as `readOnlyReason`, and never something a client works out
 * from the status: a closed Job refuses field work, and a screen that offers the action anyway would
 * be drawing a control whose only answer is a refusal (`BR-007`, `BR-041`).
 */
enum class JobReadOnlyReason {
    /** The Job is `COMPLETED`, so its field record is final. */
    JOB_COMPLETED,

    /** The Job is `CANCELED`, so nothing further may be recorded under it. */
    JOB_CANCELED,
}

/** Reads the wire code of a read-only reason, or `null` when the Job is not read-only. */
fun jobReadOnlyReasonOrNull(code: String?): JobReadOnlyReason? =
    JobReadOnlyReason.entries.firstOrNull { reason -> reason.name == code }

/** One of a customer's Properties, with the values the detail row renders (`BR-081`). */
data class CustomerProperty(
    val id: String,
    val name: String?,
    val addressLine1: String,
    val addressLine2: String?,
    val city: String,
    val province: String,
    val postalCode: String,
    val country: String,
    val jobCount: Int,
    /** ISO-8601 UTC instant of the newest completed Visit; `null` when never serviced. */
    val lastServiceAt: String?,
    /** The Property's lifecycle state (`BR-082`); the default projection is `ACTIVE`. */
    val status: PropertyStatus = PropertyStatus.ACTIVE,
)

/** One technician assigned to a Visit (`BR-068`). [name] is absent without a member profile. */
data class CustomerJobTechnician(
    val membershipId: String,
    val name: String?,
    val roleCode: String,
)

/** One of a customer's Jobs, carrying its selected Visit's derived values (`BR-081`). */
data class CustomerJob(
    val id: String,
    val jobNumber: Int,
    val title: String,
    val status: JobStatus,
    val address: CustomerJobAddress?,
    /** ISO-8601 UTC instant of the selected Visit's scheduled start; `null` when none. */
    val scheduledStart: String?,
    val technicians: List<CustomerJobTechnician>,
)

/** The Job's preserved address snapshot (`BR-056`). Every part may be absent. */
data class CustomerJobAddress(
    val propertyName: String?,
    val addressLine1: String?,
    val addressLine2: String?,
    val city: String?,
    val province: String?,
    val postalCode: String?,
    val country: String?,
)

/**
 * Everything the customer detail screen renders (`BR-081`).
 *
 * The projections are the backend's: this type carries what the API derived, so the screen formats
 * dates and status codes rather than choosing which Visit or Property value to show (`BR-041`).
 */
data class CustomerDetail(
    val customer: Customer,
    /**
     * The customer's subtype record (`BR-023`).
     *
     * Exactly one is present in a backend read: [individual] for a `INDIVIDUAL` customer and
     * [company] for a `COMPANY` one. The Edit Customer form reads it to pre-populate the fields of
     * the customer's current type; the detail screen itself does not need it.
     */
    val individual: CustomerIndividual? = null,
    val company: CustomerCompany? = null,
    val contacts: List<CustomerContact>,
    val properties: List<CustomerProperty>,
    val jobs: List<CustomerJob>,
    /**
     * The customer's archived Properties (`BR-082`).
     *
     * The default projection excludes them (`BR-081`), so they are read explicitly and presented
     * separately: an archived Property is not active work, but it stays a retrievable record and
     * must remain reachable so it can be restored.
     */
    val archivedProperties: List<CustomerProperty> = emptyList(),
)
