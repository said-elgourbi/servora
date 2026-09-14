package com.servora.android.data.home

import com.servora.android.data.customers.CustomersFailureReason
import com.servora.android.domain.model.ManagerHome

/** Outcome of the manager home read. */
sealed interface ManagerHomeResult {
    /** The backend answered with the manager's operational day. */
    data class Success(val home: ManagerHome) : ManagerHomeResult

    /** The read failed; [reason] decides what the screen reports. */
    data class Failure(val reason: CustomersFailureReason) : ManagerHomeResult
}
