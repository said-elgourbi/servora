package com.servora.android.ui.schedule

import androidx.compose.runtime.Immutable
import com.servora.android.data.customers.CustomersFailureReason
import com.servora.android.domain.model.AdHocReportCustomerOption
import com.servora.android.domain.model.AdHocReportJobOption
import com.servora.android.domain.model.AdHocReportPropertyOption
import com.servora.android.domain.model.VisitOutcome
import java.time.Instant

/**
 * Everything the ad-hoc work report form holds (`BR-AH-001`, `BR-AH-009`).
 *
 * The form discovers the Customer/Property/Job rather than typing ids, so the search and derivation
 * answers live here beside the fields the technician fills in. Which options the API offers is its own
 * answer — the form holds what it reported and never decides a scope (`BR-007`, `BR-042`).
 *
 * [workStartedAt]/[workEndedAt] are business instants the screen resolved in the device's zone from the
 * date/time controls the technician used, sent to the API as ISO-8601. The provenance fields carry the
 * reported facts when the technician could not identify the canonical Customer/Property; when a Customer
 * is selected they stay empty and the ids are submitted instead (`BR-AH-009`).
 */
@Immutable
data class AdHocReportFormState(
    val customerQuery: String = "",
    val customerOptions: List<AdHocReportCustomerOption> = emptyList(),
    val isSearchingCustomers: Boolean = false,
    val customerSearchFailureReason: CustomersFailureReason? = null,
    val selectedCustomer: AdHocReportCustomerOption? = null,
    val propertyOptions: List<AdHocReportPropertyOption> = emptyList(),
    val isReadingProperties: Boolean = false,
    val propertyFailureReason: CustomersFailureReason? = null,
    val selectedProperty: AdHocReportPropertyOption? = null,
    val jobOptions: List<AdHocReportJobOption> = emptyList(),
    val isReadingJobs: Boolean = false,
    val jobFailureReason: CustomersFailureReason? = null,
    val selectedJob: AdHocReportJobOption? = null,
    val workStartedAt: Instant = Instant.EPOCH,
    val workEndedAt: Instant = Instant.EPOCH,
    val outcome: VisitOutcome = VisitOutcome.RESOLVED,
    val summary: String = "",
    val notes: String = "",
    val unknownCustomer: Boolean = false,
    val reportedCustomerName: String = "",
    val reportedCustomerPhone: String = "",
    val reportedCustomerAddress: String = "",
) {
    /** Why the form cannot be submitted, or `null` when it may (`BR-AH-001`, `BR-072`). */
    val problem: AdHocReportProblem?
        get() = when {
            summary.isBlank() -> AdHocReportProblem.NO_SUMMARY
            !workEndedAt.isAfter(workStartedAt) -> AdHocReportProblem.END_NOT_AFTER_START
            else -> null
        }

    /** Whether the form can be submitted as it stands. */
    val canSubmit: Boolean
        get() = problem == null
}

/** Why the values an ad-hoc report form holds cannot be submitted. */
enum class AdHocReportProblem {
    /** No work description was stated, and the API requires one. */
    NO_SUMMARY,

    /** The reported end is not after its start. */
    END_NOT_AFTER_START,
}
