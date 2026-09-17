package com.servora.android.data.jobs

import com.servora.android.data.offline.OutboxFailureReason
import com.servora.android.data.offline.OutboxOperation
import com.servora.android.data.offline.OutboxOperationState
import com.servora.android.data.offline.ReplayOutcome
import com.servora.android.data.session.FakeAuthenticatedSubject
import com.servora.android.data.session.SessionAuthenticator
import com.servora.android.data.session.SessionRenewal
import com.servora.android.domain.model.EvidencePhase
import com.servora.android.domain.model.PendingJobPhoto
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

/**
 * Replaying a queued Job photo upload (`BR-015`, `BR-031`, `offline-first-architecture.md` §5, §6, §9).
 *
 * Three properties make a late upload safe, and each is pinned here: the operation keeps the
 * idempotency key it was queued with, it uploads the bytes the technician actually captured, and a
 * failure of any kind leaves both the queued row and the local file in place (`BR-014`).
 */
class JobPhotoUploadHandlerTest {

    private val api = PhotoUploadApi()
    private val pending = InMemoryPendingJobPhotoStore()
    private val files = FakeJobPhotoFiles()
    private val payloads = JobPhotoPayloads(Json)

    @Test
    fun `uploads the queued key and the bytes the technician captured`() = runTest {
        val photo = pendingPhoto()
        pending.record(photo)
        files.writeCapture(photo.localPath)

        val outcome = handler().replay(queuedRow(photo))

        assertEquals(ReplayOutcome.Applied, outcome)
        assertEquals("photo-1", api.lastClientOperationId)
        assertEquals("DURING_WORK", api.lastPhase)
        assertEquals(photo.capturedAt, api.lastCapturedAt)
        assertEquals("Sawdust on the belt", api.lastNote)
        assertEquals(FakeJobPhotoFiles.JPEG_BYTES.toList(), api.lastFileBytes?.toList())
        assertEquals("Bearer access-1", api.lastAuthorization)
    }

    @Test
    fun `removes the local copy only once the backend holds the photo`() = runTest {
        val photo = pendingPhoto()
        pending.record(photo)
        files.writeCapture(photo.localPath)

        handler().replay(queuedRow(photo))

        assertTrue(files.storedPaths.isEmpty())
        assertNull(pending.find(photo.photoId))
    }

    @Test
    fun `keeps the photo and the queued row when the backend cannot be reached`() = runTest {
        val photo = pendingPhoto()
        pending.record(photo)
        files.writeCapture(photo.localPath)
        api.addJobPhotoAnswer = { throw photoUnreachable() }

        val outcome = handler().replay(queuedRow(photo))

        assertEquals(ReplayOutcome.Retryable(OutboxFailureReason.NETWORK), outcome)
        assertEquals(setOf(photo.localPath), files.storedPaths)
        assertEquals(photo.photoId, pending.find(photo.photoId)?.photoId)
    }

    @Test
    fun `keeps the photo and reports the refusal when the caller is not authorized`() = runTest {
        val photo = pendingPhoto()
        pending.record(photo)
        files.writeCapture(photo.localPath)
        api.addJobPhotoAnswer = { throw httpError(403) }

        val outcome = handler().replay(queuedRow(photo))

        assertEquals(ReplayOutcome.Rejected(OutboxFailureReason.NOT_AUTHORIZED), outcome)
        assertEquals(setOf(photo.localPath), files.storedPaths)
        assertEquals(photo.photoId, pending.find(photo.photoId)?.photoId)
    }

    @Test
    fun `treats an oversized upload as a refusal rather than a retry`() = runTest {
        val photo = pendingPhoto()
        pending.record(photo)
        files.writeCapture(photo.localPath)
        api.addJobPhotoAnswer = { throw httpError(413) }

        val outcome = handler().replay(queuedRow(photo))

        assertEquals(ReplayOutcome.Rejected(OutboxFailureReason.INVALID), outcome)
        assertEquals(photo.photoId, pending.find(photo.photoId)?.photoId)
    }

    @Test
    fun `reports a Job that is gone as a refusal and keeps the photo`() = runTest {
        val photo = pendingPhoto()
        pending.record(photo)
        files.writeCapture(photo.localPath)
        api.addJobPhotoAnswer = { throw httpError(404) }

        val outcome = handler().replay(queuedRow(photo))

        assertEquals(ReplayOutcome.Rejected(OutboxFailureReason.NOT_FOUND), outcome)
        assertEquals(setOf(photo.localPath), files.storedPaths)
    }

    @Test
    fun `renews the session once when the token is refused`() = runTest {
        val photo = pendingPhoto()
        pending.record(photo)
        files.writeCapture(photo.localPath)
        var calls = 0
        api.addJobPhotoAnswer = { jobId ->
            calls += 1
            if (calls == 1) {
                throw httpError(401)
            }
            JobActivityDto(jobId = jobId, events = emptyList())
        }

        val outcome = handler(
            authenticator = FixedSessionAuthenticator(
                accessToken = "access-1",
                renewal = SessionRenewal.Renewed("access-2"),
            ),
        ).replay(queuedRow(photo))

        assertEquals(ReplayOutcome.Applied, outcome)
        assertEquals("Bearer access-2", api.lastAuthorization)
    }

    @Test
    fun `refuses an upload whose bytes are no longer on the device`() = runTest {
        val photo = pendingPhoto()

        val outcome = handler().replay(queuedRow(photo))

        assertEquals(ReplayOutcome.Rejected(OutboxFailureReason.NOT_FOUND), outcome)
        assertEquals(0, api.addJobPhotoCalls)
    }

    @Test
    fun `refuses an upload whose phase this build cannot read`() = runTest {
        val photo = pendingPhoto(phase = null)
        pending.record(photo)
        files.writeCapture(photo.localPath)

        val outcome = handler().replay(queuedRow(photo))

        assertEquals(ReplayOutcome.Rejected(OutboxFailureReason.INVALID), outcome)
        assertEquals(0, api.addJobPhotoCalls)
    }

    @Test
    fun `keeps the same idempotency key when a failed upload is replayed`() = runTest {
        val photo = pendingPhoto()
        pending.record(photo)
        files.writeCapture(photo.localPath)
        api.addJobPhotoAnswer = { throw photoUnreachable() }
        val row = queuedRow(photo)

        handler().replay(row)
        api.addJobPhotoAnswer = { jobId -> JobActivityDto(jobId = jobId, events = emptyList()) }
        handler().replay(row)

        assertEquals(2, api.addJobPhotoCalls)
        assertEquals(photo.photoId, api.lastClientOperationId)
    }

    @Test
    fun `declares the type the photo was recorded as`() = runTest {
        val photo = pendingPhoto(mimeType = "image/png")
        pending.record(photo)
        files.writeCapture(photo.localPath, FakeJobPhotoFiles.PNG_BYTES)

        handler().replay(queuedRow(photo))

        // The API sniffs the bytes and refuses a declared type that disagrees with them, so the part
        // has to declare the type the photo was prepared and recorded as (`D3b`, `docs/api/job-photos.md`
        // §3.3).
        assertEquals("image/png", api.lastFileContentType)
        assertTrue(api.lastFileName.orEmpty().contains("photo.png"))
    }

    /** One pending photo, as the session records it after a capture. */
    private fun pendingPhoto(
        phase: EvidencePhase? = EvidencePhase.DURING_WORK,
        mimeType: String = "image/jpeg",
    ) = PendingJobPhoto(
        photoId = "photo-1",
        jobId = JOB_ID,
        localPath = "app-private/job-photos/user-1/photo-1.${extensionOf(mimeType)}",
        phase = phase,
        note = "Sawdust on the belt",
        capturedAt = "2026-09-15T13:04:05Z",
        mimeType = mimeType,
        recordedAt = 1_000L,
        submitted = true,
    )

    /** The outbox row the submit step writes for that photo. */
    private fun queuedRow(photo: PendingJobPhoto) = OutboxOperation(
        operationId = photo.photoId,
        operationType = JobPhotoOperations.ADD_PHOTO,
        targetId = photo.jobId,
        subjectId = "user-1",
        payload = payloads.encode(photo),
        capturedAt = photo.capturedAt,
        recordedAt = photo.recordedAt,
        expectedVersion = null,
        attemptCount = 0,
        lastAttemptAt = null,
        lastFailure = null,
        state = OutboxOperationState.PENDING,
    )

    private fun handler(
        authenticator: SessionAuthenticator = FixedSessionAuthenticator("access-1"),
    ) = JobPhotoUploadHandler(
        api = api,
        sessionAuthenticator = authenticator,
        payloads = payloads,
        pending = pending,
        files = files,
    )

    private companion object {
        const val JOB_ID = "job-1"

        /** A response the API refused with, as Retrofit reports it (`dev.md` §7). */
        fun httpError(code: Int): HttpException =
            HttpException(
                Response.error<Any>(code, "{}".toResponseBody("application/json".toMediaType())),
            )
    }
}

/** A [SessionAuthenticator] a test decides, in the shape the Jobs feature's tests already use. */
private class FixedSessionAuthenticator(
    private val accessToken: String?,
    private val renewal: SessionRenewal = SessionRenewal.Rejected,
) : SessionAuthenticator {

    override fun accessToken(): String? = accessToken

    override suspend fun renew(rejectedToken: String): SessionRenewal = renewal
}

/** The extension a photo recorded as [mimeType] is stored under. */
private fun extensionOf(mimeType: String): String =
    JobPhotoContentType.ofMimeType(mimeType)?.extension ?: "jpg"
