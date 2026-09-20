package com.servora.android.data.schedule

import kotlinx.serialization.Serializable

/*
 * Wire contracts for `GET /schedule` (`docs/api/schedule.md`).
 *
 * These types mirror the JSON exactly (camelCase, UUID identifiers, ISO-8601 UTC timestamps) and
 * stay in the data layer: the rest of the app exchanges `com.servora.android.domain.model.Schedule`
 * instead.
 */

/** Response body of the day schedule read. */
@Serializable
data class ScheduleDto(
    val generatedAt: String,
    val day: ScheduleDayDto,
    val scope: ScheduleScopeDto,
    val technicians: List<ScheduleTechnicianDto> = emptyList(),
    val visits: List<ScheduleVisitDto> = emptyList(),
    /**
     * The office's "waiting for a crew" lane, or `null` when the scope the read was resolved for
     * has no such lane (`BR-009`).
     *
     * `null` is "this scope has no such lane" and never "nothing is waiting": a field caller reads
     * the Visits assigned to them, and the operation's unassigned work is not theirs to see.
     */
    val unassigned: ScheduleUnassignedDto? = null,
)

/**
 * The scope the read was resolved for (`BR-006`, `BR-009`).
 *
 * It travels with the day because schedule ownership and schedule visibility are not the same
 * question: the payload holds the work the caller's capability authorized, and a client presents
 * what the API answered rather than deciding a scope for itself (`BR-007`, `BR-041`).
 */
@Serializable
data class ScheduleScopeDto(
    /** `ORGANIZATION` — the operation's day; `SELF` — the caller's own assigned work. */
    val kind: String,
    /** The organization membership the read was resolved for: the caller's own. */
    val membershipId: String,
)

/** The local calendar day the read was resolved for. */
@Serializable
data class ScheduleDayDto(
    val localDate: String,
    val timeZone: String,
    val start: String,
    val end: String,
)

/** The work that still needs a crew, with the total behind the returned items. */
@Serializable
data class ScheduleUnassignedDto(
    val total: Int,
    val items: List<ScheduleVisitDto> = emptyList(),
)

/** One Visit of a day, or one waiting for a crew. */
@Serializable
data class ScheduleVisitDto(
    val visitId: String,
    val visitStatus: String,
    val scheduledStart: String? = null,
    val scheduledEnd: String? = null,
    val jobId: String,
    val jobNumber: Int,
    val jobTitle: String,
    val customerId: String,
    val customerName: String,
    val address: ScheduleAddressDto? = null,
    val technicians: List<ScheduleTechnicianDto> = emptyList(),
    val overdue: Boolean = false,
)

/** One technician, as both a Visit's crew and the filter's options carry them (`BR-068`). */
@Serializable
data class ScheduleTechnicianDto(
    val membershipId: String,
    val name: String? = null,
    val roleCode: String = "",
)

/** The preserved address a Visit row shows (`BR-056`). */
@Serializable
data class ScheduleAddressDto(
    val propertyName: String? = null,
    val addressLine1: String? = null,
    val addressLine2: String? = null,
    val city: String? = null,
    val province: String? = null,
    val postalCode: String? = null,
    val country: String? = null,
)
