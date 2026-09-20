package com.servora.android.ui.jobs

import com.servora.android.domain.model.CustomerJobAddress
import com.servora.android.domain.model.JobActivityEvent
import com.servora.android.domain.model.JobContactPerson
import com.servora.android.domain.model.JobCustomerContact
import com.servora.android.domain.model.JobDetails
import com.servora.android.domain.model.JobDetailsVisit
import com.servora.android.domain.model.JobDetailsVisitSummary
import com.servora.android.domain.model.JobStatus
import com.servora.android.domain.model.VisitStatus
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeParseException

/*
 * How the redesigned Job Details screen splits what it reads (`BR-047`, `BR-059`, `BR-080`, `BR-081`).
 *
 * The screen presents three different things and must never present one as another: the **Job's own**
 * information, the **Visit** that represents the Job, and the Job's **Visit history**. The activity the
 * backend reports spans the Job and every Visit, so it is split the same way — each Visit's own events
 * beside that Visit, and the Job-level events in their own section.
 *
 * Everything here is presentation over records the backend reported: it reads values, groups them and
 * orders them, and it invents none of them (`BR-001`, `BR-042`). It holds no Android or Compose types,
 * so the rules the screen is built on are verifiable without a device.
 */

/**
 * The Job's own information (`BR-052`, `BR-053`, `BR-058`).
 *
 * It is exactly the part of the Job Details read that belongs to the Job and not to a Visit: its
 * number, its title and description, its lifecycle status, and the Customer and address it is for
 * (`BR-048`, `BR-056`). Nothing a Visit supplies is part of it — the schedule, the crew and the field
 * status are the represented Visit's (`BR-059`, `BR-081`).
 */
internal data class JobOverview(
    val jobNumber: Int,
    val title: String,
    val description: String?,
    val status: JobStatus,
    val allowedStatusTransitions: List<JobStatus>,
    val customerId: String,
    val customerName: String,
    val customerContact: JobCustomerContact?,
    val address: CustomerJobAddress?,
)

/** The Job's own information, taken from the Job the backend reported (`BR-001`). */
internal fun JobDetails.toJobOverview(): JobOverview =
    JobOverview(
        jobNumber = jobNumber,
        title = title,
        description = description,
        status = status,
        allowedStatusTransitions = allowedStatusTransitions,
        customerId = customerId,
        customerName = customerName,
        customerContact = customerContactDetails,
        address = address,
    )

/**
 * The Customer's contact block as the Job's card presents it (`BR-092`, `BR-095`).
 *
 * The card leads with **one** phone: the effective primary contact's number (`BR-095`). Every other way of
 * reaching the Customer is [others], which the card keeps behind a disclosure that is collapsed and hidden
 * by default — so the number a technician came for is not buried under the Customer's whole contact book
 * (`BR-012`).
 */
internal data class JobCustomerContactSummary(
    val primaryPhone: String?,
    val others: List<JobCustomerOtherContact>,
)

/**
 * One other way of reaching the Customer, which the Job card does not present until it is expanded.
 *
 * [name] is the contact person's whole name or, for the entry that stands for the Customer's own general
 * line, the Customer's name. [email] is `null` for that entry: the Customer's own email is already one of
 * the card's visible rows, and a value is not drawn twice on one card (`BR-012`).
 */
internal data class JobCustomerOtherContact(
    val name: String,
    val phone: String?,
    val email: String?,
)

/**
 * Resolves the Customer's effective primary contact and the numbers the Job card hides by default
 * (`BR-095`).
 *
 * The primary phone is the flagged contact person's own number, or the Customer's own number when no
 * contact person is flagged — in which case the Customer itself is the primary. A flagged contact person
 * who has no number recorded leaves the card without a primary phone rather than handing that role to a
 * number `BR-095` does not give it, and that person is then listed among the others so a contact the API
 * reported never disappears from the card.
 *
 * [customerName] names the entry that stands for the Customer's own general line, because that entry is a
 * number rather than one of the Customer's people.
 */
internal fun JobCustomerContact.contactSummary(customerName: String): JobCustomerContactSummary {
    val primary = contacts.firstOrNull { it.isPrimary }
    val presentedPrimary = primary?.takeIf { it.phone != null }
    val others = buildList {
        if (primary != null && phone != null) {
            add(JobCustomerOtherContact(name = customerName, phone = phone, email = null))
        }
        contacts.filter { it != presentedPrimary }.forEach { contact ->
            add(
                JobCustomerOtherContact(
                    name = contact.contactPersonName(),
                    phone = contact.phone,
                    email = contact.email,
                ),
            )
        }
    }
    return JobCustomerContactSummary(
        primaryPhone = when {
            primary == null -> phone
            presentedPrimary == null -> null
            else -> presentedPrimary.phone
        },
        others = others,
    )
}

/** The person's whole name, from the two parts the office recorded (`BR-095`). */
private fun JobContactPerson.contactPersonName(): String =
    listOf(firstName, lastName)
        .filter { it.isNotBlank() }
        .joinToString(" ")

/**
 * The Job's activity, split by what it belongs to (`BR-080`).
 *
 * Job Activity is one chronological read over the Job's own events and every Visit's (`BR-080`).
 * Presenting it as one list beside a Visit made it read as that Visit's own account, so it is split
 * here instead: an event whose `visitSequence` names one of the Job's Visits belongs to that Visit,
 * and an event with no Visit belongs to the Job.
 *
 * The backend answers the question rather than the client guessing it: `visitSequence` is `null` for a
 * Job-level event and otherwise the Visit's sequence (`docs/api/job-activity.md` §3.3). An event whose
 * sequence names no Visit of the Job — which an answer predating the visits list would produce — is
 * therefore reported as Job-wide rather than dropped, which is the honest reading of an event the
 * client cannot attribute (`BR-042`).
 *
 * Each group keeps the order the backend reported, newest first, so a group is a true slice of the one
 * timeline rather than a re-sorted copy of it (`BR-001`).
 */
internal data class JobActivityGroups(
    /** The events that belong to the Job itself (`JOB_*` kinds and anything unattributable). */
    val jobWide: List<JobActivityEvent>,
    /** Each Visit's own events, keyed by the Visit's identifier. */
    val byVisitId: Map<String, List<JobActivityEvent>>,
) {
    /** One Visit's events, newest first; empty when the Visit has no activity. */
    fun activityFor(visitId: String): List<JobActivityEvent> = byVisitId[visitId].orEmpty()
}

/** Splits the Job's activity by the Visit each event belongs to (`BR-080`). */
internal fun groupJobActivity(
    events: List<JobActivityEvent>?,
    visits: List<JobDetailsVisitSummary>,
): JobActivityGroups {
    val bySequence = visits.associateBy { visit -> visit.sequence }
    val jobWide = mutableListOf<JobActivityEvent>()
    val byVisitId = mutableMapOf<String, MutableList<JobActivityEvent>>()
    events.orEmpty().forEach { event ->
        val visit = event.visitSequence?.let(bySequence::get)
        if (visit == null) {
            jobWide += event
        } else {
            byVisitId.getOrPut(visit.id) { mutableListOf() } += event
        }
    }
    return JobActivityGroups(jobWide = jobWide, byVisitId = byVisitId)
}

/**
 * One Visit's group in the Job Activity section: the Visit, and the activity that belongs to it
 * (`BR-071`, `BR-080`).
 *
 * The group is what a collapsed section states about itself — which Visit it is, when it was for, what
 * its status is and who is on its crew — and [activity] is what expanding it reveals.
 */
internal data class VisitActivityGroup(
    val visit: JobDetailsVisitSummary,
    val activity: List<JobActivityEvent>,
    /**
     * Whether this is the Visit the screen represents (`BR-081`), which is the one opened by default so
     * the current field attempt's account is on screen without a tap (`BR-012`).
     */
    val isRepresentedVisit: Boolean,
)

/**
 * The Job's Visits as activity groups, newest first (`BR-047`, `BR-080`).
 *
 * Every Visit gets a group, because every Visit has its own account of what happened on it and the Job
 * Activity section is where a reader goes to find which update belongs to which field attempt. A Visit
 * with no events keeps its group and states that nothing has been recorded on it, so a missing account
 * is read as an empty one rather than as a Visit the page never mentions (`BR-042`).
 *
 * A Visit with a schedule is ordered by that schedule, most recent first, because that is what its
 * section states; a Visit that has not been scheduled yet has no date to order by, so it follows the
 * scheduled ones rather than being given an invented position. Two Visits that are equally recent are
 * ordered by the Job's own visit sequence, so the order is stable and the later Visit leads (`BR-052`).
 */
internal fun visitActivityGroups(
    visits: List<JobDetailsVisitSummary>,
    representedVisitId: String?,
    groups: JobActivityGroups,
): List<VisitActivityGroup> =
    visits
        .sortedWith(
            compareByDescending<JobDetailsVisitSummary> { visit ->
                visitStart(visit.scheduledStart)?.toEpochMilli() ?: Long.MIN_VALUE
            }.thenByDescending { visit -> visit.sequence },
        )
        .map { visit ->
            VisitActivityGroup(
                visit = visit,
                activity = groups.activityFor(visit.id),
                isRepresentedVisit = visit.id == representedVisitId,
            )
        }

/**
 * The instant a timestamp denotes, or `null` when it is absent or not one this build can read.
 *
 * A value this build cannot read is reported as no value rather than as a fabricated one (`BR-042`),
 * and it orders as a Visit with no date does.
 */
internal fun visitStart(value: String?): Instant? =
    try {
        value?.let(Instant::parse)
    } catch (unreadable: DateTimeParseException) {
        null
    }

/**
 * When the Visit the page represents is for (`BR-072`, `BR-081`).
 *
 * The Job Details read hands the screen **one** Visit to represent the Job (`BR-081`), and the screen
 * labels that Visit's section as a Visit's. Which Visit it is stays the backend's answer; what this
 * describes is *when that Visit is for*, because a section headed "Current visit" in front of a field
 * attempt two days away tells the reader something false about work that has not started.
 *
 * The period is read from facts the read reported and the device clock, never invented (`BR-042`):
 * the Visit's own internal schedule, which is authoritative for dispatch (`BR-072`), and its field
 * status, which is its own state machine (`BR-074`). It is presentation only — it changes no status, no
 * schedule and no selection, and the API remains the authority for all three (`BR-001`, `BR-007`).
 */
internal enum class VisitSectionPeriod {
    /** The field attempt is under way, or its scheduled window contains the present moment. */
    CURRENT,

    /** The Visit is scheduled for the device's own today. */
    TODAY,

    /** The Visit is scheduled for the device's own tomorrow. */
    TOMORROW,

    /** The Visit is scheduled for a later day. */
    UPCOMING,

    /** The Visit's own day has passed. */
    PREVIOUS,
}

/**
 * The field statuses that mean the attempt is under way rather than scheduled for later (`BR-074`).
 *
 * `COMPLETED` is deliberately absent: a completed Visit is over, so its section is described by when it
 * was for, whatever its completion time was. `DRAFT`, `SCHEDULED`, `CANCELED` and `NO_SHOW` are absent
 * for the same reason — none of them says a technician is on the work now.
 */
private val VisitUnderWayStatuses = setOf(
    VisitStatus.EN_ROUTE,
    VisitStatus.ON_SITE,
    VisitStatus.IN_PROGRESS,
)

/**
 * When the Visit the page represents is for, as of [now] (`BR-072`, `BR-074`, `BR-081`).
 *
 * [now] is passed in rather than read here so the rule is verifiable without a device, and the device's
 * own zone decides what "today" and "tomorrow" mean — a Visit at 23:00 local is today's, not tomorrow's,
 * because that is the day the technician reading the screen is living in (`BR-028`).
 *
 * A Visit with no schedule this build can read is described as the current one: the read selected it to
 * represent the Job, and there is no time to describe it against (`BR-042`, `BR-051`).
 */
internal fun representedVisitPeriod(visit: JobDetailsVisit?, now: Instant): VisitSectionPeriod {
    val start = visitStart(visit?.scheduledStart) ?: return VisitSectionPeriod.CURRENT
    if (visit?.status in VisitUnderWayStatuses) {
        return VisitSectionPeriod.CURRENT
    }
    val zone = ZoneId.systemDefault()
    val end = visitStart(visit?.scheduledEnd)
    if (end != null && !now.isBefore(start) && !now.isAfter(end)) {
        return VisitSectionPeriod.CURRENT
    }
    val day = start.atZone(zone).toLocalDate()
    val today = now.atZone(zone).toLocalDate()
    return when {
        day == today -> VisitSectionPeriod.TODAY
        day == today.plusDays(1) -> VisitSectionPeriod.TOMORROW
        day.isAfter(today) -> VisitSectionPeriod.UPCOMING
        else -> VisitSectionPeriod.PREVIOUS
    }
}
