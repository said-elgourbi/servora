package com.servora.android.ui.customers

import androidx.compose.runtime.Immutable
import com.servora.android.data.customers.CustomersFailureReason
import com.servora.android.domain.model.CustomerType
import com.servora.android.domain.model.PropertyProvince

/**
 * The optional part of creating a customer that is still outstanding after the customer itself was
 * created.
 *
 * The customer create is one call and the optional first Property and primary contact are separate
 * calls, so a failure after the customer exists must be reported as exactly which part did not save
 * rather than as a failed customer create (`BR-001`, `BR-067`).
 */
enum class CustomerSetupStep {
    /** The optional first service Property was not created. */
    FIRST_PROPERTY,

    /** The optional primary contact was not recorded. */
    PRIMARY_CONTACT,
}

/**
 * The New Customer form (`BR-023`, `BR-049`, `BR-050`).
 *
 * Every field is one the authoritative model already has (`BR-042`): the customer type and the
 * fields of its subtype, the optional header phone/email/notes, the optional first service Property's
 * authoritative fields, and — for a business customer — the optional primary contact's name.
 *
 * The first Property section is **optional end to end**. Leaving it untouched creates only the
 * customer; the Property can be added later from the customer's page. A section that was started,
 * however, must be completed: a half-typed Property is never silently dropped (`BR-067`).
 *
 * [showsValidationError] only appears after a save was attempted, so an untouched form is never
 * marked incomplete before the user has done anything.
 */
@Immutable
data class AddCustomerUiState(
    val type: CustomerType = CustomerType.INDIVIDUAL,
    val companyName: String = "",
    val firstName: String = "",
    val lastName: String = "",
    val phone: String = "",
    val email: String = "",
    val notes: String = "",
    // First service Property (optional).
    val addressLine1: String = "",
    val addressLine2: String = "",
    val city: String = "",
    val province: PropertyProvince? = null,
    val postalCode: String = "",
    val propertyName: String = "",
    val propertyNotes: String = "",
    // Primary contact (a business customer's optional contact person).
    val contactFirstName: String = "",
    val contactLastName: String = "",
    val isSaving: Boolean = false,
    val saveAttempted: Boolean = false,
    val isSaved: Boolean = false,
    val failureReason: CustomersFailureReason? = null,
    /** The created customer's id, or `null` until the create was accepted (`BR-001`). */
    val createdCustomerId: String? = null,
    /** Which optional part did not save after the customer was created. */
    val unfinishedStep: CustomerSetupStep? = null,
) {
    val isCompany: Boolean
        get() = type == CustomerType.COMPANY

    /** Whether the customer's own required fields are filled. */
    val hasCustomerFields: Boolean
        get() = if (isCompany) {
            companyName.isNotBlank()
        } else {
            firstName.isNotBlank() && lastName.isNotBlank()
        }

    /** Whether the optional first Property section was started at all. */
    val propertyStarted: Boolean
        get() = addressLine1.isNotBlank() ||
            addressLine2.isNotBlank() ||
            city.isNotBlank() ||
            province != null ||
            postalCode.isNotBlank() ||
            propertyName.isNotBlank() ||
            propertyNotes.isNotBlank()

    /** Whether a started first Property section carries everything the API requires. */
    val propertyComplete: Boolean
        get() = addressLine1.isNotBlank() &&
            city.isNotBlank() &&
            province != null &&
            postalCode.isNotBlank()

    /** Whether the optional primary contact section was started. */
    val contactStarted: Boolean
        get() = isCompany && (contactFirstName.isNotBlank() || contactLastName.isNotBlank())

    /** A contact needs both names, so a started contact section must carry both. */
    val contactComplete: Boolean
        get() = contactFirstName.isNotBlank() && contactLastName.isNotBlank()

    /** Whether the form can be submitted: the customer, plus any section the user started. */
    val canSave: Boolean
        get() = hasCustomerFields &&
            (!propertyStarted || propertyComplete) &&
            (!contactStarted || contactComplete)

    val showsValidationError: Boolean
        get() = saveAttempted && !canSave

    /**
     * The customer exists and an optional part remains, so the form is no longer submittable: the
     * screen explains what did not save and offers the customer it did create.
     */
    val hasUnfinishedStep: Boolean
        get() = createdCustomerId != null && unfinishedStep != null
}
