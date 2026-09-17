package com.servora.android.data.jobs

import com.servora.android.data.session.FakeAuthenticatedSubject
import com.servora.android.data.session.SessionAuthenticator
import com.servora.android.data.session.SessionRenewal
import java.io.File
import java.io.IOException
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import retrofit2.HttpException
import retrofit2.Response

/**
 * Where the bytes of an accepted recording come from when the technician plays it (`BR-013`, `BR-091`,
 * `ADR-018` A9).
 *
 * Playback is the API's bytes read through the API port and kept in this session's own cache, so these
 * pin what that is worth: a recording is downloaded **once**, a recording the device already holds is
 * played with no round trip — which is what makes one the technician has heard playable without
 * connectivity — one session's recordings are never played for another, and a read that could not
 * deliver bytes says which of the two it was, so the technician is told something they can act on
 * (`BR-042`).
 *
 * The cache directory is one this test owns, because the entries written and read back are the behaviour
 * under test (`qa.md` §6.1).
 */
class JobAudioEvidenceCacheTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `reads a recording through the API once and plays it from the device afterwards`() = runTest {
        val api = PhotoUploadApi()
        val cache = cache(api)

        val first = cache.read(JOB_ID, AUDIO_NOTE_ID)

        assertEquals(AUDIO_NOTE_ID, api.lastAudioNoteId)
        assertEquals(1, api.audioContentCalls)
        val available = first as JobAudioEvidenceRead.Available
        assertEquals(FakeJobAudioFiles.M4A_BYTES.toList(), File(available.path).readBytes().toList())

        val second = cache.read(JOB_ID, AUDIO_NOTE_ID)

        // The bytes are on this device now, so the second play asks the API for nothing: that is what
        // makes a recording the technician has already heard playable without connectivity (`BR-013`).
        assertEquals(1, api.audioContentCalls)
        assertEquals(available.path, (second as JobAudioEvidenceRead.Available).path)
    }

    @Test
    fun `keeps one session's recordings from another`() = runTest {
        val api = PhotoUploadApi()
        val shared = temporaryFolder.newFolder("cache")
        val first = cache(api, subjectId = "user-1", directory = shared)
        val second = cache(api, subjectId = "user-2", directory = shared)

        val one = first.read(JOB_ID, AUDIO_NOTE_ID) as JobAudioEvidenceRead.Available
        val two = second.read(JOB_ID, AUDIO_NOTE_ID) as JobAudioEvidenceRead.Available

        // A recording cached for one member is never played for another: the second session reads its own
        // copy through the API rather than being served the first one's file (`§10`).
        assertEquals(2, api.audioContentCalls)
        assertNotEquals(one.path, two.path)
    }

    @Test
    fun `reports a backend it could not reach rather than substituting a local copy`() = runTest {
        val api = PhotoUploadApi().apply { audioContentAnswer = { throw IOException("offline") } }
        val cache = cache(api)

        val read = cache.read(JOB_ID, AUDIO_NOTE_ID)

        // The backend did not answer, so nothing is known about the recording and nothing was kept
        // (`BR-013`): the caller says the recording needs the server rather than that it failed to play.
        assertEquals(JobAudioEvidenceRead.Unreachable, read)
        assertEquals(emptyList<File>(), cachedFiles())
    }

    @Test
    fun `reports a recording the backend did not deliver`() = runTest {
        val api = PhotoUploadApi().apply {
            audioContentAnswer = {
                throw HttpException(Response.error<ResponseBody>(NOT_FOUND, REFUSED_BODY))
            }
        }
        val cache = cache(api)

        // A recording the backend refuses — including one removed from ordinary use, whose bytes are
        // refused (`BR-089`) — is not played from a local copy (`BR-007`).
        assertEquals(JobAudioEvidenceRead.Unavailable, cache.read(JOB_ID, AUDIO_NOTE_ID))
        assertEquals(emptyList<File>(), cachedFiles())
    }

    @Test
    fun `reads a recording off the thread that asked for it`() = runTest {
        val caller = Thread.currentThread()
        var readOn: Thread? = null
        val api = PhotoUploadApi().apply {
            audioContentAnswer = {
                readOn = Thread.currentThread()
                FakeJobAudioFiles.M4A_BYTES.toResponseBody(AUDIO_CONTENT_TYPE.toMediaType())
            }
        }

        cache(api).read(JOB_ID, AUDIO_NOTE_ID)

        // Playback starts from a tap, so the screen asks for the bytes on the thread that draws it. The
        // response is **streamed**, which means this caller reads it rather than the call itself, so the
        // read has to be moved off that thread (`dev.md` §10): reading a socket where the tap arrived is
        // what a device refuses to do, and that refusal is a crash rather than a message (`BR-042`).
        assertNotNull(readOn)
        assertNotEquals(caller, readOn)
    }

    @Test
    fun `does not keep an answer that holds no bytes`() = runTest {
        val api = PhotoUploadApi().apply {
            audioContentAnswer = { "".toResponseBody(AUDIO_CONTENT_TYPE.toMediaType()) }
        }
        val cache = cache(api)

        // The backend answered with nothing, which is not a recording: it is reported rather than kept,
        // so the next play asks again instead of trusting an entry that holds no bytes (`BR-042`).
        assertEquals(JobAudioEvidenceRead.Unavailable, cache.read(JOB_ID, AUDIO_NOTE_ID))
        assertEquals(emptyList<File>(), cachedFiles())
    }

    @Test
    fun `answers nothing for a session a recording cannot be attributed to`() = runTest {
        val api = PhotoUploadApi()
        val cache = PrivateJobAudioEvidenceCache(
            reader = reader(api, FakeAudioSessionAuthenticator(ACCESS_TOKEN)),
            subject = FakeAuthenticatedSubject(null),
            directory = temporaryFolder.newFolder("cache"),
        )

        // Nothing is read and nothing is cached: evidence belongs to the session that read it (`§10`).
        assertEquals(JobAudioEvidenceRead.Unavailable, cache.read(JOB_ID, AUDIO_NOTE_ID))
        assertEquals(0, api.audioContentCalls)
        assertEquals(emptyList<File>(), cachedFiles())
    }

    /** Every file the cache holds, so a read that kept nothing can be asserted as an empty set. */
    private fun cachedFiles(): List<File> =
        temporaryFolder.root.walkTopDown().filter { file -> file.isFile }.toList()

    private fun cache(
        api: PhotoUploadApi,
        subjectId: String? = "user-1",
        directory: File = temporaryFolder.newFolder("cache"),
    ): JobAudioEvidenceCache = PrivateJobAudioEvidenceCache(
        reader = reader(api, FakeAudioSessionAuthenticator(ACCESS_TOKEN)),
        subject = FakeAuthenticatedSubject(subjectId),
        directory = directory,
    )

    /** The reader the cache asks for bytes, over the API fake and a session a test decides. */
    private fun reader(api: PhotoUploadApi, authenticator: SessionAuthenticator): JobAudioContentReader =
        JobAudioContentReader(api = api, sessionAuthenticator = authenticator)

    private companion object {
        const val JOB_ID = "job-1"
        const val AUDIO_NOTE_ID = "audio-1"
        const val ACCESS_TOKEN = "access-token"
        const val NOT_FOUND = 404

        /** The type the API serves a recording as, which the bytes are read back as (`ADR-018` A2). */
        const val AUDIO_CONTENT_TYPE = "audio/mp4"

        /** The body a refusal carries; nothing reads it, but an error response needs one. */
        val REFUSED_BODY: ResponseBody = "".toResponseBody("application/json".toMediaType())
    }
}

/** A [SessionAuthenticator] a test decides, so an unreachable backend is the only variable. */
private class FakeAudioSessionAuthenticator(
    private val accessToken: String? = "access-token",
    private val renewal: SessionRenewal = SessionRenewal.Rejected,
) : SessionAuthenticator {

    override fun accessToken(): String? = accessToken

    override suspend fun renew(rejectedToken: String): SessionRenewal = renewal
}
