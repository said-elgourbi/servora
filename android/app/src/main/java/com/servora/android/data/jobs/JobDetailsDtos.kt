package com.servora.android.data.jobs

import kotlinx.serialization.Serializable

/*
 * Wire contract for `GET /jobs/:id` (`docs/api/job-details.md`).
 *
 * These types mirror the JSON exactly (camelCase, UUID identifiers, ISO-8601 UTC timestamps) and stay
 * in the data layer: the rest of the app exchanges `com.servora.android.domain.model.JobDetails`
 * instead.
 */

/** Response body of the Job Details read. */
@Serializable
data class JobDetailsDto(
    val id: String,
    val jobNumber: Int,
    val title: String,
    val description: String? = null,
    val typeCode: String? = null,
    val status: String,
    val allowedStatusTransitions: List<String> = emptyList(),
    val version: Int = 0,
    val customerId: String,
    val customerName: String,
    val propertyId: String? = null,
    val address: JobDetailsAddressDto? = null,
    val selectedVisit: JobDetailsVisitDto? = null,
    val technicians: List<JobDetailsTechnicianDto> = emptyList(),
)

/** The Visit the Job is represented by, with the schedule it carries (`BR-072`). */
@Serializable
data class JobDetailsVisitDto(
    val id: String,
    val status: String,
    val scheduledStart: String,
    val scheduledEnd: String,
    val version: Int = 0,
    val reschedulable: Boolean = false,
)

/** One technician assigned to the represented Visit. [name] is absent without a member profile. */
@Serializable
data class JobDetailsTechnicianDto(
    val membershipId: String,
    val name: String? = null,
    val roleCode: String,
)

/** The address the Job preserves for its Property (`BR-056`). */
@Serializable
data class JobDetailsAddressDto(
    val propertyName: String? = null,
    val addressLine1: String? = null,
    val addressLine2: String? = null,
    val city: String? = null,
    val province: String? = null,
    val postalCode: String? = null,
    val country: String? = null,
)
