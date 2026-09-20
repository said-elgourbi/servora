package com.servora.android.ui.schedule

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.servora.android.R
import com.servora.android.data.customers.CustomersFailureReason
import com.servora.android.domain.model.FollowUpVisitRequest
import com.servora.android.domain.model.Schedule
import com.servora.android.domain.model.ScheduleAddress
import com.servora.android.domain.model.ScheduleDay
import com.servora.android.domain.model.ScheduleScope
import com.servora.android.domain.model.ScheduleScopeKind
import com.servora.android.domain.model.ScheduleTechnician
import com.servora.android.domain.model.ScheduleVisit
import com.servora.android.domain.model.VisitStatus
import com.servora.android.ui.theme.ServoraTheme
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * How the manager schedule presents a day (`BR-012`, `BR-041`, `BR-074`).
 *
 * The screen decides nothing about the work — every value it draws is the backend's answer — so
 * these tests cover the presentation the platform can check without a phone: the week strip and its
 * selected day, the two lanes with the unassigned count, a card's own facts and its **Visit** status,
 * the empty states, and a read that failed offering a retry instead of an empty day.
 */
@RunWith(AndroidJUnit4::class)
class ScheduleScreenTest {

    @get:Rule val composeTestRule = createComposeRule()

    @Test
    fun showsTheSelectedDaysVisitsWithTheFactsDispatchScans() {
        render(state = state(visits = listOf(visit())))

        composeTestRule.onNodeWithTag(ScheduleContentTag).assertIsDisplayed()
        composeTestRule.onNodeWithTag(scheduleVisitTag("visit-1")).assertIsDisplayed()
        // The card names the crew, the work, the address and the customer.
        composeTestRule.onNodeWithText("Mike Johnson").assertIsDisplayed()
        composeTestRule.onNodeWithText("Furnace repair").assertIsDisplayed()
        composeTestRule
            .onNodeWithText("987 Cedar Lane, Montreal, QC, H3A 2T6")
            .assertIsDisplayed()
        composeTestRule.onNodeWithText("ABC Property Management").assertIsDisplayed()
        // The status is the **Visit**'s (`BR-074`, `BR-059`), never the Job's.
        composeTestRule.onNodeWithText(string(R.string.visit_status_in_progress)).assertIsDisplayed()
    }

    @Test
    fun showsAPlannedAttemptWithNoAgreedTimeAsNotScheduled() {
        render(
            state = state(
                visits = listOf(visit(scheduledStart = null, scheduledEnd = null)),
            ),
        )

        // The card states what is known rather than a time nobody agreed to (`BR-072`).
        composeTestRule
            .onNodeWithText(string(R.string.job_details_not_scheduled))
            .assertIsDisplayed()
    }

    @Test
    fun marksAVisitNobodyIsAssignedTo() {
        render(
            state = state(visits = listOf(visit(technicians = emptyList()))),
        )

        // An absent crew is presented as unassigned, not as a status (`BR-042`).
        composeTestRule
            .onNodeWithText(string(R.string.customers_job_unassigned))
            .assertIsDisplayed()
    }

    @Test
    fun countsTheCrewItCannotNameOnTheLine() {
        render(
            state = state(
                visits = listOf(
                    visit(
                        technicians = listOf(
                            technician(),
                            technician(membershipId = "member-2", name = "Luc Gagnon"),
                            technician(membershipId = "member-3", name = "Sarah Moreau"),
                        ),
                    ),
                ),
            ),
        )

        // The card is scanned, not read: two names fit on the line and the rest is counted, so the
        // card never grows a line just for names (`BR-012`).
        composeTestRule
            .onNodeWithText(
                string(R.string.schedule_crew_more, "Mike Johnson, Luc Gagnon", 1),
            )
            .assertIsDisplayed()
    }

    @Test
    fun namesThePropertyBesideTheCustomerWhenTheSnapshotCarriedOne() {
        render(
            state = state(visits = listOf(visit(propertyName = "Riverside Plaza"))),
        )

        composeTestRule
            .onNodeWithText(
                string(R.string.schedule_customer_property_format, "ABC Property Management", "Riverside Plaza"),
            )
            .assertIsDisplayed()
    }

    @Test
    fun showsTheNowCueOnTodayOnly() {
        val today = LocalDate.now(ZoneId.of(DEVICE_ZONE))
        render(state = state(selectedDate = today, displayedWeekStart = weekStartOf(today, DayOfWeek.MONDAY)))

        // The cue says where in the day the manager is reading, and only a day being lived through
        // has a "now" in it.
        composeTestRule.onNodeWithTag(ScheduleNowCueTag).assertIsDisplayed()
    }

    @Test
    fun leavesTheNowCueOutOfAnotherDay() {
        render(state = state())

        composeTestRule.onNodeWithTag(ScheduleNowCueTag).assertDoesNotExist()
    }

    @Test
    fun showsTheWeekStripWithTheSelectedDay() {
        render(state = state())

        composeTestRule.onNodeWithTag(ScheduleWeekStripTag).assertIsDisplayed()
        // The selected day's cell is drawn from the state's date, not from the device clock, and its
        // neighbours are on the same page.
        composeTestRule.onNodeWithTag(scheduleDayTag(SELECTED_DATE)).assertIsDisplayed()
        composeTestRule.onNodeWithTag(scheduleDayTag(SELECTED_DATE.plusDays(1))).assertExists()
    }

    @Test
    fun reportsTheDayTheManagerTaps() {
        var selected: LocalDate? = null
        render(state = state(), onSelectDate = { selected = it })

        composeTestRule.onNodeWithTag(scheduleDayTag(SELECTED_DATE.plusDays(2))).performClick()

        assertEquals(SELECTED_DATE.plusDays(2), selected)
    }

    @Test
    fun opensTheMonthCalendarFromTheDateHeader() {
        render(state = state())

        composeTestRule.onNodeWithTag(ScheduleDateHeaderTag).performClick()

        // The calendar is the jump-to-date mechanism, and nothing else (`BR-042`).
        composeTestRule.onNodeWithTag(ScheduleDatePickerTag).assertIsDisplayed()
    }

    @Test
    fun switchesToTheUnassignedLaneAndShowsItsCount() {
        var lane: ScheduleLane? = null
        render(state = state(), onSelectLane = { lane = it })

        // The badge carries the backend's own total (`BR-042`).
        composeTestRule.onNodeWithTag(ScheduleUnassignedBadgeTag).assertIsDisplayed()
        composeTestRule.onNodeWithTag(ScheduleLaneUnassignedTag).performClick()

        assertEquals(ScheduleLane.UNASSIGNED, lane)
    }

    @Test
    fun showsTheUnassignedLanesOwnCards() {
        render(state = state(lane = ScheduleLane.UNASSIGNED))

        composeTestRule.onNodeWithTag(scheduleVisitTag("visit-9")).assertIsDisplayed()
        composeTestRule.onNodeWithTag(scheduleVisitTag("visit-1")).assertDoesNotExist()
    }

    @Test
    fun showsTheEmptyStatesInTheLanesOwnWords() {
        render(state = state(visits = emptyList(), unassigned = emptyList(), unassignedTotal = 0))

        composeTestRule.onNodeWithTag(ScheduleEmptyDayTag).assertIsDisplayed()
        composeTestRule
            .onNodeWithText(string(R.string.schedule_empty_day_title))
            .assertIsDisplayed()
    }

    @Test
    fun saysWhenNothingIsWaitingForACrew() {
        render(
            state = state(
                lane = ScheduleLane.UNASSIGNED,
                unassigned = emptyList(),
                unassignedTotal = 0,
            ),
        )

        composeTestRule.onNodeWithTag(ScheduleEmptyUnassignedTag).assertIsDisplayed()
        composeTestRule
            .onNodeWithText(string(R.string.schedule_empty_unassigned_title))
            .assertIsDisplayed()
    }

    @Test
    fun opensTheJobACardIsFor() {
        var opened: String? = null
        render(state = state(), onOpenJob = { opened = it })

        composeTestRule.onNodeWithTag(scheduleVisitTag("visit-1")).performClick()

        assertEquals("job-1", opened)
    }

    @Test
    fun filtersTheDayByTheTechniciansTheManagerApplies() {
        var applied: List<ScheduleTechnician>? = null
        render(state = state(), onApplyTechnicians = { applied = it })

        composeTestRule.onNodeWithTag(ScheduleTechnicianFilterTag).performClick()
        composeTestRule.onNodeWithTag(ScheduleTechnicianSheetTag).assertIsDisplayed()
        composeTestRule.onNodeWithTag(ScheduleTechnicianAllTag).assertIsDisplayed()
        composeTestRule.onNodeWithTag(scheduleTechnicianOptionTag("member-1")).performClick()
        composeTestRule.onNodeWithTag(ScheduleTechnicianApplyTag).performClick()

        // Choosing a technician is a draft: the day is narrowed when the manager applies it
        // (`BR-068`).
        assertEquals(listOf("member-1"), applied?.map { it.membershipId })
    }

    @Test
    fun closesTheSheetWhenTheManagerAppliesTheSelection() {
        render(state = state())

        composeTestRule.onNodeWithTag(ScheduleTechnicianFilterTag).performClick()
        composeTestRule.onNodeWithTag(scheduleTechnicianOptionTag("member-1")).performClick()
        composeTestRule.onNodeWithTag(ScheduleTechnicianApplyTag).performClick()

        // Applying commits the filter and closes the sheet, so the manager is left looking at the day
        // they just asked for rather than at the sheet over it (`BR-012`). Clear is the exception: it
        // asks for the whole organization and stays open, which its own test covers.
        composeTestRule.onNodeWithTag(ScheduleTechnicianSheetTag).assertDoesNotExist()
    }

    @Test
    fun collectsSeveralTechniciansBeforeApplyingThem() {
        var applied: List<ScheduleTechnician>? = null
        val technicians = listOf(
            technician(membershipId = "member-1", name = "Mike Johnson"),
            technician(membershipId = "member-2", name = "Luc Gagnon"),
            technician(membershipId = "member-3", name = "Sarah Moreau"),
        )
        render(state = state(technicians = technicians), onApplyTechnicians = { applied = it })

        composeTestRule.onNodeWithTag(ScheduleTechnicianFilterTag).performClick()
        composeTestRule.onNodeWithTag(scheduleTechnicianOptionTag("member-1")).performClick()
        composeTestRule.onNodeWithTag(scheduleTechnicianOptionTag("member-2")).performClick()
        composeTestRule.onNodeWithTag(scheduleTechnicianOptionTag("member-3")).performClick()

        // The sheet stays open while technicians are tapped, so a group is chosen in one visit.
        composeTestRule.onNodeWithTag(ScheduleTechnicianSheetTag).assertIsDisplayed()
        composeTestRule.onNodeWithTag(ScheduleTechnicianApplyTag).performClick()

        assertEquals(
            listOf("member-1", "member-2", "member-3"),
            applied?.map { it.membershipId },
        )
    }

    @Test
    fun clearsTheFilterFromTheSheet() {
        var applied: List<ScheduleTechnician>? = null
        render(
            state = state(selected = listOf(technician())),
            onApplyTechnicians = { applied = it },
        )

        composeTestRule.onNodeWithTag(ScheduleTechnicianFilterTag).performClick()
        composeTestRule.onNodeWithTag(ScheduleTechnicianClearTag).performClick()

        // Clearing asks for the whole organization at once and leaves the sheet open, so another
        // filter can be chosen without reopening it (`BR-068`).
        assertEquals(emptyList<String>(), applied?.map { it.membershipId })
        composeTestRule.onNodeWithTag(ScheduleTechnicianSheetTag).assertIsDisplayed()
    }

    @Test
    fun namesTheSingleTechnicianTheChipSummarises() {
        render(state = state(selected = listOf(technician(name = "Luc Gagnon"))))

        // One technician is named; several are counted, because the control is one line (`BR-012`).
        composeTestRule.onNodeWithText("Luc Gagnon").assertIsDisplayed()
    }

    @Test
    fun countsTheTechniciansTheChipCannotName() {
        render(
            state = state(
                selected = listOf(
                    technician(membershipId = "member-1", name = "Luc Gagnon"),
                    technician(membershipId = "member-2", name = "Sarah Moreau"),
                    technician(membershipId = "member-3", name = "Priya Raman"),
                ),
            ),
        )

        composeTestRule
            .onNodeWithText(string(R.plurals.schedule_filter_selected_technicians, 3, 3))
            .assertIsDisplayed()
    }

    @Test
    fun searchesTheTechniciansTheFilterOffers() {
        render(state = state())

        composeTestRule.onNodeWithTag(ScheduleTechnicianFilterTag).performClick()
        composeTestRule.onNodeWithTag(ScheduleTechnicianSearchTag).performTextInput("gagnon")

        // The search narrows the options the API returned (`BR-024`), and it never removes the way
        // back to the whole organization.
        composeTestRule.onNodeWithTag(ScheduleTechnicianAllTag).assertIsDisplayed()
        composeTestRule.onNodeWithTag(scheduleTechnicianOptionTag("member-1")).assertDoesNotExist()
    }

    @Test
    fun keepsASelectionTheSearchHasNarrowedAway() {
        var applied: List<ScheduleTechnician>? = null
        val technicians = listOf(
            technician(membershipId = "member-1", name = "Mike Johnson"),
            technician(membershipId = "member-2", name = "Luc Gagnon"),
        )
        render(
            state = state(technicians = technicians),
            onApplyTechnicians = { applied = it },
        )

        composeTestRule.onNodeWithTag(ScheduleTechnicianFilterTag).performClick()
        composeTestRule.onNodeWithTag(scheduleTechnicianOptionTag("member-1")).performClick()
        composeTestRule.onNodeWithTag(ScheduleTechnicianSearchTag).performTextInput("gagnon")
        composeTestRule.onNodeWithTag(scheduleTechnicianOptionTag("member-2")).performClick()
        composeTestRule.onNodeWithTag(ScheduleTechnicianApplyTag).performClick()

        // Searching narrows what is offered, never what is chosen: the technician picked before the
        // query was typed is still applied (`BR-012`).
        assertEquals(listOf("member-1", "member-2"), applied?.map { it.membershipId })
    }

    @Test
    fun listsTheSelectedTechniciansBeforeTheRest() {
        render(
            state = state(
                technicians = listOf(
                    technician(membershipId = "member-1", name = "Mike Johnson"),
                    technician(membershipId = "member-2", name = "Luc Gagnon"),
                ),
                selected = listOf(technician(membershipId = "member-2", name = "Luc Gagnon")),
            ),
        )

        composeTestRule.onNodeWithTag(ScheduleTechnicianFilterTag).performClick()

        // The technician who is in effect is offered first, so "who did I pick?" is answered at the
        // top of the list rather than wherever a name sorts (`BR-012`).
        composeTestRule.onNodeWithTag(scheduleTechnicianOptionTag("member-2")).assertIsDisplayed()
    }

    @Test
    fun saysWhenNoTechnicianMatchesTheSearch() {
        render(state = state())

        composeTestRule.onNodeWithTag(ScheduleTechnicianFilterTag).performClick()
        composeTestRule.onNodeWithTag(ScheduleTechnicianSearchTag).performTextInput("zzz")
        composeTestRule.onNodeWithTag(ScheduleTechnicianEmptyTag).assertIsDisplayed()

        composeTestRule
            .onNodeWithText(string(R.string.schedule_filter_no_results))
            .assertIsDisplayed()
    }

    @Test
    fun reportsAFailedFirstReadWithARetry() {
        var retried = false
        render(
            state = state(failure = CustomersFailureReason.NETWORK, schedule = null),
            onRetry = { retried = true },
        )

        composeTestRule.onNodeWithTag(ScheduleFailureTag).assertIsDisplayed()
        composeTestRule.onNodeWithTag(ScheduleRetryTag).performClick()

        assertTrue(retried)
    }
}

/** The screen a test drives, and the callbacks it recorded. */
private fun ScheduleScreenTest.render(
    state: ScheduleUiState,
    onSelectDate: (LocalDate) -> Unit = {},
    onSelectLane: (ScheduleLane) -> Unit = {},
    onApplyTechnicians: (List<ScheduleTechnician>) -> Unit = {},
    onOpenJob: (String) -> Unit = {},
    onClarifyRequest: (FollowUpVisitRequest) -> Unit = {},
    onRejectRequest: (FollowUpVisitRequest) -> Unit = {},
    onRetry: () -> Unit = {},
) {
    composeTestRule.setContent {
        ServoraTheme {
            ScheduleScreen(
                state = state,
                onSelectDate = onSelectDate,
                onShowWeek = {},
                onSelectLane = onSelectLane,
                onApplyTechnicians = onApplyTechnicians,
                onOpenJob = onOpenJob,
                onClarifyRequest = onClarifyRequest,
                onRejectRequest = onRejectRequest,
                onRetry = onRetry,
            )
        }
    }
}

private fun ScheduleScreenTest.string(resId: Int, vararg formatArgs: Any): String =
    ApplicationProvider.getApplicationContext<Context>().getString(resId, *formatArgs)

/** A day with one scheduled Visit and two waiting for a crew, unless the test says otherwise. */
private fun state(
    lane: ScheduleLane = ScheduleLane.SCHEDULE,
    visits: List<ScheduleVisit> = listOf(visit()),
    unassigned: List<ScheduleVisit> = listOf(
        visit(visitId = "visit-9", status = VisitStatus.DRAFT, scheduledStart = null, scheduledEnd = null),
        visit(visitId = "visit-10", status = VisitStatus.DRAFT, scheduledStart = null, scheduledEnd = null),
    ),
    unassignedTotal: Int = 2,
    selectedDate: LocalDate = SELECTED_DATE,
    displayedWeekStart: LocalDate = SELECTED_DATE,
    technicians: List<ScheduleTechnician> = listOf(technician()),
    selected: List<ScheduleTechnician> = emptyList(),
    schedule: Schedule? = null,
    failure: CustomersFailureReason? = null,
): ScheduleUiState = ScheduleUiState(
    timeZoneId = DEVICE_ZONE,
    firstDayOfWeek = DayOfWeek.MONDAY,
    selectedDate = selectedDate,
    displayedWeekStart = displayedWeekStart,
    lane = lane,
    technicianFilters = selected,
    schedule = schedule ?: Schedule(
        day = ScheduleDay(localDate = selectedDate.toString(), timeZone = DEVICE_ZONE),
        scope = ScheduleScope(
            kind = ScheduleScopeKind.ORGANIZATION,
            membershipId = "member-1",
        ),
        technicians = technicians,
        visits = visits,
        unassigned = unassigned,
        unassignedTotal = unassignedTotal,
        hasUnassignedLane = true,
    ),
    failureReason = failure,
)

private fun technician(
    membershipId: String = "member-1",
    name: String = "Mike Johnson",
) = ScheduleTechnician(
    membershipId = membershipId,
    name = name,
    roleCode = "LEAD",
)

private fun visit(
    visitId: String = "visit-1",
    status: VisitStatus = VisitStatus.IN_PROGRESS,
    scheduledStart: String? = "2026-09-07T13:00:00.000Z",
    scheduledEnd: String? = "2026-09-07T14:00:00.000Z",
    technicians: List<ScheduleTechnician> = listOf(technician()),
    propertyName: String? = null,
) = ScheduleVisit(
    visitId = visitId,
    visitStatus = status,
    scheduledStart = scheduledStart,
    scheduledEnd = scheduledEnd,
    jobId = "job-1",
    jobNumber = 1042,
    jobTitle = "Furnace repair",
    customerId = "customer-1",
    customerName = "ABC Property Management",
    address = ScheduleAddress(
        propertyName = propertyName,
        addressLine1 = "987 Cedar Lane",
        addressLine2 = null,
        city = "Montreal",
        province = "QC",
        postalCode = "H3A 2T6",
        country = null,
    ),
    technicians = technicians,
    isOverdue = false,
)

private val SELECTED_DATE: LocalDate = LocalDate.parse("2026-09-07")

/** The zone the screen resolves its days in, so a test can ask what "today" is there. */
private const val DEVICE_ZONE = "America/Toronto"
