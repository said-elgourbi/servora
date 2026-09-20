package com.servora.android.ui.customers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.servora.android.data.customers.ContactCreateResult
import com.servora.android.data.customers.CreateCustomerContactRequest
import com.servora.android.data.customers.CustomersRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Drives the Add Contact form.
 *
 * The ViewModel decides nothing about contacts: it holds the values the user typed, refuses to submit
 * an incomplete form before the round trip, and reports what [CustomersRepository] answered. The
 * backend remains the authority for authorization (`customers.contacts.create`), the one-primary
 * invariant and every stored value (`BR-001`, `BR-007`, `BR-095`).
 */
@HiltViewModel
class AddContactViewModel @Inject constructor(
    private val customersRepository: CustomersRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AddContactUiState())
    val uiState: StateFlow<AddContactUiState> = _uiState.asStateFlow()

    /**
     * The form session [uiState] currently belongs to, or `null` before one has started.
     *
     * It is bookkeeping for telling one session from another, not something the screen draws, so it
     * deliberately does not live in [AddContactUiState].
     */
    private var sessionId: String? = null

    /**
     * Begins the form session [sessionId], scoped to [customerId].
     *
     * A session is one destination instance. Re-entering the same [sessionId] — the screen being
     * composed again after a configuration change — keeps what the user typed and any message the
     * screen is showing. A different [sessionId] is a new form session, so the form starts empty and
     * no validation or submission error from the previous session can appear (`BR-042`).
     */
    fun start(customerId: String, sessionId: String) {
        if (sessionId == this.sessionId) {
            return
        }
        this.sessionId = sessionId
        _uiState.value = AddContactUiState(customerId = customerId)
    }

    fun onFirstNameChange(value: String) = edit { it.copy(firstName = value) }

    fun onLastNameChange(value: String) = edit { it.copy(lastName = value) }

    fun onPhoneChange(value: String) = edit { it.copy(phone = value) }

    fun onEmailChange(value: String) = edit { it.copy(email = value) }

    /** States whether this person becomes the customer's primary contact (`BR-095`). */
    fun onPrimaryChange(isPrimary: Boolean) = edit { it.copy(isPrimary = isPrimary) }

    /**
     * Submits the form.
     *
     * An incomplete form is not sent: the attempt is recorded so the screen can mark what is missing,
     * and no request is made (`BR-042`). A form already being saved is not sent twice.
     */
    fun save() {
        val state = _uiState.value
        if (state.isSaving) {
            return
        }
        if (!state.canSave) {
            // A new attempt must not leave the previous attempt's answer on screen.
            _uiState.update { it.copy(saveAttempted = true, failureReason = null) }
            return
        }

        _uiState.update {
            it.copy(isSaving = true, saveAttempted = true, failureReason = null)
        }
        // A reply that arrives after the user has left this form session must not be folded into the
        // next one (`BR-042`).
        val startedSession = sessionId
        viewModelScope.launch {
            val result = customersRepository.createContact(
                customerId = state.customerId,
                request = state.toCreateRequest(),
            )
            if (startedSession != sessionId) return@launch
            _uiState.update { current ->
                when (result) {
                    is ContactCreateResult.Success ->
                        current.copy(isSaving = false, isSaved = true, failureReason = null)

                    is ContactCreateResult.Failure ->
                        current.copy(isSaving = false, failureReason = result.reason)
                }
            }
        }
    }

    private fun edit(transform: (AddContactUiState) -> AddContactUiState) {
        _uiState.update { state -> transform(state).copy(failureReason = null) }
    }

    /** Releases the form's values when the app session ends (`BR-001`). */
    fun reset() {
        sessionId = null
        _uiState.value = AddContactUiState()
    }
}

/**
 * The form's values as the API request, with the blank optional fields left absent.
 *
 * `role`, `isBillingContact` and `isJobContact` are not stated by any client in this slice, so they
 * keep their defaults and are therefore absent from the request: the API stores the `null`/`false` it
 * defaults them to (`BR-042`, `ADR-022` D5).
 */
internal fun AddContactUiState.toCreateRequest(): CreateCustomerContactRequest =
    CreateCustomerContactRequest(
        firstName = firstName.trim(),
        lastName = lastName.trim(),
        phone = phone.trim().takeIf { it.isNotEmpty() },
        email = email.trim().takeIf { it.isNotEmpty() },
        isPrimary = isPrimary,
    )
