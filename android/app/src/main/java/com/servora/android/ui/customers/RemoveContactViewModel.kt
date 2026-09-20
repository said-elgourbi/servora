package com.servora.android.ui.customers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.servora.android.data.customers.ContactRemoveResult
import com.servora.android.data.customers.CustomersRepository
import com.servora.android.data.customers.RemoveCustomerContactRequest
import com.servora.android.domain.model.CustomerContact
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Drives removing a contact person from the customer detail screen.
 *
 * The removal is its own destination-scoped operation rather than part of the customer read, because
 * nothing about the screen changes until the backend has accepted it (`BR-001`, `BR-031`): the row
 * stays exactly as the API last described it while the removal is in flight, and the customer is
 * re-read only once the API confirms it (`BR-033`, `ADR-022` D8).
 *
 * The caller states the version it read, so a contact another member changed in the meantime is
 * refused rather than removed from under them (`BR-032`, `BR-095`, `ADR-022` D9). Whether the caller
 * may remove at all is the backend's decision (`customers.contacts.remove`).
 */
@HiltViewModel
class RemoveContactViewModel @Inject constructor(
    private val customersRepository: CustomersRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(RemoveContactUiState())
    val uiState: StateFlow<RemoveContactUiState> = _uiState.asStateFlow()

    /**
     * The destination session [uiState] currently belongs to, or `null` before one has started.
     *
     * It is bookkeeping for telling one destination instance from another, not something the screen
     * draws, so it deliberately does not live in [RemoveContactUiState].
     */
    private var sessionId: String? = null

    /**
     * Opens the removal for [customerId], scoped to the destination instance [sessionId].
     *
     * A new destination instance starts without the previous one's outcome, so a refusal the user
     * already left behind cannot be shown again; re-entering the same instance keeps what the screen is
     * showing (`BR-042`).
     */
    fun start(customerId: String, sessionId: String) {
        val newSession = sessionId != this.sessionId
        this.sessionId = sessionId
        if (newSession) {
            _uiState.update {
                it.copy(customerId = customerId, contactId = null, failureReason = null, isRemoved = false)
            }
        } else {
            _uiState.update { it.copy(customerId = customerId) }
        }
    }

    /**
     * Removes [contact], stating the version it was read with.
     *
     * A removal already in flight is not sent twice, so one tap cannot become two operations.
     */
    fun remove(contact: CustomerContact) {
        val state = _uiState.value
        if (state.isRemoving) {
            return
        }

        _uiState.update {
            it.copy(
                contactId = contact.id,
                isRemoving = true,
                failureReason = null,
                // A new attempt must not leave the previous attempt's answer on screen.
                isRemoved = false,
            )
        }
        // A reply that arrives after the user has left this destination must not be folded into the
        // next one (`BR-042`).
        val startedSession = sessionId
        viewModelScope.launch {
            val result = customersRepository.removeContact(
                customerId = state.customerId,
                contactId = contact.id,
                request = RemoveCustomerContactRequest(expectedVersion = contact.version),
            )
            if (startedSession != sessionId) return@launch
            _uiState.update { current ->
                when (result) {
                    is ContactRemoveResult.Success ->
                        current.copy(isRemoving = false, isRemoved = true, failureReason = null)

                    is ContactRemoveResult.Failure ->
                        current.copy(isRemoving = false, failureReason = result.reason)
                }
            }
        }
    }

    /** Clears the refusal once the screen has shown it. */
    fun dismissFailure() {
        _uiState.update { it.copy(failureReason = null) }
    }

    /**
     * Acknowledges that the removal was handled.
     *
     * The destination re-reads the customer when [RemoveContactUiState.isRemoved] is set and then calls
     * this, so re-entering the screen does not re-read for a removal that has already been applied.
     */
    fun acknowledgeRemoved() {
        _uiState.update { it.copy(isRemoved = false) }
    }

    /** Releases the removal's state when the app session ends (`BR-001`). */
    fun reset() {
        sessionId = null
        _uiState.value = RemoveContactUiState()
    }
}
