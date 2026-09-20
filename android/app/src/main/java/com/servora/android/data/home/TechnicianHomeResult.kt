package com.servora.android.data.home

import com.servora.android.data.customers.CustomersFailureReason
import com.servora.android.data.offline.ReadSource
import com.servora.android.domain.model.TechnicianHome

/** Outcome of the technician home read. */
sealed interface TechnicianHomeResult {
    /**
     * The backend answered with the caller's own working day, or — when the API could not be
     * reached — the last day it reported.
     *
     * [source] says which of the two, so the screen presents a local copy as the last reported
     * answer rather than as a current one (`offline-first-architecture.md` §2, §7, `BR-013`).
     */
    data class Success(
        val home: TechnicianHome,
        val source: ReadSource = ReadSource.BACKEND,
    ) : TechnicianHomeResult

    /** The read failed; [reason] decides what the screen reports. */
    data class Failure(val reason: CustomersFailureReason) : TechnicianHomeResult
}
