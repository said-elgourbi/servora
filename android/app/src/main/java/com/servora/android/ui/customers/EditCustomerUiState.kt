package com.servora.android.ui.customers

import androidx.compose.runtime.Immutable
import com.servora.android.data.customers.CustomersFailureReason
import com.servora.android.domain.model.CustomerContact
import com.servora.android.domain.model.CustomerStatus
import com.servora.android.domain.model.CustomerType

/**
 * The Edit Customer form (`BR-023`, `BR-087`).
 *
 * The form states the customer's kind, and the same statement is what converts the customer: when
 * [type] differs from [storedType] the backend replaces the customer's subtype record and records
 * the conversion (`BR-087`). The form therefore requires the fields of the **stated** type and never
 * carries a value over from the previous type.
 *
 * [isLoading] covers the read of the customer being edited: until it has answered, the form holds no
 * values and must not be saved, because saving it would overwrite a customer the user has not seen.
 *
 * [showsValidationError] only appears after a save was attempted, so an untouched form is never
 * marked incomplete before the user has done anything.
 */
@Immutable
data class EditCustomerUiState(
    val customerId: String = "",
    /** Whether the form is still reading the customer it edits. */
    val isLoading: Boolean = true,
    /** Whether the customer was read, so the form holds its current values. */
    val isLoaded: Boolean = false,
    /** The type the form states; it is what the API stores when the edit is applied. */
    val type: CustomerType = CustomerType.INDIVIDUAL,
    /** The customer's stored type, or `null` before the read answered. */
    val storedType: CustomerType? = null,
    val companyName: String = "",
    val firstName: String = "",
    val lastName: String = "",
    val phone: String = "",
    val email: String = "",
    val notes: String = "",
    val status: CustomerStatus = CustomerStatus.ACTIVE,
    /**
     * The customer's contact persons, as the backend reported them (`BR-095`).
     *
     * The form never writes them — contacts are maintained from the customer detail screen
     * (`ADR-022` D5) — so they are carried only so the user can see who is recorded while editing
     * the customer's own fields.
     */
    val contacts: List<CustomerContact> = emptyList(),
    val isSaving: Boolean = false,
    val saveAttempted: Boolean = false,
    val isSaved: Boolean = false,
    val failureReason: CustomersFailureReason? = null,
) {
    val isCompany: Boolean
        get() = type == CustomerType.COMPANY

    /**
     * Whether applying the form would convert the customer (`BR-087`).
     *
     * The screen states this, so the replacement of the current type's details is something the user
     * does deliberately rather than discovers afterwards (`BR-067`).
     */
    val isConversion: Boolean
        get() = storedType != null && storedType != type

    /** Whether the fields the stated type requires are filled (`BR-087`). */
    val hasCustomerFields: Boolean
        get() = if (isCompany) {
            companyName.isNotBlank()
        } else {
            firstName.isNotBlank() && lastName.isNotBlank()
        }

    /** Whether the form can be submitted: the customer is read and its required fields are filled. */
    val canSave: Boolean
        get() = isLoaded && hasCustomerFields

    val showsValidationError: Boolean
        get() = saveAttempted && !hasCustomerFields
}
