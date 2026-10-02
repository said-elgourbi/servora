package com.servora.android.ui.jobs

import com.servora.android.domain.model.CustomerJobAddress
import com.servora.android.domain.model.JobActivityEvent
import com.servora.android.domain.model.JobActivityKind
import com.servora.android.domain.model.JobContactPerson
import com.servora.android.domain.model.JobCustomerContact
import com.servora.android.domain.model.JobDetails
import com.servora.android.domain.model.JobDetailsVisit
import com.servora.android.domain.model.JobDetailsVisitSummary
import com.servora.android.domain.model.JobStatus
import com.servora.android.domain.model.VisitOutcome
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
 * The Customer's contact block as the Job's Contacts section presents it (`BR-092`, `BR-095`).
 *
 * The section leads with **one** contact: whoever the effective primary is (`BR-095`) — the flagged
 * contact person, or the Customer itself when no contact person is flagged — with the values that person
 * recorded, so the technician reads who they are about to speak to and not merely a number. Every other
 * way of reaching the Customer is [others], behind a disclosure that is collapsed and hidden by default,
 * so the person the technician came for is not buried under the Customer's whole contact book
 * (`BR-012`).
 */
internal data class JobCustomerContactSummary(
    val primaryName: String,
    val primaryPhone: String?,
    val primaryEmail: String?,
    /**
     * Whether the effective primary is the **Customer itself**, because no contact person is flagged
     * (`BR-095`).
     *
     * It is what lets the section state the Customer once rather than twice: when the Customer is its own
     * primary and has no recorded phone or email, the block under the Customer's row would repeat the row
     * above it and say nothing else (`BR-012`).
     */
    val primaryIsCustomer: Boolean,
    val others: List<JobCustomerOtherContact>,
)

/**
 * One other way of reaching the Customer, which the Contacts section does not present until it is
 * expanded.
 *
 * [name] is the contact person's whole name or, for the entry that stands for the Customer's own general
 * line, the Customer's name. Its phone and its email are that person's own values (`BR-095`), and a value
 * the office never recorded is `null` rather than an empty line (`BR-012`). The entry that stands for the
 * Customer's own line carries both of the Customer's, because the Customer's email is no longer one of the
 * section's visible rows — the effective primary's own values are (`BR-095`).
 */
internal data class JobCustomerOtherContact(
    val name: String,
    val phone: String?,
    val email: String?,
)

/**
 * Resolves the Customer's effective primary contact and the ways of reaching it that the Contacts section
 * hides by default (`BR-095`).
 *
 * The effective primary is the contact person flagged primary, or the Customer itself when no contact
 * person is flagged — which is a legal state, because contacts are optional. The primary's own name, phone
 * and email are the section's inline block, and a value the flagged person has not recorded stays absent
 * rather than being handed to the Customer's own number, which `BR-095` does not give it.
 *
 * [others] is every other way of reaching the Customer: the Customer's own general line when a contact
 * person is the primary, plus every contact person who is not that primary. Nothing is dropped and nothing
 * is stated twice — the flagged person is the block above, never a row below it.
 *
 * [customerName] names the entry that stands for the Customer's own general line, because that entry is a
 * way of reaching the Customer rather than one of the Customer's people.
 */
internal fun JobCustomerContact.contactSummary(customerName: String): JobCustomerContactSummary {
    val primary = contacts.firstOrNull { it.isPrimary }
    val others = buildList {
        if (primary != null && (phone != null || email != null)) {
            add(JobCustomerOtherContact(name = customerName, phone = phone, email = email))
        }
        contacts.filter { it != primary }.forEach { contact ->
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
        primaryName = primary?.contactPersonName() ?: customerName,
        primaryPhone = primary?.phone ?: phone.takeIf { primary == null },
        primaryEmail = primary?.email ?: email.takeIf { primary == null },
        primaryIsCustomer = primary == null,
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
 * The primary Job Activity timeline is operational, not an audit log (`BR-080`).
 *
 * A reader opening Job Details needs to know what actually happened in the field: notes, evidence,
 * meaningful field milestones and the outcome. Administrative events remain in the activity data and are
 * available through a secondary history, but they do not dominate the first timeline a reader sees.
 */
internal fun List<JobActivityEvent>.primaryActivity(): List<JobActivityEvent> =
    filter { event -> event.isPrimaryActivity() }

/** Administrative/system history kept out of the primary operational timeline. */
internal fun List<JobActivityEvent>.administrativeActivity(): List<JobActivityEvent> =
    filterNot { event -> event.isPrimaryActivity() }

internal fun primaryActivityCount(events: List<JobActivityEvent>?): Int =
    events.orEmpty().count { event -> event.isPrimaryActivity() }

private fun JobActivityEvent.isPrimaryActivity(): Boolean =
    when (kind) {
        JobActivityKind.VISIT_NOTE_ADDED,
        JobActivityKind.JOB_PHOTO_ADDED,
        JobActivityKind.JOB_AUDIO_ADDED,
        JobActivityKind.VISIT_OUTCOME_RECORDED,
        -> true
        JobActivityKind.VISIT_STATUS_CHANGED -> toStatus in MeaningfulVisitStatusMilestones
        JobActivityKind.JOB_STATUS_CHANGED,
        JobActivityKind.JOB_PROPERTY_CHANGED,
        JobActivityKind.JOB_CUSTOMER_CHANGED,
        JobActivityKind.VISIT_SCHEDULED,
        JobActivityKind.VISIT_RESCHEDULED,
        JobActivityKind.VISIT_TECHNICIAN_ASSIGNED,
        JobActivityKind.VISIT_TECHNICIAN_REMOVED,
        JobActivityKind.VISIT_TECHNICIAN_ROLE_CHANGED,
        JobActivityKind.JOB_PHOTO_REMOVED,
        JobActivityKind.JOB_AUDIO_REMOVED,
        -> false
    }

private val MeaningfulVisitStatusMilestones = setOf(
    VisitStatus.EN_ROUTE.name,
    VisitStatus.ON_SITE.name,
    VisitStatus.IN_PROGRESS.name,
    VisitStatus.COMPLETED.name,
    VisitStatus.CANCELED.name,
)

/** Which scheduling action, if any, Job Details may offer from the Job's current Visit shape. */
internal enum class JobVisitScheduleAction {
    INITIAL_VISIT,
    FOLLOW_UP_VISIT,
}

/**
 * The label the schedule action carries, once the API has answered that scheduling is allowed
 * (`BR-071`, `BR-078`).
 *
 * Whether a session may schedule a new Visit at all is the API's own `canScheduleVisit` answer, which
 * already folds in the capability, the Job's open state and its Visit shape (`BR-041`, `BR-007`). This
 * function only picks the label for that answer: a first field attempt when no Visit is represented, and
 * a follow-up otherwise.
 */
internal fun JobDetails.visitScheduleAction(): JobVisitScheduleAction? {
    if (!canScheduleVisit) return null
    val representedVisit = selectedVisit
    if (representedVisit == null) {
        return if (visits.none { visit -> visit.hasOpenFieldAttempt() }) {
            JobVisitScheduleAction.INITIAL_VISIT
        } else {
            null
        }
    }

    val representedSummary = visits.firstOrNull { visit -> visit.id == representedVisit.id }
    return if (
        representedVisit.status == VisitStatus.COMPLETED &&
        representedSummary?.outcome?.requiresFollowUpVisit() == true
    ) {
        JobVisitScheduleAction.FOLLOW_UP_VISIT
    } else {
        null
    }
}

private fun JobDetailsVisitSummary.hasOpenFieldAttempt(): Boolean =
    status != VisitStatus.COMPLETED && status != VisitStatus.CANCELED

private fun VisitOutcome.requiresFollowUpVisit(): Boolean =
    this == VisitOutcome.NEEDS_FOLLOW_UP ||
        this == VisitOutcome.NEEDS_PARTS ||
        this == VisitOutcome.UNABLE_TO_COMPLETE

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
 * was for, whatever its completion time was. `DRAFT`, `SCHEDULED` and `CANCELED` are absent for the
 * same reason — none of them says a technician is on the work now.
 */
private val VisitUnderWayStatuses = setOf(
    VisitStatus.EN_ROUTE,
    VisitStatus.ON_SITE,
    VisitStatus.IN_PROGRESS,
)

/** Whether this Visit is actively being worked (`BR-074`). */
internal fun JobDetailsVisit.isUnderWay(): Boolean = status in VisitUnderWayStatuses

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
