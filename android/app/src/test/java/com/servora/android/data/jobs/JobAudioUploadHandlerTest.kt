package com.servora.android.data.jobs

import com.servora.android.data.offline.OutboxFailureReason
import com.servora.android.data.offline.OutboxOperation
import com.servora.android.data.offline.OutboxOperationState
import com.servora.android.data.offline.ReplayOutcome
import com.servora.android.data.session.SessionAuthenticator
import com.servora.android.data.session.SessionRenewal
import com.servora.android.domain.model.EvidencePhase
import com.servora.android.domain.model.PendingJobAudioNote
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
 * Replaying a queued audio note upload (`BR-091`, `ADR-018` A10, §5, §6, §9).
 *
 * Three properties make a late upload safe, and each is pinned here: the operation keeps the idempotency
 * key it was queued with, it uploads the bytes the recorder actually wrote, and a failure of any kind
 * leaves both the queued row and the local file in place (`BR-014`). A fourth is audio's own: no length is
 * sent, because the API reads it from the container (`ADR-018` A3).
 */
class JobAudioUploadHandlerTest {

    private val api = PhotoUploadApi()
    private val pending = InMemoryPendingJobAudioNoteStore()
    private val files = FakeJobAudioFiles()
    private val payloads = JobAudioPayloads(Json)

    @Test
    fun `uploads the queued key and the bytes the recorder wrote`() = runTest {
        val note = pendingNote()
        pending.record(note)
        files.writeBytes(note.localPath, FakeJobAudioFiles.M4A_BYTES)

        val outcome = handler().replay(queuedRow(note))

        assertEquals(ReplayOutcome.Applied, outcome)
        assertEquals("audio-1", api.lastClientOperationId)
        assertEquals("AFTER_WORK", api.lastPhase)
        assertEquals(note.capturedAt, api.lastCapturedAt)
        assertEquals("Compressor is noisy", api.lastNote)
        assertEquals(FakeJobAudioFiles.M4A_BYTES.toList(), api.lastFileBytes?.toList())
        assertEquals("Bearer access-1", api.lastAuthorization)
    }

    @Test
    fun `declares the one container the API accepts`() = runTest {
        val note = pendingNote()
        pending.record(note)
        files.writeBytes(note.localPath, FakeJobAudioFiles.M4A_BYTES)

        handler().replay(queuedRow(note))

        // The API sniffs the bytes and refuses a declared type that disagrees with them, so the part has
        // to declare the container the device recorded in (`ADR-018` A2).
        assertEquals(JOB_AUDIO_MIME_TYPE, api.lastFileContentType)
        assertTrue(api.lastFileName.orEmpty().contains(JOB_AUDIO_FILE_NAME))
    }

    @Test
    fun `removes the local copy only once the backend holds the recording`() = runTest {
        val note = pendingNote()
        pending.record(note)
        files.writeBytes(note.localPath, FakeJobAudioFiles.M4A_BYTES)

        handler().replay(queuedRow(note))

        assertTrue(files.storedPaths.isEmpty())
        assertNull(pending.find(note.audioNoteId))
    }

    @Test
    fun `keeps the recording and the queued row when the backend cannot be reached`() = runTest {
        val note = pendingNote()
        pending.record(note)
        files.writeBytes(note.localPath, FakeJobAudioFiles.M4A_BYTES)
        api.addJobAudioNoteAnswer = { throw photoUnreachable() }

        val outcome = handler().replay(queuedRow(note))

        assertEquals(ReplayOutcome.Retryable(OutboxFailureReason.NETWORK), outcome)
        assertEquals(setOf(note.localPath), files.storedPaths)
        assertEquals(note.audioNoteId, pending.find(note.audioNoteId)?.audioNoteId)
    }

    @Test
    fun `keeps the recording and reports the refusal when the caller is not authorized`() = runTest {
        val note = pendingNote()
        pending.record(note)
        files.writeBytes(note.localPath, FakeJobAudioFiles.M4A_BYTES)
        api.addJobAudioNoteAnswer = { throw httpError(403) }

        val outcome = handler().replay(queuedRow(note))

        assertEquals(ReplayOutcome.Rejected(OutboxFailureReason.NOT_AUTHORIZED), outcome)
        assertEquals(setOf(note.localPath), files.storedPaths)
        assertEquals(note.audioNoteId, pending.find(note.audioNoteId)?.audioNoteId)
    }

    @Test
    fun `refuses a recording whose stored phase this build cannot read`() = runTest {
        val note = pendingNote()
        pending.record(note)
        files.writeBytes(note.localPath, FakeJobAudioFiles.M4A_BYTES)
        val row = queuedRow(note).copy(
            payload = payloads.encode(note).replace("AFTER_WORK", "NIGHT_WORK"),
        )

        val outcome = handler().replay(row)

        // A phase Servora does not have is never sent, and nothing is deleted for it (`BR-042`).
        assertEquals(ReplayOutcome.Rejected(OutboxFailureReason.INVALID), outcome)
        assertEquals(setOf(note.localPath), files.storedPaths)
    }

    @Test
    fun `reports a recording whose bytes are gone rather than uploading nothing`() = runTest {
        val note = pendingNote()
        pending.record(note)

        val outcome = handler().replay(queuedRow(note))

        assertEquals(ReplayOutcome.Rejected(OutboxFailureReason.NOT_FOUND), outcome)
        assertEquals(0, api.addJobAudioNoteCalls)
    }

    @Test
    fun `replays with the same key after a failure`() = runTest {
        val note = pendingNote()
        pending.record(note)
        files.writeBytes(note.localPath, FakeJobAudioFiles.M4A_BYTES)
        api.addJobAudioNoteAnswer = { throw photoUnreachable() }
        val row = queuedRow(note)

        handler().replay(row)
        api.addJobAudioNoteAnswer = { jobId -> JobActivityDto(jobId = jobId, events = emptyList()) }
        handler().replay(row)

        assertEquals(2, api.addJobAudioNoteCalls)
        assertEquals(note.audioNoteId, api.lastClientOperationId)
    }

    @Test
    fun `renews the session once and retries with the renewed token`() = runTest {
        val note = pendingNote()
        pending.record(note)
        files.writeBytes(note.localPath, FakeJobAudioFiles.M4A_BYTES)
        var first = true
        api.addJobAudioNoteAnswer = { jobId ->
            if (first) {
                first = false
                throw httpError(401)
            }
            JobActivityDto(jobId = jobId, events = emptyList())
        }

        val outcome = handler(
            FixedJobAudioAuthenticator("access-1", SessionRenewal.Renewed("access-2")),
        ).replay(queuedRow(note))

        assertEquals(ReplayOutcome.Applied, outcome)
        assertEquals("Bearer access-2", api.lastAuthorization)
    }

    /** One pending recording, as the session records it when the technician stops the recorder. */
    private fun pendingNote(
        phase: EvidencePhase? = EvidencePhase.AFTER_WORK,
    ) = PendingJobAudioNote(
        audioNoteId = "audio-1",
        jobId = JOB_ID,
        localPath = "app-private/job-audio/user-1/audio-1.$JOB_AUDIO_FILE_EXTENSION",
        phase = phase,
        note = "Compressor is noisy",
        capturedAt = "2026-09-15T13:04:05Z",
        durationSeconds = 18,
        mimeType = JOB_AUDIO_MIME_TYPE,
        recordedAt = 1_000L,
        submitted = true,
    )

    /** The outbox row the attach step writes for that recording. */
    private fun queuedRow(note: PendingJobAudioNote) = OutboxOperation(
        operationId = note.audioNoteId,
        operationType = JobAudioOperations.ADD_AUDIO,
        targetId = note.jobId,
        subjectId = "user-1",
        payload = payloads.encode(note),
        capturedAt = note.capturedAt,
        recordedAt = note.recordedAt,
        expectedVersion = null,
        attemptCount = 0,
        lastAttemptAt = null,
        lastFailure = null,
        state = OutboxOperationState.PENDING,
    )

    private fun handler(
        authenticator: SessionAuthenticator = FixedJobAudioAuthenticator("access-1"),
    ) = JobAudioUploadHandler(
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
private class FixedJobAudioAuthenticator(
    private val accessToken: String?,
    private val renewal: SessionRenewal = SessionRenewal.Rejected,
) : SessionAuthenticator {

    override fun accessToken(): String? = accessToken

    override suspend fun renew(rejectedToken: String): SessionRenewal = renewal
}
