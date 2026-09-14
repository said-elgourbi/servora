package com.servora.android.ui.customers

import androidx.compose.runtime.Immutable
import com.servora.android.data.customers.CustomersFailureReason
import com.servora.android.domain.model.PropertyProvince

/**
 * The Add Property form (`BR-049`, `BR-050`).
 *
 * Every field is one the authoritative Property model already has (`BR-042`): the street, the
 * optional unit, the city, the province code, the postal code, the optional name and the optional
 * notes. The country is deliberately absent — the backend stores the value the address model already
 * carries — and no access-instruction or property-type field exists to draw.
 *
 * [showsValidationError] only appears after a save was attempted, so an untouched form is never
 * marked incomplete before the user has done anything.
 */
@Immutable
data class AddPropertyUiState(
    val customerId: String = "",
    val addressLine1: String = "",
    val addressLine2: String = "",
    val city: String = "",
    val province: PropertyProvince? = null,
    val postalCode: String = "",
    val name: String = "",
    val notes: String = "",
    val isSaving: Boolean = false,
    val saveAttempted: Boolean = false,
    val isSaved: Boolean = false,
    val failureReason: CustomersFailureReason? = null,
    /**
     * The Property the form edits, or `null` while it creates one.
     *
     * Add Property and Edit Property share this form and its validation; only the destination, the
     * action label and whether a version is carried back differ.
     */
    val propertyId: String? = null,
    /** Whether the form is still reading the Property it edits. */
    val isLoading: Boolean = false,
    /** The version the backend last reported for the Property being edited (`BR-086`). */
    val version: Int? = null,
) {
    /** The minimum the API requires to create or edit a Property (`BR-049`, `BR-084`). */
    val canSave: Boolean
        get() = addressLine1.isNotBlank() &&
            city.isNotBlank() &&
            province != null &&
            postalCode.isNotBlank()

    val showsValidationError: Boolean
        get() = saveAttempted && !canSave
}
