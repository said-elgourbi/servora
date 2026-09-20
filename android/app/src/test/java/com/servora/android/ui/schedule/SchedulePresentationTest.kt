package com.servora.android.ui.schedule

import com.servora.android.domain.model.ScheduleTechnician
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How the schedule presents a day's rows and its filter (`BR-012`, `BR-024`, `BR-028`, `BR-041`,
 * `BR-042`, `BR-068`).
 *
 * The screen decides nothing about the work — which Visits a day holds, who is assigned and whether a
 * Visit is overdue are the API's own answers (`BR-001`) — so these tests cover the choices the screen
 * does make: how much of a crew one card names, how the filter's search compares a name, which options
 * the filter offers and in what order, how the collapsed filter summarises itself, and where the
 * "now" cue belongs and what it says. Each of them is asserted without a device, because each is a
 * decision rather than a drawing.
 */
class SchedulePresentationTest {

    @Test
    fun `names a crew of two in full`() {
        val crew = crewLine(listOf("Priya Raman", "Luc Gagnon"))

        assertEquals(listOf("Priya Raman", "Luc Gagnon"), crew.names)
        assertEquals(0, crew.hidden)
    }

    @Test
    fun `counts the crew a card cannot name`() {
        // A card is scanned, not read, so the names that fit are named and the rest are counted
        // (`BR-012`): a Visit with four technicians is never presented as a Visit with two.
        val crew = crewLine(listOf("Priya Raman", "Luc Gagnon", "Sarah Moreau", "John Tremblay"))

        assertEquals(listOf("Priya Raman", "Luc Gagnon"), crew.names)
        assertEquals(2, crew.hidden)
    }

    @Test
    fun `counts a member with no profile yet as assigned`() {
        // A member with no profile carries no name (`BR-020`), but they are still going, so a crew of
        // three with one name is not a crew of one.
        val crew = crewLine(listOf("Priya Raman", null, "  "))

        assertEquals(listOf("Priya Raman"), crew.names)
        assertEquals(2, crew.hidden)
    }

    @Test
    fun `names nobody on a crew of nobody`() {
        val crew = crewLine(emptyList())

        assertTrue(crew.names.isEmpty())
        assertEquals(0, crew.hidden)
    }

    @Test
    fun `matches a technician by part of their name, whatever the case`() {
        assertTrue(matchesTechnicianQuery("Priya Raman", "priya"))
        assertTrue(matchesTechnicianQuery("Priya Raman", "RAMAN"))
        assertFalse(matchesTechnicianQuery("Priya Raman", "gagnon"))
    }

    @Test
    fun `matches a name without its accents`() {
        // The application is bilingual (`BR-028`), so a manager typing "cote" is looking for "Côté".
        assertTrue(matchesTechnicianQuery("Luc Côté", "cote"))
        assertTrue(matchesTechnicianQuery("Luc Côté", "côté"))
    }

    @Test
    fun `matches every technician while the search is empty`() {
        assertTrue(matchesTechnicianQuery("Priya Raman", ""))
        assertTrue(matchesTechnicianQuery("Priya Raman", "   "))
    }

    @Test
    fun `never invents a name for a member who has none`() {
        assertTrue(matchesTechnicianQuery(null, ""))
        assertFalse(matchesTechnicianQuery(null, "priya"))
    }

    @Test
    fun `places the now cue before the first visit that has not started`() {
        val starts = listOf(
            Instant.parse("2026-09-07T13:00:00Z"),
            Instant.parse("2026-09-07T15:00:00Z"),
            Instant.parse("2026-09-07T17:00:00Z"),
        )

        assertEquals(0, nowCueIndex(starts, now = Instant.parse("2026-09-07T12:00:00Z")))
        // A Visit that started at 13:00 and is still running is behind the cue, not in front of it.
        assertEquals(1, nowCueIndex(starts, now = Instant.parse("2026-09-07T13:30:00Z")))
        assertEquals(1, nowCueIndex(starts, now = Instant.parse("2026-09-07T15:00:00Z")))
        assertEquals(3, nowCueIndex(starts, now = Instant.parse("2026-09-07T20:00:00Z")))
    }

    @Test
    fun `has no now cue for a list with no readable time`() {
        // The unassigned lane holds work nobody has agreed a time for (`BR-071`): there is no
        // chronology to place a cue in, so none is reported.
        assertNull(nowCueIndex(listOf(null, null), now = Instant.parse("2026-09-07T12:00:00Z")))
        assertNull(nowCueIndex(emptyList(), now = Instant.parse("2026-09-07T12:00:00Z")))
    }

    @Test
    fun `ignores a time this build cannot read`() {
        val starts = listOf(null, Instant.parse("2026-09-07T15:00:00Z"))

        assertEquals(1, nowCueIndex(starts, now = Instant.parse("2026-09-07T12:00:00Z")))
        assertNull(instantOf("not-an-instant"))
        assertEquals(Instant.parse("2026-09-07T15:00:00Z"), instantOf("2026-09-07T15:00:00Z"))
    }

    @Test
    fun `shows the now cue only on today in the day's own lane`() {
        val today = LocalDate.parse("2026-09-07")

        // The day the manager is living through, in the lane that presents that day's chronology.
        assertTrue(state(selectedDate = today, lane = ScheduleLane.SCHEDULE).showsNowCue(today))
        // Another day has no "now" in it.
        assertFalse(
            state(selectedDate = today.plusDays(1), lane = ScheduleLane.SCHEDULE).showsNowCue(today),
        )
        // The unassigned lane holds work that belongs to no day (`BR-071`).
        assertFalse(state(selectedDate = today, lane = ScheduleLane.UNASSIGNED).showsNowCue(today))
        // Nothing selected yet: the screen has not chosen a day.
        assertFalse(state(selectedDate = null, lane = ScheduleLane.SCHEDULE).showsNowCue(today))
    }

    @Test
    fun `lists the selected technicians first, in the organization's own order`() {
        val options = orderTechnicianOptions(
            technicians = crew(),
            selectedIds = setOf("member-3", "member-2"),
            query = "",
        )

        // The answer to "who did I pick?" is at the top, and each group keeps the API's order, so the
        // list does not reshuffle because a name was tapped in a different sequence (`BR-024`).
        assertEquals(
            listOf("member-2", "member-3", "member-1", "member-4"),
            options.map { it.membershipId },
        )
    }

    @Test
    fun `narrows the options to the search while the selection stays selected`() {
        // A technician chosen before a query was typed is not un-chosen by searching for somebody
        // else: the search narrows what is offered, never what is applied (`BR-012`).
        val options = orderTechnicianOptions(
            technicians = crew(),
            selectedIds = setOf("member-2", "member-3"),
            query = "gagnon",
        )

        assertEquals(listOf("member-2"), options.map { it.membershipId })
        assertEquals(
            listOf("member-2", "member-3"),
            chosenTechnicians(crew(), setOf("member-2", "member-3")).map { it.membershipId },
        )
    }

    @Test
    fun `summarises the filter as everything, one name, or a count`() {
        assertTrue(technicianFilterSummary(emptyList()) is TechnicianFilterSummary.All)

        val one = technicianFilterSummary(listOf(technician(name = "Luc Gagnon")))
        assertTrue(one is TechnicianFilterSummary.One)
        assertEquals("Luc Gagnon", (one as TechnicianFilterSummary.One).technician.name)

        // A control one tap tall counts several rather than listing them (`BR-012`).
        assertEquals(
            TechnicianFilterSummary.Many(3),
            technicianFilterSummary(crew().take(3)),
        )
    }

    @Test
    fun `names the chosen technicians in the organization's order, whatever was tapped first`() {
        val chosen = chosenTechnicians(crew(), listOf("member-4", "member-2"))

        // The chip names the first of the organization's order rather than the first tapped, so the
        // control does not change identity when another technician is added (`BR-024`).
        assertEquals(listOf("member-2", "member-4"), chosen.map { it.membershipId })
        assertEquals(emptyList<ScheduleTechnician>(), chosenTechnicians(crew(), emptyList()))
    }

    @Test
    fun `states the now cue's clock in the device's own language`() {
        val instant = Instant.parse("2026-09-07T14:35:00Z")

        // The cue is the device's clock and it is formatted the way the device's language writes a
        // time: 12-hour English, 24-hour French (`BR-028`).
        assertTrue(formatClockTime(instant, ZoneId.of("UTC"), Locale.US).contains("2:35"))
        assertTrue(formatClockTime(instant, ZoneId.of("UTC"), Locale.CANADA_FRENCH).contains("14"))
    }
}

private fun technician(
    membershipId: String = "member-1",
    name: String? = "Mike Johnson",
): ScheduleTechnician = ScheduleTechnician(
    membershipId = membershipId,
    name = name,
    roleCode = "LEAD",
)

/** Three technicians named, one unnamed, in the organization's own order. */
private fun crew(): List<ScheduleTechnician> = listOf(
    technician(membershipId = "member-1", name = "Mike Johnson"),
    technician(membershipId = "member-2", name = "Luc Gagnon"),
    technician(membershipId = "member-3", name = "Sarah Moreau"),
    technician(membershipId = "member-4", name = null),
)

private fun state(
    selectedDate: LocalDate?,
    lane: ScheduleLane,
): ScheduleUiState = ScheduleUiState(
    selectedDate = selectedDate,
    displayedWeekStart = selectedDate,
    lane = lane,
)
