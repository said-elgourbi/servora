package com.servora.android.data.jobs

import com.servora.android.data.offline.OutboxOperationState
import com.servora.android.data.offline.OutboxStore
import com.servora.android.data.offline.OfflineSync
import com.servora.android.data.session.AuthenticatedSubject
import com.servora.android.domain.model.CapturedJobAudioNote
import com.servora.android.domain.model.EvidencePhase
import com.servora.android.domain.model.JobAudioSyncState
import com.servora.android.domain.model.PendingJobAudioNote
import com.servora.android.domain.model.isDiscardable
import com.servora.android.domain.model.isRefusedUpload
import java.time.Clock
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

/**
 * One technician's audio work on one Job (`BR-091`, `ADR-018`, offline standard §9).
 *
 * It is the feature's half of the offline contract: the device's recorder writes the bytes to
 * app-private storage, this records the durable reference that survives process death, the upload is
 * queued through the **existing** outbox, and the **existing** sync trigger is asked to replay it
 * (§4, §13). It decides no business rule of its own — whether a recording is accepted is the API's
 * answer (`BR-001`, `BR-007`).
 *
 * **The draft is one per Job and session.** The sheet adds one update at a time, and the design's own
 * flow is record → review → delete/re-record → attach, so a second recording is a re-record after the
 * first is deleted rather than several drafts behind one update. That is this phase's answer to
 * tracker 035's open question 3, taken from the sheet's own draft-state rule (`BR-091`) instead of
 * inventing a second policy; the recording is nevertheless a durable row, so an unattached one
 * survives a process death (`BR-014`).
 *
 * Local state is scoped by the authenticated subject throughout: a recording belongs to the session
 * that made it and is never shown, uploaded or deleted by another (§10).
 */
@Singleton
class JobAudioSession @Inject constructor(
    private val pending: PendingJobAudioNoteStore,
    private val files: JobAudioFiles,
    private val recorder: JobAudioRecorder,
    private val outbox: OutboxStore,
    private val subject: AuthenticatedSubject,
    private val offlineSync: OfflineSync,
    private val clock: Clock,
) {

    /** The Job's not-yet-accepted recordings, or `null` when no session can own them (§10). */
    fun pendingNotes(jobId: String): Flow<List<PendingJobAudioNote>>? =
        subject.current()?.let { pending.pending(it, jobId) }

    /**
     * Allocates the identity and the app-private file one recording will be written to.
     *
     * The id is generated **before** the recorder starts and never changes, so the recording, its file
     * and its idempotency key are the same value from the first attempt onwards (`§5`), and the instant
     * it began is kept as provenance (`BR-031`).
     */
    fun beginRecording(): CapturedJobAudioNote? {
        val subjectId = subject.current() ?: return null
        val audioNoteId = UUID.randomUUID().toString()
        return CapturedJobAudioNote(
            audioNoteId = audioNoteId,
            localPath = files.fileFor(subjectId, audioNoteId).absolutePath,
            startedAt = clock.instant().toString(),
        )
    }

    /** Starts the device's recorder on a capture the screen just allocated (`ADR-018` A2). */
    fun startRecording(capture: CapturedJobAudioNote): Boolean = recorder.start(capture.localPath)

    /**
     * Records the recording the recorder just finished, or reports the reason none was recorded.
     *
     * Nothing is recorded for a recorder that could not finish or for a file with no bytes in it: a
     * draft the device cannot upload would be work the technician believes is safe (`BR-014`, `BR-042`).
     * A refusal deletes the file, exactly as a refused photo discards the bytes it came from.
     */
    suspend fun stopRecording(
        jobId: String,
        capture: CapturedJobAudioNote,
        phase: EvidencePhase?,
        durationSeconds: Int,
    ): JobAudioRecordResult {
        val finished = recorder.stop()
        val size = files.byteSize(capture.localPath)
        if (!finished || size == null || size <= 0L) {
            files.delete(capture.localPath)
            return JobAudioRecordResult.Refused(
                if (finished) JobAudioRefusal.NO_BYTES else JobAudioRefusal.NOT_RECORDED,
            )
        }
        if (subject.current() == null) {
            files.delete(capture.localPath)
            return JobAudioRecordResult.Refused(JobAudioRefusal.NOT_SIGNED_IN)
        }
        val recorded = PendingJobAudioNote(
            audioNoteId = capture.audioNoteId,
            jobId = jobId,
            localPath = capture.localPath,
            phase = phase,
            // The technician's note is written when they attach the take, from the sheet that reviewed
            // it: at the moment the recorder stops, nothing has been said about it yet (`BR-091`).
            note = null,
            capturedAt = capture.startedAt,
            durationSeconds = durationSeconds,
            mimeType = JOB_AUDIO_MIME_TYPE,
            recordedAt = clock.millis(),
            submitted = false,
        )
        return try {
            pending.record(recorded)
            JobAudioRecordResult.Recorded(recorded)
        } catch (failure: Exception) {
            // The row is the only thing that finds the bytes again, so bytes without a row are deleted
            // rather than left on the device for good (`BR-014`).
            files.delete(capture.localPath)
            JobAudioRecordResult.Refused(JobAudioRefusal.NOT_STORED)
        }
    }

    /**
     * Records the phase and note the technician chose, and answers the draft as it now stands.
     *
     * It answers the stored record rather than nothing, because what is attached next must be what is
     * stored: a queued operation carrying a value the row does not hold would upload something other than
     * what the technician reviewed (`BR-041`, `BR-091`).
     */
    suspend fun review(
        audioNoteId: String,
        phase: EvidencePhase?,
        note: String?,
    ): PendingJobAudioNote? {
        pending.updateReview(audioNoteId, phase, note)
        return pending.find(audioNoteId)
    }

    /**
     * Removes a recording the technician decided not to keep, and its bytes with it.
     *
     * An unattached recording is theirs to remove. So is one whose upload the backend **permanently
     * refused**: that upload is finished — it is never replayed — and what is left is the technician's
     * own draft rather than evidence the API might hold (`BR-014`, `BR-031`).
     *
     * A queued or retrying upload is **not** removable: the backend may still accept it, so removing it
     * would be a local decision about evidence that may already exist (`BR-014`, `BR-091`).
     *
     * A discarded refusal leaves nothing behind: its queue row goes with the file, so no row is left
     * pointing at bytes that are gone and nothing can replay what the technician finished with (§9).
     */
    suspend fun discard(note: PendingJobAudioNote): Boolean {
        val upload = uploadState(note)
        if (!note.isDiscardable(upload)) {
            return false
        }
        files.delete(note.localPath)
        if (upload == JobAudioSyncState.REFUSED) {
            outbox.discardRefused(note.audioNoteId)
        }
        pending.remove(note.audioNoteId)
        return true
    }

    /**
     * Releases a recording that never became a draft: the technician left the sheet, or the recorder
     * could not start.
     *
     * The recorder is released first — a microphone held by a recording nobody is watching is a
     * resource the rest of the device cannot use — and any file it may have created goes with it, so
     * nothing is left behind that no row points at (`BR-042`, §9).
     */
    fun abandonRecording(capture: CapturedJobAudioNote) {
        recorder.release()
        files.delete(capture.localPath)
    }

    /** Queues every recording the technician has not attached yet, then asks for a replay (§6). */
    suspend fun submit(notes: List<PendingJobAudioNote>): Int {
        var queued = 0
        for (note in notes.filterNot { it.submitted }) {
            if (pending.submit(note)) {
                queued += 1
            }
        }
        if (queued > 0) {
            // A replay may need several round trips, so it is asked for and not waited on: the run
            // belongs to the existing trigger (§6).
            offlineSync.requestSync()
        }
        return queued
    }

    /** How far each of the Job's queued uploads has got, derived from the queue itself (§7). */
    suspend fun uploadStates(jobId: String): Map<String, JobAudioSyncState> {
        val subjectId = subject.current() ?: return emptyMap()
        return outbox.queuedFor(subjectId, jobId)
            .filter { it.operationType == JobAudioOperations.ADD_AUDIO }
            .associate { operation ->
                operation.operationId to operation.state.toSyncState()
            }
    }

    /**
     * How far this recording's own upload has got, or `null` when nothing is queued for it (§7).
     *
     * The queue row is what decides whether a recording the technician attached is finished — a refused
     * upload may be discarded, a waiting one may not.
     */
    private suspend fun uploadState(note: PendingJobAudioNote): JobAudioSyncState? =
        uploadStates(note.jobId)[note.audioNoteId]
}

/** Presents a queue state as the upload state the notice shows (§6, §7). */
private fun OutboxOperationState.toSyncState(): JobAudioSyncState =
    when (this) {
        OutboxOperationState.PENDING -> JobAudioSyncState.QUEUED
        OutboxOperationState.IN_FLIGHT -> JobAudioSyncState.UPLOADING
        OutboxOperationState.FAILED -> JobAudioSyncState.RETRYING
        OutboxOperationState.REJECTED -> JobAudioSyncState.REFUSED
    }
