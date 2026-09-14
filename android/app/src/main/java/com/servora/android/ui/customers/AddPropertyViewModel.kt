package com.servora.android.ui.customers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.servora.android.data.customers.CreatePropertyRequest
import com.servora.android.data.customers.CustomersRepository
import com.servora.android.data.customers.PropertyCreateResult
import com.servora.android.domain.model.PropertyProvince
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Drives the Add Property form.
 *
 * The ViewModel decides nothing about Properties: it holds the values the user typed, refuses to
 * submit an incomplete form before the round trip, and reports what [CustomersRepository] answered.
 * The backend remains the authority for authorization, validation and the stored country
 * (`BR-001`, `BR-007`).
 */
@HiltViewModel
class AddPropertyViewModel @Inject constructor(
    private val customersRepository: CustomersRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AddPropertyUiState())
    val uiState: StateFlow<AddPropertyUiState> = _uiState.asStateFlow()

    /**
     * The form session [uiState] currently belongs to, or `null` before one has started.
     *
     * It is bookkeeping for telling one session from another, not something the screen draws, so it
     * deliberately does not live in [AddPropertyUiState].
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
        _uiState.value = AddPropertyUiState(customerId = customerId)
    }

    fun onStreetAddressChange(value: String) = edit { it.copy(addressLine1 = value) }

    fun onUnitChange(value: String) = edit { it.copy(addressLine2 = value) }

    fun onCityChange(value: String) = edit { it.copy(city = value) }

    fun onProvinceChange(province: PropertyProvince) = edit { it.copy(province = province) }

    fun onPostalCodeChange(value: String) = edit { it.copy(postalCode = value) }

    fun onNameChange(value: String) = edit { it.copy(name = value) }

    fun onNotesChange(value: String) = edit { it.copy(notes = value) }

    /**
     * Submits the form.
     *
     * An incomplete form is not sent: the attempt is recorded so the screen can mark what is
     * missing, and no request is made (`BR-042`). A form already being saved is not sent twice.
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
        // A reply that arrives after the user has left this form session must not be folded into
        // the next one (`BR-042`).
        val startedSession = sessionId
        viewModelScope.launch {
            val result = customersRepository.createProperty(
                customerId = state.customerId,
                request = state.toRequest(),
            )
            if (startedSession != sessionId) return@launch
            _uiState.update { current ->
                when (result) {
                    is PropertyCreateResult.Success ->
                        current.copy(isSaving = false, isSaved = true, failureReason = null)

                    is PropertyCreateResult.Failure ->
                        current.copy(isSaving = false, failureReason = result.reason)
                }
            }
        }
    }

    private fun edit(transform: (AddPropertyUiState) -> AddPropertyUiState) {
        _uiState.update { state -> transform(state).copy(failureReason = null) }
    }

    /** Releases the form's values when the app session ends (`BR-001`). */
    fun reset() {
        sessionId = null
        _uiState.value = AddPropertyUiState()
    }
}

/** The form's values as the API request, with blank optional fields left absent. */
private fun AddPropertyUiState.toRequest(): CreatePropertyRequest =
    CreatePropertyRequest(
        name = name.trim().takeIf { it.isNotEmpty() },
        addressLine1 = addressLine1.trim(),
        addressLine2 = addressLine2.trim().takeIf { it.isNotEmpty() },
        city = city.trim(),
        province = province?.code.orEmpty(),
        postalCode = postalCode.trim(),
        notes = notes.trim().takeIf { it.isNotEmpty() },
    )
