package com.servora.android.ui.jobs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.servora.android.data.jobs.AssignableTechniciansResult
import com.servora.android.data.jobs.JobActionFailure
import com.servora.android.data.jobs.JobActionResult
import com.servora.android.data.jobs.JobActivityResult
import com.servora.android.data.jobs.JobAudioEvidenceCache
import com.servora.android.data.jobs.JobAudioEvidenceRead
import com.servora.android.data.jobs.JobAudioPlayer
import com.servora.android.data.jobs.JobAudioPlaybackProgress
import com.servora.android.data.jobs.JobAudioRecordResult
import com.servora.android.data.jobs.JobAudioRefusal
import com.servora.android.data.jobs.JobAudioSession
import com.servora.android.data.jobs.JobDetailsRepository
import com.servora.android.data.jobs.JobDetailsResult
import com.servora.android.data.jobs.JobPhotoExportOutcome
import com.servora.android.data.jobs.JobPhotoExportSource
import com.servora.android.data.jobs.JobPhotoExporter
import com.servora.android.data.jobs.JobPhotoImages
import com.servora.android.data.jobs.JobPhotoPickedItems
import com.servora.android.data.jobs.JobPhotoRecordResult
import com.servora.android.data.jobs.JobPhotoRefusal
import com.servora.android.data.jobs.JobPhotoSession
import com.servora.android.data.jobs.ActivityWriteResult
import com.servora.android.data.jobs.VisitNote
import com.servora.android.data.jobs.VisitStatusChange
import com.servora.android.data.offline.ReadSource
import com.servora.android.domain.model.CapturedJobPhoto
import com.servora.android.domain.model.EvidencePhase
import com.servora.android.domain.model.JobActivityKind
import com.servora.android.domain.model.JobStatus
import com.servora.android.domain.model.PendingJobAudioNote
import com.servora.android.domain.model.PendingJobPhoto
import com.servora.android.domain.model.ScheduleConflict
import com.servora.android.domain.model.TechnicianAssignment
import com.servora.android.domain.model.VisitOutcome
import com.servora.android.domain.model.VisitStatus
import com.servora.android.domain.model.isRefusedUpload
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Clock
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Drives the Job Details screen.
 *
 * The ViewModel decides nothing about the Job: it asks [JobDetailsRepository] for one Job and reports
 * what came back. Which Visit represents the Job, which technicians are assigned to it, which of them
 * is the Lead, which statuses the Job may move to and whether the Visit may be rescheduled are the
 * backend's answers (`BR-001`, `BR-058`, `BR-068`, `BR-073`, `BR-081`).
 *
 * Every management action is an explicit action the user asks for, sent with the version the screen
 * last saw so the API can refuse it against newer state rather than overwriting it (`BR-086`). An
 * action the API refuses because of an availability conflict is **not** a failure: it is held in
 * [JobDetailsUiState.pendingConfirmation] so the user can be shown the conflict and the same action
 * can be resent once accepted (`BR-070`).
 *
 * An action that applies also changes the history Job Activity projects — a status change, a schedule
 * change or a crew change (`BR-069`, `BR-073`, `BR-074`). The action's own answer is the Job rather
 * than the timeline, so once an action succeeds the timeline is read again instead of being assembled
 * from that answer (`BR-001`, `BR-080`). Adding a text update needs no second read: that write answers
 * with the refreshed timeline itself. A photo is written by the outbox rather than by an action on
 * this screen, so its upload being accepted — the moment its row leaves the tray — is what reads the
 * timeline again (`BR-031`). A photo comes from the device's camera or from the device's own photo
 * picker (`D3`); the picker's items are taken one at a time, each becoming its own draft, so a photo
 * that cannot be taken is reported while the rest are recorded (`BR-014`).
 *
 * An **audio note** is the same shape for evidence of its own kind (`BR-091`, `ADR-018`): the device
 * records it into app-private storage, this holds the durable draft a process death cannot lose, and
 * the upload is queued through the outbox when the technician attaches it — so a recording made in a
 * basement uploads when the connection comes back (`BR-014`, §9). One recording is held at a time: the
 * sheet adds one update at a time, so a re-record is a delete and a new take (`JobAudioSession`).
 *
 * **Playback of an audio note** is this screen's too (`ADR-018` A9, A11): one player serves the timeline
 * and the sheet's review, which one recording is playing and whether it is moving is the device's own
 * answer, and where it has got to is read apart from the screen's state so a moving playhead does not
 * recompose the screen.
 *
 * The two **reads** follow the offline standard (`offline-first-architecture.md` §12, §13): the Job and
 * its activity are kept as the last answer the backend reported and re-read from there when the API
 * cannot be reached, so the evidence a Job holds — which photos, with their phase, note and time — and
 * the Activity around it stay readable without connectivity (`D5`, `BR-013`). The state says which
 * answer it is holding, so the screen presents a local copy as the last reported answer rather than as
 * a current one (`§7`). An **action** stays online-only: the backend's answer is the only outcome
 * reported (`BR-001`).
 */
@HiltViewModel
class JobDetailsViewModel @Inject constructor(
    private val repository: JobDetailsRepository,
    private val photos: JobPhotoSession,
    private val audio: JobAudioSession,
    private val jobPhotoImages: JobPhotoImages,
    private val pickedItems: JobPhotoPickedItems,
    private val exporter: JobPhotoExporter,
    private val audioEvidence: JobAudioEvidenceCache,
    private val audioPlayer: JobAudioPlayer,
    private val clock: Clock,
) : ViewModel() {

    private val _uiState = MutableStateFlow(JobDetailsUiState())
    val uiState: StateFlow<JobDetailsUiState> = _uiState.asStateFlow()

    /**
     * The loader the screen draws photo thumbnails through (`BR-015`).
     *
     * It is handed to the screen rather than resolved there, so a preview or a UI test can draw the
     * tiles without a session or an object store (`qa.md` §6.2).
     */
    val photoImages: JobPhotoImages get() = jobPhotoImages

    private var readInFlight = false
    private var actionInFlight = false
    private var photoInFlight = false
    /** Whether a photo is being saved or shared right now, so one export runs at a time. */
    private var exportInFlight = false
    /** Whether accepted evidence is being removed right now, so one removal runs at a time. */
    private var removalInFlight = false
    private var photoCollection: Job? = null

    /** Whether the microphone is being started or a recording is being stopped right now. */
    private var audioInFlight = false

    /** Whether an accepted recording's bytes are being read before it can play (`ADR-018` A9). */
    private var playbackInFlight = false

    /**
     * Where the recording the device's player holds has got to (`ADR-018` A11).
     *
     * It is deliberately **not** part of [uiState]: it changes ten times a second while a recording
     * plays, and [uiState] is what Job Details, the timeline and every entry around the recording are
     * composed from. The playhead and the elapsed seconds read this answer instead, so a moving position
     * costs a moving playhead rather than a recomposed screen (`BR-012`).
     */
    private val _audioPlaybackProgress = MutableStateFlow<JobAudioPlaybackProgress?>(null)
    val audioPlaybackProgress: StateFlow<JobAudioPlaybackProgress?> =
        _audioPlaybackProgress.asStateFlow()

    init {
        // The device player's own answer is the playback state (`ADR-018` A9): the screen draws which
        // recording the player holds, and whether it is moving, from it rather than from a local guess, so
        // a recording that ends, fails or is replaced returns its own control to its idle state
        // (`BR-042`).
        viewModelScope.launch {
            audioPlayer.playback.collect { playback ->
                _uiState.update { current -> current.copy(audioPlayback = playback) }
            }
        }
        // Where it has got to is collected apart from the screen's state, for the same reason (`A11`).
        viewModelScope.launch {
            audioPlayer.progress.collect { progress -> _audioPlaybackProgress.value = progress }
        }
        // A replay the backend accepted changed the Job, so the screen reads it again rather than
        // keeping a picture the applied action has moved past — and the queue that was presenting the
        // action as waiting stops doing so at the same moment (`§7`, `BR-001`).
        viewModelScope.launch {
            repository.appliedOperations.collect {
                val jobId = _uiState.value.jobId
                if (jobId.isNotEmpty()) {
                    reloadAfterSync(jobId)
                }
            }
        }
    }

    /** Follows the Job's pending recordings for as long as this Job is on screen (`§9`). */
    private var audioCollection: Job? = null

    /**
     * When the recording in progress began, as this device's clock reads it.
     *
     * It is what the length the review shows is measured from. The length is not a product fact: the
     * API reads the recording's own container and that is what it stores (`ADR-018` A3), so a device
     * that measures 8 seconds and a container that says 8.4 seconds disagree about nothing that
     * matters — and nothing here is ever sent as the length.
     */
    private var audioRecordingStartedAtMillis: Long = 0L

    /**
     * Recordings this screen removed locally, so the notice losing one is not read as the API accepting
     * its upload (`§9`).
     *
     * Only a **refused** recording is ever put here, the same rule a photo follows.
     */
    private val audioDiscardedLocally = mutableSetOf<String>()

    /**
     * Photos this screen removed locally, so the tray losing one of them is not read as the API
     * accepting its upload (`§9`, `D6c`).
     *
     * Only a **refused** photo is ever put here: it is the one removal the screen performs on a photo
     * that was submitted, and the backend holds nothing new when it goes.
     */
    private val discardedLocally = mutableSetOf<String>()

    /**
     * The photos of a pick this screen has still to take, in the order the technician chose them.
     *
     * A pick hands over several items at once and each becomes its own draft, but they are taken one
     * at a time so each gets the review the capture flow gives a photo (`D3`). The queue is the work
     * still to do, never a record: an item is not evidence until it has been read and recorded, and
     * the items left in the queue when the screen goes away were never claimed (`BR-014`).
     */
    private var pickedPhotos: ArrayDeque<PickedPhoto> = ArrayDeque()

    /**
     * The photos of a pick that could not be taken and have not been reported yet.
     *
     * They wait their turn rather than replacing one another, so every skipped photo is named to the
     * technician even when a pick skipped several (`D3`).
     */
    private val skippedPhotos: ArrayDeque<SkippedPhoto> = ArrayDeque()

    /**
     * Reads [jobId] unless this destination already holds it.
     *
     * The screen drives this from its destination's arguments, so recomposing the destination must not
     * start a second read of the same Job (`BR-001`). A Job the read failed for is read again, because
     * the user is asking to see it.
     */
    fun start(jobId: String) {
        if (readInFlight) {
            return
        }
        val current = _uiState.value
        val settled = current.jobId == jobId && current.details != null
        if (settled) {
            return
        }
        read(jobId)
    }

    /** Re-runs the read after a failure the screen reported. */
    fun retry() {
        if (readInFlight) {
            return
        }
        val current = _uiState.value
        if (current.jobId.isNotEmpty()) {
            read(current.jobId)
        }
    }

    /**
     * Releases the Job the screen held.
     *
     * Called when the app session ends, so a user who signs in next does not see the previous user's
     * record. The read is scoped to a session by the backend (`BR-001`, `BR-007`), so it must not
     * outlive that session in the UI either.
     */
    fun reset() {
        readInFlight = false
        actionInFlight = false
        // The pending photos belong to the session that captured them, so nothing of them outlives it
        // in the UI (§10).
        photoCollection?.cancel()
        photoCollection = null
        // The pending recordings belong to the session that made them, and a recording still running
        // is dropped rather than left with the microphone open (`BR-091`, §10).
        _uiState.value.audioRecording?.let { capture -> audio.abandonRecording(capture) }
        audioCollection?.cancel()
        audioCollection = null
        audioDiscardedLocally.clear()
        // Playback is device state rather than Job state: a recording stops with the session it was
        // started in rather than playing on over the next screen (`ADR-018` A9, §10).
        audioPlayer.stop()
        // A pick belongs to the Job it was made for: nothing of it is carried to the next session.
        pickedPhotos.clear()
        skippedPhotos.clear()
        _uiState.value = JobDetailsUiState()
    }

    /** Moves the Job to [status] (`BR-058`), with the optional note a reopen records (`BR-063`). */
    fun changeJobStatus(status: JobStatus, note: String? = null) {
        val details = _uiState.value.details ?: return
        submit(
            JobActionRequest.Status(
                jobId = details.id,
                status = status,
                note = note,
                jobVersion = details.version,
            ),
        )
    }

    /** Edits the represented Visit's schedule (`BR-073`). */
    fun rescheduleVisit(scheduledStart: Instant, scheduledEnd: Instant) {
        val details = _uiState.value.details ?: return
        val visit = details.selectedVisit ?: return
        submit(
            JobActionRequest.Reschedule(
                jobId = details.id,
                visitId = visit.id,
                scheduledStart = scheduledStart,
                scheduledEnd = scheduledEnd,
                visitVersion = visit.version,
            ),
        )
    }

    /** States the whole crew the represented Visit carries (`BR-068`, `BR-069`). */
    fun assignVisitTechnicians(assignments: List<TechnicianAssignment>) {
        val details = _uiState.value.details ?: return
        val visit = details.selectedVisit ?: return
        submit(
            JobActionRequest.Assignment(
                jobId = details.id,
                visitId = visit.id,
                assignments = assignments,
                visitVersion = visit.version,
            ),
        )
    }

    /**
     * Moves the represented Visit through its field lifecycle (`BR-074`), recording the outcome
     * `BR-077` requires when it is a completion.
     *
     * [confirmConflicts] is true only when the technician has been shown the `BR-070` conflicts the
     * API reported and accepted them. The request carries a fresh idempotency key and the device
     * instant, so it is applied at most once and can be queued rather than lost when the API cannot be
     * reached (`BR-031`, `BR-014`).
     */
    fun changeVisitStatus(
        status: VisitStatus,
        outcome: VisitOutcome? = null,
        outcomeSummary: String? = null,
    ) {
        val details = _uiState.value.details ?: return
        val visit = details.selectedVisit ?: return
        submit(
            JobActionRequest.VisitTransition(
                jobId = details.id,
                visitId = visit.id,
                status = status,
                outcome = outcome,
                outcomeSummary = outcomeSummary,
                visitVersion = visit.version,
                operationId = UUID.randomUUID().toString(),
                capturedAt = clock.instant(),
            ),
        )
    }

    /**
     * Discards a field action the backend **refused** once the technician has finished with it
     * (`BR-032`, `BR-014`).
     *
     * The action is read back from the queue afterwards, so what the screen presents as waiting is
     * what the queue actually holds. An action that may still apply is not removed by the queue, so a
     * discard can decide nothing that could still be applied.
     */
    fun discardQueuedVisitAction(operationId: String) {
        discardQueuedVisitWork(operationId)
    }

    /** The same for a refused note (`BR-032`, `BR-014`). */
    fun discardQueuedVisitNote(operationId: String) {
        discardQueuedVisitWork(operationId)
    }

    private fun discardQueuedVisitWork(operationId: String) {
        val jobId = _uiState.value.jobId
        if (jobId.isEmpty() || actionInFlight) {
            return
        }
        actionInFlight = true
        viewModelScope.launch {
            repository.discardQueuedVisitAction(jobId, operationId)
            val queuedAction = repository.queuedVisitAction(jobId)
            val queuedNotes = repository.queuedVisitNotes(jobId)
            actionInFlight = false
            _uiState.update { current ->
                if (current.jobId != jobId) {
                    current
                } else {
                    current.copy(
                        queuedVisitAction = queuedAction,
                        queuedVisitNotes = queuedNotes,
                    )
                }
            }
        }
    }

    /**
     * Adds one text-only update to the represented Visit's Activity (`BR-027`, `BR-013`).
     *
     * The update carries its own idempotency key and device instant, so the API cannot record it
     * twice and an update the API cannot be reached for is queued on the device rather than lost
     * (`BR-014`, `ADR-019` D5).
     */
    fun addActivityText(body: String) {
        val text = body.trim()
        if (text.isEmpty() || actionInFlight) {
            return
        }
        val details = _uiState.value.details ?: return
        val visit = details.selectedVisit ?: return

        actionInFlight = true
        _uiState.update { current ->
            current.copy(
                isSubmitting = true,
                completedAction = null,
                actionQueued = false,
                actionFailure = null,
            )
        }
        viewModelScope.launch {
            val result = repository.addVisitNoteRequest(
                VisitNote(
                    jobId = details.id,
                    visitId = visit.id,
                    body = text,
                    operationId = UUID.randomUUID().toString(),
                    capturedAt = clock.instant(),
                ),
            )
            actionInFlight = false
            val queued = if (result is ActivityWriteResult.Queued) {
                repository.queuedVisitNotes(details.id)
            } else {
                null
            }
            _uiState.update { current ->
                if (current.jobId != details.id) {
                    current
                } else {
                    when (result) {
                        is ActivityWriteResult.Success ->
                            current.copy(
                                isSubmitting = false,
                                activity = result.events,
                                // The write's own answer is the backend's, so the timeline is current
                                // again (`§7`).
                                activitySource = ReadSource.BACKEND,
                                activityFailure = null,
                                completedAction = JobActionKind.ACTIVITY_TEXT,
                                actionQueued = false,
                            )

                        // The API could not be reached, so the update is waiting rather than recorded:
                        // the timeline stays what the backend reported and the waiting note is shown
                        // from the queue (`§7`, `BR-080`).
                        ActivityWriteResult.Queued ->
                            current.copy(
                                isSubmitting = false,
                                queuedVisitNotes = queued ?: current.queuedVisitNotes,
                                completedAction = JobActionKind.ACTIVITY_TEXT,
                                actionQueued = true,
                                actionFailure = null,
                            )

                        is ActivityWriteResult.Failure ->
                            current.copy(
                                isSubmitting = false,
                                actionFailure = result.reason,
                                actionQueued = false,
                            )
                    }
                }
            }
        }
    }

    /**
     * Resends the action the API refused until its conflicts are accepted (`BR-070`).
     *
     * The same action is sent again with the conflicts confirmed, so the user decides about the
     * conflict they were shown rather than about one they were not.
     */
    fun confirmPendingAction() {
        val pending = _uiState.value.pendingConfirmation ?: return
        val details = _uiState.value.details ?: return
        val visit = details.selectedVisit ?: return
        when (pending) {
            is PendingJobAction.Reschedule ->
                submit(
                    JobActionRequest.Reschedule(
                        jobId = details.id,
                        visitId = visit.id,
                        scheduledStart = pending.scheduledStart,
                        scheduledEnd = pending.scheduledEnd,
                        visitVersion = visit.version,
                    ),
                    confirmed = true,
                )

            is PendingJobAction.Assign ->
                submit(
                    JobActionRequest.Assignment(
                        jobId = details.id,
                        visitId = visit.id,
                        assignments = pending.assignments,
                        visitVersion = visit.version,
                    ),
                    confirmed = true,
                )

            // The same operation is resent, with the same idempotency key, so accepting the conflicts
            // applies the transition once rather than twice (`BR-070`, `BR-031`).
            is PendingJobAction.VisitTransition ->
                submit(
                    JobActionRequest.VisitTransition(
                        jobId = details.id,
                        visitId = visit.id,
                        status = pending.status,
                        outcome = pending.outcome,
                        outcomeSummary = pending.outcomeSummary,
                        visitVersion = visit.version,
                        operationId = pending.operationId,
                        capturedAt = pending.capturedAt,
                    ),
                    confirmed = true,
                )
        }
    }

    /** Leaves the conflicts unaccepted: nothing was applied, so nothing is sent (`BR-070`). */
    fun dismissPendingAction() {
        _uiState.update { current -> current.copy(pendingConfirmation = null) }
    }

    /** Acknowledges the outcome the screen has reported, so it is not reported twice. */
    fun dismissActionMessage() {
        _uiState.update { current ->
            current.copy(completedAction = null, actionQueued = false, actionFailure = null)
        }
    }

    /** Reads the organization's technicians, for the assign sheet to offer (`BR-024`). */
    fun loadAssignableTechnicians() {
        _uiState.update { current ->
            current.copy(assignableTechnicians = null, assignableFailure = null)
        }
        viewModelScope.launch {
            when (val result = repository.loadAssignableTechnicians()) {
                is AssignableTechniciansResult.Success ->
                    _uiState.update { current ->
                        current.copy(
                            assignableTechnicians = result.technicians,
                            assignableFailure = null,
                        )
                    }

                is AssignableTechniciansResult.Failure ->
                    _uiState.update { current ->
                        current.copy(
                            assignableTechnicians = null,
                            assignableFailure = result.reason,
                        )
                    }
            }
        }
    }

    /** Sends one management action and reports what the backend answered. */
    private fun submit(request: JobActionRequest, confirmed: Boolean = false) {
        if (actionInFlight) {
            return
        }
        actionInFlight = true
        _uiState.update { current ->
            current.copy(
                isSubmitting = true,
                completedAction = null,
                actionQueued = false,
                actionFailure = null,
                pendingConfirmation = null,
            )
        }
        viewModelScope.launch {
            val result = request.send(repository, confirmed)
            actionInFlight = false
            // A queued action is presented from the queue, so what is waiting is read back rather than
            // assumed from the reply (`BR-041`, §7).
            val queuedAction = if (result is JobActionResult.Queued) {
                repository.queuedVisitAction(request.jobId)
            } else {
                null
            }
            _uiState.update { current ->
                if (current.jobId != request.jobId) {
                    current
                } else {
                    when (result) {
                        is JobActionResult.Success ->
                            current.copy(
                                isSubmitting = false,
                                details = result.details,
                                // An action's answer is the backend's own state, so what the screen now
                                // shows is current rather than the last answer it reported (`§7`).
                                detailsSource = ReadSource.BACKEND,
                                completedAction = request.kind,
                                actionQueued = false,
                                actionFailure = null,
                                pendingConfirmation = null,
                                // The action answered with the Job as it now stands, so the read failure
                                // it replaced is gone (`BR-001`).
                                failureReason = null,
                            )

                        // The API could not be reached, so the action is waiting rather than applied: the
                        // screen keeps the last state the backend reported and shows the action beside
                        // the Visit it will move (`BR-001`, §7).
                        is JobActionResult.Queued ->
                            current.copy(
                                isSubmitting = false,
                                details = result.details ?: current.details,
                                detailsSource = if (result.details != null) {
                                    ReadSource.WORKING_SET
                                } else {
                                    current.detailsSource
                                },
                                queuedVisitAction = queuedAction ?: current.queuedVisitAction,
                                completedAction = request.kind,
                                actionQueued = true,
                                actionFailure = null,
                                pendingConfirmation = null,
                            )

                        is JobActionResult.Conflicts -> {
                            val pending = request.pending(result.conflicts)
                            if (pending == null) {
                                // The API reported a conflict for an action that cannot have one. An
                                // answer this build cannot place is reported rather than presented
                                // (`BR-042`).
                                current.copy(
                                    isSubmitting = false,
                                    actionFailure = JobActionFailure.UNEXPECTED,
                                    actionQueued = false,
                                )
                            } else {
                                current.copy(
                                    isSubmitting = false,
                                    pendingConfirmation = pending,
                                    actionQueued = false,
                                )
                            }
                        }

                        is JobActionResult.Failure ->
                            current.copy(
                                isSubmitting = false,
                                actionFailure = result.reason,
                                actionQueued = false,
                            )
                    }
                }
            }
            if (result is JobActionResult.Success) {
                // The action answered with the Job, not with the timeline, and the history it just
                // recorded is what Job Activity projects (`BR-080`). The timeline is read again rather
                // than left showing what the Job looked like before the action (`BR-001`).
                readActivity(result.details.id)
            }
        }
    }

    /**
     * Allocates the identity and the app-private file a capture will be written to, or `null` when no
     * session can own the photo (`offline-first-architecture.md` §10).
     */
    fun beginPhotoCapture(): CapturedJobPhoto? {
        if (_uiState.value.details == null) {
            return null
        }
        val capture = photos.beginCapture()
        if (capture == null) {
            _uiState.update {
                it.copy(photoFailure = JobPhotoFailure.NOT_SIGNED_IN, photoFailureItem = null)
            }
        }
        return capture
    }

    /**
     * Records a photo the camera just wrote, before the technician has confirmed anything.
     *
     * It is recorded immediately so that a process that dies during the review does not lose the
     * capture (`BR-014`): the bytes are on disk and this record is what finds them again.
     */
    fun photoCaptured(capture: CapturedJobPhoto) {
        val details = _uiState.value.details ?: return
        if (photoInFlight) {
            return
        }
        photoInFlight = true
        viewModelScope.launch {
            val recorded = photos.recordCapture(
                capture = capture,
                jobId = details.id,
                phase = _uiState.value.photoPhase,
                capturedAt = clock.instant(),
            )
            photoInFlight = false
            _uiState.update { current ->
                when (recorded) {
                    is JobPhotoRecordResult.Refused ->
                        current.copy(
                            photoFailure = recorded.reason.toPhotoFailure(),
                            photoFailureItem = null,
                        )

                    is JobPhotoRecordResult.Recorded ->
                        // A screen that has moved to another Job keeps its own state: the photo
                        // belongs to the Job it was captured for (`BR-048`).
                        if (current.jobId != details.id) {
                            current
                        } else {
                            current.copy(
                                capturedPhoto = recorded.photo,
                                photoFailure = null,
                            )
                        }
                }
            }
        }
    }

    /**
     * Confirms the photo being reviewed.
     *
     * The phase the technician chose becomes the phase the **next** capture starts in, which is what
     * makes a run of photos of the same phase one tap each (`BR-012`).
     */
    fun confirmCapturedPhoto(phase: EvidencePhase, note: String?) {
        val captured = _uiState.value.capturedPhoto ?: return
        _uiState.update {
            it.copy(capturedPhoto = null, photoPhase = phase, photoFailure = null)
        }
        viewModelScope.launch { photos.reviewCapture(captured.photoId, phase, note) }
        continuePickedPhotos()
    }

    /** Discards the photo being reviewed: it was never submitted, so nothing else knows about it. */
    fun discardCapturedPhoto() {
        val captured = _uiState.value.capturedPhoto ?: return
        _uiState.update { it.copy(capturedPhoto = null, photoFailure = null) }
        viewModelScope.launch { photos.discard(captured) }
        continuePickedPhotos()
    }

    /**
     * Takes the photos the technician chose in the device's photo picker (`D3`).
     *
     * The pick hands over several items at once, and each is taken on its own — read, prepared,
     * written to app-private storage and recorded as its own draft with its own id — so one item that
     * cannot be read or prepared never stops the others, and the items the pick could not deliver are
     * reported rather than dropped (`BR-014`). A recorded photo opens the review the capture flow
     * already owns, where the phase and the optional note are the technician's (`BR-027`); the next
     * item follows once that review ends.
     *
     * An empty hand-over is a picker the technician dismissed: nothing was chosen, so nothing is
     * recorded and nothing is claimed (`BR-042`).
     */
    fun photosPicked(uris: List<String>) {
        val details = _uiState.value.details ?: return
        if (uris.isEmpty() || photoInFlight) {
            // A picker that handed nothing over chose nothing, and a pick that arrives while another
            // photo is being taken is not started on top of it (`BR-042`).
            return
        }
        val total = uris.size
        pickedPhotos = ArrayDeque(
            uris.mapIndexed { index, uri -> PickedPhoto(uri, PhotoItemPosition(index + 1, total)) },
        )
        takeNextPickedPhoto(details.id)
    }

    /** Reports a device that could not open its own photo picker, so no photo could be chosen. */
    fun photoPickerUnavailable() {
        _uiState.update {
            it.copy(
                photoFailure = JobPhotoFailure.PICKER_UNAVAILABLE,
                photoFailureItem = null,
            )
        }
    }

    /**
     * Reads the next photo of the pick and records it, or does nothing when the pick is done.
     *
     * The bytes are read here rather than kept from the picker's answer, so a large original is read
     * and prepared one item at a time and nothing is held in memory between reviews (`D3`, `D3c`).
     *
     * Items are taken until one is recorded: a photo this device cannot read or prepare is reported
     * by its place in the pick and the pass goes on, because one item that cannot be taken must never
     * stop the others (`D3`, `BR-014`). The recorded item opens its review, and the photos after it
     * stay queued until that review ends.
     *
     * It is reached only when no other photo work is in flight: a pick starts one only when it is not
     * (`photosPicked`), and a review ends only after the pass that opened it has finished.
     */
    private fun takeNextPickedPhoto(jobId: String) {
        photoInFlight = true
        viewModelScope.launch {
            while (true) {
                val next = pickedPhotos.removeFirstOrNull() ?: break
                val source = pickedItems.bytes(next.uri)
                val recorded = if (source == null) {
                    // The item handed over nothing this device could read, so no draft is written for
                    // it and the item is reported (`BR-014`).
                    JobPhotoRecordResult.Refused(JobPhotoRefusal.NOT_READ)
                } else {
                    // The draft carries the phase in effect, exactly as a capture's does, so a photo
                    // the technician does not review still carries a phase the API accepts
                    // (`BR-027`).
                    photos.recordPickedPhoto(
                        jobId = jobId,
                        source = source,
                        phase = _uiState.value.photoPhase,
                    )
                }
                when (recorded) {
                    is JobPhotoRecordResult.Refused ->
                        reportSkippedPhoto(jobId, next.position, recorded.reason)

                    is JobPhotoRecordResult.Recorded -> {
                        _uiState.update { current ->
                            // A pick belongs to the Job it was made for: a screen that has moved on
                            // keeps its own state (`BR-048`).
                            if (current.jobId != jobId) {
                                current
                            } else {
                                current.copy(capturedPhoto = recorded.photo)
                            }
                        }
                        break
                    }
                }
            }
            photoInFlight = false
        }
    }

    /** Moves a pick on to its next photo once the technician is done with the one under review. */
    private fun continuePickedPhotos() {
        val jobId = _uiState.value.jobId
        if (jobId.isNotEmpty() && pickedPhotos.isNotEmpty()) {
            takeNextPickedPhoto(jobId)
        }
    }

    /**
     * Records one photo the pick could not take.
     *
     * They are held in order rather than overwritten, so a pick that skipped more than one photo names
     * every one of them: the report waits for the technician, and the next one follows the moment the
     * screen has shown this one (`D3`, `BR-012`).
     */
    private fun reportSkippedPhoto(
        jobId: String,
        position: PhotoItemPosition,
        refusal: JobPhotoRefusal,
    ) {
        if (_uiState.value.jobId != jobId) {
            return
        }
        skippedPhotos.addLast(SkippedPhoto(position, refusal.toPhotoFailure()))
        showSkippedPhoto()
    }

    /** Presents the next skipped photo, or clears the report when the pick has none left. */
    private fun showSkippedPhoto() {
        val next = skippedPhotos.firstOrNull()
        _uiState.update {
            it.copy(photoFailure = next?.failure, photoFailureItem = next?.position)
        }
    }

    /**
     * Cleans up after a camera that produced no photo.
     *
     * A dismissed camera may still have created a file; nothing was recorded for it, so the file is
     * removed rather than left behind (`§9`).
     */
    fun photoCaptureCancelled(capture: CapturedJobPhoto?) {
        val abandoned = capture ?: return
        viewModelScope.launch { photos.abandonCapture(abandoned) }
    }

    /**
     * Closes the review without changing the photo.
     *
     * The photo was recorded when the camera returned, so closing the panel keeps it — with the phase
     * it was recorded with — rather than discarding it (`BR-014`). Discarding is the explicit action.
     */
    fun keepCapturedPhoto() {
        _uiState.update { it.copy(capturedPhoto = null, photoFailure = null) }
        continuePickedPhotos()
    }

    /**
     * Takes accepted evidence out of ordinary use, recording why (`BR-088`, `BR-089`).
     *
     * Only evidence the backend holds can be removed: a photo that has not been submitted is the
     * technician's own draft and is discarded on the device instead (`BR-088`), which needs no
     * capability and no API. The removal itself is a Manager action on a recorded photo, so it is
     * **online-only** — the backend's answer is the only outcome reported (`BR-001`), and a device that
     * cannot reach the API says so rather than queueing the decision (`offline-first-architecture.md`
     * §5, §8, §13.2).
     *
     * The reason is required, and the dialog asks for it before this is called: a removal records the
     * actor, the instant and the reason, so a blank one is not sent (`BR-089`).
     */
    fun removeEvidencePhoto(photoId: String, reason: String) {
        val text = reason.trim()
        val jobId = _uiState.value.jobId
        if (text.isEmpty() || jobId.isEmpty() || removalInFlight) {
            return
        }
        val photo = viewedJobPhoto(
            jobId = jobId,
            photoId = photoId,
            pendingPhotos = _uiState.value.pendingPhotos,
            activity = _uiState.value.activity,
        )
        // A photo neither the device nor the Activity reports is not evidence this screen can act on,
        // and a pending one is not evidence at all yet (`BR-042`, `BR-088`).
        if (photo !is ViewedJobPhoto.Stored) {
            reportUnreadablePhoto()
            return
        }
        removalInFlight = true
        // The action says it is running as soon as it is asked for, so the viewer draws its own progress
        // on the control that started it (`BR-042`).
        _uiState.update {
            it.copy(photoRemoval = photoId, photoFailure = null, photoMessage = null)
        }
        viewModelScope.launch {
            val result = repository.removeJobPhoto(jobId, photoId, text)
            removalInFlight = false
            _uiState.update { current ->
                when (result) {
                    is ActivityWriteResult.Success ->
                        current.copy(
                            photoRemoval = null,
                            // The write's own answer is the backend's, so the timeline — and with it the
                            // evidence a Job holds — is current again (`§7`, `BR-080`).
                            activity = result.events,
                            activitySource = ReadSource.BACKEND,
                            activityFailure = null,
                            photoFailure = null,
                            photoMessage = JobPhotoMessage.EVIDENCE_REMOVED,
                        )

                    // A removal is online-only: its route takes no idempotency key, so it is never
                    // queued (`BR-089`, `offline-first-architecture.md` §5, §13.2). The branch keeps
                    // the mapping total without inventing a meaning for an answer this operation
                    // cannot produce (`BR-042`).
                    ActivityWriteResult.Queued ->
                        current.copy(
                            photoRemoval = null,
                            photoFailure = JobPhotoFailure.REMOVAL_FAILED,
                        )

                    is ActivityWriteResult.Failure ->
                        current.copy(
                            photoRemoval = null,
                            photoFailure = result.reason.toRemovalFailure(),
                        )
                }
            }
        }
    }

    /**
     * Removes a photo the technician no longer wants from this device.
     *
     * A photo that has not been submitted is theirs to remove, and so is one whose upload the backend
     * permanently refused: that upload is finished, so nothing is being decided about evidence the API
     * might hold (`D6c`, `BR-014`). A queued or retrying upload is refused by the session and reported
     * instead, because the backend may still accept it (`BR-014`, `BR-027`).
     */
    fun removePendingPhoto(photoId: String) {
        val photo = _uiState.value.pendingPhotos.firstOrNull { it.photoId == photoId } ?: return
        // A refused photo the technician is about to discard is remembered **before** its row leaves,
        // so the tray losing it is not read as the API accepting the upload (`D6c`, §9). Nothing else
        // is remembered: a queued photo cannot be removed, and it must still be read as an acceptance
        // if it leaves the tray later.
        if (photo.isRefusedUpload(_uiState.value.photoUploads[photoId])) {
            discardedLocally += photoId
        }
        viewModelScope.launch {
            val removed = photos.discard(photo)
            _uiState.update { current ->
                current.copy(
                    photoFailure = if (removed) null else JobPhotoFailure.ALREADY_SUBMITTED,
                    photoFailureItem = null,
                )
            }
        }
    }

    /**
     * Saves one of the Job's photos on this device, into the gallery the user's own applications show
     * (`D12`).
     *
     * The bytes are read from wherever they are — the file this device holds, or the API — so the photo
     * that is saved is exactly the evidence the technician is looking at, and one whose bytes cannot be
     * read writes nothing and is reported (`BR-042`). On Android 8–9 the write needs a storage
     * permission: the screen is told which photo is waiting so it can ask, and the same save is retried
     * when it is granted.
     */
    fun savePhotoToDevice(photoId: String) {
        val source = photoExportSource(photoId)
        if (source == null) {
            // The Job no longer holds that photo — a row the tray has since dropped, or an Activity the
            // backend has re-reported — so nothing is written and the technician is told rather than
            // left with a tap that appears to do nothing (`BR-042`).
            reportUnreadablePhoto()
            return
        }
        if (exportInFlight) {
            return
        }
        exportInFlight = true
        // The action says it is running as soon as it is asked for: the viewer draws this as its own
        // progress, so a save that takes a moment does not read as a tap that did nothing (`BR-042`).
        _uiState.update { it.copy(photoExport = JobPhotoExportAction.SAVE) }
        viewModelScope.launch {
            val outcome = exporter.saveToDevice(source)
            exportInFlight = false
            _uiState.update { current ->
                val reported = current.copy(photoExport = null)
                when (outcome) {
                    JobPhotoExportOutcome.SAVED ->
                        reported.copy(
                            photoMessage = JobPhotoMessage.SAVED_TO_DEVICE,
                            photoFailure = null,
                            photoSaveAwaitingPermission = null,
                        )

                    JobPhotoExportOutcome.PERMISSION_REQUIRED ->
                        reported.copy(photoFailure = null, photoSaveAwaitingPermission = photoId)

                    JobPhotoExportOutcome.UNREADABLE ->
                        reported.copy(
                            photoFailure = JobPhotoFailure.EXPORT_UNREADABLE,
                            photoFailureItem = null,
                        )

                    // A save can only reach the two outcomes above; anything else is the device
                    // refusing the write, which is reported as such rather than assumed to have worked.
                    JobPhotoExportOutcome.FAILED,
                    JobPhotoExportOutcome.SHARED,
                    JobPhotoExportOutcome.NO_SHARE_TARGET,
                    ->
                        reported.copy(
                            photoFailure = JobPhotoFailure.EXPORT_FAILED,
                            photoFailureItem = null,
                        )
                }
            }
        }
    }

    /**
     * Hands one of the Job's photos to another application, through the platform's own share sheet
     * (`D13`).
     *
     * [chooserTitle] is the localized title the chooser shows; the screen resolves it, so no
     * user-facing text is built here (`BR-028`). Nothing is claimed about the receiving application:
     * the chooser is the platform's, and WhatsApp or anything else on the device is the user's choice.
     */
    fun sharePhoto(photoId: String, chooserTitle: String) {
        val source = photoExportSource(photoId)
        if (source == null) {
            reportUnreadablePhoto()
            return
        }
        if (exportInFlight) {
            return
        }
        exportInFlight = true
        // As with a save, the action is reported as running until it lands (`D13`, `BR-042`).
        _uiState.update { it.copy(photoExport = JobPhotoExportAction.SHARE) }
        viewModelScope.launch {
            val outcome = exporter.share(source, chooserTitle)
            exportInFlight = false
            _uiState.update { current ->
                val reported = current.copy(photoExport = null)
                when (outcome) {
                    JobPhotoExportOutcome.SHARED ->
                        reported.copy(photoMessage = JobPhotoMessage.SHARED, photoFailure = null)

                    JobPhotoExportOutcome.UNREADABLE ->
                        reported.copy(
                            photoFailure = JobPhotoFailure.EXPORT_UNREADABLE,
                            photoFailureItem = null,
                        )

                    JobPhotoExportOutcome.NO_SHARE_TARGET ->
                        reported.copy(
                            photoFailure = JobPhotoFailure.SHARE_UNAVAILABLE,
                            photoFailureItem = null,
                        )

                    // A share can only reach the three outcomes above; anything else is the device
                    // refusing the write or the chooser, reported rather than assumed to have worked.
                    JobPhotoExportOutcome.FAILED,
                    JobPhotoExportOutcome.SAVED,
                    JobPhotoExportOutcome.PERMISSION_REQUIRED,
                    ->
                        reported.copy(
                            photoFailure = JobPhotoFailure.EXPORT_FAILED,
                            photoFailureItem = null,
                        )
                }
            }
        }
    }

    /**
     * Answers the screen's storage-permission request for a save that was waiting on it (`D12`).
     *
     * A granted permission retries exactly the save that was waiting, so the technician's action
     * completes without them having to find the photo again; a declined one is reported rather than
     * left looking as though nothing happened, and the photo is untouched.
     */
    fun onSavePermissionResult(granted: Boolean) {
        val photoId = _uiState.value.photoSaveAwaitingPermission ?: return
        _uiState.update { it.copy(photoSaveAwaitingPermission = null) }
        if (granted) {
            savePhotoToDevice(photoId)
        } else {
            _uiState.update {
                it.copy(photoFailure = JobPhotoFailure.SAVE_PERMISSION_DENIED, photoFailureItem = null)
            }
        }
    }

    /**
     * Where one of the Job's photos is read from for an export, or `null` when the Job no longer holds
     * it: a photo neither the device nor the Activity reports is not written anywhere (`BR-042`).
     */
    private fun photoExportSource(photoId: String): JobPhotoExportSource? {
        val state = _uiState.value
        val photo = viewedJobPhoto(
            jobId = state.jobId,
            photoId = photoId,
            pendingPhotos = state.pendingPhotos,
            activity = state.activity,
        )
        return when (photo) {
            // The device's own bytes while it still holds them (`§9`).
            is ViewedJobPhoto.Pending ->
                JobPhotoExportSource.Local(photoId = photo.photoId, path = photo.localPath)

            // Evidence the backend holds, read through the API (`BR-015`).
            is ViewedJobPhoto.Stored ->
                JobPhotoExportSource.Evidence(photoId = photo.photoId, jobId = photo.jobId)

            null -> null
        }
    }

    /** Reports a photo neither the device nor the Activity can place any more (`BR-042`). */
    private fun reportUnreadablePhoto() {
        _uiState.update {
            it.copy(photoFailure = JobPhotoFailure.EXPORT_UNREADABLE, photoFailureItem = null)
        }
    }

    /** Queues the tray's photos for upload and asks for a replay (`§6`). */
    fun submitPendingPhotos() {
        val jobId = _uiState.value.jobId
        val unsubmitted = _uiState.value.pendingPhotos.filterNot { it.submitted }
        if (jobId.isEmpty() || unsubmitted.isEmpty() || photoInFlight) {
            return
        }
        photoInFlight = true
        _uiState.update {
            it.copy(isSubmittingPhotos = true, photoFailure = null, photoMessage = null)
        }
        viewModelScope.launch {
            val queued = photos.submit(unsubmitted)
            photoInFlight = false
            _uiState.update { current ->
                current.copy(
                    isSubmittingPhotos = false,
                    photoMessage = if (queued > 0) JobPhotoMessage.QUEUED else null,
                    photoFailure = if (queued > 0) null else JobPhotoFailure.NOT_QUEUED,
                    photoFailureItem = null,
                )
            }
            refreshPhotoUploads(jobId)
        }
    }

    /** Releases the last photo report once the screen has shown it. */
    fun dismissPhotoMessage() {
        // A pick may have skipped several photos, so the next report followed the one the screen has
        // just shown, and the slot is cleared only when nothing is left to report (`D3`).
        if (skippedPhotos.isNotEmpty()) {
            skippedPhotos.removeFirst()
        }
        _uiState.update { it.copy(photoMessage = null) }
        showSkippedPhoto()
    }

    /**
     * Follows the Job's pending photos for as long as this Job is on screen (`§9`).
     *
     * The list is the device's own state, so it is observed rather than re-read: an upload the API
     * accepted removes its row, and the tray follows without the screen asking (`BR-001`).
     */
    private fun observePhotos(jobId: String) {
        photoCollection?.cancel()
        photoCollection = null
        val flow = photos.pendingPhotos(jobId) ?: return
        photoCollection = viewModelScope.launch {
            // The tray as it was before this emission, so a photo that leaves it can be told apart
            // from one that was never there (`§9`).
            var previous: List<PendingJobPhoto>? = null
            flow.collect { pending ->
                _uiState.update { current ->
                    if (current.jobId == jobId) {
                        current.copy(pendingPhotos = pending)
                    } else {
                        current
                    }
                }
                val accepted = anUploadWasAccepted(previous, pending)
                previous = pending
                // A removal this screen performed is remembered only until the tray has reported it, so
                // the set cannot grow over a long session (`D6c`).
                discardedLocally.removeAll { photoId ->
                    pending.none { photo -> photo.photoId == photoId }
                }
                if (accepted) {
                    // The API holds evidence it did not hold before, so the Activity that projects it
                    // is read again rather than left showing a Job without the photo the technician
                    // just recorded (`BR-001`, `BR-080`).
                    readActivity(jobId)
                }
                refreshPhotoUploads(jobId)
            }
        }
    }

    /**
     * Whether an upload left the tray because the API accepted it.
     *
     * A queued photo is removed from the tray by exactly one thing: the upload handler, once the
     * backend has confirmed it holds the bytes (`JobPhotoUploadHandler`, `§9`). A photo the technician
     * removed locally was either never submitted, or a refusal this screen discarded — the backend holds
     * nothing new in either case, so nothing is read again (`BR-014`, `D6c`).
     */
    private fun anUploadWasAccepted(
        previous: List<PendingJobPhoto>?,
        pending: List<PendingJobPhoto>,
    ): Boolean {
        val before = previous ?: return false
        val inTray = pending.mapTo(mutableSetOf()) { it.photoId }
        return before.any { photo ->
            photo.submitted && photo.photoId !in inTray && photo.photoId !in discardedLocally
        }
    }

    /** Reads what the queue holds for the Job's photos, which is what the tray reports (`§7`). */
    private fun refreshPhotoUploads(jobId: String) {
        viewModelScope.launch {
            val states = photos.uploadStates(jobId)
            _uiState.update { current ->
                if (current.jobId == jobId) current.copy(photoUploads = states) else current
            }
        }
    }

    /**
     * Starts recording an audio note for this Job (`BR-091`).
     *
     * The identity and the app-private file are allocated **before** the microphone opens, exactly as a
     * capture's are (`§5`, `§9`), and a device that cannot start its recorder reports that rather than
     * leaving a recording nobody is making (`BR-042`).
     *
     * The microphone permission is the screen's to ask for, and this is called only once it has been
     * granted: nothing here can start a recording Android would refuse (`BR-011`).
     */
    fun startAudioRecording() {
        val jobId = _uiState.value.jobId
        if (jobId.isEmpty() || audioInFlight || _uiState.value.audioRecording != null) {
            return
        }
        val capture = audio.beginRecording()
        if (capture == null) {
            _uiState.update { it.copy(audioFailure = JobAudioFailure.NOT_SIGNED_IN) }
            return
        }
        if (!audio.startRecording(capture)) {
            // A recorder that never started leaves nothing behind, and the technician is told which
            // failure it was rather than being left with a control that appears to do nothing
            // (`BR-042`, §9).
            audio.abandonRecording(capture)
            _uiState.update { it.copy(audioFailure = JobAudioFailure.RECORDER_UNAVAILABLE) }
            return
        }
        audioRecordingStartedAtMillis = clock.millis()
        _uiState.update {
            it.copy(audioRecording = capture, audioFailure = null, audioMessage = null)
        }
    }

    /**
     * Stops the recording in progress and records it as a draft on this device (`BR-014`, `BR-091`).
     *
     * What it becomes is the technician's own durable draft — not evidence, and not an upload: the
     * upload happens when they attach it (`attachAudioNote`). A recorder that could not finish, or a
     * file it left with no bytes in it, is reported instead, and nothing is recorded.
     */
    fun stopAudioRecording() {
        val capture = _uiState.value.audioRecording ?: return
        if (audioInFlight) {
            return
        }
        audioInFlight = true
        val jobId = _uiState.value.jobId
        val phase = _uiState.value.audioPhase
        val durationSeconds =
            ((clock.millis() - audioRecordingStartedAtMillis) / MILLIS_PER_SECOND).toInt()
        // The microphone is off as soon as the technician stops it, so the sheet stops saying it is
        // live while the draft is being written (`BR-042`).
        _uiState.update { it.copy(audioRecording = null) }
        viewModelScope.launch {
            val recorded = audio.stopRecording(jobId, capture, phase, durationSeconds)
            audioInFlight = false
            _uiState.update { current ->
                current.copy(
                    audioFailure = when (recorded) {
                        is JobAudioRecordResult.Recorded -> null
                        is JobAudioRecordResult.Refused -> recorded.reason.toAudioFailure()
                    },
                )
            }
            refreshAudioUploads(jobId)
        }
    }

    /**
     * Drops the recording in progress without recording it: the technician left the sheet.
     *
     * Nothing was recorded, so nothing the backend could hold is lost — the recorder is released and
     * whatever file it created goes with it (`§9`, `BR-042`).
     */
    fun cancelAudioRecording() {
        val capture = _uiState.value.audioRecording ?: return
        audio.abandonRecording(capture)
        _uiState.update { it.copy(audioRecording = null) }
    }

    /** Reports that the microphone permission was declined, so nothing could be recorded (`BR-011`). */
    fun microphoneDenied() {
        _uiState.update { it.copy(audioFailure = JobAudioFailure.MICROPHONE_DENIED) }
    }

    /** Records the phase the technician chose for the recording they are about to attach (`BR-012`). */
    fun selectAudioPhase(phase: EvidencePhase) {
        _uiState.update { it.copy(audioPhase = phase) }
    }

    /**
     * Removes a recording the technician no longer wants from this device (`BR-014`, `BR-091`).
     *
     * A recording that has not been attached is theirs to remove, and so is one whose upload the
     * backend permanently refused: that upload is finished, so nothing is being decided about evidence
     * the API might hold (`BR-031`). A queued or retrying upload is refused by the session and reported
     * instead, because the backend may still accept it.
     */
    fun removePendingAudioNote(audioNoteId: String) {
        val note = _uiState.value.pendingAudioNotes
            .firstOrNull { it.audioNoteId == audioNoteId } ?: return
        // A refused recording the technician is about to discard is remembered **before** its row
        // leaves, so the notice losing it is not read as the API accepting the upload (`§9`).
        if (note.isRefusedUpload(_uiState.value.audioUploads[audioNoteId])) {
            audioDiscardedLocally += audioNoteId
        }
        viewModelScope.launch {
            val removed = audio.discard(note)
            _uiState.update { current ->
                current.copy(
                    audioFailure = if (removed) null else JobAudioFailure.ALREADY_SUBMITTED,
                )
            }
        }
    }

    /**
     * Attaches the recording the technician made, which queues its upload (`BR-091`, `BR-031`).
     *
     * The phase and note they chose are recorded on the draft first, so the queued operation carries
     * what the technician meant rather than what an earlier attempt happened to hold; the upload is
     * then queued through the outbox and replayed by the existing trigger (`§5`, `§6`).
     */
    fun attachAudioNote(note: String?) {
        val draft = _uiState.value.audioDraft ?: return
        val jobId = _uiState.value.jobId
        if (jobId.isEmpty() || audioInFlight || _uiState.value.isSubmittingAudio) {
            return
        }
        val phase = _uiState.value.audioPhase
        audioInFlight = true
        _uiState.update {
            it.copy(isSubmittingAudio = true, audioFailure = null, audioMessage = null)
        }
        viewModelScope.launch {
            // The reviewed record is what is attached, so the queued operation carries exactly the phase
            // and note the technician saw in the review (`BR-041`, `BR-091`).
            val reviewed = audio.review(
                draft.audioNoteId,
                phase,
                note?.trim()?.takeIf { it.isNotEmpty() },
            ) ?: draft
            val queued = audio.submit(listOf(reviewed))
            audioInFlight = false
            _uiState.update { current ->
                current.copy(
                    isSubmittingAudio = false,
                    audioMessage = if (queued > 0) JobAudioMessage.QUEUED else null,
                    audioFailure = if (queued > 0) null else JobAudioFailure.NOT_QUEUED,
                )
            }
            refreshAudioUploads(jobId)
        }
    }

    /** Releases the last audio report once the screen has shown it. */
    fun dismissAudioMessage() {
        _uiState.update { it.copy(audioMessage = null) }
    }

    /**
     * Plays or stops one recording: a take this device still holds, or one of the Job's accepted
     * recordings (`BR-080`, `BR-091`, `ADR-018` A9).
     *
     * It is **one** action over both surfaces — the Add update sheet's review and the timeline — because
     * the feature owns **one** player: which bytes are played is decided here rather than by the caller.
     * A take the technician has not attached plays from the file this device holds, so it needs nothing
     * from the API (`BR-013`); accepted evidence is read through the API on its first play and kept in
     * this session's own cache, so a recording the device has already played plays again offline and one
     * it has never held reports that it needs the backend (`BR-013`, `BR-042`).
     *
     * A second tap on the recording the player holds **pauses** it and keeps where it had got to, and a
     * third continues from there, which is what makes one control both the play and the pause (`A11`): the
     * playhead states a position the technician can move, so pausing must not be the thing that throws it
     * away.
     */
    fun toggleAudioPlayback(audioNoteId: String) {
        val held = _uiState.value.audioPlayback
        if (held?.audioNoteId == audioNoteId) {
            if (held.isPlaying) audioPlayer.pause() else audioPlayer.resume()
            return
        }
        // A recording this device still holds is the technician's own draft: its bytes are here, and the
        // API has not accepted anything yet (`BR-014`, `BR-088`).
        val draft = _uiState.value.pendingAudioNotes
            .firstOrNull { note -> note.audioNoteId == audioNoteId }
        if (draft != null) {
            startAudioPlayback(audioNoteId, draft.localPath)
            return
        }
        val jobId = _uiState.value.jobId
        if (jobId.isEmpty() || playbackInFlight) {
            return
        }
        playbackInFlight = true
        _uiState.update { it.copy(audioPlaybackLoading = audioNoteId, audioFailure = null) }
        viewModelScope.launch {
            val read = readPlaybackBytes(jobId, audioNoteId)
            playbackInFlight = false
            _uiState.update { current ->
                current.copy(
                    audioPlaybackLoading = null,
                    audioFailure = read.playbackFailureOrNull(),
                )
            }
            val path = (read as? JobAudioEvidenceRead.Available)?.path ?: return@launch
            startAudioPlayback(audioNoteId, path)
        }
    }

    /**
     * Moves the recording the device's player holds to [positionMillis] (`ADR-018` A11).
     *
     * Only the recording the player holds can be moved: a drag on a recording this screen has not loaded
     * is not something the device can perform, and its track is drawn as one that cannot be used
     * (`BR-042`). Nothing is remembered here — the player answers with the position it went to, which is
     * what the playhead and the elapsed seconds draw.
     */
    fun seekAudioPlayback(audioNoteId: String, positionMillis: Int) {
        if (_uiState.value.audioPlayback?.audioNoteId != audioNoteId) {
            return
        }
        audioPlayer.seekTo(positionMillis)
    }

    /**
     * Reads one accepted recording's bytes, reporting a failure this build cannot name as one the
     * screen can (`BR-042`).
     *
     * Starting playback is a tap on a phone in the field, so a read that fails in a way this build did
     * not foresee is reported as a recording that could not be read rather than taking the screen down
     * with it (`BR-012`). Cancelling is not a failure: leaving the screen stops the read instead of
     * reporting it (`dev.md` §1).
     */
    private suspend fun readPlaybackBytes(jobId: String, audioNoteId: String): JobAudioEvidenceRead =
        try {
            audioEvidence.read(jobId, audioNoteId)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            JobAudioEvidenceRead.Unavailable
        }

    /**
     * Starts one recording, replacing whatever the player held (`ADR-018` A9).
     *
     * One player serves both surfaces, so playing a recording replaces whatever was playing rather than
     * mixing two voices, and a device that cannot play the recording reports that instead of leaving a
     * control that looks as though it did something (`BR-042`).
     */
    private fun startAudioPlayback(audioNoteId: String, path: String) {
        if (!audioPlayer.play(audioNoteId, path)) {
            _uiState.update { it.copy(audioFailure = JobAudioFailure.PLAYBACK_FAILED) }
        }
    }

    /**
     * Takes accepted audio evidence out of ordinary use, recording why (`BR-088`, `BR-089`).
     *
     * It is the photo removal's own shape for the other kind (`ADR-018` A7): only evidence the backend
     * holds can be removed, the manager states the reason the removal is recorded with, and the API
     * authorizes it with `evidence.audio.remove` — so this layer never decides whether the removal is
     * allowed (`BR-007`). It is **online-only**: the route takes no idempotency key and no conflict
     * policy is decided for it, so the decision is never queued (`ADR-018` A10).
     */
    fun removeEvidenceAudioNote(audioNoteId: String, reason: String) {
        // The reason is required, and the dialog asks for it before this is called: a removal records the
        // actor, the instant and the reason, so a blank one is not sent (`BR-089`).
        val text = reason.trim()
        val jobId = _uiState.value.jobId
        if (text.isEmpty() || jobId.isEmpty() || removalInFlight) {
            return
        }
        // A recording the Activity does not report is not evidence this screen can remove: a take the
        // technician has not attached is theirs to discard, and one already removed has nothing left to
        // decide (`BR-042`, `BR-089`).
        val recorded = _uiState.value.activity.orEmpty().any { event ->
            event.kind == JobActivityKind.JOB_AUDIO_ADDED && event.audioNoteId == audioNoteId
        }
        if (!recorded) {
            _uiState.update {
                it.copy(
                    audioFailure = JobAudioFailure.REMOVAL_NO_LONGER_AVAILABLE,
                    audioMessage = null,
                )
            }
            return
        }
        removalInFlight = true
        _uiState.update {
            it.copy(audioRemoval = audioNoteId, audioFailure = null, audioMessage = null)
        }
        viewModelScope.launch {
            val result = repository.removeJobAudioNote(jobId, audioNoteId, text)
            removalInFlight = false
            // Evidence that leaves ordinary use does not keep playing (`BR-089`).
            if (_uiState.value.audioPlayback?.audioNoteId == audioNoteId) {
                audioPlayer.stop()
            }
            _uiState.update { current ->
                when (result) {
                    is ActivityWriteResult.Success ->
                        current.copy(
                            audioRemoval = null,
                            // The write's own answer is the backend's, so the timeline — and with it the
                            // evidence a Job holds — is current again (`§7`, `BR-080`).
                            activity = result.events,
                            activitySource = ReadSource.BACKEND,
                            activityFailure = null,
                            audioFailure = null,
                            audioMessage = JobAudioMessage.EVIDENCE_REMOVED,
                        )

                    // A removal is online-only, so nothing queues it (`BR-089`, §13.2). The branch
                    // keeps the mapping total without inventing a meaning for an answer this
                    // operation cannot produce (`BR-042`).
                    ActivityWriteResult.Queued ->
                        current.copy(
                            audioRemoval = null,
                            audioFailure = JobAudioFailure.REMOVAL_FAILED,
                        )

                    is ActivityWriteResult.Failure ->
                        current.copy(
                            audioRemoval = null,
                            audioFailure = result.reason.toAudioRemovalFailure(),
                        )
                }
            }
        }
    }

    /**
     * Releases the device's player when the screen is gone (`ADR-018` A9).
     *
     * A recording does not keep playing after the screen that started it has been left, exactly as the
     * recording in progress is dropped rather than left with the microphone open (`JobAudioSession`).
     */
    override fun onCleared() {
        audioPlayer.stop()
        super.onCleared()
    }

    /**
     * Follows the Job's pending recordings for as long as this Job is on screen (`§9`, `BR-091`).
     *
     * The list is the device's own state, so it is observed rather than re-read: an upload the API
     * accepted removes its row, and the notice follows without the screen asking (`BR-001`).
     */
    private fun observeAudioNotes(jobId: String) {
        audioCollection?.cancel()
        audioCollection = null
        val flow = audio.pendingNotes(jobId) ?: return
        audioCollection = viewModelScope.launch {
            // The notice as it was before this emission, so a recording that leaves it can be told apart
            // from one that was never there (`§9`).
            var previous: List<PendingJobAudioNote>? = null
            flow.collect { pending ->
                _uiState.update { current ->
                    if (current.jobId == jobId) {
                        current.copy(pendingAudioNotes = pending)
                    } else {
                        current
                    }
                }
                val accepted = anAudioUploadWasAccepted(previous, pending)
                previous = pending
                audioDiscardedLocally.removeAll { audioNoteId ->
                    pending.none { note -> note.audioNoteId == audioNoteId }
                }
                if (accepted) {
                    // The API holds evidence it did not hold before, so the Activity that projects it is
                    // read again rather than left showing a Job without the recording just made
                    // (`BR-001`, `BR-080`).
                    readActivity(jobId)
                }
                refreshAudioUploads(jobId)
            }
        }
    }

    /** Whether an upload left the notice because the API accepted it (`§9`, `BR-001`). */
    private fun anAudioUploadWasAccepted(
        previous: List<PendingJobAudioNote>?,
        pending: List<PendingJobAudioNote>,
    ): Boolean {
        val before = previous ?: return false
        val inNotice = pending.mapTo(mutableSetOf()) { it.audioNoteId }
        return before.any { note ->
            note.submitted &&
                note.audioNoteId !in inNotice &&
                note.audioNoteId !in audioDiscardedLocally
        }
    }

    /** Reads what the queue holds for the Job's recordings, which is what the notice reports (`§7`). */
    private fun refreshAudioUploads(jobId: String) {
        viewModelScope.launch {
            val states = audio.uploadStates(jobId)
            _uiState.update { current ->
                if (current.jobId == jobId) current.copy(audioUploads = states) else current
            }
        }
    }

    private fun read(jobId: String, keepContent: Boolean = false) {
        readInFlight = true
        if (keepContent) {
            // A re-read after a replay the backend accepted replaces the Job **in place**: the content
            // stays on screen while the newer answer arrives, so a background synchronization does not
            // flash the loading state over the work the technician is reading (`BR-012`, §7).
            _uiState.update { current -> current.copy(jobId = jobId) }
        } else {
            _uiState.value = JobDetailsUiState(jobId = jobId, isLoading = true)
            // A pick belongs to the Job it was made for, so a read of another Job takes nothing with it.
            pickedPhotos.clear()
            skippedPhotos.clear()
        }
        viewModelScope.launch {
            val result = repository.loadJobDetails(jobId)
            // The field work the queue is still holding is read with the Job, so what is presented as
            // waiting is the real queue rather than a second copy of it (`BR-041`, §7).
            val queuedAction = repository.queuedVisitAction(jobId)
            val queuedNotes = repository.queuedVisitNotes(jobId)
            readInFlight = false
            _uiState.update { current ->
                if (current.jobId != jobId) {
                    // Another Job replaced this one while the read was in flight.
                    current
                } else {
                    when (result) {
                        is JobDetailsResult.Success ->
                            current.copy(
                                isLoading = false,
                                details = result.details,
                                // The screen says whether this Job is the backend's current answer or
                                // the last one it reported (`§2`, `§7`).
                                detailsSource = result.source,
                                failureReason = null,
                                queuedVisitAction = queuedAction,
                                queuedVisitNotes = queuedNotes,
                                // A fresh Job read re-asks for that Job's activity rather than
                                // carrying the previous Job's (`BR-001`) — unless the content is being
                                // kept, in which case the timeline stays until its own answer arrives.
                                activity = if (keepContent) current.activity else null,
                                activityFailure = null,
                                activitySource = ReadSource.BACKEND,
                            )

                        is JobDetailsResult.Failure ->
                            current.copy(
                                isLoading = false,
                                // A failed read must not leave a Job on screen: it would be a
                                // different record than the one the screen says it is showing.
                                details = null,
                                detailsSource = ReadSource.BACKEND,
                                failureReason = result.reason,
                                activity = null,
                                activityFailure = null,
                                activitySource = ReadSource.BACKEND,
                            )
                    }
                }
            }
            if (result is JobDetailsResult.Success) {
                readActivity(jobId)
                observePhotos(jobId)
                observeAudioNotes(jobId)
            }
        }
    }

    /**
     * Re-reads the Job after a replay the backend accepted (`§7`).
     *
     * The queued work is read with it, so an action that has just been applied or refused stops being
     * presented as waiting the moment the backend answers (`BR-001`, §6).
     */
    private fun reloadAfterSync(jobId: String) {
        if (readInFlight) {
            return
        }
        read(jobId, keepContent = true)
    }

    /** Reads the Job's chronological activity after the Job itself was read (`BR-080`). */
    private fun readActivity(jobId: String) {
        viewModelScope.launch {
            val result = repository.loadJobActivity(jobId)
            _uiState.update { current ->
                if (current.jobId != jobId) {
                    current
                } else {
                    when (result) {
                        is JobActivityResult.Success ->
                            current.copy(
                                activity = result.events,
                                // A timeline the device answered is the last one the backend reported,
                                // and it is presented as that (`§7`, `D5`).
                                activitySource = result.source,
                                activityFailure = null,
                            )

                        is JobActivityResult.Failure ->
                            current.copy(
                                activity = null,
                                activitySource = ReadSource.BACKEND,
                                activityFailure = result.reason,
                            )
                    }
                }
            }
        }
    }

    /** Re-reads the activity after a failure the section reported. */
    fun retryActivity() {
        val jobId = _uiState.value.jobId
        if (jobId.isNotEmpty()) {
            readActivity(jobId)
        }
    }
}

/**
 * Why a recording was refused, as the screen reports it (`BR-042`).
 *
 * The data layer states what its own recorder could not do; this is where that becomes one of the
 * screen's stable failure codes, so the copy the technician reads is localized and says what happened
 * rather than that "something went wrong" (`BR-028`, `dev.md` §9).
 */
private fun JobAudioRefusal.toAudioFailure(): JobAudioFailure =
    when (this) {
        JobAudioRefusal.NOT_SIGNED_IN -> JobAudioFailure.NOT_SIGNED_IN
        JobAudioRefusal.RECORDER_UNAVAILABLE -> JobAudioFailure.RECORDER_UNAVAILABLE
        JobAudioRefusal.NOT_RECORDED -> JobAudioFailure.RECORDING_FAILED
        JobAudioRefusal.NO_BYTES -> JobAudioFailure.RECORDING_EMPTY
        JobAudioRefusal.NOT_STORED -> JobAudioFailure.RECORDING_NOT_SAVED
    }

/**
 * Why an accepted recording could not be played (`BR-013`, `BR-042`).
 *
 * `null` when the bytes are on the device and it is about to play: a recording the device already holds
 * needs nothing from the backend, and one it does not hold says which of the two states it is in — the
 * backend could not be reached, or it did not deliver the recording.
 */
private fun JobAudioEvidenceRead.playbackFailureOrNull(): JobAudioFailure? =
    when (this) {
        is JobAudioEvidenceRead.Available -> null
        JobAudioEvidenceRead.Unreachable -> JobAudioFailure.PLAYBACK_UNREACHABLE
        JobAudioEvidenceRead.Unavailable -> JobAudioFailure.PLAYBACK_UNAVAILABLE
    }

/**
 * Why an audio removal the API refused is reported the way it is (`BR-042`, `BR-089`).
 *
 * The photo removal's own mapping for the other kind (`ADR-018` A7), and total for the same reason: a
 * removal that failed in a way this build cannot name is reported as one that failed, never as one that
 * succeeded.
 */
private fun JobActionFailure.toAudioRemovalFailure(): JobAudioFailure =
    when (this) {
        JobActionFailure.FORBIDDEN -> JobAudioFailure.REMOVAL_NOT_PERMITTED
        JobActionFailure.AUDIO_NOTE_ALREADY_REMOVED,
        JobActionFailure.NOT_FOUND,
        JobActionFailure.VERSION_CONFLICT,
        -> JobAudioFailure.REMOVAL_NO_LONGER_AVAILABLE

        JobActionFailure.NETWORK -> JobAudioFailure.REMOVAL_UNREACHABLE
        JobActionFailure.UNAUTHENTICATED -> JobAudioFailure.NOT_SIGNED_IN

        // The photo kind's own conflict, the Job and Visit management codes and the rest cannot be
        // returned by the audio removal route: reporting them as a failure keeps the mapping total
        // without inventing a meaning for a code this operation cannot produce (`BR-042`).
        JobActionFailure.PHOTO_ALREADY_REMOVED,
        JobActionFailure.VALIDATION,
        JobActionFailure.JOB_TRANSITION_NOT_ALLOWED,
        JobActionFailure.JOB_REVIEW_CONDITION_NOT_MET,
        JobActionFailure.JOB_COMPLETION_BLOCKED,
        JobActionFailure.JOB_CANCELLATION_UNAVAILABLE,
        JobActionFailure.VISIT_NOT_RESCHEDULABLE,
        JobActionFailure.TECHNICIANS_NOT_ASSIGNABLE,
        // The Visit field lifecycle's own codes cannot be returned by the audio removal route either,
        // for the same reason (`BR-042`).
        JobActionFailure.VISIT_TRANSITION_NOT_ALLOWED,
        JobActionFailure.VISIT_SCHEDULING_CONDITION_NOT_MET,
        JobActionFailure.JOB_CLOSED_FOR_FIELD_WORK,
        JobActionFailure.VISIT_OPERATION_REUSED,
        JobActionFailure.SERVER,
        JobActionFailure.UNEXPECTED,
        -> JobAudioFailure.REMOVAL_FAILED
    }

/** A millisecond count as whole seconds, for the length the review shows (`ADR-018` A3). */
private const val MILLIS_PER_SECOND = 1_000L

/**
 * Why a photo was refused, as the screen reports it (`BR-042`).
 *
 * The data layer states what its own pipeline could not do; this is where that becomes one of the
 * screen's stable failure codes, so the copy the technician reads is localized and says what happened
 * rather than that "something went wrong" (`BR-028`, `dev.md` §9).
 */
private fun JobPhotoRefusal.toPhotoFailure(): JobPhotoFailure =
    when (this) {
        JobPhotoRefusal.NOT_SIGNED_IN -> JobPhotoFailure.NOT_SIGNED_IN
        JobPhotoRefusal.NO_BYTES -> JobPhotoFailure.CAPTURE_FAILED
        JobPhotoRefusal.NOT_READ -> JobPhotoFailure.PHOTO_NOT_READ
        JobPhotoRefusal.TYPE_NOT_ACCEPTED -> JobPhotoFailure.PHOTO_TYPE_NOT_ACCEPTED
        JobPhotoRefusal.TOO_LARGE -> JobPhotoFailure.PHOTO_TOO_LARGE
        JobPhotoRefusal.NOT_STORED -> JobPhotoFailure.PHOTO_NOT_SAVED
    }

/**
 * Why a removal the API refused is reported the way it is (`BR-042`, `BR-089`).
 *
 * The API's own outcomes are the vocabulary (`docs/api/job-photos.md` §6), so the manager is told what
 * happened — the capability is missing, the evidence is no longer there to remove, the API could not be
 * reached — rather than that "something went wrong" (`dev.md` §9). The mapping is total: a removal that
 * failed in a way this build cannot name is reported as one that failed, never as one that succeeded.
 */
private fun JobActionFailure.toRemovalFailure(): JobPhotoFailure =
    when (this) {
        JobActionFailure.FORBIDDEN -> JobPhotoFailure.REMOVAL_NOT_PERMITTED
        JobActionFailure.PHOTO_ALREADY_REMOVED,
        JobActionFailure.NOT_FOUND,
        JobActionFailure.VERSION_CONFLICT,
        -> JobPhotoFailure.REMOVAL_NO_LONGER_AVAILABLE

        JobActionFailure.NETWORK -> JobPhotoFailure.REMOVAL_UNREACHABLE
        JobActionFailure.UNAUTHENTICATED -> JobPhotoFailure.NOT_SIGNED_IN
        JobActionFailure.VALIDATION,
        JobActionFailure.SERVER,
        JobActionFailure.UNEXPECTED,
        -> JobPhotoFailure.REMOVAL_FAILED

        // The audio kind's own conflict, the Job and Visit management codes and the rest cannot be
        // returned by the photo removal route: reporting them as a failure keeps the mapping total
        // without inventing a meaning for a code this operation cannot produce (`BR-042`).
        JobActionFailure.AUDIO_NOTE_ALREADY_REMOVED,
        JobActionFailure.JOB_TRANSITION_NOT_ALLOWED,
        JobActionFailure.JOB_REVIEW_CONDITION_NOT_MET,
        JobActionFailure.JOB_COMPLETION_BLOCKED,
        JobActionFailure.JOB_CANCELLATION_UNAVAILABLE,
        JobActionFailure.VISIT_NOT_RESCHEDULABLE,
        JobActionFailure.TECHNICIANS_NOT_ASSIGNABLE,
        // The Visit field lifecycle's own codes cannot be returned by the photo removal route either,
        // for the same reason (`BR-042`).
        JobActionFailure.VISIT_TRANSITION_NOT_ALLOWED,
        JobActionFailure.VISIT_SCHEDULING_CONDITION_NOT_MET,
        JobActionFailure.JOB_CLOSED_FOR_FIELD_WORK,
        JobActionFailure.VISIT_OPERATION_REUSED,
        -> JobPhotoFailure.REMOVAL_FAILED
    }

/**
 * One photo of a pick the screen still has to take, and the place the technician chose it in.
 *
 * The position is what lets a photo that could not be taken be named: a pick of five that delivers
 * four has to say which one it could not take (`D3`).
 */
private data class PickedPhoto(
    val uri: String,
    val position: PhotoItemPosition,
)

/** A photo of a pick the device could not take, and why, as the screen reports it (`D3`). */
private data class SkippedPhoto(
    val position: PhotoItemPosition,
    val failure: JobPhotoFailure,
)

/**
 * One management action the user asked for, kept whole so it can be sent a second time once its
 * conflicts are accepted (`BR-070`).
 */
private sealed interface JobActionRequest {
    /** The action this request performs, for the confirmation the screen reports. */
    val kind: JobActionKind

    /** The Job the action belongs to, so a reply is only applied to the Job it was asked for. */
    val jobId: String

    /** The action as the screen presents it while the user decides about its conflicts. */
    fun pending(conflicts: List<ScheduleConflict>): PendingJobAction?

    /** Sends the action, confirming its conflicts only when the user has accepted them. */
    suspend fun send(
        repository: JobDetailsRepository,
        confirmed: Boolean,
    ): JobActionResult

    data class Status(
        override val jobId: String,
        val status: JobStatus,
        val note: String?,
        val jobVersion: Int,
    ) : JobActionRequest {
        override val kind: JobActionKind = JobActionKind.STATUS_CHANGE

        override suspend fun send(
            repository: JobDetailsRepository,
            confirmed: Boolean,
        ): JobActionResult =
            repository.changeJobStatus(
                jobId = jobId,
                status = status,
                note = note,
                expectedVersion = jobVersion,
            )

        override fun pending(conflicts: List<ScheduleConflict>): PendingJobAction? =
            // A status change carries no schedule, so the API cannot report a technician's time as
            // conflicting for it (`BR-070`). Reporting one would be presenting an answer this build
            // cannot place, so the screen refuses it rather than guessing (`BR-042`).
            null
    }

    data class Reschedule(
        override val jobId: String,
        val visitId: String,
        val scheduledStart: Instant,
        val scheduledEnd: Instant,
        val visitVersion: Int,
    ) : JobActionRequest {
        override val kind: JobActionKind = JobActionKind.RESCHEDULE

        override suspend fun send(
            repository: JobDetailsRepository,
            confirmed: Boolean,
        ): JobActionResult =
            repository.rescheduleVisit(
                jobId = jobId,
                visitId = visitId,
                scheduledStart = scheduledStart,
                scheduledEnd = scheduledEnd,
                reason = null,
                confirmConflicts = confirmed,
                expectedVersion = visitVersion,
            )

        override fun pending(conflicts: List<ScheduleConflict>): PendingJobAction =
            PendingJobAction.Reschedule(scheduledStart, scheduledEnd, conflicts)
    }

    data class Assignment(
        override val jobId: String,
        val visitId: String,
        val assignments: List<TechnicianAssignment>,
        val visitVersion: Int,
    ) : JobActionRequest {
        override val kind: JobActionKind = JobActionKind.ASSIGNMENT

        override suspend fun send(
            repository: JobDetailsRepository,
            confirmed: Boolean,
        ): JobActionResult =
            repository.assignVisitTechnicians(
                jobId = jobId,
                visitId = visitId,
                assignments = assignments,
                confirmConflicts = confirmed,
                expectedVersion = visitVersion,
            )

        override fun pending(conflicts: List<ScheduleConflict>): PendingJobAction =
            PendingJobAction.Assign(assignments, conflicts)
    }

    /**
     * One Visit status transition, with the outcome a completion carries (`BR-074`, `BR-077`).
     *
     * The identity, the device instant and the version are held here rather than regenerated on a
     * confirmed resend, because the resend is the **same** business operation: it carries the same
     * idempotency key, so the API applies it once however many times the request is sent (`BR-031`).
     */
    data class VisitTransition(
        override val jobId: String,
        val visitId: String,
        val status: VisitStatus,
        val outcome: VisitOutcome?,
        val outcomeSummary: String?,
        val visitVersion: Int,
        val operationId: String,
        val capturedAt: Instant,
    ) : JobActionRequest {
        override val kind: JobActionKind = JobActionKind.VISIT_STATUS_CHANGE

        override suspend fun send(
            repository: JobDetailsRepository,
            confirmed: Boolean,
        ): JobActionResult =
            repository.changeVisitStatus(
                VisitStatusChange(
                    jobId = jobId,
                    visitId = visitId,
                    status = status,
                    outcome = outcome,
                    outcomeSummary = outcomeSummary,
                    confirmConflicts = confirmed,
                    operationId = operationId,
                    capturedAt = capturedAt,
                    expectedVersion = visitVersion,
                ),
            )

        override fun pending(conflicts: List<ScheduleConflict>): PendingJobAction =
            PendingJobAction.VisitTransition(
                status = status,
                outcome = outcome,
                outcomeSummary = outcomeSummary,
                operationId = operationId,
                capturedAt = capturedAt,
                conflicts = conflicts,
            )
    }
}
