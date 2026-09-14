package com.servora.android.ui.home

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.servora.android.R
import com.servora.android.domain.model.JobStatus
import com.servora.android.domain.model.ManagerAttentionItem
import com.servora.android.domain.model.ManagerAttentionKind
import com.servora.android.domain.model.ManagerHome
import com.servora.android.domain.model.ManagerHomeTodaySummary
import com.servora.android.ui.theme.ServoraTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * How the manager home presents the conditions that need the manager (`BR-012`).
 *
 * A long "Needs attention" list must not bury today's summary and schedule, so the section is a
 * disclosure: it opens expanded while it fits a screenful, and collapsed once it would push the rest
 * of the day out of sight. Collapsing is presentation only — the backend's count stays on the
 * heading, so a collapsed section never denies that work needs the manager (`BR-001`).
 */
@RunWith(AndroidJUnit4::class)
class ManagerHomeScreenTest {

    @get:Rule val composeTestRule = createComposeRule()

    @Test
    fun showsEveryConditionWhileTheListFitsAScreenful() {
        render(attentionCount = 3)

        composeTestRule.onNodeWithTag(attentionCardTag(1)).assertIsDisplayed()
        composeTestRule.onNodeWithTag(attentionCardTag(2)).assertIsDisplayed()
        composeTestRule.onNodeWithTag(attentionCardTag(3)).assertIsDisplayed()
        composeTestRule.onNodeWithTag(ManagerHomeTodayTag).assertIsDisplayed()
    }

    @Test
    fun startsCollapsedWhenManyConditionsNeedAttention() {
        render(attentionCount = 6)

        // The heading keeps the backend's count, so the manager still sees that work needs them.
        composeTestRule
            .onNodeWithText(
                sectionCount(R.string.home_attention_title, 6),
                useUnmergedTree = true,
            )
            .assertIsDisplayed()
        composeTestRule.onNodeWithTag(attentionCardTag(1)).assertDoesNotExist()

        // Which is what makes today's summary reachable without scrolling through the conditions.
        composeTestRule.onNodeWithTag(ManagerHomeTodayTag).assertIsDisplayed()
    }

    @Test
    fun showsTheCollapsedConditionsWhenTheHeadingIsTapped() {
        render(attentionCount = 6)

        composeTestRule.onNodeWithTag(ManagerHomeAttentionToggleTag).performClick()

        composeTestRule.onNodeWithTag(attentionCardTag(1)).assertIsDisplayed()
        composeTestRule.onNodeWithTag(attentionCardTag(2)).assertIsDisplayed()
    }

    @Test
    fun hidesTheConditionsAgainWhenTheHeadingIsTappedBack() {
        render(attentionCount = 6)

        composeTestRule.onNodeWithTag(ManagerHomeAttentionToggleTag).performClick()
        composeTestRule.onNodeWithTag(ManagerHomeAttentionToggleTag).performClick()

        composeTestRule.onNodeWithTag(attentionCardTag(1)).assertDoesNotExist()
    }

    @Test
    fun offersNoToggleWhenNothingNeedsAttention() {
        render(attentionCount = 0)

        composeTestRule.onNodeWithTag(ManagerHomeAllClearTag).assertIsDisplayed()
        composeTestRule
            .onNodeWithContentDescription(string(R.string.home_attention_expand))
            .assertDoesNotExist()
        composeTestRule
            .onNodeWithContentDescription(string(R.string.home_attention_collapse))
            .assertDoesNotExist()
    }

    private fun render(attentionCount: Int) {
        composeTestRule.setContent {
            ServoraTheme {
                ManagerHomeScreen(
                    state = ManagerHomeUiState(home = home(attentionCount)),
                    onOpenJob = {},
                    onOpenSchedule = {},
                    onRetry = {},
                )
            }
        }
    }

    private fun home(attentionCount: Int) = ManagerHome(
        displayName = "Sarah Tremblay",
        attention = (1..attentionCount).map { index -> attentionItem(index) },
        // The API caps the list it returns; the count is every matching condition (`attentionTotal`).
        attentionTotal = attentionCount,
        today = ManagerHomeTodaySummary(total = 8, completed = 6, inProgress = 1, upcoming = 1),
        visits = emptyList(),
    )

    private fun attentionItem(index: Int) = ManagerAttentionItem(
        kind = ManagerAttentionKind.VISIT_OVERDUE,
        jobId = jobId(index),
        jobNumber = 1040 + index,
        jobTitle = "Furnace repair",
        jobStatus = JobStatus.IN_PROGRESS,
        customerId = "customer-$index",
        customerName = "ABC Property Management",
        visitId = "visit-$index",
        scheduledStart = "2026-09-14T14:00:00Z",
        scheduledEnd = "2026-09-14T15:00:00Z",
    )

    private fun attentionCardTag(index: Int) = managerHomeAttentionItemTag(jobId(index))

    private fun jobId(index: Int) = "job-$index"

    private fun string(resId: Int): String =
        ApplicationProvider.getApplicationContext<Context>().getString(resId)

    private fun sectionCount(labelResId: Int, count: Int): String =
        ApplicationProvider.getApplicationContext<Context>()
            .getString(R.string.section_count_format, string(labelResId), count)
}
