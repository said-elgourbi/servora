package com.servora.android.data.jobs

import com.servora.android.domain.model.EvidencePhase
import com.servora.android.domain.model.PendingJobAudioNote
import java.io.File
import java.time.Clock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * An audio session that holds nothing, for instrumented tests about something else.
 *
 * The Job Details destination is wired by the application shell, so a navigation test has to build the
 * ViewModel that destination uses. These tests are about where navigation goes, not about audio evidence,
 * so every collaborator here answers the smallest honest thing: no pending recordings, no bytes, no
 * recorder and no uploads (`qa.md` §6.2). The outbox, the subject and the sync trigger are the same inert
 * ones the photo session defines, because they are not this feature's to invent.
 */
fun inertJobAudioSession(): JobAudioSession = JobAudioSession(
    pending = InertPendingJobAudioNoteStore,
    files = InertJobAudioFiles,
    recorder = InertJobAudioRecorder,
    outbox = InertOutboxStore,
    subject = InertSubject,
    offlineSync = InertOfflineSync,
    clock = Clock.systemUTC(),
)

private object InertPendingJobAudioNoteStore : PendingJobAudioNoteStore {
    override fun pending(subjectId: String, jobId: String): Flow<List<PendingJobAudioNote>> =
        flowOf(emptyList())

    override suspend fun record(note: PendingJobAudioNote) = Unit

    override suspend fun updateReview(audioNoteId: String, phase: EvidencePhase?, note: String?) = Unit

    override suspend fun submit(note: PendingJobAudioNote): Boolean = false

    override suspend fun find(audioNoteId: String): PendingJobAudioNote? = null

    override suspend fun remove(audioNoteId: String) = Unit
}

private object InertJobAudioFiles : JobAudioFiles {
    override fun fileFor(subjectId: String, audioNoteId: String): File =
        File("app-private/job-audio/$subjectId/$audioNoteId.$JOB_AUDIO_FILE_EXTENSION")

    override fun exists(path: String): Boolean = false

    override fun byteSize(path: String): Long? = null

    override fun readBytes(path: String): ByteArray? = null

    override fun delete(path: String) = Unit
}

private object InertJobAudioRecorder : JobAudioRecorder {
    override fun start(path: String): Boolean = false

    override fun stop(): Boolean = false

    override fun release() = Unit
}
