package com.servora.android.domain.model

/**
 * Stable, machine-readable Job status codes (`BR-058`).
 *
 * The backend's vocabulary is the only one (`BR-041`): the UI resolves a localized label for a code
 * and never invents one of its own.
 */
enum class JobStatus {
    NEW,
    SCHEDULED,
    IN_PROGRESS,
    PENDING_REVIEW,
    COMPLETED,
    CANCELED,
}

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
    val contacts: List<CustomerContact>,
    val properties: List<CustomerProperty>,
    val jobs: List<CustomerJob>,
)
