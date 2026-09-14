package com.servora.android.ui.home

import com.servora.android.data.customers.CustomersFailureReason
import com.servora.android.domain.model.ManagerHome
import com.servora.android.domain.model.ManagerHomeTodaySummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the manager home shows while it is loading, when it has content, and when a refresh failed.
 *
 * The screen's three states are decided here rather than in the composable, so "nothing was read
 * yet", "the backend reported this" and "the backend reported this but cannot be reached" can never
 * be confused with one another (`BR-013`, `BR-031`).
 */
class ManagerHomeUiStateTest {

    @Test
    fun `holds nothing before the first read and shows it as loading`() {
        val state = ManagerHomeUiState()

        assertTrue(state.showsInitialLoading)
        assertFalse(state.hasContent)
        assertFalse(state.showsFailure)
        assertFalse(state.showsUnrefreshedNotice)
    }

    @Test
    fun `reports a failure only when there is nothing to show`() {
        val failed = ManagerHomeUiState(failureReason = CustomersFailureReason.NETWORK)

        assertTrue(failed.showsFailure)
        assertFalse(failed.showsInitialLoading)
        assertFalse(failed.hasContent)
    }

    @Test
    fun `keeps showing content when a refresh failed`() {
        val stale = ManagerHomeUiState(
            home = home(),
            failureReason = CustomersFailureReason.NETWORK,
        )

        assertTrue(stale.hasContent)
        assertTrue(stale.showsUnrefreshedNotice)
        assertFalse(stale.showsFailure)
        assertFalse(stale.showsInitialLoading)
    }

    @Test
    fun `does not call a refresh a failure while content is on screen`() {
        val refreshing = ManagerHomeUiState(home = home(), isRefreshing = true)

        assertTrue(refreshing.hasContent)
        assertTrue(refreshing.showsRefreshingIndicator)
        assertFalse(refreshing.showsInitialLoading)
        assertFalse(refreshing.showsUnrefreshedNotice)
        assertFalse(refreshing.showsFailure)
    }

    @Test
    fun `shows no refresh indicator when nothing is being read`() {
        val idle = ManagerHomeUiState(home = home())

        assertFalse(idle.showsRefreshingIndicator)
    }

    private fun home() = ManagerHome(
        displayName = null,
        attention = emptyList(),
        attentionTotal = 0,
        today = ManagerHomeTodaySummary(total = 0, completed = 0, inProgress = 0, upcoming = 0),
        visits = emptyList(),
    )
}

/** Which greeting an hour of the day calls for (`BR-028`). */
class GreetingPeriodTest {

    @Test
    fun `greets the morning up to noon, the afternoon to six, the evening after`() {
        assertEquals(GreetingPeriod.MORNING, greetingPeriod(0))
        assertEquals(GreetingPeriod.MORNING, greetingPeriod(11))
        assertEquals(GreetingPeriod.AFTERNOON, greetingPeriod(12))
        assertEquals(GreetingPeriod.AFTERNOON, greetingPeriod(17))
        assertEquals(GreetingPeriod.EVENING, greetingPeriod(18))
        assertEquals(GreetingPeriod.EVENING, greetingPeriod(23))
    }
}

/**
 * How the "Needs attention" section starts, so a long list cannot bury the rest of the day
 * (`BR-012`).
 */
class AttentionSectionExpansionTest {

    @Test
    fun `opens expanded while the conditions fit a screenful`() {
        assertTrue(attentionSectionStartsExpanded(0))
        assertTrue(attentionSectionStartsExpanded(1))
        assertTrue(attentionSectionStartsExpanded(3))
    }

    @Test
    fun `opens collapsed once the conditions would push the day off the screen`() {
        assertFalse(attentionSectionStartsExpanded(4))
        assertFalse(attentionSectionStartsExpanded(20))
    }
}
