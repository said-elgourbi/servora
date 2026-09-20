package com.servora.android.ui.schedule

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.servora.android.R
import com.servora.android.data.customers.CustomersFailureReason
import com.servora.android.data.offline.ReadSource
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
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * How the technician's schedule presents the caller's own day (`BR-010`, `BR-012`, `BR-041`, `BR-074`).
 *
 * The screen decides nothing about the work — every value it draws is the backend's answer for the
 * caller's own membership — so these tests cover the presentation the platform can check without a
 * phone: the week the header names, the day heading, a Visit's own facts and its **Visit** status, the
 * caller's own crew line, the empty day, and a read that failed offering a retry.
 */
@RunWith(AndroidJUnit4::class)
class TechnicianScheduleScreenTest {

    @get:Rule val composeTestRule = createComposeRule()

    @Test
    fun showsTheCallersDayWithTheFactsFieldWorkNeeds() {
        render(state = technicianState(visits = listOf(technicianVisit())))

        composeTestRule.onNodeWithTag(TechnicianScheduleContentTag).assertIsDisplayed()
        composeTestRule
            .onNodeWithTag(technicianScheduleVisitTag("visit-1"))
            .assertIsDisplayed()
        composeTestRule.onNodeWithText("Furnace repair").assertIsDisplayed()
        composeTestRule.onNodeWithText("ABC Property Management").assertIsDisplayed()
        composeTestRule
            .onNodeWithText("987 Cedar Lane, Montreal, QC, H3A 2T6")
            .assertIsDisplayed()
        // The status is the **Visit**'s (`BR-074`, `BR-059`), never the Job's.
        composeTestRule
            .onNodeWithText(string(R.string.visit_status_scheduled))
            .assertIsDisplayed()
    }

    @Test
    fun saysTheCallerIsOnTheCrew() {
        render(
            state = technicianState(
                visits = listOf(
                    technicianVisit(
                        crew = listOf(
                            crewMember("member-7", "Mike Johnson", roleCode = "LEAD"),
                            crewMember("member-2", "Sarah Kim"),
                        ),
                    ),
                ),
            ),
        )

        // "You + 1" is what the field asks: how many are coming with me (`BR-068`).
        composeTestRule
            .onNodeWithText(
                string(R.string.schedule_crew_more, string(R.string.technician_schedule_you), 1),
            )
            .assertIsDisplayed()
    }

    @Test
    fun namesTheCrewWhenTheCallerIsNotOnIt() {
        render(
            state = technicianState(
                visits = listOf(
                    technicianVisit(crew = listOf(crewMember("member-2", "Sarah Kim", roleCode = "LEAD"))),
                ),
            ),
        )

        composeTestRule.onNodeWithText("Sarah Kim").assertIsDisplayed()
    }

    @Test
    fun opensTheJobTheVisitBelongsTo() {
        var opened: String? = null
        render(
            state = technicianState(visits = listOf(technicianVisit())),
            onOpenJob = { jobId -> opened = jobId },
        )

        composeTestRule.onNodeWithTag(technicianScheduleVisitTag("visit-1")).performClick()

        assertEquals("job-1", opened)
    }

    @Test
    fun saysWhenTheDayHasNothingAssigned() {
        render(state = technicianState(visits = emptyList()))

        composeTestRule.onNodeWithTag(TechnicianScheduleEmptyDayTag).assertIsDisplayed()
        composeTestRule
            .onNodeWithText(string(R.string.technician_schedule_empty_title))
            .assertIsDisplayed()
    }

    @Test
    fun offersARetryWhenNothingCouldBeRead() {
        var retried = false
        render(
            state = TechnicianScheduleUiState(
                timeZoneId = TECHNICIAN_DEVICE_ZONE,
                selectedDate = SELECTED_DAY,
                displayedWeekStart = SELECTED_DAY,
                failureReason = CustomersFailureReason.NETWORK,
            ),
            onRetry = { retried = true },
        )

        composeTestRule.onNodeWithTag(TechnicianScheduleFailureTag).assertIsDisplayed()
        composeTestRule.onNodeWithTag(TechnicianScheduleRetryTag).performClick()

        assertEquals(true, retried)
    }

    @Test
    fun showsTheFirstReadAsLoading() {
        render(
            state = TechnicianScheduleUiState(
                timeZoneId = TECHNICIAN_DEVICE_ZONE,
                selectedDate = SELECTED_DAY,
                displayedWeekStart = SELECTED_DAY,
            ),
        )

        composeTestRule.onNodeWithTag(TechnicianScheduleLoadingTag).assertIsDisplayed()
    }
}

/** The screen a test drives, and the callbacks it recorded. */
private fun TechnicianScheduleScreenTest.render(
    state: TechnicianScheduleUiState,
    onSelectDate: (LocalDate) -> Unit = {},
    onShowToday: () -> Unit = {},
    onOpenJob: (String) -> Unit = {},
    onRetry: () -> Unit = {},
) {
    composeTestRule.setContent {
        ServoraTheme {
            TechnicianScheduleScreen(
                state = state,
                onSelectDate = onSelectDate,
                onShowWeek = {},
                onShowToday = onShowToday,
                onOpenJob = onOpenJob,
                onRetry = onRetry,
            )
        }
    }
}

private fun TechnicianScheduleScreenTest.string(resId: Int, vararg formatArgs: Any): String =
    ApplicationProvider.getApplicationContext<Context>().getString(resId, *formatArgs)

/**
 * The caller's own day: one Visit they are on, unless the test says otherwise.
 *
 * The scope is `SELF`, which is the answer the API gives a field caller, and the lane is absent
 * because that scope has none (`BR-009`).
 */
private fun technicianState(
    visits: List<ScheduleVisit> = listOf(technicianVisit()),
    selectedDate: LocalDate = SELECTED_DAY,
    displayedWeekStart: LocalDate = SELECTED_DAY,
    source: ReadSource = ReadSource.BACKEND,
): TechnicianScheduleUiState = TechnicianScheduleUiState(
    timeZoneId = TECHNICIAN_DEVICE_ZONE,
    firstDayOfWeek = DayOfWeek.MONDAY,
    selectedDate = selectedDate,
    displayedWeekStart = displayedWeekStart,
    schedule = Schedule(
        day = ScheduleDay(localDate = selectedDate.toString(), timeZone = TECHNICIAN_DEVICE_ZONE),
        scope = ScheduleScope(kind = ScheduleScopeKind.SELF, membershipId = VIEWER_MEMBERSHIP_ID),
        technicians = emptyList(),
        visits = visits,
        unassigned = emptyList(),
        unassignedTotal = 0,
        hasUnassignedLane = false,
    ),
    source = source,
)

private fun crewMember(
    membershipId: String,
    name: String,
    roleCode: String = "TECHNICIAN",
): ScheduleTechnician = ScheduleTechnician(
    membershipId = membershipId,
    name = name,
    roleCode = roleCode,
)

private fun technicianVisit(
    visitId: String = "visit-1",
    status: VisitStatus = VisitStatus.SCHEDULED,
    crew: List<ScheduleTechnician> = listOf(
        crewMember(VIEWER_MEMBERSHIP_ID, "Mike Johnson", roleCode = "LEAD"),
    ),
) = ScheduleVisit(
    visitId = visitId,
    visitStatus = status,
    scheduledStart = "2026-09-07T13:00:00.000Z",
    scheduledEnd = "2026-09-07T14:00:00.000Z",
    jobId = "job-1",
    jobNumber = 1042,
    jobTitle = "Furnace repair",
    customerId = "customer-1",
    customerName = "ABC Property Management",
    address = ScheduleAddress(
        propertyName = null,
        addressLine1 = "987 Cedar Lane",
        addressLine2 = null,
        city = "Montreal",
        province = "QC",
        postalCode = "H3A 2T6",
        country = null,
    ),
    technicians = crew,
    isOverdue = false,
)

private val SELECTED_DAY: LocalDate = LocalDate.parse("2026-09-07")

/** The membership the read was resolved for, which is how the screen recognises the caller. */
private const val VIEWER_MEMBERSHIP_ID = "member-7"

/** The zone the screen resolves its days in, so a test can ask what "today" is there. */
private const val TECHNICIAN_DEVICE_ZONE = "America/Toronto"
