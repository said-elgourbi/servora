package com.servora.android.data.home

import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Query

/**
 * Retrofit contract for the manager home read.
 *
 * The access token is passed explicitly, as every other call in this app does, because the app has
 * no transport interceptor: keeping the header at the call site means a call cannot be sent a
 * credential it was not meant to carry.
 */
interface ManagerHomeApi {
    /**
     * `GET /home/manager` — the manager's operational day, derived by the backend.
     *
     * [timeZone] is the IANA identifier the device renders in. The backend resolves the local day
     * from it, so "today" is one window over authoritative records rather than a device's own idea
     * of the date (`BR-001`).
     */
    @GET("home/manager")
    suspend fun managerHome(
        @Header("Authorization") authorization: String,
        @Query("timeZone") timeZone: String,
    ): ManagerHomeDto
}
