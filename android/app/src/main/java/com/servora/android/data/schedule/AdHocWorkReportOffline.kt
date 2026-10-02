package com.servora.android.data.schedule

import kotlinx.serialization.Serializable

/**
 * The stable operation codes the ad-hoc work report feature queues
 * (`docs/architecture/offline-first-architecture.md` §4).
 *
 * A report submission is the feature's offline-capable operation: the technician records field work
 * that must not be silently lost, so a submit the API could not be reached for is queued and replayed
 * when connectivity returns (`BR-013`, `BR-014`). The code is machine-readable and never localized
 * (`BR-041`).
 */
object AdHocWorkReportOperationTypes {
    const val SUBMIT = "ad_hoc_work_report.submit"

    /** Every code the ad-hoc work report feature owns. */
    val ALL: Set<String> = setOf(SUBMIT)
}

/**
 * The arguments a queued submission carries, stored as the outbox row's payload (`§4`).
 *
 * It is the whole report the technician filled in. The idempotency key and the device time are
 * properties of the queued operation itself, so the handler takes them from the row — exactly as the
 * Property lifecycle does for its own queued actions.
 */
@Serializable
internal data class AdHocWorkReportOperationPayload(
    val customerId: String? = null,
    val propertyId: String? = null,
    val knownJobId: String? = null,
    val workStartedAt: String,
    val workEndedAt: String,
    val outcomeCode: String,
    val summary: String,
    val notes: String? = null,
    val reportedCustomerName: String? = null,
    val reportedCustomerPhone: String? = null,
    val reportedCustomerAddress: String? = null,
)
