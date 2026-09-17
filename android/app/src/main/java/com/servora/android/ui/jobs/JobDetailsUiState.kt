package com.servora.android.ui.jobs

import androidx.compose.runtime.Immutable
import com.servora.android.data.customers.CustomersFailureReason
import com.servora.android.data.jobs.JobActionFailure
import com.servora.android.data.offline.ReadSource
import com.servora.android.domain.model.AssignableTechnician
import com.servora.android.domain.model.CapturedJobAudioNote
import com.servora.android.domain.model.JobActivityEvent
import com.servora.android.domain.model.JobDetails
import com.servora.android.domain.model.EvidencePhase
import com.servora.android.domain.model.JobAudioSyncState
import com.servora.android.domain.model.JobPhotoSyncState
import com.servora.android.domain.model.PendingJobAudioNote
import com.servora.android.domain.model.PendingJobPhoto
import com.servora.android.domain.model.ScheduleConflict
import com.servora.android.domain.model.TechnicianAssignment
import java.time.Instant

/**
 * Why a photo action could not be completed (`BR-042`).
 *
 * They are stable codes the screen resolves to localized copy, so the technician is told what
 * actually happened rather than that "something went wrong".
 */
enum class JobPhotoFailure {
    /** The camera returned, but nothing was written: no photo exists on this device. */
    CAPTURE_FAILED,

    /** No session is held, so the photo cannot be attributed or queued (`§10`). */
    NOT_SIGNED_IN,

    /** The photos could not be queued, so they stay on the device unsubmitted (`BR-014`). */
    NOT_QUEUED,

    /** The photo has already been queued for upload, so it can no longer be removed here. */
    ALREADY_SUBMITTED,

    /** The photo's bytes are not a type Servora accepts and could not be converted to one (`D3b`). */
    PHOTO_TYPE_NOT_ACCEPTED,

    /** The photo is over the API's upload limit and could not be resized to fit (`D3c`). */
    PHOTO_TOO_LARGE,

    /** The photo's bytes could not be stored on this device, so nothing was recorded (`BR-014`). */
    PHOTO_NOT_SAVED,

    /** A photo the technician chose handed over nothing readable, so nothing was recorded (`D3`). */
    PHOTO_NOT_READ,

    /** The device's own photo picker could not be opened, so no photo could be chosen (`D3`). */
    PICKER_UNAVAILABLE,

    /** The photo's bytes could not be read, so saving or sharing it wrote nothing anywhere (`D12`). */
    EXPORT_UNREADABLE,

    /** The device refused the write, so the photo was not saved (`D12`). */
    EXPORT_FAILED,

    /** No application on this device accepts a shared photo (`D13`). */
    SHARE_UNAVAILABLE,

    /** Saving on this device needs a storage permission the technician has not granted (`D12`). */
    SAVE_PERMISSION_DENIED,

    /** The session does not hold `evidence.photo.remove`, so the API refused the removal (`BR-089`). */
    REMOVAL_NOT_PERMITTED,

    /**
     * The photo is not in this Job any more, or it has already been removed (`BR-089`).
     *
     * One photo has one removal and no restore is defined, so the evidence is either there to remove or
     * already out of ordinary use: both leave the manager with nothing to decide, and the refreshed
     * timeline says which.
     */
    REMOVAL_NO_LONGER_AVAILABLE,

    /**
     * The removal needs the API and it could not be reached (`BR-089`).
     *
     * The removal is **online-only**: its route takes no client-generated idempotency key and no
     * conflict policy is decided for it, so it is never queued (`offline-first-architecture.md` §5, §8,
     * §13.2). A manager who removes evidence needs an answer the backend gave, not one the device
     * assumed.
     */
    REMOVAL_UNREACHABLE,

    /** The API refused the removal itself, so nothing was taken out of use (`BR-042`). */
    REMOVAL_FAILED,
}

/**
 * Which photo of a multi-photo pick an action refers to (`D3`).
 *
 * A pick may hand over several photos at once, and each is recorded on its own, so a photo that
 * cannot be taken has to be named: "one of them did not work" tells the technician nothing about
 * which one to choose again (`BR-012`).
 */
data class PhotoItemPosition(
    /** The photo's place in the pick, counting from one. */
    val position: Int,
    /** How many photos the pick handed over. */
    val total: Int,
)

/** What the last photo action did, until the screen acknowledges it. */
enum class JobPhotoMessage {
    /** The photos are queued on the device and will upload when the API can be reached (`§7`). */
    QUEUED,

    /** The photo is in the device's own gallery (`D12`). */
    SAVED_TO_DEVICE,

    /** The photo was handed to another application (`D13`). */
    SHARED,

    /** Accepted evidence was taken out of ordinary use (`BR-088`, `BR-089`). */
    EVIDENCE_REMOVED,
}

/**
 * Why an audio action could not be completed (`BR-042`).
 *
 * They are stable codes the screen resolves to localized copy, so the technician is told what actually
 * happened rather than that "something went wrong" — a microphone nobody allowed, a recorder the
 * device would not start, a recording that came out empty, or a draft the device could not keep
 * (`BR-091`, `dev.md` §9).
 */
enum class JobAudioFailure {
    /** The technician declined the microphone permission, so nothing could be recorded (`BR-012`). */
    MICROPHONE_DENIED,

    /** No session is held, so the recording cannot be attributed or queued (`§10`). */
    NOT_SIGNED_IN,

    /** The device's recorder could not be started, so no recording was made. */
    RECORDER_UNAVAILABLE,

    /** The device's recorder could not finish the recording, so nothing was kept. */
    RECORDING_FAILED,

    /** The recorder finished without producing any bytes, so no draft was recorded (`BR-014`). */
    RECORDING_EMPTY,

    /** The recording's bytes could not be kept on this device, so nothing was recorded (`BR-014`). */
    RECORDING_NOT_SAVED,

    /** The recording could not be queued, so it stays on the device unattached (`BR-014`). */
    NOT_QUEUED,

    /** The recording has already been queued for upload, so it can no longer be removed here. */
    ALREADY_SUBMITTED,
}

/** What the last audio action did, until the screen acknowledges it. */
enum class JobAudioMessage {
    /** The recording is queued on the device and will upload when the API can be reached (`§7`). */
    QUEUED,
}

/**
 * The evidence action the viewer is carrying out, or `null` when none is running (`D12`, `D13`).
 *
 * Reading a photo's bytes — from the file this device holds, or from the API — is not instant, so the
 * action that was asked for says it is running rather than leaving the technician with a tap that
 * appears to have done nothing until it lands (`BR-042`).
 */
enum class JobPhotoExportAction {
    /** The photo is being written into the device's own gallery. */
    SAVE,

    /** The photo is being handed to another application. */
    SHARE,
}

/** The management action the screen last completed, so its confirmation names what happened. */
enum class JobActionKind {
    /** The Job's lifecycle status changed (`BR-058`). */
    STATUS_CHANGE,

    /** The represented Visit's schedule changed (`BR-073`). */
    RESCHEDULE,

    /** The represented Visit's crew changed (`BR-068`, `BR-069`). */
    ASSIGNMENT,

    /** A text update was added to the represented Visit's activity (`BR-027`, `BR-077`). */
    ACTIVITY_TEXT,
}

/**
 * A management action the API refused until the user accepts the conflicts it reported (`BR-070`).
 *
 * Holding the action here is what lets the screen resend the *same* action once the user confirms,
 * instead of asking them to enter it again. A conflict is a warning rather than a prohibition in v1,
 * so confirming it applies the change.
 */
sealed interface PendingJobAction {
    /** The overlapping Visits the API reported, as the user has to see them. */
    val conflicts: List<ScheduleConflict>

    /** A reschedule waiting for confirmation (`BR-073`). */
    data class Reschedule(
        val scheduledStart: Instant,
        val scheduledEnd: Instant,
        override val conflicts: List<ScheduleConflict>,
    ) : PendingJobAction

    /** An assignment waiting for confirmation (`BR-068`). */
    data class Assign(
        val assignments: List<TechnicianAssignment>,
        override val conflicts: List<ScheduleConflict>,
    ) : PendingJobAction
}

/**
 * Everything the Job Details screen renders.
 *
 * [details] is the last state the backend reported. A failed read never leaves the previous Job on
 * screen: it describes a different record, so the state is dropped and only the failure is reported
 * (`BR-001`).
 *
 * The action flags are separate from the read, so a failed read and a refused action cannot be
 * confused with each other, and nothing here is derived from another client's copy: even whether the
 * represented Visit may be rescheduled is the API's answer (`BR-007`, `BR-073`).
 *
 * [pendingPhotos] are the technician's own captures the backend has not accepted yet. They are
 * presented separately from [activity] on purpose: the Activity is what the backend reported
 * (`BR-080`), and a pending photo is a local, unconfirmed action (`offline-first-architecture.md`
 * §2, §7).
 *
 * [pendingAudioNotes] are the same thing for the other evidence kind (`BR-091`): a recording the
 * technician made that the API has not answered for yet. It is a separate list rather than one
 * kind-tagged list, because the two kinds are separate rows on the device and separate capabilities on
 * the API (`ADR-018` A1), and the notice states which kind it is reporting.
 */
@Immutable
data class JobDetailsUiState(
    /** The Job being read, so a reply can be matched to the Job it was asked for. */
    val jobId: String = "",
    /** The state the backend last reported, or `null` when nothing readable is held. */
    val details: JobDetails? = null,
    /**
     * Where [details] was served from (`offline-first-architecture.md` §2, §7).
     *
     * A value from the working set is the last answer the backend reported, not a current one, so the
     * screen says so instead of presenting a local copy as up to date (`D5`).
     */
    val detailsSource: ReadSource = ReadSource.BACKEND,
    /** Whether a read is in flight. */
    val isLoading: Boolean = false,
    /** Why the last read failed, or `null` when it succeeded. */
    val failureReason: CustomersFailureReason? = null,
    /** Whether an action the user asked for is in flight. */
    val isSubmitting: Boolean = false,
    /** The action the screen last completed, until the screen acknowledges it. */
    val completedAction: JobActionKind? = null,
    /** Why the last action did not complete, or `null` when there is none to report. */
    val actionFailure: JobActionFailure? = null,
    /** An action waiting for the user to accept the conflicts it would create (`BR-070`). */
    val pendingConfirmation: PendingJobAction? = null,
    /** The technicians the assign sheet can offer, or `null` while they have not been read. */
    val assignableTechnicians: List<AssignableTechnician>? = null,
    /** Why the technicians could not be read, or `null`. */
    val assignableFailure: JobActionFailure? = null,
    /** The Job's chronological activity, newest first; `null` while it has not been read. */
    val activity: List<JobActivityEvent>? = null,
    /**
     * Where [activity] was served from (`offline-first-architecture.md` §2, §7).
     *
     * The timeline a device answered is the last one the backend reported, so it says that rather than
     * looking like a fresh read (`D5`, `BR-080`).
     */
    val activitySource: ReadSource = ReadSource.BACKEND,
    /** Why the activity could not be read, or `null`. */
    val activityFailure: CustomersFailureReason? = null,
    /**
     * The photo the technician just captured and is reviewing, or `null` when no review is open.
     *
     * The row it refers to is already durable, so closing the app mid-review cannot lose the photo;
     * the phase and note the technician is choosing are recorded when they confirm (`BR-014`).
     */
    val capturedPhoto: PendingJobPhoto? = null,
    /** The Job's photos the backend has not accepted yet, oldest first (`§9`). */
    val pendingPhotos: List<PendingJobPhoto> = emptyList(),
    /** How far each queued upload has got, derived from the queue (`§7`). */
    val photoUploads: Map<String, JobPhotoSyncState> = emptyMap(),
    /** The phase the next capture starts in: the one the technician chose last (`BR-012`). */
    val photoPhase: EvidencePhase = EvidencePhase.DURING_WORK,
    /** Whether the pending photos are being queued right now. */
    val isSubmittingPhotos: Boolean = false,
    /** Why the last photo action did not complete, or `null`. */
    val photoFailure: JobPhotoFailure? = null,
    /** Which photo of a multi-photo pick [photoFailure] is about, or `null` when it is not one pick. */
    val photoFailureItem: PhotoItemPosition? = null,
    /** What the last photo action did, until the screen acknowledges it. */
    val photoMessage: JobPhotoMessage? = null,
    /** The evidence action running right now, or `null` when none is (`D12`, `D13`). */
    val photoExport: JobPhotoExportAction? = null,
    /**
     * The photo whose save is waiting for the storage permission Android 8–9 needs (`D12`).
     *
     * It is the photo id, not a flag, so the screen can ask for the permission and the same save is
     * retried when it is granted — and so a save that needs no permission never waits for anything.
     */
    val photoSaveAwaitingPermission: String? = null,
    /**
     * The photo a removal is running for, or `null` when none is (`BR-089`).
     *
     * It is the photo id, not a flag, so the viewer can show the progress on the control of the photo
     * it belongs to and disable it while the API answers — a removal is a Manager action on recorded
     * evidence, and it must not be asked for twice.
     */
    val photoRemoval: String? = null,
    /**
     * The Job's recordings the backend has not accepted yet, oldest first (`§9`, `BR-091`).
     *
     * The technician may hold exactly one unattached recording for a Job, which is the draft the sheet
     * records into and the notice reports (`JobAudioSession`).
     */
    val pendingAudioNotes: List<PendingJobAudioNote> = emptyList(),
    /** How far each queued audio upload has got, derived from the queue (`§7`). */
    val audioUploads: Map<String, JobAudioSyncState> = emptyMap(),
    /**
     * The recording running right now, or `null` (`BR-091`).
     *
     * It is held so the sheet can say the microphone is live and how long the recording has been
     * running. A recording that the process kills before it stops is not recoverable — nothing has been
     * recorded and the recorder belonged to that process — so the technician records again (`BR-014`).
     */
    val audioRecording: CapturedJobAudioNote? = null,
    /** Whole seconds the recording in progress has been running, for the sheet's own counter. */
    val audioRecordingSeconds: Int = 0,
    /** The phase the next recording starts in: the one the technician chose last (`BR-012`). */
    val audioPhase: EvidencePhase = EvidencePhase.DURING_WORK,
    /** Whether the unattached recording is being queued right now. */
    val isSubmittingAudio: Boolean = false,
    /** Why the last audio action did not complete, or `null`. */
    val audioFailure: JobAudioFailure? = null,
    /** What the last audio action did, until the screen acknowledges it. */
    val audioMessage: JobAudioMessage? = null,
) {
    /** Nothing has been read yet: the screen shows its first-load state. */
    val showsInitialLoading: Boolean
        get() = details == null && failureReason == null

    /** Nothing is readable and the read failed: the screen reports the failure. */
    val showsFailure: Boolean
        get() = details == null && failureReason != null

    /** Whether an action may be started right now. */
    val canAct: Boolean
        get() = details != null && !isSubmitting

    /** Whether the represented Visit may be rescheduled, as the API answered (`BR-073`). */
    val canReschedule: Boolean
        get() = details?.selectedVisit?.reschedulable == true && canAct

    /** Whether a crew may be stated for the represented Visit (`BR-068`). */
    val canAssign: Boolean
        get() = details?.selectedVisit != null && canAct

    /** Whether the activity has been asked for and not answered yet. */
    val showsActivityLoading: Boolean
        get() = details != null && activity == null && activityFailure == null

    /**
     * The recording the technician has not attached yet, if any (`BR-088`, `BR-091`).
     *
     * There is at most one: the sheet adds one update at a time, so a second recording is a re-record
     * after the first is deleted rather than a queue of drafts (`JobAudioSession`).
     */
    val audioDraft: PendingJobAudioNote?
        get() = pendingAudioNotes.firstOrNull { note -> !note.submitted }

    /**
     * Whether the screen is holding evidence the backend has not accepted yet (`§9`).
     *
     * While it is, the bottom of the screen belongs to that work — the tray or the notice — rather than
     * to the action that would add another update, exactly as a pending photo already takes it
     * (`BR-012`).
     */
    val holdsUnacceptedEvidence: Boolean
        get() = pendingPhotos.isNotEmpty() || pendingAudioNotes.isNotEmpty()
}
