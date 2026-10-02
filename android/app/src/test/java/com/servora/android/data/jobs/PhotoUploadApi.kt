package com.servora.android.data.jobs

import com.servora.android.data.schedule.FollowUpVisitRequestDto
import java.io.IOException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer

/**
 * [JobDetailsApi] for tests that are about what the client sends and what it does with the answers.
 *
 * The photo upload is the part that matters here: the fake records the multipart fields and the bytes
 * it was given, so a test can assert that a retry carries the **same** idempotency key and the
 * technician's original bytes — which is what makes a replayed upload safe (`BR-031`, §5).
 */
class PhotoUploadApi : JobDetailsApi {

    /** How the next upload answers. A throw models an unreachable backend. */
    var addJobPhotoAnswer: (String) -> JobActivityDto = { jobId ->
        JobActivityDto(jobId = jobId, events = emptyList())
    }

    var contentAnswer: () -> ResponseBody = {
        FakeJobPhotoFiles.JPEG_BYTES.toResponseBody("image/jpeg".toMediaType())
    }

    var addJobPhotoCalls = 0
    var contentCalls = 0
    var lastAuthorization: String? = null
    var lastJobId: String? = null
    var lastClientOperationId: String? = null

    /**
     * The Visit the last upload named (`BR-047`).
     *
     * Evidence is recorded against a Visit, so what the client sends here is part of the contract: a
     * photo or recording the API has no field attempt for would be refused (`docs/api/job-photos.md`).
     */
    var lastVisitId: String? = null
    var lastPhase: String? = null
    var lastNote: String? = null
    var lastCapturedAt: String? = null
    var lastFileBytes: ByteArray? = null
    var lastFileName: String? = null
    var lastFileContentType: String? = null

    /** How the next audio upload answers, and what it was sent (`BR-091`). */
    var addJobAudioNoteAnswer: (String) -> JobActivityDto = { jobId ->
        JobActivityDto(jobId = jobId, events = emptyList())
    }

    /**
     * The tests this fake serves never create a Job: they are about what the client sends for evidence
     * and what it does with the answers (`BR-042`).
     */
    override suspend fun createJob(
        authorization: String,
        request: CreateJobRequest,
    ): JobDetailsDto = throw AssertionError("these tests do not create a Job")

    var addJobAudioNoteCalls = 0

    /**
     * How the next audio content read answers (`BR-091`). A test about playback's bytes scripts what the
     * backend delivers, or the failure it reports instead.
     */
    var audioContentAnswer: () -> ResponseBody = {
        FakeJobAudioFiles.M4A_BYTES.toResponseBody("audio/mp4".toMediaType())
    }

    var audioContentCalls = 0
    var lastAudioNoteId: String? = null

    override suspend fun changeVisitStatus(
        authorization: String,
        jobId: String,
        visitId: String,
        request: ChangeVisitStatusRequestDto,
    ): JobDetailsDto = throw AssertionError("these tests do not apply a Visit transition")

    override suspend fun completeVisit(
        authorization: String,
        jobId: String,
        visitId: String,
        request: CompleteVisitRequestDto,
    ): JobDetailsDto = throw AssertionError("these tests do not complete a Visit")

    override suspend fun addJobAudioNote(
        authorization: String,
        jobId: String,
        clientOperationId: RequestBody,
        visitId: RequestBody,
        phase: RequestBody,
        note: RequestBody?,
        capturedAt: RequestBody?,
        file: MultipartBody.Part,
    ): JobActivityDto {
        addJobAudioNoteCalls += 1
        lastAuthorization = authorization
        lastJobId = jobId
        lastClientOperationId = clientOperationId.multipartText()
        lastVisitId = visitId.multipartText()
        lastPhase = phase.multipartText()
        lastNote = note?.multipartText()
        lastCapturedAt = capturedAt?.multipartText()
        lastFileName = file.headers?.get("Content-Disposition")
        lastFileContentType = file.body.contentType()?.toString()
        lastFileBytes = file.body.multipartBytes()
        return addJobAudioNoteAnswer(jobId)
    }

    override suspend fun addJobPhoto(
        authorization: String,
        jobId: String,
        clientOperationId: RequestBody,
        visitId: RequestBody,
        phase: RequestBody,
        note: RequestBody?,
        capturedAt: RequestBody?,
        file: MultipartBody.Part,
    ): JobActivityDto {
        addJobPhotoCalls += 1
        lastAuthorization = authorization
        lastJobId = jobId
        lastClientOperationId = clientOperationId.multipartText()
        lastVisitId = visitId.multipartText()
        lastPhase = phase.multipartText()
        lastNote = note?.multipartText()
        lastCapturedAt = capturedAt?.multipartText()
        lastFileName = file.headers?.get("Content-Disposition")
        lastFileContentType = file.body.contentType()?.toString()
        lastFileBytes = file.body.multipartBytes()
        return addJobPhotoAnswer(jobId)
    }

    override suspend fun jobPhotoContent(
        authorization: String,
        jobId: String,
        photoId: String,
    ): ResponseBody {
        contentCalls += 1
        lastAuthorization = authorization
        lastJobId = jobId
        return contentAnswer()
    }

    override suspend fun jobDetails(authorization: String, jobId: String): JobDetailsDto =
        throw NotImplementedError("Not used by these tests.")

    override suspend fun jobActivity(authorization: String, jobId: String): JobActivityDto =
        throw NotImplementedError("Not used by these tests.")

    override suspend fun changeJobStatus(
        authorization: String,
        jobId: String,
        request: ChangeJobStatusRequestDto,
    ): JobDetailsDto = throw NotImplementedError("Not used by these tests.")

    override suspend fun rescheduleVisit(
        authorization: String,
        jobId: String,
        visitId: String,
        request: RescheduleVisitRequestDto,
    ): JobDetailsDto = throw NotImplementedError("Not used by these tests.")

    override suspend fun assignVisitTechnicians(
        authorization: String,
        jobId: String,
        visitId: String,
        request: AssignVisitTechniciansRequestDto,
    ): JobDetailsDto = throw NotImplementedError("Not used by these tests.")

    override suspend fun addVisitNote(
        authorization: String,
        jobId: String,
        visitId: String,
        request: AddVisitNoteRequestDto,
    ): JobActivityDto = throw NotImplementedError("Not used by these tests.")

    override suspend fun editVisitNote(
        authorization: String,
        jobId: String,
        noteId: String,
        request: EditVisitNoteRequestDto,
    ): JobActivityDto = throw NotImplementedError("Not used by these tests.")

    override suspend fun removeVisitNote(
        authorization: String,
        jobId: String,
        noteId: String,
        request: RemoveVisitNoteRequestDto,
    ): JobActivityDto = throw NotImplementedError("Not used by these tests.")

    override suspend fun removeJobPhoto(
        authorization: String,
        jobId: String,
        photoId: String,
        request: RemoveJobPhotoRequestDto,
    ): JobActivityDto = throw NotImplementedError("Not used by these tests.")

    override suspend fun removeJobAudioNote(
        authorization: String,
        jobId: String,
        audioNoteId: String,
        request: RemoveJobAudioNoteRequestDto,
    ): JobActivityDto = throw NotImplementedError("Not used by these tests.")

    override suspend fun jobAudioNoteContent(
        authorization: String,
        jobId: String,
        audioNoteId: String,
    ): ResponseBody {
        audioContentCalls += 1
        lastAuthorization = authorization
        lastJobId = jobId
        lastAudioNoteId = audioNoteId
        return audioContentAnswer()
    }

    override suspend fun assignableTechnicians(
        authorization: String,
    ): List<AssignableTechnicianDto> =
        throw NotImplementedError("Not used by these tests.")

    /** These tests are about evidence, so nothing here schedules a Visit. */
    override suspend fun createVisit(
        authorization: String,
        jobId: String,
        request: CreateVisitRequestDto,
    ): JobDetailsDto = throw NotImplementedError("Not used by these tests.")

    /** These tests are about evidence, so nothing here approves a follow-up request. */
    override suspend fun approveVisitRequest(
        authorization: String,
        jobId: String,
        requestId: String,
        request: ApproveVisitRequestRequestDto,
    ): JobDetailsDto = throw NotImplementedError("Not used by these tests.")

    /** These tests are about evidence, so nothing here submits a follow-up request. */
    override suspend fun submitVisitRequest(
        authorization: String,
        jobId: String,
        request: SubmitVisitRequestRequestDto,
    ): FollowUpVisitRequestDto = throw NotImplementedError("Not used by these tests.")
}

/** The text a multipart part carries, as the API receives it. */
private fun RequestBody.multipartText(): String {
    val buffer = Buffer()
    writeTo(buffer)
    return buffer.readUtf8()
}

/** The bytes a multipart file part carries. */
private fun RequestBody.multipartBytes(): ByteArray {
    val buffer = Buffer()
    writeTo(buffer)
    return buffer.readByteArray()
}

/** An unreachable backend, as every read and write in these tests models one. */
internal fun photoUnreachable(): IOException = IOException("no connectivity")
