package com.servora.android.ui.customers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.servora.android.data.customers.CompanyCustomerPayload
import com.servora.android.data.customers.CustomerDetailResult
import com.servora.android.data.customers.CustomerUpdateResult
import com.servora.android.data.customers.CustomersRepository
import com.servora.android.data.customers.IndividualCustomerPayload
import com.servora.android.data.customers.UpdateCustomerRequest
import com.servora.android.domain.model.CustomerStatus
import com.servora.android.domain.model.CustomerType
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Drives the Edit Customer form.
 *
 * Its responsibility is what an edit adds to the customer: reading the customer it edits, filling the
 * form with the values the backend returned, and stating the type so a conversion is applied by the
 * backend (`BR-087`). The ViewModel decides nothing about customers — it holds what the user typed,
 * refuses to submit an incomplete form, and reports what [CustomersRepository] answered. The backend
 * remains the authority for authorization, validation and every stored value (`BR-001`, `BR-007`).
 */
@HiltViewModel
class EditCustomerViewModel @Inject constructor(
    private val customersRepository: CustomersRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(EditCustomerUiState())
    val uiState: StateFlow<EditCustomerUiState> = _uiState.asStateFlow()

    /**
     * The form session [uiState] currently belongs to, or `null` before one has started.
     *
     * It is bookkeeping for telling one session from another, not something the screen draws, so it
     * deliberately does not live in [EditCustomerUiState].
     */
    private var sessionId: String? = null

    /**
     * Begins the form session [sessionId] for [customerId], reading the customer the session edits.
     *
     * A session is one destination instance. Re-entering the same [sessionId] — the screen being
     * composed again after a configuration change — keeps the values it read and any message the
     * screen is showing. A different [sessionId] is a new form session: it starts from a fresh read,
     * so neither a value nor a validation or submission error from the previous session carries over
     * (`BR-001`).
     */
    fun start(customerId: String, sessionId: String) {
        if (sessionId == this.sessionId) {
            return
        }
        this.sessionId = sessionId
        _uiState.value = EditCustomerUiState(customerId = customerId, isLoading = true)
        load()
    }

    /** Re-reads the customer after the read failed, without starting a new form session. */
    fun retry() {
        val state = _uiState.value
        if (state.isLoading || state.isLoaded) {
            return
        }
        _uiState.update { it.copy(isLoading = true, failureReason = null) }
        load()
    }

    fun onTypeChange(type: CustomerType) = edit { it.copy(type = type) }

    fun onCompanyNameChange(value: String) = edit { it.copy(companyName = value) }

    fun onFirstNameChange(value: String) = edit { it.copy(firstName = value) }

    fun onLastNameChange(value: String) = edit { it.copy(lastName = value) }

    fun onPhoneChange(value: String) = edit { it.copy(phone = value) }

    fun onEmailChange(value: String) = edit { it.copy(email = value) }

    fun onNotesChange(value: String) = edit { it.copy(notes = value) }

    fun onStatusChange(status: CustomerStatus) = edit { it.copy(status = status) }

    /**
     * Submits the edit.
     *
     * A form that is not complete, and a form that has not finished reading the customer it edits,
     * are not sent: the first records the attempt so the screen can mark what is missing, and the
     * second would overwrite values the user never saw.
     */
    fun save() {
        val state = _uiState.value
        if (state.isSaving || state.isLoading || !state.isLoaded) {
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
            val result = customersRepository.updateCustomer(
                customerId = state.customerId,
                request = state.toUpdateRequest(),
            )
            if (startedSession != sessionId) return@launch
            _uiState.update { current ->
                when (result) {
                    CustomerUpdateResult.Success -> current.copy(
                        isSaving = false,
                        isSaved = true,
                        failureReason = null,
                    )

                    is CustomerUpdateResult.Failure -> current.copy(
                        isSaving = false,
                        failureReason = result.reason,
                    )
                }
            }
        }
    }

    /** Reads the customer the form edits, filling the form with the authoritative values. */
    private fun load() {
        val startedSession = sessionId ?: return
        val customerId = _uiState.value.customerId
        viewModelScope.launch {
            val result = customersRepository.loadCustomerDetail(customerId)
            if (startedSession != sessionId) return@launch
            _uiState.update { state ->
                when (result) {
                    is CustomerDetailResult.Success -> state.copy(
                        isLoading = false,
                        isLoaded = true,
                        failureReason = null,
                        type = result.detail.customer.type,
                        storedType = result.detail.customer.type,
                        companyName = result.detail.company?.legalName.orEmpty(),
                        firstName = result.detail.individual?.firstName.orEmpty(),
                        lastName = result.detail.individual?.lastName.orEmpty(),
                        phone = result.detail.customer.phone.orEmpty(),
                        email = result.detail.customer.email.orEmpty(),
                        notes = result.detail.customer.notes.orEmpty(),
                        status = result.detail.customer.status,
                        // The form never writes them; they are shown read-only so the user sees who
                        // is recorded while editing the customer's own fields (`BR-095`, `ADR-022` D5).
                        contacts = result.detail.contacts,
                    )

                    is CustomerDetailResult.Failure -> state.copy(
                        isLoading = false,
                        isLoaded = false,
                        failureReason = result.reason,
                    )
                }
            }
        }
    }

    private fun edit(transform: (EditCustomerUiState) -> EditCustomerUiState) {
        _uiState.update { state -> transform(state).copy(failureReason = null) }
    }

    /** Releases the customer the form held, when the app session ends (`BR-001`). */
    fun reset() {
        sessionId = null
        _uiState.value = EditCustomerUiState()
    }
}

/**
 * The form's values as the API's edit request, with blank optional fields absent.
 *
 * `displayName` is what the list and detail show; the form has no separate field for it, so it is
 * derived from the name the user entered — the company name for a company customer, and the first
 * and last name for an individual — exactly as the create form derives it.
 *
 * Only the stated type's subtype payload is sent. The API rejects a body that states one type and
 * carries the other subrecord, and no value is ever carried between the two (`BR-087`).
 */
internal fun EditCustomerUiState.toUpdateRequest(): UpdateCustomerRequest {
    val displayName = if (isCompany) {
        companyName.trim()
    } else {
        "${firstName.trim()} ${lastName.trim()}".trim()
    }

    return UpdateCustomerRequest(
        type = type.name,
        displayName = displayName,
        email = email.trim().takeIf { it.isNotEmpty() },
        phone = phone.trim().takeIf { it.isNotEmpty() },
        notes = notes.trim().takeIf { it.isNotEmpty() },
        status = status.name,
        individual = if (isCompany) {
            null
        } else {
            IndividualCustomerPayload(
                firstName = firstName.trim(),
                lastName = lastName.trim(),
            )
        },
        company = if (isCompany) {
            CompanyCustomerPayload(legalName = companyName.trim())
        } else {
            null
        },
    )
}

