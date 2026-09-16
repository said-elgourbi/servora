package com.servora.android.data.jobs

import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Multipart
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Streaming

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

    /**
     * `POST /jobs/{jobId}/photos` — adds one photo to the Job's Activity (`BR-015`, `BR-027`).
     *
     * The photo is sent as multipart rather than JSON so the bytes are never base64-encoded into a
     * request body, and the parts are `RequestBody`s rather than scalars so the client controls
     * exactly what each text part contains. `clientOperationId` is the queued operation's id, sent
     * unchanged on every retry, which is what makes a replayed upload store the evidence once
     * (`BR-031`).
     */
    @Multipart
    @POST("jobs/{jobId}/photos")
    suspend fun addJobPhoto(
        @Header("Authorization") authorization: String,
        @Path("jobId") jobId: String,
        @Part("clientOperationId") clientOperationId: RequestBody,
        @Part("phase") phase: RequestBody,
        @Part("note") note: RequestBody?,
        @Part("capturedAt") capturedAt: RequestBody?,
        @Part file: MultipartBody.Part,
    ): JobActivityDto

    /**
     * `POST /jobs/{jobId}/photos/{photoId}/removal` — takes accepted evidence out of ordinary use
     * (`BR-088`, `BR-089`).
     *
     * It is not a delete: nothing here edits or deletes a recorded photo. The API appends a removal
     * record carrying the actor, the instant and the reason, and answers with the refreshed timeline,
     * so the evidence stops appearing in ordinary views while its record and history are preserved
     * (`BR-067`). `evidence.photo.remove` authorizes it, and the API enforces that whatever this client
     * draws (`BR-007`).
     */
    @POST("jobs/{jobId}/photos/{photoId}/removal")
    suspend fun removeJobPhoto(
        @Header("Authorization") authorization: String,
        @Path("jobId") jobId: String,
        @Path("photoId") photoId: String,
        @Body request: RemoveJobPhotoRequestDto,
    ): JobActivityDto

    /**
     * `GET /jobs/{jobId}/photos/{photoId}/content` — the photo's bytes (`BR-015`).
     *
     * Evidence is read through the API on the API port, so no storage endpoint, bucket or signature
     * host is configured in or visible to the client (`ADR-013` D7). It is streamed, because the
     * caller decodes a thumbnail from it rather than holding the whole image in memory.
     */
    @Streaming
    @GET("jobs/{jobId}/photos/{photoId}/content")
    suspend fun jobPhotoContent(
        @Header("Authorization") authorization: String,
        @Path("jobId") jobId: String,
        @Path("photoId") photoId: String,
    ): ResponseBody
}
