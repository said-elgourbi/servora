package com.servora.android.domain.model

/**
 * One technician currently assigned to a Visit (`BR-068`), Lead first.
 *
 * [roleCode] is the stable assignment role the API exchanges (`LEAD`, `TECHNICIAN`); Servora has no
 * other one and never uses the term "Helper".
 */
data class ScheduleTechnician(
    val membershipId: String,
    /** The member's name, or `null` when they have no profile yet (`BR-020`). */
    val name: String?,
    val roleCode: String,
)

/** The preserved address a schedule row shows (`BR-056`). Every part may be absent. */
data class ScheduleAddress(
    val propertyName: String?,
    val addressLine1: String?,
    val addressLine2: String?,
    val city: String?,
    val province: String?,
    val postalCode: String?,
    val country: String?,
)

/**
 * One Visit as the schedule presents it (`GET /schedule`).
 *
 * The schedule fields are absent for a Visit that has no agreed time yet — an unassigned attempt
 * being arranged — so the card states what is known rather than a time nobody agreed to (`BR-072`).
 *
 * [visitStatus] is the **Visit**'s status (`BR-074`) and never the Job's: the two are separate state
 * machines (`BR-059`), and the schedule displays the field attempt it is scheduling.
 */
data class ScheduleVisit(
    val visitId: String,
    val visitStatus: VisitStatus,
    /** ISO-8601 UTC instant the Visit is scheduled to start; `null` when no time is agreed yet. */
    val scheduledStart: String?,
    val scheduledEnd: String?,
    val jobId: String,
    val jobNumber: Int,
    val jobTitle: String,
    val customerId: String,
    val customerName: String,
    val address: ScheduleAddress?,
    /** The current crew, Lead first (`BR-068`); empty when the Visit has none. */
    val technicians: List<ScheduleTechnician>,
    /**
     * Whether the backend reports the Visit as overdue (`BR-072`, `BR-074`).
     *
     * It is derived from the server clock, so the card presents the condition without deciding it
     * from the device clock (`BR-001`).
     */
    val isOverdue: Boolean,
)

/** The local calendar day the schedule was resolved for by the API. */
data class ScheduleDay(
    /** The `YYYY-MM-DD` date the day's instants were resolved from. */
    val localDate: String,
    val timeZone: String,
)

/**
 * Which work a schedule read was resolved for (`BR-006`, `BR-009`, `ADR-019` D2).
 *
 * `ORGANIZATION` is the operation's day, read with the office capability; `SELF` is the caller's own
 * assigned work, read with the field one. The scope is the API's answer rather than the client's
 * choice: schedule ownership and schedule visibility are different questions, and a client presents
 * the work the backend authorized (`BR-001`, `BR-007`).
 */
enum class ScheduleScopeKind {
    /** The operation's day (`customers.view`). */
    ORGANIZATION,

    /** The Visits the caller's own membership is on (`VISIT_VIEW_ASSIGNED`). */
    SELF,
}

/** The scope one schedule read was resolved for, and the membership it names (`BR-006`, `BR-068`). */
data class ScheduleScope(
    val kind: ScheduleScopeKind,
    /**
     * The organization membership the read was resolved for: the caller's own.
     *
     * It is what lets a screen recognise the caller among a Visit's crew ("You"), and it is never
     * used to decide anything about the work (`BR-068`).
     */
    val membershipId: String,
)

/**
 * One local day's schedule, as the backend reported it (`BR-001`).
 *
 * Nothing here is stored or re-derived on the device: which Visits a day holds, what their crew is
 * and which work is unassigned are the API's own answers (`BR-042`, `BR-041`).
 */
data class Schedule(
    val day: ScheduleDay,
    /** The scope the day was resolved for (`BR-006`, `BR-009`). */
    val scope: ScheduleScope,
    /** The technicians the day may be narrowed to (`BR-024`, `BR-068`); empty for a field scope. */
    val technicians: List<ScheduleTechnician>,
    /** The selected day's Visits, chronologically (`BR-072`). */
    val visits: List<ScheduleVisit>,
    /**
     * The work that still needs a crew (`BR-068`, `BR-071`).
     *
     * The lane is deliberately **not** day-scoped: a Visit with no agreed time belongs to no day, and
     * it is still waiting for a crew whatever date the screen is showing.
     */
    val unassigned: List<ScheduleVisit>,
    /** Every unassigned Visit, including any beyond [unassigned]'s size. */
    val unassignedTotal: Int,
    /**
     * Whether the read's scope carries the unassigned lane at all (`BR-009`).
     *
     * A field scope has none: it reads the Visits assigned to the caller, so `false` here means "not
     * this screen's question" rather than "nothing is waiting", and the two must not be conflated
     * when a screen draws an empty state (`BR-042`).
     */
    val hasUnassignedLane: Boolean,
)

/** Stable lifecycle states for a follow-up Visit request. */
enum class FollowUpVisitRequestStatus {
    PENDING,
    NEEDS_CLARIFICATION,
    APPROVED,
    REJECTED,
}

/**
 * A technician's proposal for another Visit on an existing Job.
 *
 * This is not a Visit and must not be drawn as confirmed work. The manager schedule presents pending
 * requests in their own lane so dispatch can review them without pretending they are appointments.
 */
data class FollowUpVisitRequest(
    val id: String,
    val jobId: String,
    val sourceVisitId: String?,
    val requestingTechnicianMembershipId: String,
    val proposedStart: String,
    val proposedEnd: String,
    val reason: String,
    val sameTechnicianPreferred: Boolean,
    val status: FollowUpVisitRequestStatus,
    val reviewerMembershipId: String?,
    val reviewedAt: String?,
    val reviewNote: String?,
    val createdVisitId: String?,
    val version: Int,
    val createdAt: String,
    val updatedAt: String,
)
