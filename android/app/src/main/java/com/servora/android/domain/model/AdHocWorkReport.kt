package com.servora.android.domain.model

/**
 * The discoverability options the ad-hoc work report form presents (`BR-AH-009`).
 *
 * A technician reports field work that happened without a recorded Visit, and the form lets them say
 * *which* Customer it was for, *which* Property, and optionally *which* Job it relates to. Those
 * choices are discovered, not typed by id: the API answers a scoped search rather than exposing the
 * whole Customer list, and the Property and Job choices are derived from the selected Customer
 * (`BR-050`, `BR-094`).
 *
 * These are presentation values only — a stable identifier plus what the form shows. Which ids the
 * caller may submit is re-validated by the API on the submit route, so a client can never act on an
 * option it was not offered (`BR-001`, `BR-007`).
 */

/** One Customer the reporter may select, as the type-ahead search answers it. */
data class AdHocReportCustomerOption(
    val id: String,
    val displayName: String,
    val phone: String? = null,
)

/** One active Property of the selected Customer the reporter may select (`BR-050`). */
data class AdHocReportPropertyOption(
    val id: String,
    val name: String?,
    val addressLine1: String,
    val addressLine2: String?,
    val city: String,
    val province: String,
    val postalCode: String,
    val country: String,
)

/** One Job of the selected Customer the reporter may name as the related work. */
data class AdHocReportJobOption(
    val id: String,
    val jobNumber: Int,
    val title: String,
)

/**
 * Stable lifecycle states for an ad-hoc work report (`BR-AH-005`).
 *
 * `PENDING` is the actionable state the office reviews; `LINKED`, `CONVERTED` and `REJECTED` are
 * terminal and one-way. A terminal report can never be reconciled or reopened in v1.
 */
enum class AdHocWorkReportStatus {
    PENDING,
    LINKED,
    CONVERTED,
    REJECTED,
}

/**
 * One field-work report a technician submitted for office reconciliation (`BR-AH-001`).
 *
 * A report is not a Job, not a Visit and not a Visit status: it is a pending office-review record
 * that the manager later reconciles into the canonical Job and Visit model (`BR-AH-002`,
 * `BR-AH-003`). The read model carries the facts the technician reported — the canonical
 * Customer/Property/Job ids when they could be identified, and the free-text provenance fields when
 * they could not (`BR-AH-009`) — plus the reconciliation metadata the backend records when a
 * decision is made (`BR-AH-005`).
 *
 * The office decides; the client only presents the backend's answer (`BR-001`, `BR-007`).
 */
data class AdHocWorkReport(
    val id: String,
    val reportingTechnicianMembershipId: String,
    val customerId: String?,
    val customerName: String?,
    val propertyId: String?,
    /** The frozen Property address the report records (`BR-056`); `null` when no Property is known. */
    val propertyAddress: ScheduleAddress?,
    val knownJobId: String?,
    val knownJobNumber: Int?,
    val knownJobTitle: String?,
    /** ISO-8601 UTC instants the technician stated the work happened. */
    val workStartedAt: String,
    val workEndedAt: String,
    /** The outcome the technician reported for the field attempt (`BR-078`). */
    val outcomeCode: VisitOutcome,
    val summary: String,
    val notes: String?,
    val reportedCustomerName: String?,
    val reportedCustomerPhone: String?,
    val reportedCustomerAddress: String?,
    val status: AdHocWorkReportStatus,
    val reviewerMembershipId: String?,
    val reviewedAt: String?,
    val reviewNote: String?,
    val createdJobId: String?,
    val createdJobNumber: Int?,
    val createdJobTitle: String?,
    val createdVisitId: String?,
    val version: Int,
    val createdAt: String,
    val updatedAt: String,
)

/** Whether the office still owes this report a decision (`BR-AH-005`). */
val AdHocWorkReport.isPending: Boolean
    get() = status == AdHocWorkReportStatus.PENDING
