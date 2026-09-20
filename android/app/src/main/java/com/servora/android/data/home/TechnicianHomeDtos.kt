package com.servora.android.data.home

import kotlinx.serialization.Serializable

/*
 * Wire contracts for `GET /home/technician` (`docs/api/technician-home.md`).
 *
 * These types mirror the JSON exactly (camelCase, UUID identifiers, ISO-8601 UTC timestamps) and
 * stay in the data layer: the rest of the app exchanges
 * `com.servora.android.domain.model.TechnicianHome` instead.
 */

/** Response body of the technician home read. */
@Serializable
data class TechnicianHomeDto(
    val generatedAt: String,
    val day: TechnicianHomeDayDto,
    val viewer: TechnicianHomeViewerDto,
    val nextVisit: TechnicianHomeVisitDto? = null,
    val visits: List<TechnicianHomeVisitDto> = emptyList(),
    val upcoming: List<TechnicianHomeVisitDto> = emptyList(),
    val upcomingTotal: Int = 0,
    val attention: TechnicianHomeAttentionDto,
)

/** The local-calendar window the read was resolved for. */
@Serializable
data class TechnicianHomeDayDto(
    val timeZone: String,
    val start: String,
    val end: String,
)

/** Who the read was rendered for. */
@Serializable
data class TechnicianHomeViewerDto(
    val displayName: String? = null,
)

/** The conditions on the caller's own work, with the total behind the returned items. */
@Serializable
data class TechnicianHomeAttentionDto(
    val total: Int,
    val items: List<TechnicianAttentionItemDto> = emptyList(),
)

/** One condition on the caller's own work. */
@Serializable
data class TechnicianAttentionItemDto(
    val kind: String,
    val visitId: String,
    val jobId: String,
    val jobNumber: Int,
    val jobTitle: String,
    val jobStatus: String,
    val customerId: String,
    val customerName: String,
    val scheduledStart: String,
    val scheduledEnd: String,
)

/** One of the technician's own Visits. */
@Serializable
data class TechnicianHomeVisitDto(
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
    val address: TechnicianHomeAddressDto? = null,
    val technicians: List<TechnicianHomeTechnicianDto> = emptyList(),
    val overdue: Boolean = false,
)

/** One technician assigned to a Visit. [name] is absent without a member profile. */
@Serializable
data class TechnicianHomeTechnicianDto(
    val membershipId: String,
    val name: String? = null,
    val roleCode: String,
)

/** The preserved address a Visit row shows (`BR-056`). */
@Serializable
data class TechnicianHomeAddressDto(
    val propertyName: String? = null,
    val addressLine1: String? = null,
    val addressLine2: String? = null,
    val city: String? = null,
    val province: String? = null,
    val postalCode: String? = null,
    val country: String? = null,
)
