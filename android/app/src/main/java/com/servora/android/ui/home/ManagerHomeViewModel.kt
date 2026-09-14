package com.servora.android.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.servora.android.data.home.ManagerHomeRepository
import com.servora.android.data.home.ManagerHomeResult
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Drives the manager home.
 *
 * The ViewModel decides nothing about the operation: it asks [ManagerHomeRepository] for the
 * manager's day and reports what came back. Which Visits need attention, what today's counts are,
 * and which day "today" is are the backend's answers (`BR-001`), because they depend on the server
 * clock rather than on this device's.
 *
 * The state the backend last reported is held here for as long as the session lasts, so returning to
 * Home shows the known state immediately and refreshes behind it (`BR-013`). It is not written to
 * disk: the app has no business-data store yet, and the offline architecture's local working set
 * (Room) is the single mechanism that will provide one
 * (`docs/architecture/offline-first-architecture.md`).
 */
@HiltViewModel
class ManagerHomeViewModel @Inject constructor(
    private val repository: ManagerHomeRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ManagerHomeUiState())
    val uiState: StateFlow<ManagerHomeUiState> = _uiState.asStateFlow()

    private var readInFlight = false

    /**
     * Reads the manager home for [timeZoneId], or refreshes it when it is already known.
     *
     * Called when the Home destination is shown. A read already in flight is not started again, so
     * re-entering Home does not issue a second request for the same data.
     */
    fun load(timeZoneId: String) {
        if (readInFlight) {
            return
        }
        read(timeZoneId)
    }

    /** Re-runs the read after a failure the screen reported. */
    fun retry(timeZoneId: String) {
        if (readInFlight) {
            return
        }
        read(timeZoneId)
    }

    /**
     * Forgets the loaded state and allows it to be read again.
     *
     * Called when the session ends, so a user who signs in next does not see the previous manager's
     * operation. The read is scoped to a session by the backend (`BR-001`, `BR-007`), so it must not
     * outlive that session in the UI either. A read already in flight is abandoned with it.
     */
    fun reset() {
        generation += 1
        readInFlight = false
        _uiState.value = ManagerHomeUiState()
    }

    /**
     * Identifies the session a read belongs to.
     *
     * A read that completes after the state was reset belongs to a session that has ended, so its
     * answer is dropped rather than written over the new session's empty state.
     */
    private var generation = 0

    private fun read(timeZoneId: String) {
        readInFlight = true
        val readGeneration = generation
        _uiState.update { it.copy(isRefreshing = true) }
        viewModelScope.launch {
            // The read happens outside the state mutator: `update` may re-run its lambda on
            // contention, and a re-run must never issue a second request.
            val result = repository.loadManagerHome(timeZoneId)
            if (readGeneration != generation) {
                return@launch
            }
            readInFlight = false
            _uiState.update { state ->
                when (result) {
                    is ManagerHomeResult.Success ->
                        state.copy(
                            isRefreshing = false,
                            home = result.home,
                            failureReason = null,
                        )

                    is ManagerHomeResult.Failure ->
                        state.copy(
                            isRefreshing = false,
                            home = state.home,
                            failureReason = result.reason,
                        )
                }
            }
        }
    }
}
