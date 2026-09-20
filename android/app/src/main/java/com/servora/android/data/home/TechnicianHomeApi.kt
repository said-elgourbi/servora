package com.servora.android.data.home

import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Query

/**
 * Retrofit contract for the technician home read (`docs/api/technician-home.md`).
 *
 * The access token is passed explicitly, as every other call in this app does, because the app has
 * no transport interceptor: keeping the header at the call site means a call cannot be sent a
 * credential it was not meant to carry.
 *
 * The backend resolves the caller's own assignments from the session, so no membership or
 * organization is ever named by the client (`BR-001`, `BR-007`).
 */
interface TechnicianHomeApi {
    /**
     * `GET /home/technician` — the caller's own working day, derived by the backend.
     *
     * [timeZone] is the IANA identifier the device renders in, so "today" is one window over
     * authoritative records rather than a device's own idea of the date (`BR-001`).
     */
    @GET("home/technician")
    suspend fun technicianHome(
        @Header("Authorization") authorization: String,
        @Query("timeZone") timeZone: String,
    ): TechnicianHomeDto
}
