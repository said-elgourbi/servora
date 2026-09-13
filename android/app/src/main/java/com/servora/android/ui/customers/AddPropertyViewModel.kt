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
     * Scopes the form to [customerId].
     *
     * A form already open for the same customer is left untouched, so a recomposition does not erase
     * what the user typed. After a successful save the form is reset, so opening Add Property again
     * for that customer starts empty instead of immediately re-reporting the previous save.
     */
    fun start(customerId: String) {
        val current = _uiState.value
        if (current.customerId == customerId && !current.isSaved) {
            return
        }
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
            _uiState.update { it.copy(saveAttempted = true) }
            return
        }

        _uiState.update {
            it.copy(isSaving = true, saveAttempted = true, failureReason = null)
        }
        viewModelScope.launch {
            val result = customersRepository.createProperty(
                customerId = state.customerId,
                request = state.toRequest(),
            )
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
