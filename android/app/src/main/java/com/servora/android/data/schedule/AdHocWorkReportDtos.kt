package com.servora.android.data.schedule

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import com.servora.android.data.customers.CreatePropertyRequest

@Serializable
data class ResolveAdHocLocationRequestDto(
    val existingCustomerId: String? = null,
    val newCustomer: JsonObject? = null,
    val existingPropertyId: String? = null,
    val newProperty: CreatePropertyRequest? = null,
    val expectedStatus: String? = null,
    val expectedVersion: Int,
)

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
    val phone: String? = null,
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
    // The read shape (`GET /jobs/ad-hoc-work-reports` and the reconciliation/reject answers).
    val reportingTechnicianMembershipId: String = "",
    val customerId: String? = null,
    val customerName: String? = null,
    val propertyId: String? = null,
    val propertyAddress: ScheduleAddressDto? = null,
    val knownJobId: String? = null,
    val knownJobNumber: Int? = null,
    val knownJobTitle: String? = null,
    val reportedCustomerName: String? = null,
    val reportedCustomerPhone: String? = null,
    val reportedCustomerAddress: String? = null,
    val reviewerMembershipId: String? = null,
    val reviewedAt: String? = null,
    val reviewNote: String? = null,
    val createdJobId: String? = null,
    val createdJobNumber: Int? = null,
    val createdJobTitle: String? = null,
    val createdVisitId: String? = null,
    val updatedAt: String = "",
)

/** `POST .../link` — links a pending report to an existing Job and records its completed Visit. */
@Serializable
data class LinkAdHocWorkReportRequestDto(
    val jobId: String,
    val note: String? = null,
    val expectedStatus: String? = null,
    val expectedVersion: Int? = null,
)

/** `POST .../convert` — creates a new Job from the report's Customer/Property and records its Visit. */
@Serializable
data class ConvertAdHocWorkReportRequestDto(
    val title: String,
    val description: String? = null,
    val note: String? = null,
    val expectedStatus: String? = null,
    val expectedVersion: Int? = null,
)

/** `POST .../reject` — voids a pending report as not legitimate Servora work (`BR-AH-007`). */
@Serializable
data class RejectAdHocWorkReportRequestDto(
    val note: String? = null,
    val expectedStatus: String? = null,
    val expectedVersion: Int? = null,
)

/** The Job the link/convert routes answer with, reduced to what navigation needs. */
@Serializable
data class AdHocReconciliationJobDto(
    val id: String,
    val jobNumber: Int = 0,
)
