package com.servora.android.data.jobs

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path

/**
 * Retrofit contract for the Job read and the Job and Visit management actions.
 *
 * The access token is passed explicitly, as every other call in this app does, because the app has
 * no transport interceptor: keeping the header at the call site means a call cannot be sent a
 * credential it was not meant to carry.
 *
 * Every action answers with the Job as it now stands, so the screen presents the backend's state
 * rather than a locally patched copy (`BR-001`).
 */
interface JobDetailsApi {
    /** `GET /jobs/{jobId}` — one Job of the caller's organization, as its details screen shows it. */
    @GET("jobs/{jobId}")
    suspend fun jobDetails(
        @Header("Authorization") authorization: String,
        @Path("jobId") jobId: String,
    ): JobDetailsDto

    /** `GET /jobs/{jobId}/activity` — the Job's chronological activity, newest first (`BR-080`). */
    @GET("jobs/{jobId}/activity")
    suspend fun jobActivity(
        @Header("Authorization") authorization: String,
        @Path("jobId") jobId: String,
    ): JobActivityDto

    /** `PATCH /jobs/{jobId}/status` — moves a Job through its lifecycle (`BR-058`). */
    @PATCH("jobs/{jobId}/status")
    suspend fun changeJobStatus(
        @Header("Authorization") authorization: String,
        @Path("jobId") jobId: String,
        @Body request: ChangeJobStatusRequestDto,
    ): JobDetailsDto

    /** `PATCH /jobs/{jobId}/visits/{visitId}/schedule` — edits the Visit's schedule (`BR-073`). */
    @PATCH("jobs/{jobId}/visits/{visitId}/schedule")
    suspend fun rescheduleVisit(
        @Header("Authorization") authorization: String,
        @Path("jobId") jobId: String,
        @Path("visitId") visitId: String,
        @Body request: RescheduleVisitRequestDto,
    ): JobDetailsDto

    /** `PUT /jobs/{jobId}/visits/{visitId}/technicians` — states the Visit's crew (`BR-069`). */
    @PUT("jobs/{jobId}/visits/{visitId}/technicians")
    suspend fun assignVisitTechnicians(
        @Header("Authorization") authorization: String,
        @Path("jobId") jobId: String,
        @Path("visitId") visitId: String,
        @Body request: AssignVisitTechniciansRequestDto,
    ): JobDetailsDto

    /** `POST /jobs/{jobId}/visits/{visitId}/notes` — adds a text Activity update. */
    @POST("jobs/{jobId}/visits/{visitId}/notes")
    suspend fun addVisitNote(
        @Header("Authorization") authorization: String,
        @Path("jobId") jobId: String,
        @Path("visitId") visitId: String,
        @Body request: AddVisitNoteRequestDto,
    ): JobActivityDto

    /** `GET /technicians` — the organization's technicians, for choosing a crew (`BR-024`). */
    @GET("technicians")
    suspend fun assignableTechnicians(
        @Header("Authorization") authorization: String,
    ): List<AssignableTechnicianDto>
}
