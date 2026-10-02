package com.servora.android.data.jobs

import com.servora.android.data.schedule.FollowUpVisitRequestDto
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
import retrofit2.http.Query
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
    /**
     * `POST /jobs` — creates a Job for a Customer at a Property (`BR-047` – `BR-056`, `BR-094`).
     *
     * The caller names the Customer, the Property and the title; the backend owns the identifier, the
     * organization-scoped Job number, the `NEW` status, the address snapshot and the version, and
     * refuses a Customer or a Property that is not usable (`BR-001`, `BR-007`). The answer is the same
     * projection `GET /jobs/{jobId}` returns, so no second representation of a Job exists (`BR-041`).
     */
    @POST("jobs")
    suspend fun createJob(
        @Header("Authorization") authorization: String,
        @Body request: CreateJobRequest,
    ): JobDetailsDto

    /** `GET /jobs/{jobId}` — one Job of the caller's organization, as its details screen shows it. */
    @GET("jobs/{jobId}")
    suspend fun jobDetails(
        @Header("Authorization") authorization: String,
        @Path("jobId") jobId: String,
        @Query("visitId") visitId: String? = null,
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

    /**
     * `POST /jobs/{jobId}/visits` — schedules one further Visit on the Job (`BR-071`, `BR-072`).
     *
     * A Visit is one field attempt, so adding one is how a Job's additional work is represented; the
     * route creates the Visit as `SCHEDULED` with the crew it is given and records its schedule and
     * assignment history. `visits.create_schedule` authorizes it and the API enforces it, whatever this
     * client draws (`BR-007`). `BR-070` conflicts are refused on the first attempt and carried in the
     * answer, exactly as the reschedule route's are: the same request resent with `confirmConflicts`
     * applies it, because a conflict is a warning rather than a prohibition in v1.
     *
     * The answer is the same projection `GET /jobs/{jobId}` returns, so the screen shows the Job the
     * backend holds rather than a locally patched copy (`BR-001`).
     */
    @POST("jobs/{jobId}/visits")
    suspend fun createVisit(
        @Header("Authorization") authorization: String,
        @Path("jobId") jobId: String,
        @Body request: CreateVisitRequestDto,
    ): JobDetailsDto

    /**
     * `POST /jobs/{jobId}/visit-requests/{requestId}/approval` — approves a pending follow-up request
     * into a scheduled Visit (`BR-FV-005`).
     *
     * Approval is the office's decision (`BR-FV-004`), so the reviewer states the schedule and the crew
     * the Visit will actually carry — which may differ from what the technician proposed (`BR-FV-003`,
     * `BR-FV-010`) — and the API creates exactly one Visit, records `createdVisitId` on the request and
     * answers it as `APPROVED` in one transaction. A retry of an already-approved request returns the
     * Visit already created rather than creating another (`docs/api/visit-requests.md`).
     *
     * The route requires `visits.review_requests` **and** `visits.create_schedule`; both are enforced by
     * the API (`BR-007`).
     */
    @POST("jobs/{jobId}/visit-requests/{requestId}/approval")
    suspend fun approveVisitRequest(
        @Header("Authorization") authorization: String,
        @Path("jobId") jobId: String,
        @Path("requestId") requestId: String,
        @Body request: ApproveVisitRequestRequestDto,
    ): JobDetailsDto

    /**
     * `POST /jobs/{jobId}/visit-requests` — submits a follow-up Visit request on the Job
     * (`BR-FV-001`, `BR-FV-003`).
     *
     * A request is **not** a Visit: it is an operational decision record waiting for the office
     * (`BR-FV-002`), so nothing is scheduled by it and the technician's proposed window is
     * informational until an authorized user approves it (`BR-FV-005`, `BR-FV-010`). The technician
     * may complete the current Visit whether or not the request is still pending (`BR-FV-006`).
     *
     * The answer is the request itself, not the Job, because a request changes no Job and creates no
     * Visit (`BR-FV-002`); the request's own status is what a client presents. `visits.request_follow_up`
     * authorizes it and the API enforces that whatever this client draws (`BR-007`).
     */
    @POST("jobs/{jobId}/visit-requests")
    suspend fun submitVisitRequest(
        @Header("Authorization") authorization: String,
        @Path("jobId") jobId: String,
        @Body request: SubmitVisitRequestRequestDto,
    ): FollowUpVisitRequestDto

    /**
     * `PATCH /jobs/{jobId}/visits/{visitId}/status` — the Visit's working states (`BR-074`, `BR-093`).
     *
     * Two authorizations reach it (`BR-093`): `VISIT_UPDATE_ASSIGNED_STATUS`, which scopes the caller to
     * their own current crew, and the office's `JOB_UPDATE`, which admits an office member to any Visit
     * of the organization **without** crew membership. Every working destination is one operation, in
     * either direction, and the answer is the Job as it now stands, so the screen presents the backend's
     * state including the Visit's new status.
     *
     * Finishing the Visit is not one of its destinations: `COMPLETED` is reached only through
     * [completeVisit], which is where `BR-077`'s outcome is recorded and where its own capability is
     * asked for (`BR-009`, `BR-077`, `BR-093`).
     */
    @PATCH("jobs/{jobId}/visits/{visitId}/status")
    suspend fun changeVisitStatus(
        @Header("Authorization") authorization: String,
        @Path("jobId") jobId: String,
        @Path("visitId") visitId: String,
        @Body request: ChangeVisitStatusRequestDto,
    ): JobDetailsDto

    /**
     * `POST /jobs/{jobId}/visits/{visitId}/completion` — finishes the Visit with its outcome
     * (`BR-077`, `BR-078`, `BR-093`).
     *
     * The Visit status route's two authorizations admit the caller (`BR-093`), and
     * `VISIT_RECORD_OUTCOME` is required of **every** caller because the request records the outcome —
     * the office included (`BR-009`). The outcome and its summary travel here rather than on the status
     * route, so a completed Visit always carries the record of what resulted from the attempt, and the
     * answer is the Job as it now stands.
     */
    @POST("jobs/{jobId}/visits/{visitId}/completion")
    suspend fun completeVisit(
        @Header("Authorization") authorization: String,
        @Path("jobId") jobId: String,
        @Path("visitId") visitId: String,
        @Body request: CompleteVisitRequestDto,
    ): JobDetailsDto

    /** `POST /jobs/{jobId}/visits/{visitId}/notes` — adds a text Activity update. */
    @POST("jobs/{jobId}/visits/{visitId}/notes")
    suspend fun addVisitNote(
        @Header("Authorization") authorization: String,
        @Path("jobId") jobId: String,
        @Path("visitId") visitId: String,
        @Body request: AddVisitNoteRequestDto,
    ): JobActivityDto

    /** `PATCH /jobs/{jobId}/visits/notes/{noteId}` — corrects one text Activity update. */
    @PATCH("jobs/{jobId}/visits/notes/{noteId}")
    suspend fun editVisitNote(
        @Header("Authorization") authorization: String,
        @Path("jobId") jobId: String,
        @Path("noteId") noteId: String,
        @Body request: EditVisitNoteRequestDto,
    ): JobActivityDto

    /** `POST /jobs/{jobId}/visits/notes/{noteId}/removal` — removes one note from ordinary activity. */
    @POST("jobs/{jobId}/visits/notes/{noteId}/removal")
    suspend fun removeVisitNote(
        @Header("Authorization") authorization: String,
        @Path("jobId") jobId: String,
        @Path("noteId") noteId: String,
        @Body request: RemoveVisitNoteRequestDto,
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
     * (`BR-031`). `visitId` names the field attempt the photo was recorded on — a photo belongs to a
     * Visit (`BR-047`, `BR-080`), and the API refuses an upload that names none.
     */
    @Multipart
    @POST("jobs/{jobId}/photos")
    suspend fun addJobPhoto(
        @Header("Authorization") authorization: String,
        @Path("jobId") jobId: String,
        @Part("clientOperationId") clientOperationId: RequestBody,
        @Part("visitId") visitId: RequestBody,
        @Part("phase") phase: RequestBody,
        @Part("note") note: RequestBody?,
        @Part("capturedAt") capturedAt: RequestBody?,
        @Part file: MultipartBody.Part,
    ): JobActivityDto

    /**
     * `POST /jobs/{jobId}/audio-notes` — adds one audio recording to the Job's Activity
     * (`BR-091`, `ADR-018`).
     *
     * The same shape as the photo upload, for the same reason: the bytes travel as a multipart part so
     * they are never base64-encoded into a request body, and `clientOperationId` is the queued
     * operation's id, sent unchanged on every retry, which is what makes a replayed upload record the
     * evidence once (`BR-031`). No length is sent: the API reads it from the container (`ADR-018` A3).
     * `visitId` names the field attempt the recording was made on (`BR-091`).
     */
    @Multipart
    @POST("jobs/{jobId}/audio-notes")
    suspend fun addJobAudioNote(
        @Header("Authorization") authorization: String,
        @Path("jobId") jobId: String,
        @Part("clientOperationId") clientOperationId: RequestBody,
        @Part("visitId") visitId: RequestBody,
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
     * `POST /jobs/{jobId}/audio-notes/{audioNoteId}/removal` — takes accepted audio evidence out of
     * ordinary use (`BR-088`, `BR-089`, `ADR-018` A7).
     *
     * The photo removal's own shape for the audio kind: nothing here edits or deletes a recording, the
     * API appends a removal record carrying the actor, the instant and the reason, and it answers with
     * the refreshed timeline. `evidence.audio.remove` authorizes it — a capability separate from the
     * photo one, so a company may withdraw one kind and keep the other.
     */
    @POST("jobs/{jobId}/audio-notes/{audioNoteId}/removal")
    suspend fun removeJobAudioNote(
        @Header("Authorization") authorization: String,
        @Path("jobId") jobId: String,
        @Path("audioNoteId") audioNoteId: String,
        @Body request: RemoveJobAudioNoteRequestDto,
    ): JobActivityDto

    /**
     * `GET /jobs/{jobId}/audio-notes/{audioNoteId}/content` — the recording's bytes (`BR-091`).
     *
     * Evidence is read through the API on the API port (`ADR-013` D7). It is streamed, because the
     * caller writes the bytes to a file it plays from rather than holding the whole recording in
     * memory.
     */
    @Streaming
    @GET("jobs/{jobId}/audio-notes/{audioNoteId}/content")
    suspend fun jobAudioNoteContent(
        @Header("Authorization") authorization: String,
        @Path("jobId") jobId: String,
        @Path("audioNoteId") audioNoteId: String,
    ): ResponseBody

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
