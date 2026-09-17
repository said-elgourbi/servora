package com.servora.android.data.jobs

import android.content.Context
import com.servora.android.data.session.AuthenticatedSubject
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Where the bytes of a recording the **backend holds** are kept so the device can play them
 * (`BR-091`, `ADR-018` A9, offline standard §9).
 *
 * Android plays from a file, so a recording read through the API has to land somewhere first. That
 * somewhere is this session's own entry in the app cache directory, under the recording's own id: the
 * first play downloads the bytes once and every later play — including a cold start, and including one
 * without connectivity — reads the file. That is what `BR-013` asks of evidence bytes: the recording's
 * **metadata** is always readable offline and its **bytes** are best-effort.
 *
 * The cache is deliberately **evictable**. It is the platform's cache directory, so the system may
 * reclaim it under storage pressure; a recording whose bytes are gone is read from the API again, and a
 * device that cannot reach the API says so rather than playing something else (`BR-042`).
 *
 * The directory is partitioned by the session's subject, so one signed-in member's recordings are never
 * read or played by another (§10). The bytes of a recording this device still holds as a draft are
 * **not** here: those live in app-private storage (`JobAudioFiles`) and are never evictable, because
 * they are the only copy of evidence the backend has not accepted yet (`BR-014`).
 */
interface JobAudioEvidenceCache {
    /**
     * A local file holding the recording's bytes, downloading them if this device does not have them.
     *
     * `Available` for a recording whose bytes are now on this device, and otherwise why they are not —
     * the backend could not be reached, or it did not deliver them (`BR-013`, `BR-042`).
     *
     * It is device and network work — a file, and a streamed read of the API — so it is performed off
     * the caller's thread rather than on whichever one the caller happens to hold (`dev.md` §10).
     */
    suspend fun read(jobId: String, audioNoteId: String): JobAudioEvidenceRead
}

/** What one playback's bytes came to (`BR-013`, `BR-042`). */
sealed interface JobAudioEvidenceRead {
    /** The recording's bytes are on this device, at [path]. */
    data class Available(val path: String) : JobAudioEvidenceRead

    /** The backend could not be reached and this device does not hold the bytes (`BR-013`). */
    data object Unreachable : JobAudioEvidenceRead

    /** The backend refused, the recording is not there, or no session can ask for it (`BR-007`). */
    data object Unavailable : JobAudioEvidenceRead
}

/** The default [JobAudioEvidenceCache]: a session-scoped directory of the app's cache, filled from the API. */
class PrivateJobAudioEvidenceCache(
    private val reader: JobAudioContentReader,
    private val subject: AuthenticatedSubject,
    /**
     * The cache directory every session's entries live under.
     *
     * It is a parameter rather than a `Context` read, so the two things that are actually this class's
     * behaviour — a recording read once and reused, and a read that could not deliver bytes — are
     * verifiable on the JVM (`qa.md` §6.1).
     */
    private val directory: File,
) : JobAudioEvidenceCache {

    override suspend fun read(jobId: String, audioNoteId: String): JobAudioEvidenceRead =
        // Playing a recording touches the device and the backend, and its caller is the screen, whose
        // own coroutine runs on the main thread: the response is streamed (`@Streaming`), so its bytes
        // are read by this caller rather than by the call itself. The read therefore runs on the IO
        // dispatcher — a network read must never be performed from the main thread (`dev.md` §10,
        // `BR-012`).
        withContext(Dispatchers.IO) {
            val subjectId = subject.current() ?: return@withContext JobAudioEvidenceRead.Unavailable
            val file = fileFor(subjectId, audioNoteId)
            // A recording the device already holds is played from there, with no round trip: that is
            // what makes a recording the technician played readable without connectivity (`BR-013`).
            if (file.isFile && file.length() > 0) {
                return@withContext JobAudioEvidenceRead.Available(file.absolutePath)
            }
            when (val read = reader.read(jobId, audioNoteId)) {
                is JobAudioContentRead.Bytes -> store(file, read.bytes)
                JobAudioContentRead.Unreachable -> JobAudioEvidenceRead.Unreachable
                JobAudioContentRead.Unavailable -> JobAudioEvidenceRead.Unavailable
            }
        }

    /**
     * Writes the recording beside the caller's session, or reports that it could not be kept.
     *
     * A cache that cannot be written is not a failure of the read: the bytes arrived, and a half-written
     * file must never be played, so the file is removed rather than left for the next play to trust
     * (`BR-042`). An entry holding nothing is not kept either — an empty file would fail in the player
     * instead of being reported as bytes the backend did not deliver.
     */
    private fun store(file: File, bytes: ByteArray): JobAudioEvidenceRead {
        val stored = runCatching {
            file.parentFile?.mkdirs()
            file.writeBytes(bytes)
        }
        if (stored.isFailure || !file.isFile || file.length() == 0L) {
            runCatching { file.delete() }
            return JobAudioEvidenceRead.Unavailable
        }
        return JobAudioEvidenceRead.Available(file.absolutePath)
    }

    /** One recording's entry for one session: the subject is what keeps two members' files apart (§10). */
    private fun fileFor(subjectId: String, audioNoteId: String): File =
        File(File(directory, subjectId), "$audioNoteId.$EVIDENCE_FILE_EXTENSION")

    private companion object {
        /** The same extension the container is recorded, stored and uploaded under (`ADR-018` A2). */
        const val EVIDENCE_FILE_EXTENSION = "m4a"
    }
}

/**
 * Where the cache's directory is decided (`ADR-018` A9, offline standard §9).
 *
 * It is the app's own cache directory — the platform's to reclaim — under the feature's own name, so
 * nothing else in the app writes into it and losing it costs a re-download rather than evidence.
 */
@Module
@InstallIn(SingletonComponent::class)
internal object JobAudioPlaybackModule {

    /** The feature's own cache directory name, so nothing else in the app writes into it. */
    private const val DIRECTORY = "job-audio-evidence"

    @Provides
    @Singleton
    fun provideJobAudioEvidenceCache(
        reader: JobAudioContentReader,
        subject: AuthenticatedSubject,
        @ApplicationContext context: Context,
    ): JobAudioEvidenceCache = PrivateJobAudioEvidenceCache(
        reader = reader,
        subject = subject,
        directory = File(context.cacheDir, DIRECTORY),
    )
}
