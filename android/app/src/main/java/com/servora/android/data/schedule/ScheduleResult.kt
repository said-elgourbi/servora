package com.servora.android.data.schedule

import com.servora.android.data.customers.CustomersFailureReason
import com.servora.android.data.offline.ReadSource
import com.servora.android.domain.model.Schedule

/** Outcome of the day schedule read. */
sealed interface ScheduleResult {
    /**
     * The backend answered with the day, or — when the API could not be reached and the day is the
     * caller's own work — the last day it reported.
     *
     * [source] says which of the two, so a screen presents a local copy as the last reported answer
     * rather than as a current one (`offline-first-architecture.md` §2, §7, `BR-013`). Only a
     * field-scoped read is ever answered from local state: the office board stays online-only
     * (`ADR-020` D6).
     */
    data class Success(
        val schedule: Schedule,
        val source: ReadSource = ReadSource.BACKEND,
    ) : ScheduleResult

    /** The read failed; [reason] decides what the screen reports. */
    data class Failure(val reason: CustomersFailureReason) : ScheduleResult
}
