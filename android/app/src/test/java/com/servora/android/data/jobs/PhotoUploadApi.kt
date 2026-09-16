package com.servora.android.data.jobs

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
    var lastPhase: String? = null
    var lastNote: String? = null
    var lastCapturedAt: String? = null
    var lastFileBytes: ByteArray? = null
    var lastFileName: String? = null
    var lastFileContentType: String? = null

    override suspend fun addJobPhoto(
        authorization: String,
        jobId: String,
        clientOperationId: RequestBody,
        phase: RequestBody,
        note: RequestBody?,
        capturedAt: RequestBody?,
        file: MultipartBody.Part,
    ): JobActivityDto {
        addJobPhotoCalls += 1
        lastAuthorization = authorization
        lastJobId = jobId
        lastClientOperationId = clientOperationId.multipartText()
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

    override suspend fun removeJobPhoto(
        authorization: String,
        jobId: String,
        photoId: String,
        request: RemoveJobPhotoRequestDto,
    ): JobActivityDto = throw NotImplementedError("Not used by these tests.")

    override suspend fun assignableTechnicians(
        authorization: String,
    ): List<AssignableTechnicianDto> =
        throw NotImplementedError("Not used by these tests.")
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
