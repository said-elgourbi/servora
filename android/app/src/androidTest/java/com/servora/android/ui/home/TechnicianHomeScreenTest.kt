package com.servora.android.ui.home

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
import com.servora.android.domain.model.JobStatus
import com.servora.android.domain.model.TechnicianAttentionItem
import com.servora.android.domain.model.TechnicianAttentionKind
import com.servora.android.domain.model.TechnicianHome
import com.servora.android.domain.model.TechnicianHomeVisit
import com.servora.android.domain.model.VisitStatus
import com.servora.android.ui.theme.ServoraTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * How the technician home presents the caller's own day (`BR-010`, `BR-012`; `ADR-019` D6).
 *
 * The screen decides nothing about the work — every value it draws is the backend's answer — so these
 * tests cover the presentation the platform can check without a phone: the Visit to do next is what
 * the screen leads with, each list says what it holds, a day served from the device says it is the
 * last reported one, and a read that failed offers a retry instead of an empty day.
 */
@RunWith(AndroidJUnit4::class)
class TechnicianHomeScreenTest {

    @get:Rule val composeTestRule = createComposeRule()

    @Test
    fun leadsWithTheNextVisitAndShowsTheRestOfTheDay() {
        render(
            TechnicianHomeUiState(
                home = day(
                    nextVisit = visit(visitId = "next", status = VisitStatus.EN_ROUTE),
                    visits = listOf(visit(visitId = "today", status = VisitStatus.SCHEDULED)),
                    upcoming = listOf(visit(visitId = "upcoming", status = VisitStatus.SCHEDULED)),
                    upcomingTotal = 1,
                ),
            ),
        )

        composeTestRule.onNodeWithTag(TechnicianHomeNextVisitTag).assertIsDisplayed()
        composeTestRule.onNodeWithTag(technicianHomeVisitTag("next")).assertIsDisplayed()
        composeTestRule.onNodeWithTag(technicianHomeVisitTag("today")).assertIsDisplayed()
        composeTestRule.onNodeWithTag(technicianHomeVisitTag("upcoming")).assertIsDisplayed()
        // The section headings carry what each list holds, and the preview reports its whole count.
        composeTestRule
            .onNodeWithText(sectionCount(R.string.home_today_title, 1), useUnmergedTree = true)
            .assertIsDisplayed()
        composeTestRule
            .onNodeWithText(
                sectionCount(R.string.technician_home_upcoming_title, 1),
                useUnmergedTree = true,
            )
            .assertIsDisplayed()
    }

    @Test
    fun offersNoAttentionSectionWhenNothingOnTheCallersWorkNeedsIt() {
        render(TechnicianHomeUiState(home = day(nextVisit = visit())))

        // The section is absent rather than empty: it is not a dashboard (`BR-012`).
        composeTestRule
            .onNodeWithText(string(R.string.home_attention_title), substring = true)
            .assertDoesNotExist()
    }

    @Test
    fun showsWhatNeedsAttentionWhenThereIsSomething() {
        render(
            TechnicianHomeUiState(
                home = day(
                    nextVisit = visit(),
                    attention = listOf(attentionItem()),
                    attentionTotal = 1,
                ),
            ),
        )

        composeTestRule.onNodeWithTag(technicianHomeVisitTag("overdue")).assertIsDisplayed()
        composeTestRule
            .onNodeWithText(string(R.string.home_attention_title), substring = true)
            .assertIsDisplayed()
    }

    @Test
    fun opensTheJobTheNextVisitIsFor() {
        var opened: String? = null
        render(
            state = TechnicianHomeUiState(home = day(nextVisit = visit(jobId = "job-7"))),
            onOpenJob = { opened = it },
        )

        composeTestRule.onNodeWithTag(technicianHomeOpenJobTag("job-7")).performClick()

        assertTrue(opened == "job-7")
    }

    @Test
    fun saysWhenTheDayOnScreenIsTheLastTheServerReported() {
        render(
            TechnicianHomeUiState(
                home = day(nextVisit = visit()),
                source = ReadSource.WORKING_SET,
            ),
        )

        composeTestRule.onNodeWithTag(TechnicianHomeLastReportedTag).assertIsDisplayed()
        composeTestRule.onNodeWithTag(TechnicianHomeNextVisitTag).assertIsDisplayed()
    }

    @Test
    fun saysPlainlyWhenNothingIsAssigned() {
        render(
            TechnicianHomeUiState(
                home = day(nextVisit = null, visits = emptyList(), upcomingTotal = 0),
            ),
        )

        composeTestRule.onNodeWithTag(TechnicianHomeNothingTag).assertIsDisplayed()
        composeTestRule.onNodeWithTag(TechnicianHomeNextVisitTag).assertDoesNotExist()
    }

    @Test
    fun reportsAFailedFirstReadWithARetry() {
        var retried = false
        render(
            state = TechnicianHomeUiState(failureReason = CustomersFailureReason.NETWORK),
            onRetry = { retried = true },
        )

        composeTestRule.onNodeWithTag(TechnicianHomeFailureTag).assertIsDisplayed()
        composeTestRule.onNodeWithTag(TechnicianHomeRetryTag).performClick()

        assertTrue(retried)
    }
}

/** The screen a test drives, and the callbacks it recorded. */
private fun TechnicianHomeScreenTest.render(
    state: TechnicianHomeUiState,
    onOpenJob: (String) -> Unit = {},
    onRetry: () -> Unit = {},
) {
    composeTestRule.setContent {
        ServoraTheme {
            TechnicianHomeScreen(state = state, onOpenJob = onOpenJob, onRetry = onRetry)
        }
    }
}

private fun TechnicianHomeScreenTest.string(resId: Int): String =
    ApplicationProvider.getApplicationContext<Context>().getString(resId)

private fun TechnicianHomeScreenTest.sectionCount(labelResId: Int, count: Int): String =
    ApplicationProvider.getApplicationContext<Context>()
        .getString(R.string.section_count_format, string(labelResId), count)

/** A day with one Visit to do next unless the test says otherwise. */
private fun day(
    nextVisit: TechnicianHomeVisit? = visit(),
    visits: List<TechnicianHomeVisit> = emptyList(),
    upcoming: List<TechnicianHomeVisit> = emptyList(),
    upcomingTotal: Int = 0,
    attention: List<TechnicianAttentionItem> = emptyList(),
    attentionTotal: Int = 0,
) = TechnicianHome(
    displayName = "Mike Johnson",
    nextVisit = nextVisit,
    visits = visits,
    upcoming = upcoming,
    upcomingTotal = upcomingTotal,
    attention = attention,
    attentionTotal = attentionTotal,
)

private fun visit(
    visitId: String = "next",
    status: VisitStatus = VisitStatus.SCHEDULED,
    jobId: String = "job-1",
) = TechnicianHomeVisit(
    visitId = visitId,
    visitStatus = status,
    scheduledStart = "2026-09-17T13:00:00.000Z",
    scheduledEnd = "2026-09-17T14:00:00.000Z",
    jobId = jobId,
    jobNumber = 1042,
    jobTitle = "Furnace repair",
    jobStatus = JobStatus.SCHEDULED,
    customerId = "customer-1",
    customerName = "ABC Property Management",
    address = null,
    technicians = emptyList(),
    isOverdue = false,
)

private fun attentionItem() = TechnicianAttentionItem(
    kind = TechnicianAttentionKind.VISIT_OVERDUE,
    visitId = "overdue",
    jobId = "job-9",
    jobNumber = 1049,
    jobTitle = "Water heater service",
    jobStatus = JobStatus.SCHEDULED,
    customerId = "customer-1",
    customerName = "ABC Property Management",
    scheduledStart = "2026-09-16T09:00:00.000Z",
    scheduledEnd = "2026-09-16T10:00:00.000Z",
)

