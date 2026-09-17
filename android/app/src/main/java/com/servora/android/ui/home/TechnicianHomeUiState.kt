package com.servora.android.ui.home

import androidx.compose.runtime.Immutable
import com.servora.android.data.customers.CustomersFailureReason
import com.servora.android.data.offline.ReadSource
import com.servora.android.domain.model.TechnicianHome

/**
 * Everything the technician home renders.
 *
 * [home] is the last state the backend reported, so the screen keeps showing it while a refresh is in
 * flight and when a refresh fails: the day is a read over authoritative records, and a request that
 * did not land must not blank what the app already knows (`BR-001`, `BR-013`).
 *
 * Nothing here is a competing source of truth: a successful read replaces [home] entirely, and a
 * failed read leaves it as it was while reporting [failureReason].
 */
@Immutable
data class TechnicianHomeUiState(
    /** Whether a read is in flight. Content stays visible while it is. */
    val isRefreshing: Boolean = false,
    /** The last state the backend reported, or `null` when nothing has been read yet. */
    val home: TechnicianHome? = null,
    /**
     * Whether [home] is the backend's answer or the last one it reported before connectivity was
     * lost (`docs/architecture/offline-first-architecture.md` §2, §7).
     */
    val source: ReadSource = ReadSource.BACKEND,
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
     * The day on screen is the last one the backend reported rather than a current answer.
     *
     * The screen presents it as such instead of claiming to be current (`BR-013`, §7), and it is
     * cleared the moment the backend answers again.
     */
    val showsLastReportedNotice: Boolean
        get() = home != null && source == ReadSource.WORKING_SET

    /**
     * Nothing is assigned to the caller at all — not now, not today and not after today.
     *
     * It is the read's own answer rather than an error (`BR-042`), so the screen says so plainly
     * instead of showing three empty sections.
     */
    val showsNothingAssigned: Boolean
        get() = home != null &&
            home.nextVisit == null &&
            home.visits.isEmpty() &&
            home.upcomingTotal == 0
}
