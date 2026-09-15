package com.servora.android.ui.jobs

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.servora.android.R
import com.servora.android.data.jobs.JobPhotoImages
import com.servora.android.domain.model.CustomerJobAddress
import com.servora.android.domain.model.JobDetails
import com.servora.android.domain.model.JobDetailsTechnician
import com.servora.android.domain.model.JobDetailsVisit
import com.servora.android.domain.model.JobPhotoPhase
import com.servora.android.domain.model.JobStatus
import com.servora.android.domain.model.TechnicianAssignment
import com.servora.android.ui.components.InfoCard
import com.servora.android.ui.components.JobStatusPill
import com.servora.android.ui.components.SectionLabel
import com.servora.android.ui.components.StatusDot
import com.servora.android.ui.components.VisitStatusPill
import com.servora.android.ui.components.addressLine
import com.servora.android.ui.components.initials
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.FormatStyle
import java.util.Locale

/** Identifies the whole destination, whatever state it is in. */
const val JobDetailsTag = "job-details"

/** Identifies the Job's content, so a test can assert what the screen presents. */
const val JobDetailsContentTag = "job-details-content"

/** Identifies the first-read loading state. */
const val JobDetailsLoadingTag = "job-details-loading"

/** Identifies the state shown when the Job could not be read. */
const val JobDetailsFailureTag = "job-details-failure"

/** Identifies the retry action of the failure state. */
const val JobDetailsRetryTag = "job-details-retry"

/** Identifies the represented Visit's schedule row. */
const val JobDetailsScheduleTag = "job-details-schedule"

/** Identifies the Job's address row. */
const val JobDetailsAddressTag = "job-details-address"

/** Identifies the glyph that marks the Job's address row as opening the device's map application. */
const val JobDetailsAddressMapTag = "job-details-address-map"

/** Identifies the Job's Customer row. */
const val JobDetailsCustomerTag = "job-details-customer"

/** Identifies the Technicians section, which is what this screen is primarily read for. */
const val JobDetailsTechniciansTag = "job-details-technicians"

/** Identifies the state shown when the represented Visit has no technicians. */
const val JobDetailsUnassignedTag = "job-details-unassigned"

/** Identifies the floating action that starts a Job Activity update. */
const val JobDetailsAddActivityTag = "job-details-add-activity"

/** Identifies one assigned technician's row. */
fun jobDetailsTechnicianTag(membershipId: String): String = "job-details-technician-$membershipId"

/** Identifies the Lead badge of the technician who is the Visit's Lead (`BR-068`). */
fun jobDetailsLeadBadgeTag(membershipId: String): String = "job-details-lead-$membershipId"

/*
 * Metrics the Job Details screen is built from (`docs/design/android-design-system.md`): the 20 dp
 * page gutter the signed-in shell uses, 16 dp inside a card, and avatars at the home's row size.
 */
private val JobDetailsPageGutter = 20.dp
private val JobDetailsSectionSpacing = 20.dp
private val JobDetailsBottomClearance = 72.dp

/** The clearance the capture tray needs, so the last timeline entry is never covered by it. */
private val JobDetailsTrayClearance = 260.dp
private val JobDetailsAvatarSize = 36.dp
private val JobDetailsMapAffordanceSize = 18.dp

/**
 * The Job Details screen, for a Manager (`BR-010`, `BR-021`).
 *
 * It presents one Job with the **Visit that represents it** and **every technician assigned to that
 * Visit** (`BR-068`, `BR-081`): Servora assigns technicians to Visits, never to a Job, so the crew
 * shown here is the represented Visit's assignments and nothing is duplicated onto the Job.
 *
 * The page is ordered as a manager reads it — the Job's identity, the Visit that represents it, the
 * technicians on that Visit — and each action sits with the record it affects instead of in one global
 * action row. A global row made every action look equally relevant to the whole screen and separated
 * each one from the data it changes (`BR-066`).
 *
 * The Job's status (`BR-058`) and the represented Visit's status (`BR-074`) are presented separately,
 * because they are two state machines (`BR-059`), and an unassigned Visit is presented as the absence
 * of an assignment rather than as a status (`BR-042`).
 *
 * Where the user can act, the screen offers the management actions `BR-066` names: moving the Job
 * through its lifecycle, rescheduling the represented Visit, and stating the Visit's crew. Which of
 * each is possible is the backend's answer — the Job's `allowedStatusTransitions` and the Visit's
 * `reschedulable` — so the screen never offers an action the API would refuse (`BR-041`, `BR-007`).
 *
 * What an action did is reported in a transient Snackbar rather than a standing banner, because the
 * Job the API answered with already presents the change (`BR-001`, `BR-042`).
 *
 * The screen draws content only: its title, its context line and any action belong to the app shell's
 * one contextual top bar (`docs/decisions/011-android-contextual-top-bar.md`).
 */
@Composable
fun JobDetailsScreen(
    state: JobDetailsUiState,
    canUpdateJob: Boolean,
    canAddEvidencePhoto: Boolean,
    canViewTechnicians: Boolean,
    onRetry: () -> Unit,
    onRetryActivity: () -> Unit,
    onOpenCustomer: (customerId: String) -> Unit,
    onOpenInMaps: (address: CustomerJobAddress) -> Unit,
    onLoadAssignableTechnicians: () -> Unit,
    onChangeJobStatus: (status: JobStatus) -> Unit,
    onAddActivityText: (body: String) -> Unit,
    onRescheduleVisit: (start: Instant, end: Instant) -> Unit,
    onAssignTechnicians: (assignments: List<TechnicianAssignment>) -> Unit,
    onConfirmPendingAction: () -> Unit,
    onDismissPendingAction: () -> Unit,
    onDismissActionMessage: () -> Unit,
    onCapturePhoto: () -> Unit,
    onChoosePhotos: () -> Unit,
    onConfirmCapturedPhoto: (JobPhotoPhase, String?) -> Unit,
    onDiscardCapturedPhoto: () -> Unit,
    onKeepCapturedPhoto: () -> Unit,
    onRemovePendingPhoto: (String) -> Unit,
    onSubmitPendingPhotos: () -> Unit,
    onDismissPhotoMessage: () -> Unit,
    photoImages: JobPhotoImages = JobPhotoImages.None,
    modifier: Modifier = Modifier,
) {
    val details = state.details
    var showReschedule by rememberSaveable(state.jobId) { mutableStateOf(false) }
    var showAssign by rememberSaveable(state.jobId) { mutableStateOf(false) }
    var showAddActivity by rememberSaveable(state.jobId) { mutableStateOf(false) }

    // The photo the viewer is showing, if any. Only its identity is held: the phase, the note and the
    // bytes are resolved from the records that already state them, so the viewer is never a second
    // copy of the evidence (`BR-001`), and a photo neither the device nor the Activity holds any more
    // closes the viewer rather than letting it show something else (`BR-042`).
    var viewedPhotoId by rememberSaveable(state.jobId) { mutableStateOf<String?>(null) }
    val viewedPhoto = viewedPhotoId?.let { photoId ->
        viewedJobPhoto(
            jobId = state.jobId,
            photoId = photoId,
            pendingPhotos = state.pendingPhotos,
            activity = state.activity,
        )
    }
    val snackbarHostState = remember { SnackbarHostState() }

    val actionFailure = state.actionFailure
    val completedAction = state.completedAction
    val photoFailure = state.photoFailure
    val photoMessage = state.photoMessage
    val actionMessage = when {
        actionFailure != null -> stringResource(jobActionFailureMessage(actionFailure))
        completedAction != null -> stringResource(jobActionCompletionMessage(completedAction))
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
        else -> null
    }
    val dismissLabel = stringResource(R.string.job_action_dismiss)

    // The report of what the last action did is transient: the Job the API answered with already
    // presents the change, so the report is shown and released instead of being left standing in the
    // layout (`BR-001`, `BR-042`). A refusal waits for the user, because nothing on screen reflects a
    // change that did not happen and the refusal is the action's only report.
    LaunchedEffect(actionMessage, actionFailure != null, photoFailure != null) {
        if (actionMessage == null) {
            return@LaunchedEffect
        }
        val isFailure = actionFailure != null || photoFailure != null
        try {
            snackbarHostState.showSnackbar(
                message = actionMessage,
                actionLabel = if (isFailure) dismissLabel else null,
                duration = if (isFailure) {
                    SnackbarDuration.Indefinite
                } else {
                    SnackbarDuration.Short
                },
            )
        } finally {
            // Released once it has been shown, so re-entering the screen does not report an action the
            // Job on screen already shows.
            onDismissActionMessage()
            onDismissPhotoMessage()
        }
    }

    Box(modifier = modifier.fillMaxSize().testTag(JobDetailsTag)) {
        when {
            details != null ->
                JobDetailsContent(
                    details = details,
                    state = state,
                    canUpdateJob = canUpdateJob,
                    // Stating a crew means choosing from the organization's technicians, so the
                    // action needs that capability as well (`BR-007`, `BR-068`).
                    canManageTechnicians = canViewTechnicians && state.canAssign,
                    onOpenCustomer = onOpenCustomer,
                    onOpenInMaps = onOpenInMaps,
                    onOpenAssign = {
                        showAssign = true
                        onLoadAssignableTechnicians()
                    },
                    onOpenReschedule = { showReschedule = true },
                    onChangeJobStatus = onChangeJobStatus,
                    onRetryActivity = onRetryActivity,
                    onOpenPhoto = { photoId -> viewedPhotoId = photoId },
                    photoImages = photoImages,
                )

            state.showsFailure -> JobDetailsFailure(onRetry = onRetry)
            else -> JobDetailsLoading()
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 8.dp),
            snackbar = { data ->
                JobActionSnackbar(
                    message = data.visuals.message,
                    isError = actionFailure != null || photoFailure != null,
                    actionLabel = data.visuals.actionLabel,
                    onAction = { data.performAction() },
                )
            },
        )

        // One action adds anything to the Job's Activity: it opens the sheet that states what kind
        // of update it is (`BR-012`, `BR-027`). It needs no represented Visit, because a photo is
        // Job-level evidence even when the Job has no Visit yet (`BR-015`, `BR-051`), and each kind it
        // offers is drawn on the capability the API enforces for that kind: the Job update capability
        // for a note, and the evidence capability for a photo (`BR-006`, `BR-007`). A technician who
        // may record evidence therefore reaches the camera and the picker without being given the
        // Manager's Job capability (`BR-009`, `docs/decisions/015-evidence-capabilities.md`).
        if (
            details != null &&
            (canUpdateJob || canAddEvidencePhoto) &&
            state.pendingPhotos.isEmpty()
        ) {
            ExtendedFloatingActionButton(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
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

        // The photo tray is the technician's working state, so while it holds anything it takes the
        // bottom of the screen: the next thing they want is another capture or saving what they have,
        // and the floating action would otherwise sit on top of it (`BR-012`).
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
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }

    if (details != null && showAddActivity) {
        JobUpdateSheet(
            // A note is the represented Visit's, so a Job with no Visit has none to write here, and
            // writing one takes the Job update capability the API enforces for it (`BR-015`,
            // `BR-051`, `BR-066`).
            canWriteNote = canUpdateJob && details.selectedVisit != null,
            // A photo is authorized by the evidence capability, which the technician who records the
            // field evidence holds (`BR-006`, `BR-009`).
            canAddPhoto = canAddEvidencePhoto,
            isSubmitting = state.isSubmitting,
            onConfirmNote = { body ->
                showAddActivity = false
                onAddActivityText(body)
            },
            // Both photo sources keep the flow the photo slice owns — capture or pick, then the
            // review, then the tray — so the sheet hands over rather than growing a second photo UI
            // (`BR-015`, `D3`).
            onTakePhoto = {
                showAddActivity = false
                onCapturePhoto()
            },
            onChoosePhotos = {
                showAddActivity = false
                onChoosePhotos()
            },
            onDismiss = { showAddActivity = false },
        )
    }

    // The photo the technician just captured. Dismissing it keeps the photo — it is already recorded
    // on the device — so the explicit Discard is the only way to lose one (`BR-014`).
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
            // The sheet is only useful with the technicians it offers, so the read is asked for
            // again when it is the read that failed.
            onRetry = onLoadAssignableTechnicians,
            onDismiss = { showAssign = false },
        )
    }

    state.pendingConfirmation?.let { pending ->
        ScheduleConflictDialog(
            pending = pending,
            isSubmitting = state.isSubmitting,
            onConfirm = onConfirmPendingAction,
            onDismiss = onDismissPendingAction,
        )
    }

    // A photo the technician taps opens full size, whether the backend already holds it or the device
    // still does (`D4`). The viewer is drawn over everything else, and closing it only forgets which
    // photo it was: nothing about the photo changes (`BR-001`, `BR-067`).
    viewedPhoto?.let { photo ->
        JobPhotoViewer(
            photo = photo,
            photoImages = photoImages,
            onDismiss = { viewedPhotoId = null },
        )
    }
}

/** The first read, with nothing on screen yet. */
@Composable
private fun JobDetailsLoading() {
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
private fun JobDetailsFailure(onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(JobDetailsPageGutter)
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
 * The Job, in the order a manager reads it: what the Job is, the Visit that represents it and where it
 * is, then who is assigned (`BR-047`, `BR-081`).
 *
 * Each action the screen offers is drawn with the data it affects — the Job's status action with the
 * Job's status, the reschedule with the Visit's schedule, the crew's management with the technicians —
 * so no action is further from the record it changes than the record itself (`BR-066`).
 */
@Composable
private fun JobDetailsContent(
    details: JobDetails,
    state: JobDetailsUiState,
    canUpdateJob: Boolean,
    canManageTechnicians: Boolean,
    onOpenCustomer: (customerId: String) -> Unit,
    onOpenInMaps: (address: CustomerJobAddress) -> Unit,
    onOpenAssign: () -> Unit,
    onOpenReschedule: () -> Unit,
    onChangeJobStatus: (status: JobStatus) -> Unit,
    onRetryActivity: () -> Unit,
    onOpenPhoto: (String) -> Unit,
    photoImages: JobPhotoImages,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .testTag(JobDetailsContentTag)
            .padding(horizontal = JobDetailsPageGutter, vertical = JobDetailsPageGutter),
        verticalArrangement = Arrangement.spacedBy(JobDetailsSectionSpacing),
    ) {
        // The actions are drawn only when the user holds the capability the API enforces: a hidden
        // button is not authorization, but an action nobody may perform is not one to offer either
        // (`BR-006`, `BR-007`, `BR-066`).
        JobIdentitySection(
            details = details,
            canChangeStatus = canUpdateJob && state.canAct,
            onChangeStatus = onChangeJobStatus,
        )
        JobVisitCard(
            details = details,
            canReschedule = canUpdateJob && state.canReschedule,
            // A reschedule edits the represented Visit, so the action exists only when there is one;
            // a Job with no Visit has nothing to reschedule (`BR-051`).
            onReschedule = if (canUpdateJob && details.selectedVisit != null) {
                onOpenReschedule
            } else {
                null
            },
            onOpenCustomer = onOpenCustomer,
            onOpenInMaps = onOpenInMaps,
        )
        JobTechniciansSection(
            technicians = details.technicians,
            canManage = canManageTechnicians,
            onManage = onOpenAssign,
        )
        JobActivitySection(
            state = state,
            onRetry = onRetryActivity,
            onOpenPhoto = onOpenPhoto,
            photoImages = photoImages,
        )
        // Keeps the last timeline entry clear of the floating Add update action the design places
        // over the timeline, so it is never covered (`Figma/src/screens/JobDetails.tsx`), and clear of
        // the photo tray when it is open, which is taller than the action it replaces.
        Spacer(
            Modifier.height(
                if (state.pendingPhotos.isEmpty()) {
                    JobDetailsBottomClearance
                } else {
                    JobDetailsTrayClearance
                },
            ),
        )
    }
}

/**
 * What the Job is: its human-readable number (`BR-052`), its title and its description, and the
 * lifecycle status it is in (`BR-058`).
 *
 * The Job number, title and description are kept together as one group, because they are the Job's
 * identity, and the status is labelled as the **Job's** immediately below them rather than left
 * floating above them: the represented Visit's own status is presented further down on the Visit card
 * (`BR-074`), and the two are separate state machines (`BR-059`) that neither replace nor contradict
 * each other, so each is named for what it belongs to (`BR-028`). The Job number is stated once, on the
 * line above the title, so the header never repeats the number inside the title (`BR-052`).
 *
 * When the session may move the Job and the backend reports a permitted transition, the status chip
 * **is** the control that moves it, so the state the user wants to change is the thing they tap
 * (`docs/tracker/020-android-job-details-status-control.md`). Otherwise the status is still presented
 * and simply does not act: the read is not an action, and an action nobody may perform is not one to
 * offer (`BR-006`, `BR-007`, `BR-058`).
 */
@Composable
private fun JobIdentitySection(
    details: JobDetails,
    canChangeStatus: Boolean,
    onChangeStatus: (JobStatus) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(JobDetailsSectionSpacing)) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = stringResource(R.string.job_details_job_number_format, details.jobNumber),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = details.title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            details.description
                ?.takeIf { it.isNotBlank() }
                ?.let { description ->
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
        }
        Column {
            SectionLabel(
                label = stringResource(R.string.job_details_job_status_label),
                count = null,
            )
            if (canChangeStatus && details.allowedStatusTransitions.isNotEmpty()) {
                JobStatusAction(
                    currentStatus = details.status,
                    allowedTransitions = details.allowedStatusTransitions,
                    enabled = canChangeStatus,
                    onSelect = onChangeStatus,
                )
            } else {
                // Presented, not controlled: the Job's status is still the Job's status, so it wears
                // the same dot the control's chip does and does not act (`BR-006`, `BR-007`).
                JobStatusPill(
                    status = details.status,
                    leading = { StatusDot() },
                )
            }
        }
    }
}

/**
 * The Visit that represents the Job: when it is scheduled for, where it is and who it is for
 * (`BR-048`, `BR-056`, `BR-072`).
 *
 * The schedule is the represented Visit's internal schedule, which is the one that is authoritative
 * for dispatch and conflict detection. A Job that has no Visit yet says so rather than showing an
 * invented date (`BR-051`, `BR-042`).
 *
 * The row is labelled for the Visit's **date**, not with a word that reads as a status: the Visit's
 * status is what the card's own badge presents on the right (`BR-074`), so the card never appears to
 * state two statuses for one Visit (`BR-059`, `BR-028`).
 *
 * The Visit's own action sits at the foot of this card. A reschedule edits **this** Visit, so drawing
 * it anywhere else would put the action further from the record it changes than the record itself
 * (`BR-066`, `BR-073`).
 */
@Composable
private fun JobVisitCard(
    details: JobDetails,
    canReschedule: Boolean,
    onReschedule: (() -> Unit)?,
    onOpenCustomer: (customerId: String) -> Unit,
    onOpenInMaps: (address: CustomerJobAddress) -> Unit,
) {
    val address = details.address
    val addressText = address
        ?.let { snapshot -> addressLine(snapshot) }
        ?.takeIf { it.isNotBlank() }
    Column {
        SectionLabel(
            label = stringResource(R.string.job_details_visit_label),
            count = null,
        )
        InfoCard {
            InformationRow(
                label = stringResource(R.string.job_details_visit_date_label),
                value = scheduledValue(details.selectedVisit),
                modifier = Modifier.testTag(JobDetailsScheduleTag),
                trailing = details.selectedVisit?.let { visit ->
                    { VisitStatusPill(status = visit.status) }
                },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            // A Job with no address has nothing to navigate to, so the row is only tappable — and only
            // marked as opening a map — when there is a location to open (`BR-056`).
            val navigableAddress = address?.takeIf { addressText != null }
            InformationRow(
                label = stringResource(R.string.job_details_address_label),
                value = addressText ?: stringResource(R.string.job_details_no_address),
                modifier = Modifier.testTag(JobDetailsAddressTag),
                // The design ends this row with the navigation glyph, so the row says what the tap
                // will do before it is tapped (`Figma/src/screens/JobDetails.tsx`).
                trailing = navigableAddress?.let { { MapAffordance() } },
                onClick = navigableAddress?.let { location -> { onOpenInMaps(location) } },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            InformationRow(
                label = stringResource(R.string.job_details_customer_label),
                value = details.customerName,
                modifier = Modifier.testTag(JobDetailsCustomerTag),
                // The customer the Job belongs to is a customer of this organization, so the row
                // opens that customer rather than showing its name as dead text (`BR-048`).
                onClick = { onOpenCustomer(details.customerId) },
            )
            if (onReschedule != null) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(JobDetailsVisitActionsTag),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    JobContextualAction(
                        text = stringResource(R.string.job_action_reschedule),
                        // Whether the Visit may be rescheduled now is the API's answer, not a rule
                        // re-implemented here (`BR-073`, `BR-041`).
                        enabled = canReschedule,
                        testTag = JobDetailsRescheduleActionTag,
                        onClick = onReschedule,
                    )
                }
            }
        }
    }
}

/** One labelled value of the information card, with an optional trailing chip and tap action. */
@Composable
private fun InformationRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
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
            Text(
                text = value,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
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
 * The design ends the address row with the navigation glyph, so what the tap will do is visible before
 * it is tapped. The glyph also names that action for a screen reader, because the row's own text states
 * the address and not what opening it does (`BR-028`).
 */
@Composable
private fun MapAffordance() {
    Icon(
        painter = painterResource(R.drawable.ic_navigation),
        contentDescription = stringResource(R.string.job_details_open_in_maps),
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .size(JobDetailsMapAffordanceSize)
            .testTag(JobDetailsAddressMapTag),
    )
}

/**
 * Every technician assigned to the Visit this screen represents (`BR-068`).
 *
 * The section is plural because a Visit may carry several technicians and they are all shown, not
 * only the lead: the assignment is recorded on the Visit, and this screen presents the assignment
 * exactly as the backend reports it, Lead first. A Visit with no assignment is presented as
 * unassigned, which is the absence of a technician and never a Job or Visit status (`BR-042`).
 *
 * The section offers one action, **Manage technicians**, rather than a bare "reassign": the crew is
 * stated as a whole, so the same action adds a technician, removes one and names the Lead
 * (`BR-068`, `BR-069`). The action is drawn only when the session may state a crew and the Job has a
 * Visit to state it on — there is no technician relationship on a Job to change (`BR-068`).
 */
@Composable
private fun JobTechniciansSection(
    technicians: List<JobDetailsTechnician>,
    canManage: Boolean,
    onManage: () -> Unit,
) {
    Column(modifier = Modifier.testTag(JobDetailsTechniciansTag)) {
        SectionLabel(
            label = stringResource(R.string.job_details_technicians_label),
            count = technicians.size.takeIf { technicians.isNotEmpty() },
        )
        InfoCard {
            if (technicians.isEmpty()) {
                UnassignedCrew()
            } else {
                technicians.forEachIndexed { index, technician ->
                    if (index > 0) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                    TechnicianRow(technician)
                }
            }
            if (canManage) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    JobContextualAction(
                        text = stringResource(R.string.job_action_manage_technicians),
                        enabled = true,
                        testTag = JobDetailsManageTechniciansActionTag,
                        onClick = onManage,
                    )
                }
            }
        }
    }
}

/** The state of a represented Visit nobody is assigned to yet. */
@Composable
private fun UnassignedCrew() {
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
 * One assigned technician: their initials or photo avatar, their name, and the Lead badge when they
 * are the Visit's Lead (`BR-020`, `BR-068`).
 */
@Composable
private fun TechnicianRow(technician: JobDetailsTechnician) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(jobDetailsTechnicianTag(technician.membershipId)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TechnicianAvatar(name = technician.name)
        Spacer(Modifier.width(12.dp))
        Text(
            modifier = Modifier.weight(1f),
            text = technician.name
                ?.takeIf { it.isNotBlank() }
                ?: stringResource(R.string.job_details_technician_name_unknown),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
        )
        if (technician.isLead) {
            LeadBadge(
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
private fun LeadBadge(modifier: Modifier = Modifier) {
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
 * The technician's initials, which is the avatar a member without a photo is presented with
 * (`BR-020`). A member whose profile is not readable yet shows `?` rather than nothing.
 */
@Composable
private fun TechnicianAvatar(name: String?, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(JobDetailsAvatarSize)
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
 * The represented Visit's schedule, in the device's language and time zone.
 *
 * The schedule is the Visit's internal one, which is authoritative for dispatch and conflict
 * detection (`BR-072`). A Job that has no Visit yet says it is not scheduled rather than showing an
 * invented date (`BR-051`), and a value this build cannot read is reported as unavailable rather than
 * as a fabricated one (`BR-042`).
 */
@Composable
private fun scheduledValue(visit: JobDetailsVisit?): String {
    if (visit == null) {
        return stringResource(R.string.job_details_not_scheduled)
    }
    val instant = readInstant(visit.scheduledStart)
        ?: return stringResource(R.string.job_details_time_unknown)
    val locale = deviceLocale()
    val date = instant.format(
        DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale),
    )
    val time = instant.format(
        DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale),
    )
    return stringResource(R.string.job_details_date_time_format, date, time)
}

/** The instant a timestamp denotes, or `null` when it is not one this build can read. */
private fun readInstant(value: String): ZonedDateTime? =
    try {
        Instant.parse(value).atZone(ZoneId.systemDefault())
    } catch (unreadable: DateTimeParseException) {
        null
    }

/**
 * The language the device is set to, read from the configuration so a change to it recomposes the
 * screen instead of leaving it formatted in the previous language.
 */
@Composable
internal fun deviceLocale(): Locale {
    val locales = LocalConfiguration.current.locales
    return if (locales.isEmpty) Locale.ROOT else locales[0]
}
