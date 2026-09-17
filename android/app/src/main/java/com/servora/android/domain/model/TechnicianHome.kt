package com.servora.android.domain.model

/**
 * Why something on the technician's own work asks for attention (`BR-060`, `BR-072`, `BR-074`).
 *
 * Each kind is a condition the backend derived from authoritative records; the client presents the
 * condition and never decides one (`BR-001`). The code is the one the manager home reports for the
 * same condition, so one Visit is never described with two vocabularies (`BR-041`).
 */
enum class TechnicianAttentionKind {
    /** An assigned Visit still `SCHEDULED` after its scheduled window ended. */
    VISIT_OVERDUE,
}

/** One technician assigned to a Visit (`BR-068`). [name] is absent without a member profile. */
data class TechnicianHomeTechnician(
    val membershipId: String,
    val name: String?,
    val roleCode: String,
)

/** The address a technician's Visit row shows (`BR-056`). Every part may be absent. */
data class TechnicianHomeAddress(
    val propertyName: String?,
    val addressLine1: String?,
    val addressLine2: String?,
    val city: String?,
    val province: String?,
    val postalCode: String?,
    val country: String?,
)

/** One of the technician's own Visits, as their home renders it. */
data class TechnicianHomeVisit(
    val visitId: String,
    val visitStatus: VisitStatus,
    /** ISO-8601 UTC instant the Visit is scheduled to start. */
    val scheduledStart: String,
    val scheduledEnd: String,
    val jobId: String,
    val jobNumber: Int,
    val jobTitle: String,
    val jobStatus: JobStatus,
    val customerId: String,
    val customerName: String,
    val address: TechnicianHomeAddress?,
    /** Assigned technicians, Lead first (`BR-068`); empty when the Visit has none. */
    val technicians: List<TechnicianHomeTechnician>,
    /**
     * Whether the backend reports the Visit as overdue.
     *
     * It is derived from the server clock, so the row presents the condition without deciding it
     * from the device clock (`BR-001`).
     */
    val isOverdue: Boolean,
)

/** One condition on the technician's own work. */
data class TechnicianAttentionItem(
    val kind: TechnicianAttentionKind,
    val visitId: String,
    val jobId: String,
    val jobNumber: Int,
    val jobTitle: String,
    val jobStatus: JobStatus,
    val customerId: String,
    val customerName: String,
    /** ISO-8601 UTC instant of the Visit's scheduled start. */
    val scheduledStart: String,
    val scheduledEnd: String,
)

/**
 * Everything the technician home renders, as the backend reported it (`BR-010`, `BR-012`).
 *
 * Nothing is stored on the client as authoritative: this is a read over backend records, refreshed
 * from the API (`BR-001`). Which Visit is next, which Visits are today's and which are overdue are
 * the backend's answers, never the device's.
 */
data class TechnicianHome(
    /** The signed-in member's name, or `null` when they have no profile yet. */
    val displayName: String?,
    /** The one Visit to do next, or `null` when nothing assigned is left to do. */
    val nextVisit: TechnicianHomeVisit?,
    /** Today's own Visits, chronologically. */
    val visits: List<TechnicianHomeVisit>,
    /** A capped preview of the own Visits after today. */
    val upcoming: List<TechnicianHomeVisit>,
    /** How many own Visits are after today, including any beyond [upcoming]. */
    val upcomingTotal: Int,
    val attention: List<TechnicianAttentionItem>,
    /** Every matching condition, including any beyond [attention]'s size. */
    val attentionTotal: Int,
)
