package com.servora.android.ui.customers

import androidx.compose.runtime.Immutable
import com.servora.android.data.customers.CustomersFailureReason

/**
 * The state of removing a contact person from the customer detail screen (`BR-095`).
 *
 * A removal is **soft** (`ADR-022` D8): the record survives and the reads stop reporting it, so the
 * screen that follows is the customer re-read from the backend rather than a locally patched list
 * (`BR-033`, `BR-001`). [isRemoved] is the one-shot signal that says the backend accepted the removal.
 *
 * Nothing here decides whether the removal is allowed: the capability is the API's (`BR-007`).
 */
@Immutable
data class RemoveContactUiState(
    val customerId: String = "",
    /** The contact the removal addresses, while one is in flight or has just answered. */
    val contactId: String? = null,
    /** Whether a removal is in flight. */
    val isRemoving: Boolean = false,
    /** Why the last removal did not complete. */
    val failureReason: CustomersFailureReason? = null,
    /** Whether the backend removed the contact, so the destination can re-read the customer. */
    val isRemoved: Boolean = false,
)
