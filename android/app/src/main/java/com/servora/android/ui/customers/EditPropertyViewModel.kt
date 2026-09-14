package com.servora.android.ui.customers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.servora.android.data.customers.PropertyRepository
import com.servora.android.data.customers.PropertyResult
import com.servora.android.data.customers.UpdatePropertyRequest
import com.servora.android.domain.model.PropertyProvince
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Drives the Edit Property form.
 *
 * It reuses the Add Property form and its validation (`AddPropertyUiState`), because an edit replaces
 * exactly the fields a create supplies (`BR-084`). Its own responsibility is the part an edit adds:
 * reading the Property it edits, pre-populating the form, and carrying back the version the backend
 * last reported so a Property that moved on is refused rather than overwritten (`BR-086`).
 *
 * Whether the caller may edit at all is the backend's decision (`BR-001`, `BR-007`).
 */
@HiltViewModel
class EditPropertyViewModel @Inject constructor(
    private val propertyRepository: PropertyRepository,
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
     * Begins the form session [sessionId] for [propertyId] of [customerId], reading the Property the
     * session edits.
     *
     * A session is one destination instance. Re-entering the same [sessionId] — the screen being
     * composed again after a configuration change — keeps the Property it read and any message the
     * screen is showing. A different [sessionId] is a new form session: it starts from a fresh read
     * of the authoritative Property, so neither a value nor a validation or submission error from
     * the previous session is carried over (`BR-001`, `BR-086`).
     */
    fun start(customerId: String, propertyId: String, sessionId: String) {
        if (sessionId == this.sessionId) {
            return
        }
        this.sessionId = sessionId
        _uiState.value = AddPropertyUiState(
            customerId = customerId,
            propertyId = propertyId,
            isLoading = true,
        )
        viewModelScope.launch {
            val result = propertyRepository.loadProperty(customerId, propertyId)
            _uiState.update { state ->
                when (result) {
                    is PropertyResult.Success -> state.copy(
                        isLoading = false,
                        failureReason = null,
                        addressLine1 = result.property.addressLine1,
                        addressLine2 = result.property.addressLine2.orEmpty(),
                        city = result.property.city,
                        province = PropertyProvince.fromCode(result.property.province),
                        postalCode = result.property.postalCode,
                        name = result.property.name.orEmpty(),
                        notes = result.property.notes.orEmpty(),
                        version = result.property.version,
                    )

                    is PropertyResult.Failure -> state.copy(
                        isLoading = false,
                        failureReason = result.reason,
                    )
                }
            }
        }
    }

    fun onStreetAddressChange(value: String) = edit { it.copy(addressLine1 = value) }

    fun onUnitChange(value: String) = edit { it.copy(addressLine2 = value) }

    fun onCityChange(value: String) = edit { it.copy(city = value) }

    fun onProvinceChange(province: PropertyProvince) = edit { it.copy(province = province) }

    fun onPostalCodeChange(value: String) = edit { it.copy(postalCode = value) }

    fun onNameChange(value: String) = edit { it.copy(name = value) }

    fun onNotesChange(value: String) = edit { it.copy(notes = value) }

    /**
     * Submits the edit.
     *
     * An incomplete form is not sent: the attempt is recorded so the screen can mark what is missing.
     * A form that has not finished reading the Property it edits is not sent either, because saving it
     * would overwrite values the user never saw.
     */
    fun save() {
        val state = _uiState.value
        val propertyId = state.propertyId
        if (state.isSaving || state.isLoading || propertyId == null) {
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
            val result = propertyRepository.updateProperty(
                customerId = state.customerId,
                propertyId = propertyId,
                request = state.toUpdateRequest(),
            )
            if (startedSession != sessionId) return@launch
            _uiState.update { current ->
                when (result) {
                    is PropertyResult.Success -> current.copy(
                        isSaving = false,
                        isSaved = true,
                        failureReason = null,
                        version = result.property.version,
                    )

                    is PropertyResult.Failure -> current.copy(
                        isSaving = false,
                        failureReason = result.reason,
                    )
                }
            }
        }
    }

    private fun edit(transform: (AddPropertyUiState) -> AddPropertyUiState) {
        _uiState.update { state -> transform(state).copy(failureReason = null) }
    }

    /** Releases the Property the form held, when the app session ends (`BR-001`). */
    fun reset() {
        sessionId = null
        _uiState.value = AddPropertyUiState()
    }
}

/** The form's values as the API request, with blank optional fields left absent. */
private fun AddPropertyUiState.toUpdateRequest(): UpdatePropertyRequest =
    UpdatePropertyRequest(
        name = name.trim().takeIf { it.isNotEmpty() },
        addressLine1 = addressLine1.trim(),
        addressLine2 = addressLine2.trim().takeIf { it.isNotEmpty() },
        city = city.trim(),
        province = province?.code.orEmpty(),
        postalCode = postalCode.trim(),
        notes = notes.trim().takeIf { it.isNotEmpty() },
        expectedVersion = version,
    )
