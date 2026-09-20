package com.servora.android.data.schedule

import com.servora.android.domain.model.Schedule
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Query

/**
 * Retrofit contract for the day schedule read (`docs/api/schedule.md`).
 *
 * The access token is passed explicitly, as every other call in this app does, because the app has
 * no transport interceptor: keeping the header at the call site means a call cannot be sent a
 * credential it was not meant to carry.
 *
 * The client names the **local date** it is showing and the zone it renders in; the API resolves the
 * day's instants from them, so a client never decides which Visits belong to a day (`BR-001`). It also
 * never decides **whose** Visits the day may hold: the API authorizes the read's scope from the
 * caller's capabilities and answers for that work (`BR-007`, `BR-009`).
 */
interface ScheduleApi {
    /**
     * `GET /schedule` — one local day's schedule, resolved for the caller's own scope.
     *
     * [membershipIds] are the technicians the day is narrowed to, and an empty list is the whole
     * organization: each id is the organization membership an assignment names (`BR-068`), so the
     * filter uses the same vocabulary the assignments do. The parameter is repeated when several are
     * selected — one read for a group of technicians rather than one read per technician — and the
     * API answers with the union, because a dispatcher's filter asks whose work to show rather than
     * which Visits a group shares.
     *
     * The filter is the office scope's: a field caller reads their own work, sends no ids, and a
     * request that names somebody else is refused `403` by the API rather than answered.
     */
    @GET("schedule")
    suspend fun schedule(
        @Header("Authorization") authorization: String,
        @Query("date") date: String,
        @Query("timeZone") timeZone: String,
        @Query("membershipId") membershipIds: List<String>,
    ): ScheduleDto
}
