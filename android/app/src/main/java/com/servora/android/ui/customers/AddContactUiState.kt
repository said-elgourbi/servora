package com.servora.android.ui.customers

import androidx.compose.runtime.Immutable
import com.servora.android.data.customers.CustomersFailureReason

/**
 * The Add/Edit Contact form (`BR-095`).
 *
 * The four fields are the ones a contact person owns (`BR-095`): its first name, its last name, its
 * own phone number and its own email address — never the customer header's. The free-text `role` is
 * deliberately absent, because no rule decides what it holds and `ADR-022` D5 leaves it uncaptured by
 * every client, and so are `isBillingContact` and `isJobContact`, which `BR-023` names while no rule
 * defines what makes one (`BR-042`).
 *
 * [isPrimary] is the flag the form states. [storedIsPrimary] is what the backend reported for the
 * contact being edited, so the form knows whether promoting it is still a change the API can apply:
 * the route documents `isPrimary: true`, which promotes a contact and clears the customer's previous
 * primary in one transaction, and defines no un-promotion (`ADR-022` D9). A new contact's stored flag
 * is `false`, so a create may always state it.
 *
 * [showsValidationError] only appears after a save was attempted, so an untouched form is never
 * marked incomplete before the user has done anything.
 */
@Immutable
data class AddContactUiState(
    val customerId: String = "",
    val firstName: String = "",
    val lastName: String = "",
    val phone: String = "",
    val email: String = "",
    val isPrimary: Boolean = false,
    /**
     * The primary flag the backend reported for the contact being edited.
     *
     * It is always `false` while the form records a new contact, because a contact that does not
     * exist yet holds no stored flag.
     */
    val storedIsPrimary: Boolean = false,
    val isSaving: Boolean = false,
    val saveAttempted: Boolean = false,
    val isSaved: Boolean = false,
    val failureReason: CustomersFailureReason? = null,
    /**
     * The contact the form edits, or `null` while it records a new one.
     *
     * Add Contact and Edit Contact share this form and its validation; only the destination, the
     * action label and whether a read and a version are carried differ.
     */
    val contactId: String? = null,
    /** Whether the form is still reading the contact it edits. */
    val isLoading: Boolean = false,
    /** The version the backend last reported for the contact being edited (`BR-095`, `ADR-022` D9). */
    val version: Int? = null,
) {
    /** The minimum the API requires: a contact person needs both names (`BR-095`). */
    val canSave: Boolean
        get() = firstName.isNotBlank() && lastName.isNotBlank()

    /**
     * Whether the primary flag is the form's to state.
     *
     * A contact that already holds the flag cannot be un-promoted: the API has no documented
     * operation for it, so the form has no change to state for that contact (`BR-042`).
     */
    val canStatePrimary: Boolean
        get() = !storedIsPrimary

    /** Whether the form states a promotion the API can apply. */
    val statesPrimaryChange: Boolean
        get() = isPrimary && !storedIsPrimary

    val showsValidationError: Boolean
        get() = saveAttempted && !canSave
}
