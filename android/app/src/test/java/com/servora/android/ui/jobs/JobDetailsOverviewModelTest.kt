package com.servora.android.ui.jobs

import com.servora.android.domain.model.AssignmentRole
import com.servora.android.domain.model.CustomerJobAddress
import com.servora.android.domain.model.JobActivityEvent
import com.servora.android.domain.model.JobActivityKind
import com.servora.android.domain.model.JobContactPerson
import com.servora.android.domain.model.JobCustomerContact
import com.servora.android.domain.model.JobDetails
import com.servora.android.domain.model.JobDetailsTechnician
import com.servora.android.domain.model.JobDetailsVisit
import com.servora.android.domain.model.JobDetailsVisitSummary
import com.servora.android.domain.model.JobStatus
import com.servora.android.domain.model.VisitOutcome
import com.servora.android.domain.model.VisitStatus
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How the redesigned Job Details screen splits what it reads (`BR-047`, `BR-059`, `BR-080`, `BR-081`).
 *
 * These are the rules the page is built on — which information is the Job's, and which activity belongs to
 * which Visit — and they are verified here rather than only through a device, because they decide whether
 * a Visit reads as the Job. The composables that draw them are Compose device tests
 * (`JobDetailsOverviewScreenTest`), which the product owner runs.
 */
class JobDetailsOverviewModelTest {

    @Test
    fun `takes the Job's own information and nothing a Visit supplies`() {
        val overview = job().toJobOverview()

        assertEquals(1042, overview.jobNumber)
        assertEquals("Furnace repair", overview.title)
        assertEquals("Blower motor is noisy.", overview.description)
        assertEquals(JobStatus.ACTIVE, overview.status)
        assertEquals(listOf(JobStatus.COMPLETED), overview.allowedStatusTransitions)
        assertEquals("customer-1", overview.customerId)
        assertEquals("Martha Reynolds", overview.customerName)
        assertEquals("+15145550142", overview.customerContact?.phone)
        assertEquals("987 Cedar Lane", overview.address?.addressLine1)
    }

    @Test
    fun `presents the Customer itself as the primary when no contact person is flagged`() {
        // Zero primary contacts is a legal state (`BR-095`): the Customer is then its own primary, so its
        // own name, phone and email are the contact the section leads with, and every contact person is one
        // of the others.
        val contact = JobCustomerContact(
            phone = "+15145550142",
            email = "martha@example.com",
            notes = null,
            contacts = listOf(
                JobContactPerson(
                    firstName = "John",
                    lastName = "Smith",
                    phone = "+15551234567",
                    email = "john@example.com",
                ),
                JobContactPerson(
                    firstName = "Marie",
                    lastName = "Tremblay",
                    phone = null,
                    email = "marie@example.com",
                ),
            ),
        )

        val summary = contact.contactSummary("Martha Reynolds")

        assertEquals("Martha Reynolds", summary.primaryName)
        assertEquals("+15145550142", summary.primaryPhone)
        assertEquals("martha@example.com", summary.primaryEmail)
        // The Customer is its own primary, so the section does not name it a second time (`BR-012`).
        assertTrue(summary.primaryIsCustomer)
        assertEquals(
            listOf(
                JobCustomerOtherContact("John Smith", "+15551234567", "john@example.com"),
                JobCustomerOtherContact("Marie Tremblay", null, "marie@example.com"),
            ),
            summary.others,
        )
    }

    @Test
    fun `leads with the flagged contact person and lists the Customer's own line among the others`() {
        // A flagged contact person is the effective primary (`BR-095`), so the section leads with that
        // person's own name, number and email. The Customer's own general line is then a way of reaching the
        // Customer that the section hides until it is asked for, named after the Customer because that
        // entry is not one of the Customer's people.
        val contact = JobCustomerContact(
            phone = "+15145550142",
            email = "martha@example.com",
            notes = null,
            contacts = listOf(
                JobContactPerson(
                    firstName = "John",
                    lastName = "Smith",
                    phone = "+15551234567",
                    email = "john@example.com",
                    isPrimary = true,
                ),
                JobContactPerson(
                    firstName = "Marie",
                    lastName = "Tremblay",
                    phone = "+15559876543",
                    email = null,
                ),
            ),
        )

        val summary = contact.contactSummary("Martha Reynolds")

        assertEquals("John Smith", summary.primaryName)
        assertEquals("+15551234567", summary.primaryPhone)
        assertEquals("john@example.com", summary.primaryEmail)
        // A flagged contact person is the primary, not the Customer (`BR-095`).
        assertFalse(summary.primaryIsCustomer)
        assertEquals(
            listOf(
                JobCustomerOtherContact("Martha Reynolds", "+15145550142", "martha@example.com"),
                JobCustomerOtherContact("Marie Tremblay", "+15559876543", null),
            ),
            summary.others,
        )
    }

    @Test
    fun `reports no primary phone when the flagged contact person has none`() {
        // `BR-095` makes a flagged contact person the primary; it does not make the Customer's own number
        // the primary instead. A contact with no number therefore leaves the section without one rather than
        // handing the role to a number the rule does not give it, and that person is still the contact the
        // section leads with — by name — because the API reported them (`BR-001`, `BR-042`).
        val contact = JobCustomerContact(
            phone = "+15145550142",
            email = null,
            notes = null,
            contacts = listOf(
                JobContactPerson(
                    firstName = "John",
                    lastName = "Smith",
                    phone = null,
                    email = "john@example.com",
                    isPrimary = true,
                ),
            ),
        )

        val summary = contact.contactSummary("Martha Reynolds")

        assertEquals("John Smith", summary.primaryName)
        assertNull(summary.primaryPhone)
        assertEquals("john@example.com", summary.primaryEmail)
        assertFalse(summary.primaryIsCustomer)
        assertEquals(
            listOf(
                JobCustomerOtherContact("Martha Reynolds", "+15145550142", null),
            ),
            summary.others,
        )
    }

    @Test
    fun `reports no others when there is no other way of reaching the Customer`() {
        // An individual Customer is their own primary and holds no contact person row (`BR-095`), so the
        // section shows one contact and has nothing to expand.
        val contact = JobCustomerContact(
            phone = "+15145550142",
            email = null,
            notes = null,
        )

        val summary = contact.contactSummary("Martha Reynolds")

        assertEquals("Martha Reynolds", summary.primaryName)
        assertEquals("+15145550142", summary.primaryPhone)
        assertTrue(summary.others.isEmpty())
    }

    @Test
    fun `groups each Visit's activity with that Visit and leaves the Job's own events job-wide`() {
        val jobEvents = listOf(
            event(id = "job-1", kind = JobActivityKind.JOB_STATUS_CHANGED),
            event(id = "photo-1", kind = JobActivityKind.JOB_PHOTO_ADDED, visitSequence = null),
        )
        val firstVisitEvents = listOf(
            event(id = "visit-1-note", visitSequence = 1),
            event(
                id = "visit-1-outcome",
                kind = JobActivityKind.VISIT_OUTCOME_RECORDED,
                visitSequence = 1,
            ),
        )
        val secondVisitEvents = listOf(event(id = "visit-2-note", visitSequence = 2))

        val groups = groupJobActivity(
            events = firstVisitEvents + jobEvents + secondVisitEvents,
            visits = listOf(visit(id = "visit-1", sequence = 1), visit(id = "visit-2", sequence = 2)),
        )

        assertEquals(listOf("job-1", "photo-1"), groups.jobWide.map { it.id })
        assertEquals(
            listOf("visit-1-note", "visit-1-outcome"),
            groups.activityFor("visit-1").map { it.id },
        )
        assertEquals(listOf("visit-2-note"), groups.activityFor("visit-2").map { it.id })
    }

    @Test
    fun `keeps administrative events out of the primary activity timeline`() {
        val events = listOf(
            event(id = "note", kind = JobActivityKind.VISIT_NOTE_ADDED, visitSequence = 1),
            event(
                id = "started",
                kind = JobActivityKind.VISIT_STATUS_CHANGED,
                visitSequence = 1,
                toStatus = VisitStatus.IN_PROGRESS.name,
            ),
            event(id = "assigned", kind = JobActivityKind.VISIT_TECHNICIAN_ASSIGNED, visitSequence = 1),
            event(id = "scheduled", kind = JobActivityKind.VISIT_SCHEDULED, visitSequence = 1),
            event(
                id = "routine-status",
                kind = JobActivityKind.VISIT_STATUS_CHANGED,
                visitSequence = 1,
                toStatus = VisitStatus.SCHEDULED.name,
            ),
        )

        assertEquals(listOf("note", "started"), events.primaryActivity().map { it.id })
        assertEquals(
            listOf("assigned", "scheduled", "routine-status"),
            events.administrativeActivity().map { it.id },
        )
    }

    @Test
    fun `keeps the order the backend reported inside a group`() {
        // Job Activity is one timeline, newest first (`BR-080`): a group is a slice of it, never a
        // re-sorted copy of it (`BR-001`).
        val groups = groupJobActivity(
            events = listOf(
                event(id = "newest", visitSequence = 1),
                event(id = "middle", visitSequence = 1),
                event(id = "oldest", visitSequence = 1),
            ),
            visits = listOf(visit(id = "visit-1", sequence = 1)),
        )

        assertEquals(
            listOf("newest", "middle", "oldest"),
            groups.activityFor("visit-1").map { it.id },
        )
    }

    @Test
    fun `reports an event that names no Visit of the Job as job-wide rather than dropping it`() {
        // An event whose sequence the Job's visit list does not name cannot be attributed to a Visit —
        // which is what an answer predating the visits list produces — so it is reported as the Job's own
        // rather than silently left out of the page (`BR-042`, `BR-080`).
        val groups = groupJobActivity(
            events = listOf(
                event(id = "no-visit", visitSequence = null),
                event(id = "unknown-visit", visitSequence = 9),
            ),
            visits = listOf(visit(id = "visit-1", sequence = 1)),
        )

        assertEquals(listOf("no-visit", "unknown-visit"), groups.jobWide.map { it.id })
        assertTrue(groups.byVisitId.isEmpty())
    }

    @Test
    fun `reports no groups at all before the activity has been read`() {
        val groups = groupJobActivity(
            events = null,
            visits = listOf(visit(id = "visit-1", sequence = 1)),
        )

        assertTrue(groups.jobWide.isEmpty())
        assertTrue(groups.activityFor("visit-1").isEmpty())
    }

    @Test
    fun `gives every Visit of the Job its own group, newest first`() {
        // Every field attempt has its own account, and the section is where a reader finds which update
        // belongs to which: a Visit is never dropped from the groups because it has nothing recorded yet
        // (`BR-047`, `BR-080`).
        val groups = visitActivityGroups(
            visits = listOf(
                visit(id = "visit-1", sequence = 1, scheduledStart = "2026-09-07T13:00:00.000Z"),
                visit(id = "visit-2", sequence = 2, scheduledStart = "2026-09-14T13:00:00.000Z"),
                visit(id = "visit-3", sequence = 3, scheduledStart = "2026-09-21T13:00:00.000Z"),
            ),
            representedVisitId = "visit-2",
            groups = groupJobActivity(events = null, visits = emptyList()),
        )

        assertEquals(listOf("visit-3", "visit-2", "visit-1"), groups.map { it.visit.id })
        assertEquals(listOf(3, 2, 1), groups.map { it.visit.sequence })
    }

    @Test
    fun `opens the Visit the screen represents by default and leaves the others folded`() {
        val groups = visitActivityGroups(
            visits = listOf(
                visit(id = "visit-1", sequence = 1, scheduledStart = "2026-09-07T13:00:00.000Z"),
                visit(id = "visit-2", sequence = 2, scheduledStart = "2026-09-14T13:00:00.000Z"),
            ),
            representedVisitId = "visit-2",
            groups = groupJobActivity(events = null, visits = emptyList()),
        )

        // Newest first, and the represented Visit is the one group that opens without a tap (`BR-012`).
        assertEquals(listOf("visit-2", "visit-1"), groups.map { it.visit.id })
        assertEquals(listOf(true, false), groups.map { it.isRepresentedVisit })
    }

    @Test
    fun `orders a Visit that has not been scheduled after the scheduled ones`() {
        // A Visit with no schedule has no date to be ordered by, so it follows the scheduled ones rather
        // than being given an invented position (`BR-042`, `BR-072`).
        val groups = visitActivityGroups(
            visits = listOf(
                visit(id = "draft", sequence = 1, scheduledStart = null),
                visit(id = "scheduled", sequence = 2, scheduledStart = "2026-09-07T13:00:00.000Z"),
            ),
            representedVisitId = null,
            groups = groupJobActivity(events = null, visits = emptyList()),
        )

        assertEquals(listOf("scheduled", "draft"), groups.map { it.visit.id })
    }

    @Test
    fun `orders two Visits of the same time by the Job's own visit sequence`() {
        val groups = visitActivityGroups(
            visits = listOf(
                visit(id = "first", sequence = 1, scheduledStart = "2026-09-07T13:00:00.000Z"),
                visit(id = "second", sequence = 2, scheduledStart = "2026-09-07T13:00:00.000Z"),
            ),
            representedVisitId = null,
            groups = groupJobActivity(events = null, visits = emptyList()),
        )

        assertEquals(listOf("second", "first"), groups.map { it.visit.id })
    }

    @Test
    fun `attaches each Visit's own activity, and none where the Visit has none`() {
        val groups = groupJobActivity(
            events = listOf(
                event(id = "visit-1-note", visitSequence = 1),
                event(id = "visit-2-note", visitSequence = 2),
            ),
            visits = listOf(visit(id = "visit-1", sequence = 1), visit(id = "visit-2", sequence = 2)),
        )

        val activityGroups = visitActivityGroups(
            visits = listOf(
                visit(id = "visit-1", sequence = 1),
                visit(id = "visit-2", sequence = 2),
                visit(id = "visit-3", sequence = 3),
            ),
            representedVisitId = "visit-2",
            groups = groups,
        )

        val byVisit = activityGroups.associateBy { group -> group.visit.id }
        assertEquals(listOf("visit-1-note"), byVisit.getValue("visit-1").activity.map { it.id })
        assertEquals(listOf("visit-2-note"), byVisit.getValue("visit-2").activity.map { it.id })
        // A Visit with no update keeps its group and states that it has none (`BR-042`).
        assertTrue(byVisit.getValue("visit-3").activity.isEmpty())
    }

    @Test
    fun `places every event in exactly one group`() {
        // Grouping never duplicates an event: an entry is either a Visit's or the Job's, and the two sets
        // together are the one timeline the backend reported (`BR-080`, `BR-001`).
        val events = listOf(
            event(id = "job-1", kind = JobActivityKind.JOB_STATUS_CHANGED, visitSequence = null),
            event(id = "visit-1-note", visitSequence = 1),
            event(id = "visit-2-note", visitSequence = 2),
            event(id = "unattributable", visitSequence = 9),
        )
        val groups = groupJobActivity(
            events = events,
            visits = listOf(visit(id = "visit-1", sequence = 1), visit(id = "visit-2", sequence = 2)),
        )

        val visitGroups = visitActivityGroups(
            visits = listOf(visit(id = "visit-1", sequence = 1), visit(id = "visit-2", sequence = 2)),
            representedVisitId = "visit-2",
            groups = groups,
        )
        val placed = visitGroups.flatMap { group -> group.activity.map { it.id } } + groups.jobWide.map { it.id }

        assertEquals(events.size, placed.size)
        assertEquals(events.map { it.id }.sorted(), placed.sorted())
    }

    @Test
    fun `groups nothing for a Job that has no Visit`() {
        val groups = visitActivityGroups(
            visits = emptyList(),
            representedVisitId = null,
            groups = groupJobActivity(
                events = listOf(event(id = "job-1", kind = JobActivityKind.JOB_STATUS_CHANGED)),
                visits = emptyList(),
            ),
        )

        assertTrue(groups.isEmpty())
    }

    @Test
    fun `reads a timestamp, and reports one this build cannot read as no timestamp`() {
        assertEquals(Instant.parse("2026-09-14T13:00:00.000Z"), visitStart("2026-09-14T13:00:00.000Z"))
        assertNull(visitStart(null))
        assertNull(visitStart("not a timestamp"))
    }

    @Test
    fun `offers initial scheduling when the API answers it and no Visit is represented`() {
        val jobWithoutVisits = job().copy(
            selectedVisit = null,
            technicians = emptyList(),
            visits = emptyList(),
            canScheduleVisit = true,
        )

        assertEquals(JobVisitScheduleAction.INITIAL_VISIT, jobWithoutVisits.visitScheduleAction())
        assertNull(jobWithoutVisits.copy(canScheduleVisit = false).visitScheduleAction())
        assertNull(job().visitScheduleAction())
    }

    @Test
    fun `does not offer another schedule action while an open Visit already exists`() {
        val base = job()
        val existingVisit = base.visits.first().copy(status = VisitStatus.SCHEDULED)
        val withoutSelectedVisit = base.copy(
            selectedVisit = null,
            visits = listOf(existingVisit),
            canScheduleVisit = true,
        )

        assertNull(withoutSelectedVisit.visitScheduleAction())
    }

    @Test
    fun `identifies a Visit whose field work is under way`() {
        assertTrue(selectedVisit(status = VisitStatus.EN_ROUTE, startingAt = "2026-09-14T13:00:00.000Z").isUnderWay())
        assertTrue(selectedVisit(status = VisitStatus.ON_SITE, startingAt = "2026-09-14T13:00:00.000Z").isUnderWay())
        assertTrue(selectedVisit(status = VisitStatus.IN_PROGRESS, startingAt = "2026-09-14T13:00:00.000Z").isUnderWay())
        assertFalse(selectedVisit(status = VisitStatus.SCHEDULED, startingAt = "2026-09-14T13:00:00.000Z").isUnderWay())
        assertFalse(selectedVisit(status = VisitStatus.COMPLETED, startingAt = "2026-09-14T13:00:00.000Z").isUnderWay())
    }

    @Test
    fun `offers follow-up scheduling only after a completed Visit with a follow-up outcome`() {
        val base = job()
        val completedSelected = requireNotNull(base.selectedVisit).copy(status = VisitStatus.COMPLETED)
        fun withOutcome(outcome: VisitOutcome?) = base.copy(
            selectedVisit = completedSelected,
            visits = base.visits.map { visit ->
                if (visit.id == completedSelected.id) {
                    visit.copy(status = VisitStatus.COMPLETED, outcome = outcome)
                } else {
                    visit
                }
            },
            canScheduleVisit = true,
        )

        assertEquals(
            JobVisitScheduleAction.FOLLOW_UP_VISIT,
            withOutcome(VisitOutcome.NEEDS_FOLLOW_UP).visitScheduleAction(),
        )
        assertEquals(
            JobVisitScheduleAction.FOLLOW_UP_VISIT,
            withOutcome(VisitOutcome.NEEDS_PARTS).visitScheduleAction(),
        )
        assertEquals(
            JobVisitScheduleAction.FOLLOW_UP_VISIT,
            withOutcome(VisitOutcome.UNABLE_TO_COMPLETE).visitScheduleAction(),
        )
        assertNull(withOutcome(VisitOutcome.RESOLVED).visitScheduleAction())
        assertNull(withOutcome(null).visitScheduleAction())
        assertNull(withOutcome(VisitOutcome.NEEDS_FOLLOW_UP).copy(selectedVisit = completedSelected.copy(status = VisitStatus.IN_PROGRESS)).visitScheduleAction())
    }

    @Test
    fun `states the represented Visit by the day it is for`() {
        // The section labels the Visit the read selected (`BR-081`), and the label says when that field
        // attempt is for: a Visit two days out is not the current one (`BR-072`). The device's own zone
        // decides what "today" means, so the expectations are built from it rather than from a fixed
        // string (`BR-028`).
        val zone = ZoneId.systemDefault()
        val now = Instant.parse("2026-09-14T09:00:00.000Z")
        val today = now.atZone(zone).toLocalDate()

        assertEquals(
            VisitSectionPeriod.TODAY,
            representedVisitPeriod(selectedVisit(startingAt = sameDayAs(now, zone)), now),
        )
        assertEquals(
            VisitSectionPeriod.TOMORROW,
            representedVisitPeriod(selectedVisit(startingAt = midday(today.plusDays(1), zone)), now),
        )
        assertEquals(
            VisitSectionPeriod.UPCOMING,
            representedVisitPeriod(selectedVisit(startingAt = midday(today.plusDays(2), zone)), now),
        )
        assertEquals(
            VisitSectionPeriod.PREVIOUS,
            representedVisitPeriod(selectedVisit(startingAt = midday(today.minusDays(2), zone)), now),
        )
    }

    @Test
    fun `describes a Visit as current while its own window is running`() {
        // The Visit's internal schedule is authoritative for dispatch (`BR-072`), so a Visit whose window
        // contains the present moment is the current field attempt even though its day is today's.
        val now = Instant.parse("2026-09-14T09:00:00.000Z")

        val running = selectedVisit(
            startingAt = now.minus(Duration.ofMinutes(30)).toString(),
            windowHours = 1,
        )

        assertEquals(VisitSectionPeriod.CURRENT, representedVisitPeriod(running, now))
    }

    @Test
    fun `describes a Visit as current while its field work is under way`() {
        // A technician travelling to, on site at, or working on a Visit is on the current field attempt
        // whatever the clock says about its window (`BR-074`).
        val zone = ZoneId.systemDefault()
        val now = Instant.parse("2026-09-14T09:00:00.000Z")
        val later = midday(now.atZone(zone).toLocalDate().plusDays(2), zone)

        listOf(VisitStatus.EN_ROUTE, VisitStatus.ON_SITE, VisitStatus.IN_PROGRESS).forEach { status ->
            assertEquals(
                status.name,
                VisitSectionPeriod.CURRENT,
                representedVisitPeriod(selectedVisit(status = status, startingAt = later), now),
            )
        }
    }

    @Test
    fun `describes a Visit with no schedule this build can read as the current one`() {
        // The read selected this Visit to represent the Job and there is no time to describe it against,
        // so the section states the one thing it knows rather than a fabricated day (`BR-042`, `BR-051`).
        val now = Instant.parse("2026-09-14T09:00:00.000Z")

        assertEquals(VisitSectionPeriod.CURRENT, representedVisitPeriod(null, now))
        assertEquals(
            VisitSectionPeriod.CURRENT,
            // A Visit whose schedule this build cannot read at all is reported the same way (`BR-042`).
            representedVisitPeriod(
                selectedVisit(startingAt = "not a timestamp", scheduledEnd = "not a timestamp"),
                now,
            ),
        )
    }
}

/**
 * The Visit the read represents, starting at [startingAt] and running for [windowHours] (`BR-081`).
 *
 * The Visit a test is not about keeps the fixture's own status, so a status-based expectation is stated
 * explicitly where it is the point of the test.
 */
private fun selectedVisit(
    status: VisitStatus = VisitStatus.SCHEDULED,
    startingAt: String,
    windowHours: Long = 2,
    scheduledEnd: String = Instant.parse(startingAt).plus(Duration.ofHours(windowHours)).toString(),
) = JobDetailsVisit(
    id = "visit-2",
    status = status,
    scheduledStart = startingAt,
    scheduledEnd = scheduledEnd,
    version = 2,
    reschedulable = true,
    allowedStatusTransitions = emptyList(),
    fieldActionable = true,
)

/**
 * A start instant on the device's own day as [now], far enough from it that the Visit's own window
 * cannot be running: three hours ahead while that is still the same day, otherwise three hours behind.
 */
private fun sameDayAs(now: Instant, zone: ZoneId): String {
    val localNow = now.atZone(zone)
    val later = localNow.plusHours(3)
    val start = later.takeIf { it.toLocalDate() == localNow.toLocalDate() } ?: localNow.minusHours(3)
    return start.toInstant().toString()
}

/** The instant a Visit scheduled for [day] at 12:00 local time starts, as the read reports it. */
private fun midday(day: LocalDate, zone: ZoneId): String =
    day.atTime(12, 0).atZone(zone).toInstant().toString()

/** One Visit of the Job as the read reports it (`BR-047`, `BR-071`). */
private fun visit(
    id: String,
    sequence: Int,
    status: VisitStatus = VisitStatus.COMPLETED,
    scheduledStart: String? = "2026-09-07T13:00:00.000Z",
    technicians: List<JobDetailsTechnician> = emptyList(),
) = JobDetailsVisitSummary(
    id = id,
    sequence = sequence,
    status = status,
    // Grouping reads no outcome: this model splits the activity by Visit (`BR-080`), and the Visit's own
    // outcome is stated by the group's card from the read's own field (`BR-077`, `BR-079`).
    outcome = null,
    scheduledStart = scheduledStart,
    scheduledEnd = scheduledStart?.replace("13:00", "15:00"),
    version = 1,
    technicians = technicians,
)

/** One Job Activity event (`BR-080`). */
private fun event(
    id: String,
    kind: JobActivityKind = JobActivityKind.VISIT_NOTE_ADDED,
    visitSequence: Int? = null,
    toStatus: String? = null,
) = JobActivityEvent(
    id = id,
    kind = kind,
    recordedAt = "2026-09-14T14:14:00.000Z",
    actorName = "John Smith",
    visitSequence = visitSequence,
    fromStatus = null,
    toStatus = toStatus,
    technicianName = null,
    roleCode = null,
    previousRoleCode = null,
    outcomeCode = null,
    outcomeSummary = null,
    body = null,
)

/** The Job these tests read: two Visits, the second of which the read represents. */
private fun job() = JobDetails(
    id = "job-1",
    jobNumber = 1042,
    title = "Furnace repair",
    description = "Blower motor is noisy.",
    status = JobStatus.ACTIVE,
    allowedStatusTransitions = listOf(JobStatus.COMPLETED),
    version = 7,
    customerId = "customer-1",
    customerName = "Martha Reynolds",
    customerContactDetails = JobCustomerContact(
        phone = "+15145550142",
        email = null,
        notes = null,
    ),
    address = CustomerJobAddress(
        propertyName = null,
        addressLine1 = "987 Cedar Lane",
        addressLine2 = null,
        city = "Ottawa",
        province = "ON",
        postalCode = "K1A 0B1",
        country = "Canada",
    ),
    selectedVisit = JobDetailsVisit(
        id = "visit-2",
        status = VisitStatus.SCHEDULED,
        scheduledStart = "2026-09-14T13:00:00.000Z",
        scheduledEnd = "2026-09-14T15:00:00.000Z",
        version = 2,
        reschedulable = true,
        allowedStatusTransitions = listOf(VisitStatus.EN_ROUTE),
        fieldActionable = true,
    ),
    technicians = listOf(
        JobDetailsTechnician(
            membershipId = "member-1",
            name = "Mike Lead",
            role = AssignmentRole.LEAD,
        ),
    ),
    visits = listOf(
        visit(
            id = "visit-1",
            sequence = 1,
            scheduledStart = "2026-09-07T13:00:00.000Z",
            technicians = listOf(
                JobDetailsTechnician(
                    membershipId = "member-9",
                    name = "Dave Past",
                    role = AssignmentRole.LEAD,
                ),
            ),
        ),
        visit(
            id = "visit-2",
            sequence = 2,
            status = VisitStatus.SCHEDULED,
            scheduledStart = "2026-09-14T13:00:00.000Z",
            technicians = listOf(
                JobDetailsTechnician(
                    membershipId = "member-1",
                    name = "Mike Lead",
                    role = AssignmentRole.LEAD,
                ),
            ),
        ),
    ),
)
