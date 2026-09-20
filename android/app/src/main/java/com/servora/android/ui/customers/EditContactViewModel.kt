package com.servora.android.ui.customers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.servora.android.data.customers.ContactUpdateResult
import com.servora.android.data.customers.CustomerDetailResult
import com.servora.android.data.customers.CustomersFailureReason
import com.servora.android.data.customers.CustomersRepository
import com.servora.android.data.customers.UpdateCustomerContactRequest
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Drives the Edit Contact form.
 *
 * It reuses the Add Contact form and its validation (`AddContactUiState`), because an edit states
 * exactly the fields a create supplies (`BR-095`). Its own responsibility is the part an edit adds:
 * reading the contact it edits, filling the form with what the backend reported, and carrying back the
 * version so a contact that moved on is refused rather than overwritten (`BR-032`, `BR-086`).
 *
 * The contact is read through the customer detail projection, because the API exposes no single
 * contact route (`docs/api/customers.md` §5.2): the contacts of a customer the caller may read are
 * part of the projection `customers.view` already authorizes (`ADR-022` D4). A contact the projection
 * does not carry — one that has been removed, or one that never belonged to this customer — is
 * reported as not found rather than guessed at (`BR-042`).
 *
 * Whether the caller may edit at all is the backend's decision (`BR-001`, `BR-007`).
 */
@HiltViewModel
class EditContactViewModel @Inject constructor(
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
     * Begins the form session [sessionId] for [contactId] of [customerId], reading the contact the
     * session edits.
     *
     * A session is one destination instance. Re-entering the same [sessionId] — the screen being
     * composed again after a configuration change — keeps the contact it read and any message the
     * screen is showing. A different [sessionId] is a new form session: it starts from a fresh read,
     * so neither a value nor a validation or submission error from the previous session is carried
     * over (`BR-001`, `BR-086`).
     */
    fun start(customerId: String, contactId: String, sessionId: String) {
        if (sessionId == this.sessionId) {
            return
        }
        this.sessionId = sessionId
        _uiState.value = AddContactUiState(
            customerId = customerId,
            contactId = contactId,
            isLoading = true,
        )
        load()
    }

    fun onFirstNameChange(value: String) = edit { it.copy(firstName = value) }

    fun onLastNameChange(value: String) = edit { it.copy(lastName = value) }

    fun onPhoneChange(value: String) = edit { it.copy(phone = value) }

    fun onEmailChange(value: String) = edit { it.copy(email = value) }

    /**
     * States whether this person becomes the customer's primary contact (`BR-095`).
     *
     * On an edit this states a **promotion**: the API documents `isPrimary: true`, which clears the
     * customer's previous primary in the same transaction, and defines no un-promotion, so a contact
     * that already holds the flag keeps it (`ADR-022` D9).
     */
    fun onPrimaryChange(isPrimary: Boolean) = edit { it.copy(isPrimary = isPrimary) }

    /**
     * Submits the edit.
     *
     * An incomplete form is not sent, and neither is a form that has not finished reading the contact
     * it edits: submitting it would write over state the user never saw. The attempt is recorded so the
     * screen can mark what is missing (`BR-042`).
     */
    fun save() {
        val state = _uiState.value
        val contactId = state.contactId
        val version = state.version
        if (state.isSaving || state.isLoading || contactId == null || version == null) {
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
            val result = customersRepository.updateContact(
                customerId = state.customerId,
                contactId = contactId,
                request = state.toUpdateRequest(expectedVersion = version),
            )
            if (startedSession != sessionId) return@launch
            _uiState.update { current ->
                when (result) {
                    is ContactUpdateResult.Success ->
                        current.copy(isSaving = false, isSaved = true, failureReason = null)

                    is ContactUpdateResult.Failure ->
                        current.copy(isSaving = false, failureReason = result.reason)
                }
            }
        }
    }

    /** Reads the contact the form edits, filling it with the authoritative values. */
    private fun load() {
        val startedSession = sessionId ?: return
        val state = _uiState.value
        val contactId = state.contactId ?: return
        viewModelScope.launch {
            val result = customersRepository.loadCustomerDetail(state.customerId)
            if (startedSession != sessionId) return@launch
            _uiState.update { current ->
                if (current.contactId != contactId) {
                    // Another contact replaced this one while the read was in flight.
                    current
                } else {
                    when (result) {
                        is CustomerDetailResult.Success -> {
                            val contact = result.detail.contacts.firstOrNull { it.id == contactId }
                            if (contact == null) {
                                // The projection no longer carries it, which is what a removed
                                // contact looks like from here (`BR-095`, `ADR-022` D8).
                                current.copy(
                                    isLoading = false,
                                    failureReason = CustomersFailureReason.NOT_FOUND,
                                )
                            } else {
                                current.copy(
                                    isLoading = false,
                                    failureReason = null,
                                    firstName = contact.firstName,
                                    lastName = contact.lastName,
                                    phone = contact.phone.orEmpty(),
                                    email = contact.email.orEmpty(),
                                    isPrimary = contact.isPrimary,
                                    storedIsPrimary = contact.isPrimary,
                                    version = contact.version,
                                )
                            }
                        }

                        is CustomerDetailResult.Failure -> current.copy(
                            isLoading = false,
                            failureReason = result.reason,
                        )
                    }
                }
            }
        }
    }

    private fun edit(transform: (AddContactUiState) -> AddContactUiState) {
        _uiState.update { state -> transform(state).copy(failureReason = null) }
    }

    /** Releases the contact the form held, when the app session ends (`BR-001`). */
    fun reset() {
        sessionId = null
        _uiState.value = AddContactUiState()
    }
}

/**
 * The form's values as the API's partial edit (`BR-095`, `ADR-022` D9).
 *
 * The two optional fields are stated only when they hold something, because this client's wire shape
 * cannot express "clear this value": a member left out of the request is left as the contact holds it.
 * The form can therefore state a phone or an email and it can leave one alone — the limitation
 * `docs/tracker/047-customer-contact-persons.md` records rather than a decision taken here (`BR-042`).
 *
 * `isPrimary` is stated **only to promote**, because the route documents `isPrimary: true` and defines
 * no un-promotion; a contact that already holds the flag leaves it untouched.
 */
internal fun AddContactUiState.toUpdateRequest(expectedVersion: Int): UpdateCustomerContactRequest =
    UpdateCustomerContactRequest(
        firstName = firstName.trim(),
        lastName = lastName.trim(),
        phone = phone.trim().takeIf { it.isNotEmpty() },
        email = email.trim().takeIf { it.isNotEmpty() },
        isPrimary = true.takeIf { statesPrimaryChange },
        expectedVersion = expectedVersion,
    )
