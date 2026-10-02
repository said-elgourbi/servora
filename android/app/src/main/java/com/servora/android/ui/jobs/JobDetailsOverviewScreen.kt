package com.servora.android.ui.jobs

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.servora.android.R
import com.servora.android.data.jobs.JobAudioPlaybackProgress
import com.servora.android.data.jobs.JobPhotoImages
import com.servora.android.data.jobs.QueuedVisitFieldAction
import com.servora.android.data.jobs.QueuedVisitNote
import com.servora.android.data.offline.ReadSource
import com.servora.android.domain.model.CustomerJobAddress
import com.servora.android.domain.model.EvidencePhase
import com.servora.android.domain.model.JobActivityEvent
import com.servora.android.domain.model.JobCustomerContact
import com.servora.android.domain.model.FollowUpVisitRequestStatus
import com.servora.android.domain.model.JobDetails
import com.servora.android.domain.model.JobDetailsTechnician
import com.servora.android.domain.model.JobDetailsVisitSummary
import com.servora.android.domain.model.JobStatus
import com.servora.android.domain.model.TechnicianAssignment
import com.servora.android.domain.model.VisitOutcome
import com.servora.android.domain.model.VisitStatus
import com.servora.android.ui.components.ContactPrimaryBadge
import com.servora.android.ui.components.CustomerContactLine
import com.servora.android.ui.components.InfoCard
import com.servora.android.ui.components.OfflineNotice
import com.servora.android.ui.components.RequestStatusPill
import com.servora.android.ui.components.SectionLabel
import com.servora.android.ui.components.VisitStatusPill
import com.servora.android.ui.components.addressLine
import com.servora.android.ui.components.dialIntent
import com.servora.android.ui.components.initials
import com.servora.android.ui.components.mailIntent
import com.servora.android.ui.components.startContactIntent
import com.servora.android.ui.components.visitStatusLabel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Identifies the Job's own information: its number, title and description (`BR-052`, `BR-053`).
 *
 * It is a heading rather than a card, because it is the page's hierarchy rather than one of its records
 * (`BR-012`). The Job's status is deliberately not part of it: a routine `ACTIVE` status says nothing a
 * technician can act on, so it is drawn as a control only for a session that may change it and is
 * otherwise carried by the notice that reports a closed Job (`BR-058`, `BR-059`).
 */
const val JobHeaderSectionTag = "job-details-header"

/** Identifies the section that presents the Job's status control, for a session that may change it. */
const val JobStatusSectionTag = "job-details-status-section"

/** Identifies the section that presents where the work is (`BR-049`, `BR-056`). */
const val JobLocationSectionTag = "job-details-location"

/** Identifies the section that presents who can be reached at the Customer (`BR-092`, `BR-095`). */
const val JobContactsSectionTag = "job-details-contacts"

/** Identifies the Customer's effective primary contact inside the Contacts section (`BR-095`). */
const val JobDetailsContactPrimaryTag = "job-details-contact-primary"

/** Identifies the section that presents the notes the office recorded (`BR-092`). */
const val JobNotesSectionTag = "job-details-notes"

/** Identifies the section that presents the Visit representing the Job (`BR-081`). */
const val JobCurrentVisitSectionTag = "job-details-current-visit"

/** Identifies the represented Visit's own card, excluding its related follow-up request. */
const val JobDetailsVisitCardTag = "job-details-visit-card"

/** Identifies the follow-up Visit request status row on Job Details. */
const val JobDetailsFollowUpRequestTag = "job-details-follow-up-request"

/**
 * Identifies the Job Activity section: the Job's one account of what happened, grouped by the Visit each
 * update belongs to (`BR-080`).
 */
const val JobActivitySectionTag = "job-details-job-activity"

/** Identifies one Visit's group inside the Job Activity section. */
fun jobActivityVisitGroupTag(visitId: String): String = "job-details-activity-visit-$visitId"

/** Identifies the expand/collapse control of one Visit's activity group. */
fun jobActivityVisitToggleTag(visitId: String): String = "job-details-activity-visit-toggle-$visitId"

/**
 * Identifies what one expanded Visit group reveals: that Visit's own evidence and activity (`BR-080`).
 *
 * It no longer states the Visit's own facts — its date, its window, its outcome and its crew. Those are
 * the Visit section's and the group heading's, and repeating them here made a reader open a timeline to
 * find a second copy of what the page already said (`BR-047`, `BR-012`).
 */
fun jobActivityVisitBodyTag(visitId: String): String = "job-details-activity-visit-body-$visitId"

/**
 * Identifies what one Visit group states under its heading: where that field attempt ended up and what it
 * resulted in — `Completed · Needs follow-up` (`BR-074`, `BR-078`).
 */
fun jobActivityVisitSummaryTag(visitId: String): String =
    "job-details-activity-visit-summary-$visitId"

/** Identifies the state shown inside a Visit that has no updates yet. */
fun jobActivityVisitEmptyTag(visitId: String): String = "job-details-activity-visit-empty-$visitId"

/** Identifies the group holding the updates that belong to the Job and to no Visit (`BR-080`). */
const val JobActivityGeneralGroupTag = "job-details-activity-general"

/** Identifies the expand/collapse control of the general job updates group. */
const val JobActivityGeneralToggleTag = "job-details-activity-general-toggle"

/** Identifies what the expanded general job updates group reveals. */
const val JobActivityGeneralBodyTag = "job-details-activity-general-body"

/** Identifies the state shown when the Job has no job-wide update. */
const val JobActivityGeneralEmptyTag = "job-details-activity-general-empty"

/** Identifies the **Primary** badge on the Customer's phone row of the Job card (`BR-095`). */
const val JobDetailsCustomerPrimaryBadgeTag = "job-details-customer-primary-badge"

/** Identifies the disclosure holding the Customer's other ways of being reached (`BR-095`). */
const val JobDetailsCustomerOtherContactsTag = "job-details-customer-other-contacts"

/** Identifies the control that opens and closes that disclosure. */
const val JobDetailsCustomerOtherContactsToggleTag = "job-details-customer-other-contacts-toggle"

/*
 * Metrics of the redesigned page (`docs/design/android-design-system.md`): the 20 dp page gutter the
 * signed-in shell uses, 20 dp between sections and 16 dp inside a card, exactly as the screen this one
 * replaces. A group's header is a control, so it keeps the platform's minimum touch target, and it
 * states what the group holds on fixed lines, so expanding a Visit never reflows the header it was
 * opened from.
 */
private val OverviewPageGutter = 20.dp
private val OverviewSectionSpacing = 20.dp
private val OverviewBottomClearance = 72.dp
private val OverviewTrayClearance = 260.dp
private val OverviewReportSpacing = 8.dp
private val OverviewAvatarSize = 36.dp
private val OverviewMapAffordanceSize = 18.dp
private val OverviewActivityHeaderMinHeight = 56.dp
private val OverviewChevronSize = 18.dp
private val OverviewNestedSpacing = 12.dp
private val OverviewEvidenceStackSpacing = 8.dp

/**
 * The redesigned Job Details screen (`BR-010`, `BR-021`).
 *
 * It presents the **Job** and its **Visits** as the different things they are (`BR-047`): the Job's own
 * information — its number, title, description, lifecycle status, Customer and address (`BR-048`,
 * `BR-052`, `BR-056`, `BR-058`) — the Visit that represents it (`BR-081`), and the activity the read
 * reports, grouped by the Visit each update belongs to (`BR-080`).
 *
 * The screen this replaces presented one Visit with the whole Job Activity beneath it, so the account
 * of every Visit read as the represented Visit's own. Here the Job Activity section holds one foldable
 * group per Visit — each with that Visit's date, its schedule and its crew above its own entries — and
 * a group of its own for the events that belong to the Job and to no Visit, so a reader sees which
 * update belongs to which field attempt (`BR-059`, `BR-080`).
 *
 * Every value is the backend's (`BR-001`): the represented Visit is the one the read selected, the
 * Visits are the ones the read listed, and which events belong to which Visit is the API's own
 * `visitSequence` answer (`docs/api/job-activity.md` §3.3). The screen decides no lifecycle of its own —
 * the Job's destinations, the Visit's destinations and whether a Visit may be rescheduled all come from
 * the API, and the screen presents exactly what it was given (`BR-041`, `BR-007`).
 *
 * `JobDetailsScreen` remains in this package as the screen it replaces, and the destination draws this
 * one: restoring the previous presentation is a matter of pointing the destination back at it.
 */
@Composable
fun JobDetailsOverviewScreen(
    state: JobDetailsUiState,
    canUpdateJob: Boolean,
    canUpdateAssignedVisit: Boolean,
    canRecordVisitOutcome: Boolean,
    canAddVisitNote: Boolean,
    canAddEvidencePhoto: Boolean,
    canViewTechnicians: Boolean,
    /** Whether the session may review follow-up Visit requests (`BR-FV-004`). */
    canReviewVisitRequests: Boolean,
    /**
     * Whether the session may propose a follow-up Visit on the Job (`BR-FV-001`).
     *
     * It is the capability the API enforces on `POST /jobs/:id/visit-requests`, drawn on its own and
     * never inferred from the field capabilities a technician also holds (`BR-006`, `BR-011`): a company
     * may withdraw the proposal and keep the field work.
     */
    canRequestFollowUpVisit: Boolean,
    canOpenCustomer: Boolean,
    onRetry: () -> Unit,
    onRetryActivity: () -> Unit,
    onOpenCustomer: (customerId: String) -> Unit,
    onOpenInMaps: (address: CustomerJobAddress) -> Unit,
    onLoadAssignableTechnicians: () -> Unit,
    onChangeJobStatus: (status: JobStatus) -> Unit,
    onChangeVisitStatus: (status: VisitStatus) -> Unit,
    onCompleteVisit: (outcome: VisitOutcome, summary: String) -> Unit,
    onDiscardQueuedVisitAction: (operationId: String) -> Unit,
    onDiscardQueuedVisitNote: (operationId: String) -> Unit,
    onAddActivityText: (body: String) -> Unit,
    onEditVisitNote: (noteId: String, body: String) -> Unit,
    onRemoveVisitNote: (noteId: String, reason: String) -> Unit,
    onRescheduleVisit: (start: Instant, end: Instant) -> Unit,
    onScheduleVisit: (start: Instant, end: Instant, assignments: List<TechnicianAssignment>) -> Unit,
    /**
     * Submits the technician's follow-up Visit proposal (`BR-FV-001`, `BR-FV-003`).
     *
     * The request states a window, a reason and whether the same technician is preferred; it decides
     * nothing, because the office reviews it and creates the Visit (`BR-FV-002`, `BR-FV-004`).
     */
    onRequestFollowUpVisit: (
        reason: String,
        start: Instant,
        end: Instant,
        sameTechnicianPreferred: Boolean,
    ) -> Unit,
    onOpenRequest: (requestId: String) -> Unit,
    onOpenVisit: (visitId: String) -> Unit,
    onAssignTechnicians: (assignments: List<TechnicianAssignment>) -> Unit,
    onConfirmPendingAction: () -> Unit,
    onDismissPendingAction: () -> Unit,
    onDismissActionMessage: () -> Unit,
    onCapturePhoto: () -> Unit,
    onChoosePhotos: () -> Unit,
    onConfirmCapturedPhoto: (EvidencePhase, String?) -> Unit,
    onDiscardCapturedPhoto: () -> Unit,
    onKeepCapturedPhoto: () -> Unit,
    onRemovePendingPhoto: (String) -> Unit,
    onSubmitPendingPhotos: () -> Unit,
    onDismissPhotoMessage: () -> Unit,
    onSavePhoto: (String) -> Unit,
    onSharePhoto: (String, String) -> Unit,
    onRemoveEvidencePhoto: (String, String) -> Unit,
    onSavePermissionResult: (Boolean) -> Unit,
    canViewEvidence: Boolean,
    canRemoveEvidence: Boolean,
    canManageVisitNotes: Boolean,
    canAddAudio: Boolean,
    canRemoveAudioEvidence: Boolean,
    onSelectAudioPhase: (EvidencePhase) -> Unit,
    onStartAudioRecording: () -> Unit,
    onStopAudioRecording: () -> Unit,
    onCancelAudioRecording: () -> Unit,
    onToggleAudioPlayback: (String) -> Unit,
    onSeekAudioPlayback: (audioNoteId: String, positionMillis: Int) -> Unit,
    onAttachAudioNote: (String?) -> Unit,
    onRemovePendingAudioNote: (String) -> Unit,
    onRemoveEvidenceAudioNote: (String, String) -> Unit,
    onMicrophoneDenied: () -> Unit,
    onDismissAudioMessage: () -> Unit,
    audioProgress: State<JobAudioPlaybackProgress?>,
    // `modifier` leads the optional parameters, as the Compose conventions require: this screen is the
    // one *new* full-screen composable of the redesign, so it is written to the convention even where
    // its sibling's older signature is not.
    modifier: Modifier = Modifier,
    photoImages: JobPhotoImages = JobPhotoImages.None,
) {
    val details = state.details
    var showReschedule by rememberSaveable(state.jobId) { mutableStateOf(false) }
    var showSchedule by rememberSaveable(state.jobId) { mutableStateOf(false) }
    var showRequestFollowUp by rememberSaveable(state.jobId) { mutableStateOf(false) }
    var showAssign by rememberSaveable(state.jobId) { mutableStateOf(false) }
    var showAddActivity by rememberSaveable(state.jobId) { mutableStateOf(false) }
    var showVisitCompletion by rememberSaveable(state.jobId) { mutableStateOf(false) }
    var audioRemovalTargetId by remember { mutableStateOf<String?>(null) }
    var viewedPhotoId by rememberSaveable(state.jobId) { mutableStateOf<String?>(null) }
    // Adding an update is a Visit-scoped field write (`tracker 051`), so it is offered only where the API
    // allows one on this Visit **and** the session holds a capability that would perform it: the office
    // update capability for a note, or the evidence capability a photo or a recording needs
    // (`BR-006`, `BR-007`, `BR-027`).
    val canAddVisitUpdate = state.canAddVisitUpdate &&
        (canUpdateJob || canAddEvidencePhoto || canAddAudio)

    // Every photo the viewer pages through (`D11`), in the order this screen presents them, and the page
    // the tapped photo is on — so a swipe continues through exactly what the technician sees around the
    // photo they opened. The viewer is opened from a Visit's own activity as well as from the Job's, and
    // both are entries of the one Activity the backend reported.
    val viewedPhotos = viewedJobPhotoSequence(
        jobId = state.jobId,
        pendingPhotos = state.pendingPhotos,
        activity = state.activity,
    )
    val viewedPhotoPage = viewedPhotoId?.let { photoId ->
        jobPhotoViewerInitialPage(viewedPhotos, photoId)
    }
    val shareChooserTitle = stringResource(R.string.job_photo_viewer_share_title)
    val isViewerOpen = viewedPhotoPage != null

    // Android 8-9 guards a write into shared storage with a permission (`D12`). The screen asks for it
    // when a save says it needs one, and the answer goes back to that same save.
    val savePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = onSavePermissionResult,
    )
    LaunchedEffect(state.photoSaveAwaitingPermission) {
        if (state.photoSaveAwaitingPermission != null) {
            savePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }
    val snackbarHostState = remember { SnackbarHostState() }

    val actionFailure = state.actionFailure
    val completedAction = state.completedAction
    val photoFailure = state.photoFailure
    val photoMessage = state.photoMessage
    val audioFailure = state.audioFailure
    val audioMessage = state.audioMessage
    val actionMessage = when {
        actionFailure != null -> stringResource(jobActionFailureMessage(actionFailure))
        completedAction != null -> stringResource(
            // A queued action is not applied, so it is reported as saved on the device rather than as
            // done (`BR-001`, `BR-014`, §7).
            if (state.actionQueued) {
                jobActionQueuedMessage(completedAction)
            } else {
                jobActionCompletionMessage(completedAction)
            },
        )
        photoFailure != null -> {
            // A photo that could not be taken is named by its place in the pick, because a pick hands
            // over several at once and "a photo did not work" would not say which one to choose again
            // (`D3`, `BR-012`).
            val reason = stringResource(jobPhotoFailureMessage(photoFailure))
            val item = state.photoFailureItem
            if (item == null) {
                reason
            } else {
                stringResource(R.string.job_photo_error_item, item.position, item.total, reason)
            }
        }
        photoMessage != null -> stringResource(jobPhotoMessageText(photoMessage))
        audioFailure != null -> stringResource(jobAudioFailureMessage(audioFailure))
        audioMessage != null -> stringResource(jobAudioMessageText(audioMessage))
        else -> null
    }
    val dismissLabel = stringResource(R.string.job_action_dismiss)

    // The report of what the last action did is transient: the Job the API answered with already
    // presents the change, so the report is shown and released instead of being left standing in the
    // layout (`BR-001`, `BR-042`). A refusal waits for the user, because nothing on screen reflects a
    // change that did not happen and the refusal is the action's only report.
    LaunchedEffect(actionMessage, actionFailure != null, photoFailure != null, audioFailure != null) {
        if (actionMessage == null) {
            return@LaunchedEffect
        }
        val isFailure = actionFailure != null || photoFailure != null || audioFailure != null
        try {
            snackbarHostState.showSnackbar(
                visuals = JobActionSnackbarVisuals(
                    message = actionMessage,
                    isError = isFailure,
                    actionLabel = if (isFailure) dismissLabel else null,
                    duration = if (isFailure) {
                        SnackbarDuration.Indefinite
                    } else {
                        SnackbarDuration.Short
                    },
                ),
            )
        } finally {
            // Released once it has been shown, so re-entering the screen does not report an action the
            // Job on screen already shows.
            onDismissActionMessage()
            onDismissPhotoMessage()
            onDismissAudioMessage()
        }
    }

    Box(modifier = modifier.fillMaxSize().testTag(JobDetailsTag)) {
        when {
            details != null ->
                JobDetailsOverviewContent(
                    details = details,
                    state = state,
                    canUpdateJob = canUpdateJob,
                    // Stating a crew means choosing from the organization's technicians, so the action
                    // needs that capability as well (`BR-007`, `BR-068`).
                    canManageTechnicians = canViewTechnicians && state.canAssign,
                    canReviewVisitRequests = canReviewVisitRequests,
                    // A proposal names the field attempt it grew from (`BR-FV-008`), so the action exists
                    // only where the API reported a represented Visit that admits one — `COMPLETED` with a
                    // follow-up outcome (`BR-078`). It is drawn from the API's own answer rather than a
                    // client-side reading of the Visit's status or outcome (`BR-041`, `BR-042`), and it is
                    // deliberately **not** hidden for a Job `readOnlyReason` reports as closed: the request
                    // route accepts a proposal on a closed Job, and hiding the action would be a
                    // client-side rule the API does not state (`BR-042`).
                    canRequestFollowUpVisit = canRequestFollowUpVisit &&
                        details.selectedVisit?.requestFollowUpAllowed == true,
                    // Driving the represented Visit reaches the route through either authorization
                    // `BR-093` defines: the technician's field capability for their own assigned work,
                    // or the office capability that admits an office member without crew membership
                    // (`BR-008`, `ADR-019` D7).
                    canChangeVisitStatus = (canUpdateAssignedVisit || canUpdateJob) &&
                        state.canChangeVisitStatus,
                    // Completion is its own operation and asks for its own capability of every caller
                    // (`BR-009`, `BR-077`), so it is gated on both the session's capability and the
                    // API's answer on this Visit (`state.canCompleteVisit`).
                    canCompleteVisit = canRecordVisitOutcome && state.canCompleteVisit,
                    canOpenCustomer = canOpenCustomer,
                    onOpenCustomer = onOpenCustomer,
                    onOpenInMaps = onOpenInMaps,
                    onOpenAssign = {
                        showAssign = true
                        onLoadAssignableTechnicians()
                    },
                    onOpenReschedule = { showReschedule = true },
                    // Scheduling a Visit states the crew it will carry, so the technicians are read with
                    // the form, exactly as the crew sheet reads them (`BR-068`, `BR-024`).
                    onOpenSchedule = {
                        showSchedule = true
                        onLoadAssignableTechnicians()
                    },
                    onOpenRequestFollowUp = { showRequestFollowUp = true },
                    onOpenSubmittedFollowUpRequest = onOpenRequest,
                    onOpenSubmittedFollowUpVisit = onOpenVisit,
                    onChangeJobStatus = onChangeJobStatus,
                    onChangeVisitStatus = onChangeVisitStatus,
                    onOpenVisitCompletion = { showVisitCompletion = true },
                    onDiscardQueuedVisitAction = onDiscardQueuedVisitAction,
                    onDiscardQueuedVisitNote = onDiscardQueuedVisitNote,
                    onRetryActivity = onRetryActivity,
                    onOpenPhoto = { photoId -> viewedPhotoId = photoId },
                    canManageVisitNotes = canManageVisitNotes,
                    onEditVisitNote = onEditVisitNote,
                    onRemoveVisitNote = onRemoveVisitNote,
                    photoImages = photoImages,
                    // The recordings on this Job, as the timelines draw them: what the device player is
                    // playing, what the session may remove, and the two actions a recording offers
                    // (`BR-011`, `BR-089`, `ADR-018` A9).
                    audio = JobActivityAudio(
                        playback = state.audioPlayback,
                        progress = audioProgress,
                        playbackLoading = state.audioPlaybackLoading,
                        removal = state.audioRemoval,
                        canRemoveEvidence = canRemoveAudioEvidence,
                        onTogglePlayback = onToggleAudioPlayback,
                        onSeek = onSeekAudioPlayback,
                        onRemove = { audioNoteId -> audioRemovalTargetId = audioNoteId },
                    ),
                )

            state.showsFailure -> OverviewFailure(onRetry = onRetry)
            else -> OverviewLoading()
        }

        /*
         * Everything the screen pins to the bottom is one stack, and the report of what an action did is
         * the top of it, so neither the floating action nor the unaccepted evidence can cover a refusal
         * the manager has to read (`BR-042`). The report is drawn by whichever window is on screen: this
         * stack normally, and the photo viewer's own host while its full-screen dialog is open —
         * otherwise a save made from the viewer would report itself behind the viewer
         * (`docs/tracker/031-android-photo-viewer-ui.md`).
         */
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth(),
        ) {
            if (!isViewerOpen) {
                JobActionSnackbarHost(
                    hostState = snackbarHostState,
                    modifier = Modifier.padding(bottom = OverviewReportSpacing),
                )
            }

            // One action adds anything to the Job's Activity: it opens the sheet that states what kind
            // of update it is (`BR-012`, `BR-027`). A technician-created update is **Visit-scoped** — the
            // note carries the Visit's id and the evidence is recorded against the Visit the technician
            // is working — so the API's own answer on whether that Visit still takes field work
            // (`addUpdateAllowed`) is part of the gate: a Job the office canceled or completed while the
            // technician was offline offers no Add update at all (`BR-062`, `BR-079`). Each kind is then
            // drawn on the capability the API enforces for it: the Job update capability for a note, and
            // the evidence capability for a photo (`BR-006`, `BR-007`).
            // It is the page's own floating action: a technician's update is the write this page exists
            // for, so it is offered at every scroll position rather than in a card row the reader has to
            // reach, and it is the **only** Add update control — the Visit card draws no second one, so
            // two entry points for one write never make the technician choose a place before choosing
            // what they are recording (`BR-012`). It is withheld while the device holds unaccepted
            // evidence, because the bottom of the screen belongs to that work (`BR-014`).
            if (
                details != null &&
                canAddVisitUpdate &&
                !state.holdsUnacceptedEvidence
            ) {
                ExtendedFloatingActionButton(
                    modifier = Modifier
                        .align(Alignment.End)
                        .padding(16.dp)
                        .testTag(JobDetailsAddActivityTag),
                    onClick = { showAddActivity = true },
                    shape = MaterialTheme.shapes.large,
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_add),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.job_activity_add_update))
                }
            }

            // The evidence the backend has not accepted yet is the technician's working state, so while
            // the device holds any of it the bottom of the screen belongs to it, and the floating action
            // would otherwise sit on top of it (`BR-012`). Both kinds are stacked rather than one hiding
            // the other, because a Job can hold a photo waiting to save and a recording waiting to
            // attach at the same time (`§9`).
            if (state.holdsUnacceptedEvidence) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(OverviewEvidenceStackSpacing),
                ) {
                    if (state.pendingAudioNotes.isNotEmpty()) {
                        JobAudioNotice(
                            notes = state.pendingAudioNotes,
                            uploads = state.audioUploads,
                            isAttaching = state.isSubmittingAudio,
                            // The notice is not the sheet, so it attaches the take with the note
                            // already recorded on it (`BR-091`).
                            onAttach = { onAttachAudioNote(state.audioDraft?.note) },
                            onRemove = onRemovePendingAudioNote,
                        )
                    }
                    if (state.pendingPhotos.isNotEmpty()) {
                        JobPhotoTray(
                            photos = state.pendingPhotos,
                            uploads = state.photoUploads,
                            isSubmitting = state.isSubmittingPhotos,
                            onCapture = onCapturePhoto,
                            onSubmit = onSubmitPendingPhotos,
                            onRemove = onRemovePendingPhoto,
                            onOpen = { photoId -> viewedPhotoId = photoId },
                            photoImages = photoImages,
                        )
                    }
                }
            }
        }
    }

    if (details != null && showAddActivity) {
        JobUpdateSheet(
            // A note is the represented Visit's, so a Job with no Visit has none to write here. A note is
            // written by the office with `JOB_UPDATE` or by the technician with `VISIT_ADD_NOTE`, which is
            // the capability `BR-009` gives them and the one the API enforces on the route (`BR-015`,
            // `BR-051`, `BR-066`, `docs/api/job-actions.md` §2). The API keeps the office's
            // organization-wide reach and scopes a technician to the Visit's own crew, so the kind follows
            // that same split instead of offering a write the route answers with a refusal (`ADR-019` D2,
            // D3).
            canWriteNote = details.selectedVisit?.let { visit ->
                canUpdateJob || (canAddVisitNote && visit.fieldActionable)
            } == true,
            // A photo is authorized by the evidence capability, which the technician who records the field
            // evidence holds (`BR-006`, `BR-009`).
            canAddPhoto = canAddEvidencePhoto,
            // Audio is its own capability, so the kind is offered to a session that may record one and
            // withheld from a session that may not — the API enforces the same code on the route
            // (`BR-006`, `BR-007`, `ADR-018` A7).
            canAddAudio = canAddAudio,
            isSubmitting = state.isSubmitting,
            audioDraft = state.audioDraft,
            isRecordingAudio = state.audioRecording != null,
            isAttachingAudio = state.isSubmittingAudio,
            audioPhase = state.audioPhase,
            // The take under review plays back through the one player the feature owns, so whether it is
            // playing comes from the device rather than from the tap that asked, and where it has got to
            // is the same answer the timeline draws (`ADR-018` A9, A11).
            audioPlayback = state.audioPlayback,
            audioProgress = audioProgress,
            onConfirmNote = { body ->
                showAddActivity = false
                onAddActivityText(body)
            },
            // Both photo sources keep the flow the photo slice owns — capture or pick, then the review,
            // then the tray — so the sheet hands over rather than growing a second photo UI (`BR-015`,
            // `D3`).
            onTakePhoto = {
                showAddActivity = false
                onCapturePhoto()
            },
            onChoosePhotos = {
                showAddActivity = false
                onChoosePhotos()
            },
            onSelectAudioPhase = onSelectAudioPhase,
            onStartAudioRecording = onStartAudioRecording,
            onStopAudioRecording = onStopAudioRecording,
            onPlayAudioDraft = {
                state.audioDraft?.let { draft -> onToggleAudioPlayback(draft.audioNoteId) }
            },
            onSeekAudioDraft = onSeekAudioPlayback,
            // Deleting the take is the device's own removal of evidence the backend has not accepted; it
            // needs no capability, because nothing has been recorded yet (`BR-088`, `BR-091`).
            onDiscardAudioDraft = {
                state.audioDraft?.let { draft -> onRemovePendingAudioNote(draft.audioNoteId) }
            },
            onAttachAudio = { note ->
                showAddActivity = false
                onAttachAudioNote(note)
            },
            onMicrophoneDenied = onMicrophoneDenied,
            onDismiss = {
                // Leaving the sheet ends a recording that is still running: nothing was recorded, so
                // nothing is lost, and the microphone does not stay open behind a closed sheet
                // (`BR-091`).
                if (state.audioRecording != null) {
                    onCancelAudioRecording()
                }
                showAddActivity = false
            },
        )
    }

    // The Visit's completion: the outcome `BR-077` requires is stated here, and submitting sends the
    // completion operation itself, so the recorded outcome and the Visit's finished state are one
    // recorded fact (`BR-077`, `BR-078`).
    if (details != null && showVisitCompletion && details.selectedVisit != null) {
        VisitCompletionSheet(
            isSubmitting = state.isSubmitting,
            onConfirm = { outcome, summary ->
                showVisitCompletion = false
                onCompleteVisit(outcome, summary)
            },
            onDismiss = { showVisitCompletion = false },
        )
    }

    // The photo the technician just captured. Dismissing it keeps the photo — it is already recorded on
    // the device — so the explicit Discard is the only way to lose one (`BR-014`).
    state.capturedPhoto?.let { captured ->
        JobPhotoReviewSheet(
            photo = captured,
            initialPhase = state.photoPhase,
            isBusy = state.isSubmittingPhotos,
            photoImages = photoImages,
            onConfirm = onConfirmCapturedPhoto,
            onDiscard = onDiscardCapturedPhoto,
            onDismiss = onKeepCapturedPhoto,
        )
    }

    if (details != null && showReschedule) {
        details.selectedVisit?.let { visit ->
            RescheduleVisitDialog(
                visit = visit,
                isSubmitting = state.isSubmitting,
                onConfirm = { start, end ->
                    showReschedule = false
                    onRescheduleVisit(start, end)
                },
                onDismiss = { showReschedule = false },
            )
        }
    }

    if (details != null && showSchedule) {
        // Scheduling a Visit on the Job: the third field attempt is another Visit, never a new Job
        // (`BR-047`, `BR-051`). The sheet closes on confirm, and the screen reports what the API
        // answered — the scheduling was applied, or why it was refused (`BR-001`, `BR-042`).
        ScheduleVisitSheet(
            titleRes = R.string.schedule_visit_title,
            messageRes = R.string.schedule_visit_message,
            confirmRes = R.string.schedule_visit_confirm,
            initialStart = null,
            initialEnd = null,
            technicians = state.assignableTechnicians,
            crewFailure = state.assignableFailure,
            isSubmitting = state.isSubmitting,
            onRetryCrew = onLoadAssignableTechnicians,
            onConfirm = { start, end, assignments ->
                showSchedule = false
                onScheduleVisit(start, end, assignments)
            },
            onDismiss = { showSchedule = false },
        )
    }

    if (details != null && showRequestFollowUp) {
        // Proposing a follow-up Visit: the technician states what they suggest and why, and the office
        // decides what happens to it (`BR-FV-001`, `BR-FV-003`, `BR-FV-004`). The sheet closes on
        // confirm, and the screen reports the backend's own answer — the request was recorded, or why it
        // was not (`BR-001`, `BR-042`).
        RequestFollowUpVisitSheet(
            isSubmitting = state.isSubmitting,
            onConfirm = { reason, start, end, sameTechnicianPreferred ->
                showRequestFollowUp = false
                onRequestFollowUpVisit(reason, start, end, sameTechnicianPreferred)
            },
            onDismiss = { showRequestFollowUp = false },
        )
    }

    if (details != null && showAssign) {
        AssignTechniciansSheet(
            technicians = state.assignableTechnicians,
            assigned = details.technicians,
            failure = state.assignableFailure,
            isSubmitting = state.isSubmitting,
            onConfirm = { assignments ->
                showAssign = false
                onAssignTechnicians(assignments)
            },
            // The sheet is only useful with the technicians it offers, so the read is asked for again
            // when it is the read that failed.
            onRetry = onLoadAssignableTechnicians,
            onDismiss = { showAssign = false },
        )
    }

    state.pendingConfirmation?.let { pending ->
        ScheduleConflictDialog(
            conflicts = pending.conflicts,
            isSubmitting = state.isSubmitting,
            onConfirm = onConfirmPendingAction,
            onDismiss = onDismissPendingAction,
        )
    }

    // A recording the manager asked to remove is confirmed before anything is applied: the reason is what
    // the removal is recorded with, and the wording says what a removal does not do (`BR-067`, `BR-088`,
    // `BR-089`).
    audioRemovalTargetId?.let { audioNoteId ->
        EvidenceRemovalDialog(
            copy = JobAudioRemovalCopy,
            isRemoving = state.audioRemoval == audioNoteId,
            onConfirm = { reason ->
                audioRemovalTargetId = null
                onRemoveEvidenceAudioNote(audioNoteId, reason)
            },
            onDismiss = { audioRemovalTargetId = null },
        )
    }

    // A photo the technician taps opens full size, whether the backend already holds it or the device
    // still does (`D4`), and the viewer pages through the Job's other photos from there (`D11`). The
    // viewer is drawn over everything else, and closing it only forgets which photo it was: nothing about
    // the photo changes (`BR-001`, `BR-067`).
    if (viewedPhotoPage != null) {
        JobPhotoViewer(
            photos = viewedPhotos,
            initialPage = viewedPhotoPage,
            uploads = state.photoUploads,
            canExportEvidence = canViewEvidence,
            // Removing accepted evidence is its own capability, and a Manager-level one (`BR-089`): it is
            // never inferred from the ability to read or record a photo.
            canRemoveEvidence = canRemoveEvidence,
            export = state.photoExport,
            removal = state.photoRemoval,
            onSave = onSavePhoto,
            onShare = { photoId -> onSharePhoto(photoId, shareChooserTitle) },
            onRemove = onRemoveEvidencePhoto,
            photoImages = photoImages,
            reportHostState = snackbarHostState,
            onDismiss = { viewedPhotoId = null },
        )
    }
}

/** The first read, with nothing on screen yet. */
@Composable
private fun OverviewLoading() {
    Box(
        modifier = Modifier.fillMaxSize().testTag(JobDetailsLoadingTag),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.job_details_loading),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The Job could not be read, so the screen reports why and offers the read again. */
@Composable
private fun OverviewFailure(onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(OverviewPageGutter)
            .testTag(JobDetailsFailureTag),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.job_details_error_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.job_details_error_message),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = onRetry,
            modifier = Modifier.testTag(JobDetailsRetryTag),
            shape = MaterialTheme.shapes.medium,
        ) {
            Text(stringResource(R.string.job_details_retry))
        }
    }
}

/**
 * The Job, presented as a technician works it (`BR-010`, `BR-012`): what the work is, where it is, who can
 * be reached there, what the office recorded, the Visit being worked, and the Job's activity grouped by the
 * Visit each update belongs to (`BR-047`, `BR-080`).
 *
 * The order is the order of a technician's questions, so the page answers them without a hunt: the Job's
 * identity, then the location, then the people, then the instructions, then the field attempt itself. A
 * routine Job status is not among them — `ACTIVE` means only that the request is open, which a technician
 * acting on an assigned Visit already knows — so the Job's status appears as a control for a session that
 * may change it and as the notice that reports a closed Job otherwise (`BR-058`, `BR-059`, `BR-062`).
 */
@Composable
private fun JobDetailsOverviewContent(
    details: JobDetails,
    state: JobDetailsUiState,
    canUpdateJob: Boolean,
    canManageTechnicians: Boolean,
    canReviewVisitRequests: Boolean,
    canRequestFollowUpVisit: Boolean,
    canChangeVisitStatus: Boolean,
    canCompleteVisit: Boolean,
    canOpenCustomer: Boolean,
    onOpenCustomer: (customerId: String) -> Unit,
    onOpenInMaps: (address: CustomerJobAddress) -> Unit,
    onOpenAssign: () -> Unit,
    onOpenReschedule: () -> Unit,
    onOpenSchedule: () -> Unit,
    onOpenRequestFollowUp: () -> Unit,
    onOpenSubmittedFollowUpRequest: (requestId: String) -> Unit,
    onOpenSubmittedFollowUpVisit: (visitId: String) -> Unit,
    onChangeJobStatus: (status: JobStatus) -> Unit,
    onChangeVisitStatus: (status: VisitStatus) -> Unit,
    onOpenVisitCompletion: () -> Unit,
    onDiscardQueuedVisitAction: (operationId: String) -> Unit,
    onDiscardQueuedVisitNote: (operationId: String) -> Unit,
    onRetryActivity: () -> Unit,
    onOpenPhoto: (String) -> Unit,
    canManageVisitNotes: Boolean,
    onEditVisitNote: (String, String) -> Unit,
    onRemoveVisitNote: (String, String) -> Unit,
    photoImages: JobPhotoImages,
    audio: JobActivityAudio,
) {
    // What the page presents is derived once from the records the backend reported: the Job's own
    // information, the activity split by what it belongs to, and the Visits that activity belongs to
    // (`BR-001`, `BR-080`). Nothing is stored and nothing is derived from anything but the read's answer.
    val overview = remember(details) { details.toJobOverview() }
    val activityGroups = remember(details, state.activity) {
        groupJobActivity(state.activity, details.visits)
    }
    // The Job's Visits as activity groups, in the order a reader scans them: the Visit the read
    // represents — which is the one its own section presents and the one a group opens by default —
    // is named by `selectedVisit` (`BR-081`).
    val visitGroups = remember(details, activityGroups) {
        visitActivityGroups(details.visits, details.selectedVisit?.id, activityGroups)
    }
    val submittedFollowUpRequest = details.followUpVisitRequest?.let { request ->
        FollowUpRequestStatusModel(
            id = request.id,
            sourceVisitId = request.sourceVisitId,
            createdVisitId = request.createdVisitId,
            status = request.status,
        )
    } ?: state.submittedFollowUpRequest?.takeIf { request ->
        request.jobId == details.id && request.sourceVisitId == details.selectedVisit?.id
    }?.let { request ->
        FollowUpRequestStatusModel(
            id = request.id,
            sourceVisitId = request.sourceVisitId,
            createdVisitId = request.createdVisitId,
            status = request.status,
        )
    }
    // The page's scroll container: the sections and the timeline are one column, so the page scrolls as
    // a single record rather than as nested lists (`BR-012`, `BR-080`).
    val scrollState = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .testTag(JobDetailsContentTag)
            .padding(horizontal = OverviewPageGutter, vertical = OverviewPageGutter),
        verticalArrangement = Arrangement.spacedBy(OverviewSectionSpacing),
    ) {
        JobHeaderSection(
            overview = overview,
            statusControl = if (canUpdateJob && overview.allowedStatusTransitions.isNotEmpty()) {
                {
                    CompactJobStatusControl(
                        status = overview.status,
                        jobNumber = overview.jobNumber,
                        allowedTransitions = overview.allowedStatusTransitions,
                        enabled = state.canAct,
                        onChangeStatus = onChangeJobStatus,
                    )
                }
            } else {
                null
            },
        )
        if (state.detailsSource == ReadSource.WORKING_SET) {
            // Offline, the Job on screen is the last one the backend reported rather than a current
            // answer, and the screen says so instead of presenting a local copy as up to date
            // (`offline-first-architecture.md` §2, §7, `D5`).
            OfflineNotice(
                message = stringResource(R.string.offline_last_reported),
                tag = JobDetailsLastReportedTag,
            )
        }
        // A closed Job is stated before the work it holds, because it changes what the technician is
        // looking at: nothing further may be recorded, and every field control below is gone
        // (`BR-062`, `BR-079`). Cancellation is drawn prominently — the technician may be on their way
        // to, or standing at, a Job the office has already canceled (`BR-012`).
        details.readOnlyReason?.let { reason ->
            JobReadOnlyNotice(reason = reason)
        }
        JobLocationSection(address = overview.address, onOpenInMaps = onOpenInMaps)
        JobContactsSection(
            customerName = overview.customerName,
            contact = overview.customerContact,
            canOpenCustomer = canOpenCustomer,
            onOpenCustomer = { onOpenCustomer(overview.customerId) },
        )
        JobNotesSection(contact = overview.customerContact)
        CurrentVisitSection(
            details = details,
            // A reschedule edits the represented Visit, so the action exists only when there is one
            // (`BR-051`); whether it may be rescheduled now is the API's own answer (`BR-073`).
            showReschedule = canUpdateJob && details.selectedVisit != null,
            canReschedule = canUpdateJob && state.canReschedule,
            canChangeVisitStatus = canChangeVisitStatus,
            canCompleteVisit = canCompleteVisit,
            actionEnabled = state.canAct,
            canManageTechnicians = canManageTechnicians,
            scheduleAction = details.visitScheduleAction(),
            canRequestFollowUp = canRequestFollowUpVisit && submittedFollowUpRequest == null,
            submittedFollowUpRequest = submittedFollowUpRequest,
            canReviewFollowUpRequest = canReviewVisitRequests,
            onChangeVisitStatus = onChangeVisitStatus,
            onOpenVisitCompletion = onOpenVisitCompletion,
            onReschedule = onOpenReschedule,
            onSchedule = onOpenSchedule,
            onManageCrew = onOpenAssign,
            onRequestFollowUp = onOpenRequestFollowUp,
            onOpenSubmittedFollowUpRequest = onOpenSubmittedFollowUpRequest,
            onOpenSubmittedFollowUpVisit = onOpenSubmittedFollowUpVisit,
            queuedAction = state.queuedVisitAction,
            queuedNotes = state.queuedVisitNotes,
            onDiscardQueuedVisitAction = onDiscardQueuedVisitAction,
            onDiscardQueuedVisitNote = onDiscardQueuedVisitNote,
        )
        JobActivitySection(
            state = state,
            visitGroups = visitGroups,
            jobWide = activityGroups.jobWide,
            jobId = state.jobId,
            onRetry = onRetryActivity,
            onOpenPhoto = onOpenPhoto,
            canManageVisitNotes = canManageVisitNotes,
            onEditVisitNote = onEditVisitNote,
            onRemoveVisitNote = onRemoveVisitNote,
            photoImages = photoImages,
            audio = audio,
        )
        // Keeps the last entry clear of the floating Add update action the design places over the
        // timeline, so it is never covered, and clear of the photo tray when it is open, which is taller
        // than the action it replaces (`Figma/src/screens/JobDetails.tsx`).
        Spacer(
            Modifier.height(
                if (state.pendingPhotos.isEmpty()) {
                    OverviewBottomClearance
                } else {
                    OverviewTrayClearance
                },
            ),
        )
    }
}

/**
 * The Job's own information: its number, its title and what it is for (`BR-052`, `BR-053`).
 *
 * It is a heading and not a card: the Job's identity is the page's hierarchy, and putting it on a bordered
 * surface among the record cards made it one row of metadata rather than the answer to "what work am I
 * here to do" (`BR-012`). Nothing a Visit supplies is here, because the two are separate state machines
 * and separate schedules (`BR-059`).
 */
@Composable
private fun JobHeaderSection(
    overview: JobOverview,
    statusControl: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = Modifier.testTag(JobHeaderSectionTag),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = stringResource(R.string.job_details_job_number_format, overview.jobNumber),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = overview.title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        overview.description
            ?.takeIf { description -> description.isNotBlank() }
            ?.let { description ->
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        statusControl?.let { control ->
            Box(
                modifier = Modifier
                    .padding(top = 2.dp)
                    .testTag(JobStatusSectionTag),
            ) {
                control()
            }
        }
    }
}

/**
 * The Job's status, drawn only where a session may change it (`BR-058`, `BR-006`).
 *
 * The chip that presents the status **is** the control that moves it, so the state a user wants to change
 * is the thing they tap and no separate action is drawn beside it
 * (`docs/tracker/020-android-job-details-status-control.md`). A session that may not act on the Job is
 * shown no routine status: `ACTIVE` states only that the request is open, which a technician acting on an
 * assigned Visit already knows, and a Job that has *stopped* being open is reported by the notice above
 * instead (`BR-012`, `BR-062`).
 */
@Composable
private fun CompactJobStatusControl(
    status: JobStatus,
    jobNumber: Int,
    allowedTransitions: List<JobStatus>,
    enabled: Boolean,
    onChangeStatus: (JobStatus) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = stringResource(R.string.job_details_job_status_label),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        JobStatusAction(
            currentStatus = status,
            jobNumber = jobNumber,
            allowedTransitions = allowedTransitions,
            enabled = enabled,
            onSelect = onChangeStatus,
        )
    }
}

/**
 * Where the work is (`BR-049`, `BR-056`).
 *
 * The address is the Job's preserved snapshot, so it belongs to the Job and not to a Visit, and the row
 * opens it in the device's map application. It is its own section because it is the second question a
 * technician asks, and it was previously one row among the Job's metadata (`BR-012`).
 */
@Composable
private fun JobLocationSection(
    address: CustomerJobAddress?,
    onOpenInMaps: (address: CustomerJobAddress) -> Unit,
) {
    Column(
        modifier = Modifier.testTag(JobLocationSectionTag),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SectionLabel(label = stringResource(R.string.job_details_location_label), count = null)
        InfoCard {
            // A Job with no address has nothing to navigate to, so the row is only tappable — and only
            // marked as opening a map — when there is a location to open.
            val addressText = address
                ?.let { snapshot -> addressLine(snapshot) }
                ?.takeIf { line -> line.isNotBlank() }
            val navigableAddress = address?.takeIf { addressText != null }
            OverviewRow(
                label = stringResource(R.string.job_details_address_label),
                value = addressText ?: stringResource(R.string.job_details_no_address),
                modifier = Modifier.testTag(JobDetailsAddressTag),
                trailing = navigableAddress?.let { { OverviewMapAffordance() } },
                onClick = navigableAddress?.let { location -> { onOpenInMaps(location) } },
            )
        }
    }
}

/**
 * Who can be reached at the Customer, and how (`BR-092`, `BR-095`).
 *
 * A Customer may have several contact persons and none is required, so the section leads with **one**
 * contact — the effective primary (`BR-095`) — with the phone and email that person recorded, each of
 * which opens the device's dialer or mail client (`ADR-022` D6). Every other way of reaching the Customer
 * sits behind a disclosure that starts closed, so the number a technician came for is not buried under the
 * Customer's whole contact book (`BR-012`).
 *
 * Nothing is chosen here: which contact is the primary is the API's `isPrimary` answer, and a Customer
 * whose contact persons are all secondary is presented as its own primary rather than having one of them
 * promoted to the phone the technician dials (`BR-095`, `BR-041`). A session the API did not admit to the
 * Customer receives no block and is therefore shown nothing, because whether it may read the Customer is
 * the API's answer and never the client's (`BR-001`, `BR-007`).
 */
@Composable
private fun JobContactsSection(
    customerName: String,
    contact: JobCustomerContact?,
    canOpenCustomer: Boolean,
    onOpenCustomer: () -> Unit,
) {
    Column(
        modifier = Modifier.testTag(JobContactsSectionTag),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SectionLabel(label = stringResource(R.string.job_details_contacts_label), count = null)
        InfoCard {
            OverviewRow(
                label = stringResource(R.string.job_details_customer_label),
                value = customerName,
                modifier = Modifier.testTag(JobDetailsCustomerTag),
                // The Customer the Job belongs to is a customer of this organization, so the row opens
                // that customer — for a session whose read the API accepts. A technician holds no
                // `customers.view`, so the office destination would answer `403`; they read the contact
                // details in place instead (`BR-011`, `BR-092`).
                onClick = if (canOpenCustomer) onOpenCustomer else null,
            )
            contact?.let { details ->
                val summary = details.contactSummary(customerName)
                // A Customer that is its own primary and has nothing recorded under it says nothing the
                // row above has not already said, so the block is drawn only when it adds something: a
                // contact person who *is* the primary, a phone, an email, or someone else to reach
                // (`BR-012`, `BR-095`).
                val statesSomething =
                    !summary.primaryIsCustomer ||
                        summary.primaryPhone != null ||
                        summary.primaryEmail != null ||
                        summary.others.isNotEmpty()
                if (statesSomething) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    JobContactsPrimary(summary = summary)
                }
            }
        }
    }
}

/**
 * The Customer's effective primary contact, and every other way of reaching the Customer (`BR-095`).
 *
 * [summary] is the one contact the section leads with, with the values that person recorded, and the
 * disclosure under it is the rest. The disclosure is the same foldable heading the Job's other groups use,
 * so a second way of presenting one exists nowhere (`docs/design/android-design-system.md`).
 */
@Composable
private fun JobContactsPrimary(summary: JobCustomerContactSummary) {
    val context = LocalContext.current
    val phone = summary.primaryPhone?.takeIf { value -> value.isNotBlank() }
    val email = summary.primaryEmail?.takeIf { value -> value.isNotBlank() }
    Column(
        modifier = Modifier.fillMaxWidth().testTag(JobDetailsContactPrimaryTag),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // The flagged contact person is named, because the section has to say *who* the technician is
        // about to speak to. When the Customer is its own primary it is not named again: the row above
        // already states the Customer's name, and one card does not state one value twice (`BR-012`).
        if (!summary.primaryIsCustomer) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    modifier = Modifier.weight(1f),
                    text = summary.primaryName,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                // The badge marks the effective primary and nothing else (`BR-095`).
                ContactPrimaryBadge(Modifier.testTag(JobDetailsCustomerPrimaryBadgeTag))
            }
        }
        // A value the office never recorded is left out rather than drawn as an empty line (`BR-012`),
        // and each value that is recorded opens the application that reaches that person (`ADR-022` D6).
        // When the Customer is its own primary the marker rides the first value it recorded, because that
        // line is where the primary is then read from (`BR-095`).
        phone?.let { number ->
            OverviewContactLine(
                value = number,
                glyph = R.drawable.ic_phone,
                onClick = { context.startContactIntent(dialIntent(number)) },
                trailing = if (summary.primaryIsCustomer) {
                    { ContactPrimaryBadge(Modifier.testTag(JobDetailsCustomerPrimaryBadgeTag)) }
                } else {
                    null
                },
            )
        }
        email?.let { address ->
            OverviewContactLine(
                value = address,
                glyph = R.drawable.ic_mail,
                onClick = { context.startContactIntent(mailIntent(address)) },
                trailing = if (summary.primaryIsCustomer && phone == null) {
                    { ContactPrimaryBadge(Modifier.testTag(JobDetailsCustomerPrimaryBadgeTag)) }
                } else {
                    null
                },
            )
        }
        if (summary.others.isNotEmpty()) {
            OverviewOtherContacts(others = summary.others)
        }
    }
}

/**
 * One contact value that reaches the person it belongs to, with an optional trailing marker.
 *
 * The value is the shared [CustomerContactLine] the other Servora screens draw, so the app reaches a person
 * the same way everywhere (`ADR-022` D6); the marker is separate from the line because only the effective
 * primary carries one (`BR-095`).
 */
@Composable
private fun OverviewContactLine(
    value: String,
    glyph: Int,
    onClick: () -> Unit,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CustomerContactLine(text = value, glyph = glyph, onClick = onClick)
        trailing?.invoke()
    }
}

/**
 * The notes the office recorded for this Customer (`BR-092`).
 *
 * They are their own section rather than a row of the Contacts block, because a note is what a technician
 * needs to know before working — an access instruction, a parking restriction, a time the Customer must not
 * be disturbed — and burying it among the Customer's metadata is how an instruction is missed
 * (`BR-012`). It is the notes field the field read already returns; no second notes domain is invented
 * (`BR-042`).
 */
@Composable
private fun JobNotesSection(contact: JobCustomerContact?) {
    val notes = contact?.notes?.takeIf { value -> value.isNotBlank() } ?: return
    Column(
        modifier = Modifier.testTag(JobNotesSectionTag),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SectionLabel(
            label = stringResource(R.string.job_details_customer_notes_label),
            count = null,
        )
        InfoCard {
            Text(
                text = notes,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.testTag(JobDetailsCustomerNotesTag),
            )
        }
    }
}

/**
 * The Visit that represents the Job (`BR-081`), with the actions that address it.
 *
 * Everything here belongs to this one field attempt: when it is scheduled for, where it is in its field
 * lifecycle and who is on its crew (`BR-068`, `BR-072`, `BR-074`). It is labelled as a Visit's — and, since
 * the read selects the Visit that represents the Job (`BR-081`), the label states **when that field
 * attempt is for** as of the device's own clock rather than calling every one of them current. It is
 * the only Visit whose actions the screen offers, because the actions the API reports — reschedule, field
 * status, crew — address the Visit the read selected (`BR-041`, `BR-073`).
 *
 * The Visit's own account of what happened is not repeated here: it is this Visit's group in the Job
 * Activity section, which states the same Visit once (`BR-080`, `BR-047`).
 */
@Composable
private fun CurrentVisitSection(
    details: JobDetails,
    showReschedule: Boolean,
    canReschedule: Boolean,
    canChangeVisitStatus: Boolean,
    canCompleteVisit: Boolean,
    actionEnabled: Boolean,
    canManageTechnicians: Boolean,
    scheduleAction: JobVisitScheduleAction?,
    /**
     * Whether the session may propose a follow-up Visit on this field attempt (`BR-FV-001`).
     *
     * It is the capability gate only: what the request carries and when the office decides about it are
     * `BR-FV-003` and `BR-FV-004`, and the API authorizes the write whatever this screen draws
     * (`BR-007`).
     */
    canRequestFollowUp: Boolean,
    submittedFollowUpRequest: FollowUpRequestStatusModel?,
    canReviewFollowUpRequest: Boolean,
    onChangeVisitStatus: (VisitStatus) -> Unit,
    onOpenVisitCompletion: () -> Unit,
    onReschedule: () -> Unit,
    onSchedule: () -> Unit,
    onManageCrew: () -> Unit,
    onRequestFollowUp: () -> Unit,
    onOpenSubmittedFollowUpRequest: (requestId: String) -> Unit,
    onOpenSubmittedFollowUpVisit: (visitId: String) -> Unit,
    queuedAction: QueuedVisitFieldAction?,
    queuedNotes: List<QueuedVisitNote>,
    onDiscardQueuedVisitAction: (operationId: String) -> Unit,
    onDiscardQueuedVisitNote: (operationId: String) -> Unit,
) {
    val visit = details.selectedVisit
    // The working states the Visit may be driven to, as the API reported them (`BR-074`). The list is
    // already narrowed to what this caller may execute, so it is the whole answer: no lifecycle rule is
    // re-implemented here (`BR-041`, `BR-093`). A Job the office has closed (`BR-062`, `BR-079`) offers
    // no destination at all.
    val destinations = if (details.readOnlyReason == null) {
        visit?.allowedStatusTransitions.orEmpty()
    } else {
        emptyList()
    }
    val canDriveVisit = canChangeVisitStatus && destinations.isNotEmpty()
    Column(
        modifier = Modifier.testTag(JobCurrentVisitSectionTag),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // The section states when the Visit it represents is for, as of the device's own clock: the read
        // selected the Visit (`BR-081`) and this says whether that field attempt is under way, today,
        // tomorrow, later or past — an unqualified "Current visit" in front of work two days away would
        // tell the reader something false (`BR-072`, `BR-074`).
        SectionLabel(
            label = stringResource(representedVisitLabel(representedVisitPeriod(visit, Instant.now()))),
            count = null,
        )
        InfoCard(modifier = Modifier.testTag(JobDetailsVisitCardTag)) {
            if (visit == null) {
                Text(
                    text = stringResource(R.string.job_details_no_selected_visit_detail),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                OverviewRow(
                    label = stringResource(R.string.job_details_visit_date_label),
                    value = overviewScheduleText(visit.scheduledStart),
                    modifier = Modifier.testTag(JobDetailsScheduleTag),
                    // Presented, not controlled, when this caller may not drive the Visit: an action
                    // nobody may perform is not one to offer (`BR-006`, `BR-007`).
                    trailing = {
                        if (!canDriveVisit) {
                            VisitStatusPill(status = visit.status)
                        }
                    },
                )
                if (canDriveVisit) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = stringResource(R.string.job_visit_status_label),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        // The four technician working states are the control, and the state the Visit is
                        // in is the selected chip: the technician taps where they are, and every one of
                        // the four is a destination of its own (`BR-074`, `BR-075`).
                        VisitWorkingStatusSelector(
                            status = visit.status,
                            allowedTransitions = destinations,
                            enabled = actionEnabled,
                            onSelect = onChangeVisitStatus,
                        )
                    }
                }
                if (canCompleteVisit) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    // Completing the field attempt is the Visit's own business action: it states the
                    // outcome `BR-077` requires, which is why completion is deliberately not one of the
                    // states above. Adding an update is the page's floating action instead, so it is not
                    // drawn a second time here — one write keeps one entry point (`BR-012`).
                    VisitCompleteAction(
                        enabled = actionEnabled,
                        onClick = onOpenVisitCompletion,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag(JobDetailsVisitFieldActionsTag),
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.job_details_technicians_label),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (details.technicians.isEmpty()) {
                    OverviewUnassignedCrew()
                } else {
                    details.technicians.forEach { technician -> OverviewTechnicianRow(technician) }
                }
            }
            if (showReschedule || canManageTechnicians || scheduleAction != null || canRequestFollowUp) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        // A session holding every capability these actions are drawn on may have more of
                        // them than a phone is wide, so the row scrolls rather than clipping the last
                        // control (`BR-011`, `Project.md` §11).
                        .horizontalScroll(rememberScrollState())
                        .testTag(JobDetailsVisitActionsTag),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (showReschedule) {
                        JobContextualAction(
                            text = stringResource(R.string.job_action_reschedule),
                            // Whether the Visit may be rescheduled now is the API's answer, not a rule
                            // re-implemented here (`BR-073`, `BR-041`).
                            enabled = canReschedule,
                            testTag = JobDetailsRescheduleActionTag,
                            onClick = onReschedule,
                        )
                    }
                    // The crew is stated as a whole, so the same action adds a technician, removes one and
                    // names the Lead (`BR-068`, `BR-069`).
                    if (canManageTechnicians) {
                        JobContextualAction(
                            text = stringResource(R.string.job_action_manage_technicians),
                            enabled = true,
                            testTag = JobDetailsManageTechniciansActionTag,
                            onClick = onManageCrew,
                        )
                    }
                    // Scheduling a new Visit is not offered merely because one already exists: active
                    // Visits keep actions focused on their own schedule and crew, while a completed Visit
                    // with a follow-up outcome gets an explicit follow-up scheduling action.
                    scheduleAction?.let { action ->
                        JobContextualAction(
                            text = stringResource(
                                when (action) {
                                    JobVisitScheduleAction.INITIAL_VISIT -> R.string.job_action_schedule_visit
                                    JobVisitScheduleAction.FOLLOW_UP_VISIT ->
                                        R.string.job_action_schedule_follow_up_visit
                                },
                            ),
                            // A property-less Job has no service location to put on the Visit, so the
                            // API would refuse the schedule write. Keep the initial scheduling affordance
                            // visible for no-Visit Jobs, but do not let the client submit an impossible
                            // schedule (`BR-072`, `BR-056`).
                            enabled = actionEnabled && details.address != null,
                            testTag = JobDetailsScheduleVisitActionTag,
                            onClick = onSchedule,
                        )
                    }
                    // A field attempt that needs more work is proposed by its technician and decided by
                    // the office (`BR-FV-001`, `BR-FV-004`): the action states a request, so it is drawn
                    // on its own capability and never on the office scheduling one (`BR-006`, `BR-011`).
                    if (canRequestFollowUp) {
                        JobContextualAction(
                            text = stringResource(R.string.job_action_request_follow_up),
                            enabled = actionEnabled,
                            testTag = JobDetailsRequestFollowUpActionTag,
                            onClick = onRequestFollowUp,
                        )
                    }
                }
            }
        }
        submittedFollowUpRequest?.let { request ->
            FollowUpRequestCard(
                request = request,
                sourceVisit = details.visits.firstOrNull { it.id == request.sourceVisitId },
                resultingVisit = details.visits.firstOrNull { it.id == request.createdVisitId },
                canReview = canReviewFollowUpRequest,
                onOpen = { onOpenSubmittedFollowUpRequest(request.id) },
                onOpenResultingVisit = onOpenSubmittedFollowUpVisit,
            )
        }
        // What the queue is still holding sits directly under the Visit it will move, so the technician
        // sees their own unfinished work beside the record it changes rather than only in the activity the
        // backend reported (`BR-012`, `BR-080`, §7).
        VisitFieldPendingNotice(
            action = queuedAction,
            notes = queuedNotes,
            onDiscardAction = onDiscardQueuedVisitAction,
            onDiscardNote = onDiscardQueuedVisitNote,
        )
    }
}

private data class FollowUpRequestStatusModel(
    val id: String,
    val sourceVisitId: String?,
    val createdVisitId: String?,
    val status: FollowUpVisitRequestStatus,
)

/** A follow-up proposal, kept separate from the completed Visit that produced it. */
@Composable
private fun FollowUpRequestCard(
    request: FollowUpRequestStatusModel,
    sourceVisit: JobDetailsVisitSummary?,
    resultingVisit: JobDetailsVisitSummary?,
    canReview: Boolean,
    onOpen: () -> Unit,
    onOpenResultingVisit: (visitId: String) -> Unit,
) {
    InfoCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .testTag(JobDetailsFollowUpRequestTag),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.job_follow_up_request_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            RequestStatusPill(status = request.status)
        }
        Text(
            text = sourceVisit?.let { visit ->
                stringResource(
                    R.string.job_follow_up_request_source,
                    overviewScheduleText(visit.scheduledStart),
                )
            } ?: stringResource(R.string.job_follow_up_request_source_generic),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (request.status == FollowUpVisitRequestStatus.APPROVED) {
            if (resultingVisit == null) {
                Text(
                    text = stringResource(R.string.job_follow_up_request_awaiting_schedule),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    text = stringResource(
                        R.string.job_follow_up_request_scheduled_for,
                        overviewScheduleText(resultingVisit.scheduledStart),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { onOpenResultingVisit(resultingVisit.id) },
                )
            }
        }
        Text(
            text = stringResource(
                when {
                    canReview && request.status.isAwaitingManagerReview() ->
                        R.string.job_follow_up_request_review
                    canReview -> R.string.job_follow_up_request_view
                    else -> R.string.job_follow_up_request_open
                },
            ),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

private fun FollowUpVisitRequestStatus.isAwaitingManagerReview(): Boolean =
    this == FollowUpVisitRequestStatus.PENDING ||
        this == FollowUpVisitRequestStatus.NEEDS_CLARIFICATION


/**
 * The Job's activity, grouped by the Visit each update belongs to (`BR-080`).
 *
 * Job Activity is one read spanning the Job and every Visit, and a reader's first question about an entry
 * is which field attempt it happened on. The section answers that before the entry is read: one foldable
 * group per Visit — newest first, with the Visit the page represents opened by default — each headed
 * `Visit N · date` and stating where that attempt ended up and what it resulted in, and a group of its own
 * for the events that belong to the Job and to no Visit (`BR-047`, `BR-059`, `BR-081`).
 *
 * The groups are not cards themselves: the page is an operational one, so a group is a heading with a
 * timeline under it (`BR-012`). What a group reveals is that Visit's own evidence and its own entries —
 * never a second statement of the Visit's schedule, its crew or its outcome, which the Visit section and
 * the heading already state (`BR-047`, `BR-072`, `BR-077`).
 *
 * The read's own states are reported once, for the section as a whole — loading, and a failure with its
 * retry. Emptiness is reported where it is true: a Visit whose group has no update says so, and the Job's
 * own group says when no job-wide update exists, so nothing is invented and nothing is hidden (`BR-042`).
 */
@Composable
private fun JobActivitySection(
    state: JobDetailsUiState,
    visitGroups: List<VisitActivityGroup>,
    jobWide: List<JobActivityEvent>,
    jobId: String,
    onRetry: () -> Unit,
    onOpenPhoto: (String) -> Unit,
    canManageVisitNotes: Boolean,
    onEditVisitNote: (String, String) -> Unit,
    onRemoveVisitNote: (String, String) -> Unit,
    photoImages: JobPhotoImages,
    audio: JobActivityAudio,
) {
    Column(
        modifier = Modifier.testTag(JobActivitySectionTag),
        verticalArrangement = Arrangement.spacedBy(OverviewNestedSpacing),
    ) {
        SectionLabel(
            label = stringResource(R.string.job_activity_label),
            count = primaryActivityCount(state.activity).takeIf { count -> count > 0 },
        )
        if (state.activitySource == ReadSource.WORKING_SET && state.activity != null) {
            // The entries these groups draw are the last the backend reported: the section says so rather
            // than letting a local copy read as a fresh answer (`offline-first-architecture.md` §7, `D5`).
            OfflineNotice(
                message = stringResource(R.string.offline_last_reported),
                tag = JobActivityLastReportedTag,
            )
        }
        when {
            state.showsActivityLoading -> ActivityLoading()
            state.activityFailure != null -> ActivityFailure(onRetry = onRetry)
            else -> {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    visitGroups.forEach { group ->
                        VisitActivityGroupRow(
                            group = group,
                            jobId = jobId,
                            photoImages = photoImages,
                            onOpenPhoto = onOpenPhoto,
                            canManageVisitNotes = canManageVisitNotes,
                            onEditVisitNote = onEditVisitNote,
                            onRemoveVisitNote = onRemoveVisitNote,
                            audio = audio,
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
                GeneralJobUpdatesGroup(
                    events = jobWide,
                    jobId = jobId,
                    photoImages = photoImages,
                    onOpenPhoto = onOpenPhoto,
                    canManageVisitNotes = canManageVisitNotes,
                    onEditVisitNote = onEditVisitNote,
                    onRemoveVisitNote = onRemoveVisitNote,
                    audio = audio,
                )
            }
        }
    }
}

/**
 * One Visit's group inside the Job Activity section: its updates, under a heading that says which Visit
 * they belong to (`BR-047`, `BR-080`).
 *
 * The heading states what a reader scans a field attempt for — which Visit it is, when it was for, where
 * it ended up and what it resulted in (`Completed · Needs follow-up`) — on lines that wrap with an
 * ellipsis rather than pressing against the chevron, and the whole heading is the control so it keeps the
 * platform's minimum touch target. Opening it never reflows the heading it was opened from (`BR-012`).
 * The heading does not repeat the Visit's card: the crew, the scheduled window and the outcome's own card
 * were a second statement of what the Visit section already says, and what a group reveals is that Visit's
 * account and its evidence (`BR-080`).
 *
 * The Visit the page represents opens by default, whatever its position, because it is the Visit the page's
 * own section presents (`BR-081`); every other Visit's group starts folded. The folded state is
 * presentation and belongs to the group: it survives recomposition and a configuration change, and it is
 * held per Visit, so two groups open and close independently (`BR-042`).
 *
 * The chevron's description states the action **and** which Visit it belongs to, and the heading reports
 * whether that Visit is open, because a screen reader announcing "expand" alone would not say which of
 * several Visits it would open nor whether it is already open (`BR-011`, `BR-028`).
 */
@Composable
private fun VisitActivityGroupRow(
    group: VisitActivityGroup,
    jobId: String,
    photoImages: JobPhotoImages,
    onOpenPhoto: (String) -> Unit,
    canManageVisitNotes: Boolean,
    onEditVisitNote: (String, String) -> Unit,
    onRemoveVisitNote: (String, String) -> Unit,
    audio: JobActivityAudio,
) {
    val visit = group.visit
    // The Visit this page represents opens by default, so the current field attempt's account is on
    // screen without a tap; the Job's earlier attempts stay folded until they are asked for (`BR-012`).
    var expanded by rememberSaveable(visit.id) { mutableStateOf(group.isRepresentedVisit) }
    val toggleLabel = stringResource(
        if (expanded) {
            R.string.job_activity_visit_collapse_label
        } else {
            R.string.job_activity_visit_expand_label
        },
        visit.sequence,
    )
    val stateLabel = stringResource(
        if (expanded) R.string.job_activity_group_expanded else R.string.job_activity_group_collapsed,
    )
    // The heading is `Visit N · date`: the number says which field attempt of the Job it is, which is
    // what tells two Visits of similar dates apart, and the date is what the group was for (`BR-052`).
    val title = stringResource(
        R.string.job_activity_visit_title_format,
        visit.sequence,
        overviewDateText(visit.scheduledStart),
    )
    Column(modifier = Modifier.fillMaxWidth().testTag(jobActivityVisitGroupTag(visit.id))) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.medium)
                .clickable(onClickLabel = toggleLabel) { expanded = !expanded }
                // The heading is the control, so it states whether the Visit is open as well as what
                // opening it does (`BR-028`, `BR-011`).
                .semantics { stateDescription = stateLabel }
                .heightIn(min = OverviewActivityHeaderMinHeight)
                .padding(vertical = 8.dp)
                .testTag(jobActivityVisitToggleTag(visit.id)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                // The title and the summary wrap rather than pressing against the chevron: a long date or
                // a longer translation shortens with an ellipsis instead of overlapping the control
                // beside it (`BR-028`).
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                // Where the field attempt ended up and what it resulted in, in the field's own words —
                // `Completed · Needs follow-up` — so a folded group answers what happened without being
                // opened and without a second status badge beside a status already written out
                // (`BR-074`, `BR-078`, `BR-012`).
                Text(
                    text = visitActivitySummary(visit),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag(jobActivityVisitSummaryTag(visit.id)),
                )
            }
            Spacer(Modifier.width(8.dp))
            // The chevron says the group opens, and it turns a quarter turn while it is open — the
            // disclosure the customer detail and the photo gallery already use
            // (`docs/design/android-design-system.md`).
            Icon(
                painter = painterResource(R.drawable.ic_chevron_right),
                contentDescription = toggleLabel,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(OverviewChevronSize)
                    .rotate(if (expanded) 90f else 0f),
            )
        }
        if (expanded) {
            VisitActivityGroupBody(
                group = group,
                jobId = jobId,
                photoImages = photoImages,
                onOpenPhoto = onOpenPhoto,
                canManageVisitNotes = canManageVisitNotes,
                onEditVisitNote = onEditVisitNote,
                onRemoveVisitNote = onRemoveVisitNote,
                audio = audio,
            )
        }
    }
}

/**
 * What one expanded Visit group reveals: what happened on that Visit (`BR-080`).
 *
 * It is the Visit's own evidence and its own entries, in the order the backend reported them, and nothing
 * else: the day the group was for is its heading, its status and its outcome are its summary line, and the
 * schedule, the crew and the outcome card belong to the Visit section. Repeating them here made a reader
 * open a timeline to find a second copy of what the page already said (`BR-047`, `BR-012`).
 *
 * The evidence the backend accepted is read as a compact collection above the account of what happened, and
 * a photo also stays in the entry that recorded it, so a reader can browse the evidence or follow when it
 * was captured (`BR-015`, `BR-080`). A Visit no one has written anything on says so rather than reading as
 * a Visit the page left out (`BR-042`).
 */
@Composable
private fun VisitActivityGroupBody(
    group: VisitActivityGroup,
    jobId: String,
    photoImages: JobPhotoImages,
    onOpenPhoto: (String) -> Unit,
    canManageVisitNotes: Boolean,
    onEditVisitNote: (String, String) -> Unit,
    onRemoveVisitNote: (String, String) -> Unit,
    audio: JobActivityAudio,
) {
    val visit = group.visit
    val primary = group.activity.primaryActivity()
    val administrative = group.activity.administrativeActivity()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = OverviewNestedSpacing)
            .testTag(jobActivityVisitBodyTag(visit.id)),
        verticalArrangement = Arrangement.spacedBy(OverviewNestedSpacing),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionLabel(
                label = stringResource(R.string.job_details_visit_activity_label),
                count = primary.size.takeIf { count -> count > 0 },
            )
            if (group.activity.isEmpty()) {
                Text(
                    text = stringResource(R.string.job_details_visit_activity_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag(jobActivityVisitEmptyTag(visit.id)),
                )
            } else {
                val photos = jobActivityPhotos(primary)
                if (photos.isNotEmpty()) {
                    JobPhotoGallerySection(
                        photos = photos,
                        jobId = jobId,
                        photoImages = photoImages,
                        onOpenPhoto = onOpenPhoto,
                    )
                }
                if (primary.isEmpty()) {
                    Text(
                        text = stringResource(R.string.job_details_visit_activity_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag(jobActivityVisitEmptyTag(visit.id)),
                    )
                } else {
                    ActivityTimeline(
                        events = primary,
                        jobId = jobId,
                        photoImages = photoImages,
                        onOpenPhoto = onOpenPhoto,
                        canManageVisitNotes = canManageVisitNotes,
                        onEditVisitNote = onEditVisitNote,
                        onRemoveVisitNote = onRemoveVisitNote,
                        audio = audio,
                    )
                }
                AdministrativeActivityGroup(
                    events = administrative,
                    jobId = jobId,
                    photoImages = photoImages,
                    onOpenPhoto = onOpenPhoto,
                    canManageVisitNotes = canManageVisitNotes,
                    onEditVisitNote = onEditVisitNote,
                    onRemoveVisitNote = onRemoveVisitNote,
                    audio = audio,
                )
            }
        }
    }
}

/**
 * The updates that belong to the Job itself and to no Visit (`BR-080`).
 *
 * It is the Job's own part of the one account: its status changes, its location and customer changes, and
 * the evidence recorded against the Job. Every Visit's own events sit in that Visit's group instead, so this
 * group is never read as the account of a field attempt, and a field attempt's entries are never read as
 * the Job's.
 *
 * It starts **folded**, because it holds office-level history rather than the technician's own field work:
 * a technician's updates are Visit-scoped (`tracker 051`), so what is here is what the office did to the
 * Job. It stays one tap away rather than being removed, because it is where the Job's own evidence is read
 * from (`BR-015`, `BR-012`). A Job with no job-wide event says so once it is opened rather than reading as
 * a Job nothing happened to (`BR-042`).
 */
@Composable
private fun GeneralJobUpdatesGroup(
    events: List<JobActivityEvent>,
    jobId: String,
    photoImages: JobPhotoImages,
    onOpenPhoto: (String) -> Unit,
    canManageVisitNotes: Boolean,
    onEditVisitNote: (String, String) -> Unit,
    onRemoveVisitNote: (String, String) -> Unit,
    audio: JobActivityAudio,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val toggleLabel = stringResource(
        if (expanded) {
            R.string.job_activity_general_collapse_label
        } else {
            R.string.job_activity_general_expand_label
        },
    )
    val stateLabel = stringResource(
        if (expanded) R.string.job_activity_group_expanded else R.string.job_activity_group_collapsed,
    )
    Column(modifier = Modifier.fillMaxWidth().testTag(JobActivityGeneralGroupTag)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.medium)
                .clickable(onClickLabel = toggleLabel) { expanded = !expanded }
                .semantics { stateDescription = stateLabel }
                .heightIn(min = OverviewActivityHeaderMinHeight)
                .padding(vertical = 8.dp)
                .testTag(JobActivityGeneralToggleTag),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.job_details_job_updates_label),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Icon(
                painter = painterResource(R.drawable.ic_chevron_right),
                contentDescription = toggleLabel,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(OverviewChevronSize)
                    .rotate(if (expanded) 90f else 0f),
            )
        }
        if (expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = OverviewNestedSpacing)
                    .testTag(JobActivityGeneralBodyTag),
                verticalArrangement = Arrangement.spacedBy(OverviewNestedSpacing),
            ) {
                if (events.isEmpty()) {
                    Text(
                        text = stringResource(R.string.job_details_job_updates_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag(JobActivityGeneralEmptyTag),
                    )
                } else {
                    // The photos the backend accepted, read as a collection above the account of what
                    // happened (`BR-080`). Photos still held by this device are the tray's, because the
                    // backend has not accepted them yet (`BR-001`).
                    val primary = events.primaryActivity()
                    val administrative = events.administrativeActivity()
                    val photos = jobActivityPhotos(primary)
                    if (photos.isNotEmpty()) {
                        JobPhotoGallerySection(
                            photos = photos,
                            jobId = jobId,
                            photoImages = photoImages,
                            onOpenPhoto = onOpenPhoto,
                        )
                    }
                    if (primary.isEmpty()) {
                        Text(
                            text = stringResource(R.string.job_details_job_updates_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag(JobActivityGeneralEmptyTag),
                        )
                    } else {
                        ActivityTimeline(
                            events = primary,
                            jobId = jobId,
                            photoImages = photoImages,
                            onOpenPhoto = onOpenPhoto,
                            canManageVisitNotes = canManageVisitNotes,
                            onEditVisitNote = onEditVisitNote,
                            onRemoveVisitNote = onRemoveVisitNote,
                            audio = audio,
                        )
                    }
                    AdministrativeActivityGroup(
                        events = administrative,
                        jobId = jobId,
                        photoImages = photoImages,
                        onOpenPhoto = onOpenPhoto,
                        canManageVisitNotes = canManageVisitNotes,
                        onEditVisitNote = onEditVisitNote,
                        onRemoveVisitNote = onRemoveVisitNote,
                        audio = audio,
                    )
                }
            }
        }
    }
}

@Composable
private fun AdministrativeActivityGroup(
    events: List<JobActivityEvent>,
    jobId: String,
    photoImages: JobPhotoImages,
    onOpenPhoto: (String) -> Unit,
    canManageVisitNotes: Boolean,
    onEditVisitNote: (String, String) -> Unit,
    onRemoveVisitNote: (String, String) -> Unit,
    audio: JobActivityAudio,
) {
    if (events.isEmpty()) return

    var expanded by rememberSaveable { mutableStateOf(false) }
    val toggleLabel = stringResource(
        if (expanded) {
            R.string.job_activity_admin_collapse_label
        } else {
            R.string.job_activity_admin_expand_label
        },
    )
    val stateLabel = stringResource(
        if (expanded) R.string.job_activity_group_expanded else R.string.job_activity_group_collapsed,
    )
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.medium)
                .clickable(onClickLabel = toggleLabel) { expanded = !expanded }
                .semantics { stateDescription = stateLabel }
                .heightIn(min = OverviewActivityHeaderMinHeight)
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.job_activity_admin_label, events.size),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Icon(
                painter = painterResource(R.drawable.ic_chevron_right),
                contentDescription = toggleLabel,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(OverviewChevronSize)
                    .rotate(if (expanded) 90f else 0f),
            )
        }
        if (expanded) {
            ActivityTimeline(
                events = events,
                jobId = jobId,
                photoImages = photoImages,
                onOpenPhoto = onOpenPhoto,
                canManageVisitNotes = canManageVisitNotes,
                onEditVisitNote = onEditVisitNote,
                onRemoveVisitNote = onRemoveVisitNote,
                audio = audio,
            )
        }
    }
}

/**
 * The Customer's other ways of being reached, behind a disclosure that starts closed (`BR-095`).
 *
 * The heading is the control, as the Job Activity groups and the manager home's sections are: it states
 * what it holds and how many of them there are, it says whether it is open, and its content is the one
 * thing it hides (`docs/design/android-design-system.md`). It starts **closed**, so the contact the
 * technician came for is the one on screen and the rest are one tap away rather than competing with it
 * (`BR-012`).
 */
@Composable
private fun OverviewOtherContacts(others: List<JobCustomerOtherContact>) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val toggleLabel = stringResource(
        if (expanded) {
            R.string.job_details_customer_other_contacts_hide
        } else {
            R.string.job_details_customer_other_contacts_show
        },
    )
    val stateLabel = stringResource(
        if (expanded) R.string.job_activity_group_expanded else R.string.job_activity_group_collapsed,
    )
    Column(
        modifier = Modifier.fillMaxWidth().testTag(JobDetailsCustomerOtherContactsTag),
    ) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.medium)
                .clickable(onClickLabel = toggleLabel) { expanded = !expanded }
                .semantics { stateDescription = stateLabel }
                .heightIn(min = OverviewActivityHeaderMinHeight)
                .padding(vertical = 8.dp)
                .testTag(JobDetailsCustomerOtherContactsToggleTag),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                modifier = Modifier.weight(1f),
                text = pluralStringResource(
                    R.plurals.job_details_customer_other_contacts,
                    others.size,
                    others.size,
                ),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(8.dp))
            Icon(
                painter = painterResource(R.drawable.ic_chevron_right),
                contentDescription = toggleLabel,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(OverviewChevronSize)
                    .rotate(if (expanded) 90f else 0f),
            )
        }
        if (expanded) {
            others.forEach { other -> OverviewOtherContactRow(other) }
        }
    }
}

/**
 * One other way of reaching the Customer (`BR-095`): who it belongs to, and the values the office
 * recorded. A value that is not recorded is left out rather than drawn as an empty line (`BR-012`), and a
 * recorded one opens the application that reaches that person — the same `tel:`/`mailto:` affordances the
 * customer screens use, so the app dials and composes the same way everywhere (`ADR-022` D6).
 */
@Composable
private fun OverviewOtherContactRow(contact: JobCustomerOtherContact, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Column(
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = contact.name,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        contact.phone?.takeIf { it.isNotBlank() }?.let { phone ->
            CustomerContactLine(
                text = phone,
                glyph = R.drawable.ic_phone,
                onClick = { context.startContactIntent(dialIntent(phone)) },
            )
        }
        contact.email?.takeIf { it.isNotBlank() }?.let { email ->
            CustomerContactLine(
                text = email,
                glyph = R.drawable.ic_mail,
                onClick = { context.startContactIntent(mailIntent(email)) },
            )
        }
    }
}

/**
 * One labelled value of a card, with an optional trailing control and tap action.
 *
 * The label states **what** the value belongs to — the Job or the Visit — because the two carry separate
 * state machines and separate schedules (`BR-059`, `BR-072`), and a value without a stated owner is how
 * one came to be read as the other.
 */
@Composable
private fun OverviewRow(
    label: String,
    modifier: Modifier = Modifier,
    value: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (onClick == null) {
                    Modifier
                } else {
                    Modifier.clickable(onClick = onClick)
                },
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (value != null) {
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            trailing()
        }
    }
}

/**
 * The glyph that marks the Job's address row as opening the address in the device's map application
 * (`ui/components/ServoraMaps.kt`).
 *
 * The design ends the address row with the navigation glyph, so what the tap will do is visible before it
 * is tapped. The glyph also names that action for a screen reader, because the row's own text states the
 * address and not what opening it does (`BR-028`).
 */
@Composable
private fun OverviewMapAffordance() {
    Icon(
        painter = painterResource(R.drawable.ic_navigation),
        contentDescription = stringResource(R.string.job_details_open_in_maps),
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .size(OverviewMapAffordanceSize)
            .testTag(JobDetailsAddressMapTag),
    )
}

/**
 * One technician on a Visit: their initials avatar, their name, and the Lead badge when they are that
 * Visit's Lead (`BR-020`, `BR-068`).
 *
 * The row is the same wherever a crew is stated — the Visit that represents the Job and each Visit of
 * the history — because a crew is the Visit's in every case (`BR-068`).
 */
@Composable
private fun OverviewTechnicianRow(technician: JobDetailsTechnician) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(jobDetailsTechnicianTag(technician.membershipId)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OverviewTechnicianAvatar(name = technician.name)
        Spacer(Modifier.width(12.dp))
        Text(
            modifier = Modifier.weight(1f),
            text = technician.name
                ?.takeIf { name -> name.isNotBlank() }
                ?: stringResource(R.string.job_details_technician_name_unknown),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
        )
        if (technician.isLead) {
            OverviewLeadBadge(
                modifier = Modifier.testTag(jobDetailsLeadBadgeTag(technician.membershipId)),
            )
        }
    }
}

/**
 * The badge the Visit's single Lead carries (`BR-068`).
 *
 * Every other assigned technician carries no badge: `TECHNICIAN` is the ordinary role, so marking it
 * would say less than the absence of a badge already says.
 */
@Composable
private fun OverviewLeadBadge(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Text(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            text = stringResource(R.string.assignment_role_lead),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
        )
    }
}

/**
 * The technician's initials, which is the avatar a member without a photo is presented with (`BR-020`).
 *
 * A member whose profile is not readable yet shows `?` rather than nothing.
 */
@Composable
private fun OverviewTechnicianAvatar(name: String?, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(OverviewAvatarSize)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.secondary),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initials(name.orEmpty()),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSecondary,
        )
    }
}

/**
 * The state of a Visit nobody is assigned to.
 *
 * It is the absence of an assignment, never a Job or Visit status (`BR-042`).
 */
@Composable
private fun OverviewUnassignedCrew() {
    Column(modifier = Modifier.testTag(JobDetailsUnassignedTag)) {
        Text(
            text = stringResource(R.string.customers_job_unassigned),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = stringResource(R.string.job_details_unassigned_detail),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The label the Visit that represents the Job is presented under, for the period it is currently in
 * (`BR-072`, `BR-081`).
 *
 * The label names **when** that field attempt is for rather than its role on the page, so the statement
 * is true of the Visit the read selected. The wording stays in the resources, because casing and
 * translation belong to the language and not to the code that draws them (`BR-028`).
 */
@StringRes
private fun representedVisitLabel(period: VisitSectionPeriod): Int =
    when (period) {
        VisitSectionPeriod.CURRENT -> R.string.job_details_current_visit_label
        VisitSectionPeriod.TODAY -> R.string.job_details_today_visit_label
        VisitSectionPeriod.TOMORROW -> R.string.job_details_tomorrow_visit_label
        VisitSectionPeriod.UPCOMING -> R.string.job_details_upcoming_visit_label
        VisitSectionPeriod.PREVIOUS -> R.string.job_details_previous_visit_label
    }

/**
 * A Visit's schedule in the device's language and time zone.
 *
 * The schedule is the Visit's internal one, which is authoritative for dispatch and conflict detection
 * (`BR-072`). It is formatted by the same device-language helper the screen this one replaces uses, so a
 * Visit's date is presented one way throughout the application (`BR-041`). A Visit that has not been
 * scheduled says so rather than showing an invented date (`BR-051`), and a value this build cannot read
 * is reported as unavailable rather than as a fabricated one (`BR-042`).
 */
@Composable
private fun overviewScheduleText(scheduledStart: String?): String {
    val instant = visitStart(scheduledStart)
        ?: return stringResource(
            if (scheduledStart == null) {
                R.string.job_details_not_scheduled
            } else {
                R.string.job_details_time_unknown
            },
        )
    val locale = deviceLocale()
    val zoned = instant.atZone(ZoneId.systemDefault())
    val date = zoned.format(
        DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale),
    )
    val time = zoned.format(
        DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale),
    )
    return stringResource(R.string.job_details_date_time_format, date, time)
}

/**
 * A Visit's date in the device's language and time zone, without a time.
 *
 * It is the date a Visit's activity group is headed with: a group is scanned for which field attempt it is
 * and when it was for (`BR-052`, `BR-072`). A Visit that has not been scheduled says so rather than showing
 * an invented date (`BR-051`), and a value this build cannot read is reported as unavailable rather than as
 * a fabricated one (`BR-042`).
 */
@Composable
private fun overviewDateText(scheduledStart: String?): String {
    val instant = visitStart(scheduledStart)
        ?: return stringResource(
            if (scheduledStart == null) {
                R.string.job_details_not_scheduled
            } else {
                R.string.job_details_time_unknown
            },
        )
    return instant.atZone(ZoneId.systemDefault()).format(
        DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(deviceLocale()),
    )
}

/**
 * What a Visit's activity group states under its heading: where the field attempt ended up, and what it
 * resulted in (`BR-074`, `BR-078`).
 *
 * The status is the Visit's own state and the outcome is the Visit's own recorded outcome, so the two read
 * as one line — `Completed · Needs follow-up` — and a Visit that holds no outcome states its status alone,
 * because a Visit reopened after completion holds none until it is completed again (`BR-079`, `BR-042`).
 * The words come from the shared status and outcome labels, so the group, the Visit's badge and the
 * completion sheet name one code the same way (`BR-041`).
 */
@Composable
private fun visitActivitySummary(visit: JobDetailsVisitSummary): String {
    val status = stringResource(visitStatusLabel(visit.status))
    val outcome = visit.outcome?.let { outcome -> stringResource(visitOutcomeLabel(outcome)) }
        ?: return status
    return stringResource(R.string.job_activity_visit_summary_format, status, outcome)
}


