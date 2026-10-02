package com.servora.android.data.schedule

import kotlinx.serialization.Serializable

/**
 * `POST /jobs/ad-hoc-work-reports` — the report a technician submits (`BR-AH-001`).
 *
 * When the technician can identify the canonical Customer/Property/Job, the ids are sent and the
 * provenance fields stay `null`; when they cannot, the provenance fields carry the reported facts and
 * the ids stay `null`. The two are never combined: free text is provenance only and never creates a
 * canonical record (`BR-AH-009`).
 */
@Serializable
data class SubmitAdHocWorkReportRequestDto(
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
    val clientOperationId: String? = null,
)

/** One Customer the type-ahead search answers (`GET .../customer-options`). */
@Serializable
data class AdHocReportCustomerOptionDto(
    val id: String,
    val displayName: String,
)

/** One active Property of the selected Customer (`GET .../property-options`). */
@Serializable
data class AdHocReportPropertyOptionDto(
    val id: String,
    val name: String? = null,
    val addressLine1: String,
    val addressLine2: String? = null,
    val city: String,
    val province: String,
    val postalCode: String,
    val country: String,
)

/** One Job of the selected Customer, the optional related-work hint (`GET .../job-options`). */
@Serializable
data class AdHocReportJobOptionDto(
    val id: String,
    val jobNumber: Int,
    val title: String,
)

@Serializable
data class AdHocWorkReportDto(
    val id: String,
    val workStartedAt: String,
    val workEndedAt: String,
    val outcomeCode: String,
    val summary: String,
    val notes: String? = null,
    val status: String,
    val version: Int,
    val createdAt: String,
)
