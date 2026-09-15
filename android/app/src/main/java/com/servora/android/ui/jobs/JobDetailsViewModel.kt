package com.servora.android.ui.jobs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.servora.android.data.jobs.AssignableTechniciansResult
import com.servora.android.data.jobs.JobActionFailure
import com.servora.android.data.jobs.JobActionResult
import com.servora.android.data.jobs.JobActivityResult
import com.servora.android.data.jobs.JobDetailsRepository
import com.servora.android.data.jobs.JobDetailsResult
import com.servora.android.data.jobs.JobPhotoImages
import com.servora.android.data.jobs.JobPhotoPickedItems
import com.servora.android.data.jobs.JobPhotoRecordResult
import com.servora.android.data.jobs.JobPhotoRefusal
import com.servora.android.data.jobs.JobPhotoSession
import com.servora.android.data.jobs.VisitNoteResult
import com.servora.android.domain.model.CapturedJobPhoto
import com.servora.android.domain.model.JobPhotoPhase
import com.servora.android.domain.model.JobStatus
import com.servora.android.domain.model.PendingJobPhoto
import com.servora.android.domain.model.ScheduleConflict
import com.servora.android.domain.model.TechnicianAssignment
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Clock
import java.time.Instant
import javax.inject.Inject
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
 * The work is transient: this screen holds no offline working set yet, because the offline
 * architecture's local store (Room) is the single mechanism that will hold business data on the
 * device (`docs/architecture/offline-first-architecture.md`). Until then an action is online-only.
 */
@HiltViewModel
class JobDetailsViewModel @Inject constructor(
    private val repository: JobDetailsRepository,
    private val photos: JobPhotoSession,
    private val jobPhotoImages: JobPhotoImages,
    private val pickedItems: JobPhotoPickedItems,
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
    private var photoCollection: Job? = null

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

    /** Adds one text-only update to the represented Visit's Activity (`BR-027`, `BR-077`). */
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
                actionFailure = null,
            )
        }
        viewModelScope.launch {
            val result = repository.addVisitNote(
                jobId = details.id,
                visitId = visit.id,
                body = text,
            )
            actionInFlight = false
            _uiState.update { current ->
                when (result) {
                    is VisitNoteResult.Success ->
                        current.copy(
                            isSubmitting = false,
                            activity = result.events,
                            activityFailure = null,
                            completedAction = JobActionKind.ACTIVITY_TEXT,
                        )

                    is VisitNoteResult.Failure ->
                        current.copy(
                            isSubmitting = false,
                            actionFailure = result.reason,
                        )
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
        }
    }

    /** Leaves the conflicts unaccepted: nothing was applied, so nothing is sent (`BR-070`). */
    fun dismissPendingAction() {
        _uiState.update { current -> current.copy(pendingConfirmation = null) }
    }

    /** Acknowledges the outcome the screen has reported, so it is not reported twice. */
    fun dismissActionMessage() {
        _uiState.update { current ->
            current.copy(completedAction = null, actionFailure = null)
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
                actionFailure = null,
                pendingConfirmation = null,
            )
        }
        viewModelScope.launch {
            val result = request.send(repository, confirmed)
            actionInFlight = false
            _uiState.update { current ->
                when (result) {
                    is JobActionResult.Success ->
                        current.copy(
                            isSubmitting = false,
                            details = result.details,
                            completedAction = request.kind,
                            actionFailure = null,
                            pendingConfirmation = null,
                            // The action answered with the Job as it now stands, so the read failure
                            // it replaced is gone (`BR-001`).
                            failureReason = null,
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
                            )
                        } else {
                            current.copy(
                                isSubmitting = false,
                                pendingConfirmation = pending,
                            )
                        }
                    }

                    is JobActionResult.Failure ->
                        current.copy(
                            isSubmitting = false,
                            actionFailure = result.reason,
                        )
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
    fun confirmCapturedPhoto(phase: JobPhotoPhase, note: String?) {
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
     * Removes a pending photo the technician no longer wants.
     *
     * This is offered only while the photo is unsaved: once its upload is queued the backend may
     * already hold the evidence, so removing it is not a local decision (`BR-014`, `BR-027`).
     */
    fun removePendingPhoto(photoId: String) {
        val photo = _uiState.value.pendingPhotos.firstOrNull { it.photoId == photoId } ?: return
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
     * backend has confirmed it holds the bytes (`JobPhotoUploadHandler`, `§9`). A photo the
     * technician discarded locally was never submitted, so nothing was recorded for it and there is
     * nothing new to read (`BR-014`).
     */
    private fun anUploadWasAccepted(
        previous: List<PendingJobPhoto>?,
        pending: List<PendingJobPhoto>,
    ): Boolean {
        val before = previous ?: return false
        val inTray = pending.mapTo(mutableSetOf()) { it.photoId }
        return before.any { photo -> photo.submitted && photo.photoId !in inTray }
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

    private fun read(jobId: String) {
        readInFlight = true
        _uiState.value = JobDetailsUiState(jobId = jobId, isLoading = true)
        // A pick belongs to the Job it was made for, so a read of another Job takes nothing with it.
        pickedPhotos.clear()
        skippedPhotos.clear()
        viewModelScope.launch {
            val result = repository.loadJobDetails(jobId)
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
                                failureReason = null,
                                // A fresh Job read re-asks for that Job's activity rather than
                                // carrying the previous Job's (`BR-001`).
                                activity = null,
                                activityFailure = null,
                            )

                        is JobDetailsResult.Failure ->
                            current.copy(
                                isLoading = false,
                                // A failed read must not leave a Job on screen: it would be a
                                // different record than the one the screen says it is showing.
                                details = null,
                                failureReason = result.reason,
                                activity = null,
                                activityFailure = null,
                            )
                    }
                }
            }
            if (result is JobDetailsResult.Success) {
                readActivity(jobId)
                observePhotos(jobId)
            }
        }
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
                            current.copy(activity = result.events, activityFailure = null)

                        is JobActivityResult.Failure ->
                            current.copy(activity = null, activityFailure = result.reason)
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

    /** The action as the screen presents it while the user decides about its conflicts. */
    fun pending(conflicts: List<ScheduleConflict>): PendingJobAction?

    /** Sends the action, confirming its conflicts only when the user has accepted them. */
    suspend fun send(
        repository: JobDetailsRepository,
        confirmed: Boolean,
    ): JobActionResult

    data class Status(
        val jobId: String,
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
        val jobId: String,
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
        val jobId: String,
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
}
