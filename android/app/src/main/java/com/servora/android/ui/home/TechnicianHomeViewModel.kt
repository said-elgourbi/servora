package com.servora.android.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.servora.android.data.home.TechnicianHomeRepository
import com.servora.android.data.home.TechnicianHomeResult
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Drives the technician home.
 *
 * The ViewModel decides nothing about the work: it asks [TechnicianHomeRepository] for the day and
 * reports what came back. Which Visit is next, which Visits are today's, which are overdue and which
 * day "today" is are the backend's answers (`BR-001`, `BR-012`), because they depend on the server
 * clock, on the caller's own assignments and on the device's time zone.
 *
 * The state the backend last reported is held here for as long as the session lasts, so returning to
 * Home shows the known day immediately and refreshes behind it — and a read that could not reach the
 * backend is served from the working set instead, marked as the last reported answer
 * (`BR-013`, offline standard §2, §7).
 */
@HiltViewModel
class TechnicianHomeViewModel @Inject constructor(
    private val repository: TechnicianHomeRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(TechnicianHomeUiState())
    val uiState: StateFlow<TechnicianHomeUiState> = _uiState.asStateFlow()

    private var readInFlight = false

    /**
     * Reads the day for [timeZoneId], or refreshes it when it is already known.
     *
     * Called when the Home destination is shown. A read already in flight is not started again, so
     * re-entering Home does not issue a second request for the same day.
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
     * Called when the session ends, so the technician who signs in next does not see the previous
     * technician's day. The read is scoped to a session by the backend and the local copy is scoped to
     * its subject (`BR-001`, `BR-007`, §10), so it must not outlive that session in the UI either. A
     * read already in flight is abandoned with it.
     */
    fun reset() {
        generation += 1
        readInFlight = false
        _uiState.value = TechnicianHomeUiState()
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
            val result = repository.loadTechnicianHome(timeZoneId)
            if (readGeneration != generation) {
                return@launch
            }
            readInFlight = false
            _uiState.update { state ->
                when (result) {
                    is TechnicianHomeResult.Success ->
                        state.copy(
                            isRefreshing = false,
                            home = result.home,
                            source = result.source,
                            failureReason = null,
                        )

                    is TechnicianHomeResult.Failure ->
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
