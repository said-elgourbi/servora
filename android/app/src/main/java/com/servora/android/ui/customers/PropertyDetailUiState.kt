package com.servora.android.ui.customers

import androidx.compose.runtime.Immutable
import com.servora.android.data.customers.CustomersFailureReason
import com.servora.android.domain.model.PropertyDetail

/**
 * The state the Property lifecycle screen renders (`BR-082` – `BR-086`).
 *
 * [detail] is the backend's answer and stays `null` until a read succeeds; [failureReason] reports
 * why the last read did not. The action flags are separated from the read so a failed read and a
 * failed archive cannot be confused with each other.
 *
 * Nothing here is derived from another client's copy: the open-work counts and whether permanent
 * deletion is allowed are the API's values (`BR-001`, `BR-007`).
 */
@Immutable
data class PropertyDetailUiState(
    val customerId: String = "",
    val propertyId: String = "",
    val isLoading: Boolean = false,
    val detail: PropertyDetail? = null,
    val failureReason: CustomersFailureReason? = null,
    /** Whether an archive, restore or delete is in flight. */
    val isWorking: Boolean = false,
    /** Why the last action did not complete. */
    val actionFailure: CustomersFailureReason? = null,
    /**
     * Whether the API refused permanent deletion because the Property has history (`BR-082`).
     *
     * The user stays on the screen: the Property is not deleted, and archiving is the removal it
     * has.
     */
    val deletionProhibited: Boolean = false,
    /** Whether the Property was permanently deleted, so the destination can leave. */
    val isDeleted: Boolean = false,
    /**
     * Whether a confirmed archive or restore changed the Property's lifecycle state.
     *
     * It is a one-shot signal for the destination: the derived projections other screens hold (the
     * customer's Property rows, its counts and its archived Properties) are re-read from the backend
     * when it is set (`BR-001`). The destination acknowledges it once handled, so re-entering the
     * screen does not re-read again.
     */
    val lifecycleChanged: Boolean = false,
)
