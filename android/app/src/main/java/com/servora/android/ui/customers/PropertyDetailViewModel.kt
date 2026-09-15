package com.servora.android.ui.customers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.servora.android.data.customers.PropertyDeleteResult
import com.servora.android.data.customers.PropertyLifecycleRequest
import com.servora.android.data.customers.PropertyRepository
import com.servora.android.data.customers.PropertyResult
import com.servora.android.data.offline.ReadSource
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Clock
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Drives the Property lifecycle screen.
 *
 * The ViewModel decides nothing about Properties: it asks [PropertyRepository] and reports what came
 * back. Whether the user may edit, archive, restore or delete is the backend's decision, surfaced
 * here only as an outcome the screen can explain (`BR-001`, `BR-007`).
 *
 * No mutation changes the screen's state before the backend confirms it (`BR-001`, `BR-031`): a
 * failed archive leaves the Property displayed exactly as the API last described it.
 */
@HiltViewModel
class PropertyDetailViewModel @Inject constructor(
    private val propertyRepository: PropertyRepository,
    private val clock: Clock,
) : ViewModel() {

    private val _uiState = MutableStateFlow(PropertyDetailUiState())
    val uiState: StateFlow<PropertyDetailUiState> = _uiState.asStateFlow()

    init {
        // A queued action the backend finally accepted changes what this screen must show, so the
        // open Property is read again rather than left describing the state before it landed
        // (`BR-086`, §7, §10).
        viewModelScope.launch {
            propertyRepository.appliedOperations.collect { reloadAfterSync() }
        }
    }

    /**
     * The destination session [uiState] currently belongs to, or `null` before one has started.
     *
     * It is bookkeeping for telling one destination instance from another, not something the screen
     * draws, so it deliberately does not live in [PropertyDetailUiState].
     */
    private var sessionId: String? = null

    /**
     * Opens [propertyId] of [customerId], reading it unless the same Property is already settled.
     *
     * The screen drives this from its destination's arguments, so recomposing the destination must
     * not start a second read of the same Property (`BR-001`). A new destination instance, however,
     * is a new session: it starts without the previous one's action outcome, so an archive, restore
     * or delete failure the user already left behind cannot be shown again. The Property already read
     * is still reused rather than re-fetched.
     */
    fun start(customerId: String, propertyId: String, sessionId: String) {
        val newSession = sessionId != this.sessionId
        this.sessionId = sessionId
        if (newSession) {
            _uiState.update { it.copy(actionFailure = null) }
        }
        val current = _uiState.value
        if (current.customerId == customerId && current.propertyId == propertyId) {
            val settled = current.detail != null && current.failureReason == null
            if (current.isLoading || settled) {
                return
            }
        }
        load(customerId, propertyId)
    }

    /** Re-reads the open Property, after an edit or a failure. */
    fun reload(customerId: String, propertyId: String) {
        load(customerId, propertyId)
    }

    /** Re-reads the open Property once a queued action was accepted by the backend (`§7`). */
    private fun reloadAfterSync() {
        val current = _uiState.value
        if (current.propertyId.isNotEmpty()) {
            load(current.customerId, current.propertyId)
        }
    }

    /** Re-runs the read after a failure. */
    fun retry() {
        val current = _uiState.value
        if (current.propertyId.isNotEmpty()) {
            load(current.customerId, current.propertyId)
        }
    }

    /**
     * Releases the Property the screen held.
     *
     * Called when the app session ends, so a user who signs in next does not see the previous user's
     * record. The data is scoped to a session by the backend (`BR-001`), so it must not outlive that
     * session in the UI either.
     */
    fun reset() {
        sessionId = null
        _uiState.value = PropertyDetailUiState()
    }

    /**
     * Archives the Property (`BR-082`).
     *
     * The action carries a client-generated operation identifier and the device time, so a request
     * that has to be repeated cannot be applied twice by the backend (`BR-031`, `BR-086`).
     */
    fun archive() = lifecycle { customerId, propertyId, request ->
        propertyRepository.archiveProperty(customerId, propertyId, request)
    }

    /** Restores the Property to active use (`BR-082`). */
    fun restore() = lifecycle { customerId, propertyId, request ->
        propertyRepository.restoreProperty(customerId, propertyId, request)
    }

    /**
     * Permanently deletes the Property (`BR-082`).
     *
     * A refusal because the Property has history is not reported as a failure: the screen explains
     * that the record cannot be deleted and offers archiving instead. Nothing is removed locally
     * before the backend confirms the deletion.
     */
    fun delete() {
        val state = _uiState.value
        if (state.isWorking || state.propertyId.isEmpty()) {
            return
        }
        _uiState.update {
            it.copy(isWorking = true, actionFailure = null, deletionProhibited = false)
        }
        viewModelScope.launch {
            val result = propertyRepository.deleteProperty(
                customerId = state.customerId,
                propertyId = state.propertyId,
            )
            _uiState.update { current ->
                when (result) {
                    PropertyDeleteResult.Success ->
                        current.copy(isWorking = false, isDeleted = true)

                    PropertyDeleteResult.HasReferences ->
                        current.copy(isWorking = false, deletionProhibited = true)

                    is PropertyDeleteResult.Failure ->
                        current.copy(
                            isWorking = false,
                            actionFailure = result.reason,
                        )
                }
            }
        }
    }

    /** Clears the message a completed action left on screen. */
    fun dismissActionFailure() {
        _uiState.update {
            it.copy(actionFailure = null, deletionProhibited = false)
        }
    }

    /**
     * Acknowledges the archive/restore signal once the destinations it affects have re-read.
     *
     * The signal is not a value the screen renders; it exists so the derived projections another
     * screen holds can be re-read exactly once after a confirmed lifecycle change (`BR-001`).
     */
    fun acknowledgeLifecycleChange() {
        _uiState.update { it.copy(lifecycleChanged = false) }
    }

    private fun lifecycle(
        call: suspend (
            customerId: String,
            propertyId: String,
            request: PropertyLifecycleRequest,
        ) -> PropertyResult,
    ) {
        val state = _uiState.value
        if (state.isWorking || state.propertyId.isEmpty()) {
            return
        }
        // The version the client last saw travels with the mutation, so a Property that moved on is
        // refused rather than overwritten (`BR-086`).
        val request = PropertyLifecycleRequest(
            clientOperationId = UUID.randomUUID().toString(),
            capturedAt = clock.instant().toString(),
            expectedVersion = state.detail?.version,
        )
        // A reply that arrives after the user has left this destination must not be reported by the
        // next session (`BR-042`).
        val startedSession = sessionId
        _uiState.update { it.copy(isWorking = true, actionFailure = null) }
        viewModelScope.launch {
            val result = call(state.customerId, state.propertyId, request)
            if (startedSession != sessionId) return@launch
            val queued = propertyRepository.queuedOperation(state.propertyId)
            _uiState.update { current ->
                when (result) {
                    is PropertyResult.Success ->
                        current.copy(
                            isWorking = false,
                            detail = result.property,
                            detailSource = result.source,
                            queuedOperation = queued,
                            actionFailure = null,
                            // The lifecycle moved on the backend, so the projections other screens
                            // hold are stale until they are re-read (`BR-001`).
                            lifecycleChanged = true,
                        )

                    // The API could not be reached, so the action is waiting rather than done: the
                    // screen keeps the last state the backend reported and shows the pending action
                    // next to it (`BR-086`, §7).
                    is PropertyResult.Queued ->
                        current.copy(
                            isWorking = false,
                            detail = result.property ?: current.detail,
                            detailSource = if (result.property != null) {
                                ReadSource.WORKING_SET
                            } else {
                                current.detailSource
                            },
                            queuedOperation = queued,
                            actionFailure = null,
                        )

                    is PropertyResult.Failure ->
                        current.copy(
                            isWorking = false,
                            queuedOperation = queued,
                            actionFailure = result.reason,
                        )
                }
            }
        }
    }

    private fun load(customerId: String, propertyId: String) {
        _uiState.value = PropertyDetailUiState(
            customerId = customerId,
            propertyId = propertyId,
            isLoading = true,
        )
        viewModelScope.launch {
            val result = propertyRepository.loadProperty(customerId, propertyId)
            val queued = propertyRepository.queuedOperation(propertyId)
            _uiState.update { current ->
                if (current.propertyId != propertyId) {
                    // Another Property replaced this one while the read was in flight.
                    current
                } else {
                    when (result) {
                        is PropertyResult.Success ->
                            current.copy(
                                isLoading = false,
                                detail = result.property,
                                detailSource = result.source,
                                queuedOperation = queued,
                                failureReason = null,
                            )

                        is PropertyResult.Queued ->
                            // A read is never queued (only a mutation is), so this outcome cannot come
                            // from `loadProperty`; it is handled so the screen would still show the
                            // backend's last reported values rather than nothing if it ever did.
                            current.copy(
                                isLoading = false,
                                detail = result.property,
                                detailSource = ReadSource.WORKING_SET,
                                queuedOperation = queued,
                                failureReason = null,
                            )

                        is PropertyResult.Failure ->
                            current.copy(
                                isLoading = false,
                                // A failed read must not leave the previous Property on screen:
                                // it described a different record (`BR-001`).
                                detail = null,
                                queuedOperation = queued,
                                failureReason = result.reason,
                            )
                    }
                }
            }
        }
    }
}
