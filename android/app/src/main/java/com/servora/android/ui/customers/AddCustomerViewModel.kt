package com.servora.android.ui.customers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.servora.android.data.customers.CompanyCustomerPayload
import com.servora.android.data.customers.ContactCreateResult
import com.servora.android.data.customers.CreateCompanyCustomerRequest
import com.servora.android.data.customers.CreateCustomerContactRequest
import com.servora.android.data.customers.CreateCustomerRequest
import com.servora.android.data.customers.CreateIndividualCustomerRequest
import com.servora.android.data.customers.CreatePropertyRequest
import com.servora.android.data.customers.CustomerCreateResult
import com.servora.android.data.customers.CustomersRepository
import com.servora.android.data.customers.IndividualCustomerPayload
import com.servora.android.data.customers.PropertyCreateResult
import com.servora.android.domain.model.CustomerType
import com.servora.android.domain.model.PropertyProvince
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Drives the New Customer form.
 *
 * The ViewModel decides nothing about customers: it holds the values the user typed, refuses to
 * submit an incomplete form before the round trip, and reports what [CustomersRepository] answered.
 * The backend remains the authority for authorization, validation and every stored value
 * (`BR-001`, `BR-007`).
 *
 * The create is up to three calls when the optional parts are filled — the customer, its first
 * Property and its primary contact — because Property and contact writes are authorized separately
 * from the customer capabilities (`BR-085`). The customer's id is kept as soon as the first call is
 * accepted, so a later failure is reported as an unfinished optional part rather than as a failed
 * customer create.
 */
@HiltViewModel
class AddCustomerViewModel @Inject constructor(
    private val customersRepository: CustomersRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AddCustomerUiState())
    val uiState: StateFlow<AddCustomerUiState> = _uiState.asStateFlow()

    /**
     * The form session [uiState] currently belongs to, or `null` before one has started.
     *
     * It is bookkeeping for telling one session from another, not something the screen draws, so it
     * deliberately does not live in [AddCustomerUiState].
     */
    private var sessionId: String? = null

    /**
     * Begins the form session [sessionId].
     *
     * A session is one destination instance. Re-entering the same [sessionId] — the screen being
     * composed again after a configuration change — keeps what the user typed and any message the
     * screen is showing. A different [sessionId] is a new form session, so New Customer starts
     * empty and no validation or submission error from the previous attempt can appear (`BR-042`).
     */
    fun start(sessionId: String) {
        if (sessionId == this.sessionId) {
            return
        }
        this.sessionId = sessionId
        _uiState.value = AddCustomerUiState()
    }

    fun onTypeChange(type: CustomerType) = edit { it.copy(type = type) }

    fun onCompanyNameChange(value: String) = edit { it.copy(companyName = value) }

    fun onFirstNameChange(value: String) = edit { it.copy(firstName = value) }

    fun onLastNameChange(value: String) = edit { it.copy(lastName = value) }

    fun onPhoneChange(value: String) = edit { it.copy(phone = value) }

    fun onEmailChange(value: String) = edit { it.copy(email = value) }

    fun onNotesChange(value: String) = edit { it.copy(notes = value) }

    fun onStreetAddressChange(value: String) = edit { it.copy(addressLine1 = value) }

    fun onUnitChange(value: String) = edit { it.copy(addressLine2 = value) }

    fun onCityChange(value: String) = edit { it.copy(city = value) }

    fun onProvinceChange(province: PropertyProvince) = edit { it.copy(province = province) }

    fun onPostalCodeChange(value: String) = edit { it.copy(postalCode = value) }

    fun onPropertyNameChange(value: String) = edit { it.copy(propertyName = value) }

    fun onPropertyNotesChange(value: String) = edit { it.copy(propertyNotes = value) }

    fun onContactFirstNameChange(value: String) = edit { it.copy(contactFirstName = value) }

    fun onContactLastNameChange(value: String) = edit { it.copy(contactLastName = value) }

    /**
     * Submits the form.
     *
     * An incomplete form is not sent: the attempt is recorded so the screen can mark what is missing
     * (`BR-042`). A form already being saved, or one whose customer already exists, is not submitted
     * again — repeating the create would duplicate the customer.
     */
    fun save() {
        val state = _uiState.value
        if (state.isSaving || state.createdCustomerId != null) {
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
        // A reply that arrives after the user has left this form session must not be folded into
        // the next one (`BR-042`).
        val startedSession = sessionId
        viewModelScope.launch {
            when (val created = customersRepository.createCustomer(state.toCustomerRequest())) {
                is CustomerCreateResult.Failure -> {
                    if (startedSession != sessionId) return@launch
                    _uiState.update {
                        it.copy(isSaving = false, failureReason = created.reason)
                    }
                }

                is CustomerCreateResult.Success -> {
                    if (startedSession != sessionId) return@launch
                    // The customer exists on the backend now, so the optional parts follow by id and
                    // a failure among them is not reported as a failed customer create (`BR-001`).
                    _uiState.update { it.copy(createdCustomerId = created.customerId) }
                    completeOptionalParts(created.customerId, state, startedSession)
                }
            }
        }
    }

    /**
     * Runs the optional writes the form was filled in for, in the order the design lists them.
     *
     * [session] is the form session the writes belong to; a result that arrives after the user has
     * left it is dropped rather than folded into the next session (`BR-042`).
     */
    private suspend fun completeOptionalParts(
        customerId: String,
        form: AddCustomerUiState,
        session: String?,
    ) {
        if (form.propertyStarted) {
            val result = customersRepository.createProperty(customerId, form.toPropertyRequest())
            if (session != sessionId) return
            if (result is PropertyCreateResult.Failure) {
                _uiState.update {
                    it.copy(
                        isSaving = false,
                        unfinishedStep = CustomerSetupStep.FIRST_PROPERTY,
                        failureReason = result.reason,
                    )
                }
                return
            }
        }

        if (form.contactStarted) {
            val result = customersRepository.createContact(customerId, form.toContactRequest())
            if (session != sessionId) return
            if (result is ContactCreateResult.Failure) {
                _uiState.update {
                    it.copy(
                        isSaving = false,
                        unfinishedStep = CustomerSetupStep.PRIMARY_CONTACT,
                        failureReason = result.reason,
                    )
                }
                return
            }
        }

        if (session != sessionId) return
        _uiState.update { it.copy(isSaving = false, isSaved = true, failureReason = null) }
    }

    private fun edit(transform: (AddCustomerUiState) -> AddCustomerUiState) {
        _uiState.update { state -> transform(state).copy(failureReason = null) }
    }

    /** Releases the form's values when the app session ends (`BR-001`). */
    fun reset() {
        sessionId = null
        _uiState.value = AddCustomerUiState()
    }
}

/**
 * The form's values as the API's discriminated customer create, with blank optional fields absent.
 *
 * `displayName` is what the list and detail show; the form has no separate field for it, so it is
 * derived from the name the user did enter — the company name for a business customer, and the first
 * and last name for an individual.
 */
internal fun AddCustomerUiState.toCustomerRequest(): CreateCustomerRequest {
    val displayName = if (isCompany) {
        companyName.trim()
    } else {
        "${firstName.trim()} ${lastName.trim()}".trim()
    }
    val emailValue = email.trim().takeIf { it.isNotEmpty() }
    val phoneValue = phone.trim().takeIf { it.isNotEmpty() }
    val notesValue = notes.trim().takeIf { it.isNotEmpty() }

    return if (isCompany) {
        CreateCustomerRequest.Company(
            CreateCompanyCustomerRequest(
                type = CustomerType.COMPANY.name,
                displayName = displayName,
                email = emailValue,
                phone = phoneValue,
                notes = notesValue,
                company = CompanyCustomerPayload(legalName = companyName.trim()),
            ),
        )
    } else {
        CreateCustomerRequest.Individual(
            CreateIndividualCustomerRequest(
                type = CustomerType.INDIVIDUAL.name,
                displayName = displayName,
                email = emailValue,
                phone = phoneValue,
                notes = notesValue,
                individual = IndividualCustomerPayload(
                    firstName = firstName.trim(),
                    lastName = lastName.trim(),
                ),
            ),
        )
    }
}

/** The started first Property section as the API request; blank optional fields are left absent. */
internal fun AddCustomerUiState.toPropertyRequest(): CreatePropertyRequest =
    CreatePropertyRequest(
        name = propertyName.trim().takeIf { it.isNotEmpty() },
        addressLine1 = addressLine1.trim(),
        addressLine2 = addressLine2.trim().takeIf { it.isNotEmpty() },
        city = city.trim(),
        province = province?.code.orEmpty(),
        postalCode = postalCode.trim(),
        notes = propertyNotes.trim().takeIf { it.isNotEmpty() },
    )

/**
 * The started primary contact as the API request.
 *
 * The contact is the customer's primary one; its own email and phone stay absent because the form
 * captures those on the customer header rather than twice.
 */
internal fun AddCustomerUiState.toContactRequest(): CreateCustomerContactRequest =
    CreateCustomerContactRequest(
        firstName = contactFirstName.trim(),
        lastName = contactLastName.trim(),
        isPrimary = true,
    )

