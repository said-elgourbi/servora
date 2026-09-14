package com.servora.android.ui.home

import androidx.compose.runtime.Immutable
import com.servora.android.data.customers.CustomersFailureReason
import com.servora.android.domain.model.ManagerHome

/**
 * Everything the manager home renders.
 *
 * [home] is the last state the backend reported, so the screen keeps showing it while a refresh is
 * in flight and when a refresh fails: the manager home is a read over authoritative records, and a
 * request that did not land must not blank what the app already knows (`BR-001`, `BR-013`).
 *
 * Nothing here is a competing source of truth: a successful read replaces [home] entirely, and a
 * failed read leaves it as it was while reporting [failureReason].
 */
@Immutable
data class ManagerHomeUiState(
    /** Whether a read is in flight. Content stays visible while it is. */
    val isRefreshing: Boolean = false,
    /** The last state the backend reported, or `null` when nothing has been read yet. */
    val home: ManagerHome? = null,
    /** Why the last read failed, or `null` when it succeeded. */
    val failureReason: CustomersFailureReason? = null,
) {
    /** Nothing has been read yet: the screen shows its first-load state. */
    val showsInitialLoading: Boolean
        get() = home == null && failureReason == null

    /** Nothing has been read and the read failed: the screen reports the failure. */
    val showsFailure: Boolean
        get() = home == null && failureReason != null

    /** The backend has reported this screen's data at least once. */
    val hasContent: Boolean
        get() = home != null

    /** Cached content is on screen while a later read is in flight. */
    val showsRefreshingIndicator: Boolean
        get() = home != null && isRefreshing

    /**
     * Cached content is on screen while the last read failed.
     *
     * The screen then presents what the backend last reported and says, without blocking, that it
     * could not be refreshed.
     */
    val showsUnrefreshedNotice: Boolean
        get() = home != null && failureReason != null
}

/** The part of the day the header greets the manager by. */
enum class GreetingPeriod {
    MORNING,
    AFTERNOON,
    EVENING,
}

/**
 * Which greeting an hour of the local day calls for.
 *
 * It is presentation, not business state: the hour comes from the device clock and decides only
 * which localized greeting the header uses.
 */
fun greetingPeriod(hourOfDay: Int): GreetingPeriod =
    when {
        hourOfDay < MORNING_ENDS_AT_HOUR -> GreetingPeriod.MORNING
        hourOfDay < AFTERNOON_ENDS_AT_HOUR -> GreetingPeriod.AFTERNOON
        else -> GreetingPeriod.EVENING
    }

private const val MORNING_ENDS_AT_HOUR = 12
private const val AFTERNOON_ENDS_AT_HOUR = 18

/**
 * Whether the "Needs attention" section opens expanded when it holds [attentionCount] cards.
 *
 * The section is the day's most important information, so it opens expanded while it fits a
 * screenful. Beyond that its cards would push today's summary and schedule out of sight, which is
 * the opposite of what the home is for (`BR-010`, `BR-012`), so a longer section opens collapsed
 * instead — its heading still reports the backend's count, so nothing disappears silently.
 *
 * This is presentation, not business state: the conditions themselves are unchanged, and one tap
 * shows them (`BR-042`).
 */
fun attentionSectionStartsExpanded(attentionCount: Int): Boolean =
    attentionCount <= ATTENTION_SECTION_EXPANDED_UP_TO

/** How many attention cards the section shows without being collapsed first. */
private const val ATTENTION_SECTION_EXPANDED_UP_TO = 3
