package com.servora.android.data.home

import kotlinx.serialization.Serializable

/*
 * Wire contracts for `GET /home/manager` (`docs/api/manager-home.md`).
 *
 * These types mirror the JSON exactly (camelCase, UUID identifiers, ISO-8601 UTC timestamps) and
 * stay in the data layer: the rest of the app exchanges `com.servora.android.domain.model.ManagerHome`
 * instead.
 */

/** Response body of the manager home read. */
@Serializable
data class ManagerHomeDto(
    val generatedAt: String,
    val day: ManagerHomeDayDto,
    val viewer: ManagerHomeViewerDto,
    val attention: ManagerHomeAttentionDto,
    val today: ManagerHomeTodayDto,
    val visits: List<ManagerHomeVisitDto> = emptyList(),
)

/** The local-calendar window the read was resolved for. */
@Serializable
data class ManagerHomeDayDto(
    val timeZone: String,
    val start: String,
    val end: String,
)

/** Who the read was rendered for. */
@Serializable
data class ManagerHomeViewerDto(
    val displayName: String? = null,
)

/** The conditions that need the manager, with the total behind the returned items. */
@Serializable
data class ManagerHomeAttentionDto(
    val total: Int,
    val items: List<ManagerAttentionItemDto> = emptyList(),
)

/** One attention condition. `visitId` and the schedule are absent for a Job-level condition. */
@Serializable
data class ManagerAttentionItemDto(
    val kind: String,
    val jobId: String,
    val jobNumber: Int,
    val jobTitle: String,
    val jobStatus: String,
    val customerId: String,
    val customerName: String,
    val visitId: String? = null,
    val scheduledStart: String? = null,
    val scheduledEnd: String? = null,
)

/** The counts today's summary shows. */
@Serializable
data class ManagerHomeTodayDto(
    val total: Int,
    val completed: Int,
    val inProgress: Int,
    val upcoming: Int,
)

/** One Visit of today's schedule. */
@Serializable
data class ManagerHomeVisitDto(
    val visitId: String,
    val visitStatus: String,
    val scheduledStart: String,
    val scheduledEnd: String,
    val jobId: String,
    val jobNumber: Int,
    val jobTitle: String,
    val jobStatus: String,
    val customerId: String,
    val customerName: String,
    val address: ManagerHomeAddressDto? = null,
    val technicians: List<ManagerHomeTechnicianDto> = emptyList(),
    val overdue: Boolean = false,
)

/** One technician assigned to a Visit. [name] is absent without a member profile. */
@Serializable
data class ManagerHomeTechnicianDto(
    val membershipId: String,
    val name: String? = null,
    val roleCode: String,
)

/** The preserved address a Visit row shows (`BR-056`). */
@Serializable
data class ManagerHomeAddressDto(
    val propertyName: String? = null,
    val addressLine1: String? = null,
    val addressLine2: String? = null,
    val city: String? = null,
    val province: String? = null,
    val postalCode: String? = null,
    val country: String? = null,
)
