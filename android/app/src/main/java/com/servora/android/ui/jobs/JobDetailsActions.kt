package com.servora.android.ui.jobs

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonColors
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import android.text.format.DateFormat
import com.servora.android.R
import com.servora.android.data.jobs.QueuedVisitFieldAction
import com.servora.android.data.jobs.QueuedVisitNote
import com.servora.android.data.offline.OutboxFailureReason
import com.servora.android.data.offline.OutboxOperationState
import com.servora.android.domain.model.AssignableTechnician
import com.servora.android.domain.model.AssignmentRole
import com.servora.android.domain.model.JobReadOnlyReason
import com.servora.android.domain.model.JobStatus
import com.servora.android.domain.model.ScheduleConflict
import com.servora.android.domain.model.TechnicianAssignment
import com.servora.android.domain.model.VisitOutcome
import com.servora.android.domain.model.VisitStatus
import com.servora.android.ui.components.JobStatusPill
import com.servora.android.ui.components.StatusDot
import com.servora.android.ui.components.jobStatusAccent
import com.servora.android.ui.components.jobStatusLabel
import com.servora.android.ui.components.visitStatusLabel
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/*
 * The management actions the Job Details screen offers (`BR-058`, `BR-066`, `BR-068`, `BR-070`,
 * `BR-073`).
 *
 * Every action here is an explicit action the user takes, never a side effect of reading the screen,
 * and each one is drawn only when the user holds the capability the API enforces (`BR-007`). What may
 * be done is the backend's answer: the statuses offered come from the Job's
 * `allowedStatusTransitions` (`BR-058`) and whether the Visit may be rescheduled comes from the
 * Visit's own `reschedulable` flag (`BR-073`), so no lifecycle rule is re-implemented here (`BR-041`).
 *
 * Each action is drawn with the data it affects rather than in one global action row: the Job's status
 * action beside the Job's status, the reschedule beside the represented Visit's schedule, the crew's
 * management beside the technicians (`BR-059`, `BR-068`). What the action did is reported in a
 * transient Snackbar, because the Job the API answered with already shows the change (`BR-001`).
 */

/** Identifies the Job's status control, which presents the Job's status (`BR-058`). */
const val JobDetailsStatusActionTag = "job-details-action-status"

/**
 * Identifies the status the Job is in now, which the control's menu states rather than offers.
 *
 * The row carries its own tag so it can never be mistaken for one of the transitions the menu offers
 * (`BR-058`).
 */
const val JobDetailsStatusCurrentTag = "job-details-status-current"

/** Identifies one status the Job may move to. */
fun jobDetailsStatusOptionTag(status: String): String = "job-details-status-option-$status"

/** Identifies the represented Visit's contextual actions (`BR-066`, `BR-073`). */
const val JobDetailsVisitActionsTag = "job-details-visit-actions"

/** Identifies the Reschedule action, which sits with the Visit's schedule (`BR-073`). */
const val JobDetailsRescheduleActionTag = "job-details-action-reschedule"

/**
 * Identifies the action that schedules a further Visit on the Job (`BR-071`).
 *
 * It sits with the represented Visit's other actions because it is work on the same Job, while the
 * Visit it schedules is the one the API is about to create rather than the one on screen (`BR-047`,
 * `BR-051`).
 */
const val JobDetailsScheduleVisitActionTag = "job-details-action-schedule-visit"

/**
 * Identifies the action that proposes a follow-up Visit (`BR-FV-001`).
 *
 * It states a **request**, not a scheduled Visit: the technician proposes a window and a reason, and
 * the office decides whether the Visit is created (`BR-FV-002`, `BR-FV-004`, `BR-FV-005`).
 */
const val JobDetailsRequestFollowUpActionTag = "job-details-action-request-follow-up"

/**
 * Identifies the represented Visit's working-status selector (`BR-074`).
 *
 * It is a compact row of the four working states the technician may choose between, and the state the
 * Visit is in is the selected chip of that row — so the status a technician wants to change is the
 * thing they tap, without a menu to open (`docs/tracker/051-job-visit-lifecycle-redesign.md`).
 */
const val JobDetailsVisitStatusSelectorTag = "job-details-visit-status-selector"

/** Identifies one working state the Visit may be moved to (`BR-074`, `BR-075`). */
fun jobDetailsVisitStatusOptionTag(status: String): String =
    "job-details-visit-status-option-$status"

/**
 * Identifies the row holding the Visit's own field action (`BR-077`).
 *
 * Completion sits with the field attempt it ends, while adding an update is the page's floating action
 * (`BR-012`).
 */
const val JobDetailsVisitFieldActionsTag = "job-details-visit-field-actions"

/**
 * Identifies the primary action that finishes the Visit (`BR-077`).
 *
 * Completion is deliberately **not** one of the selector's states: it records an outcome and is its own
 * business operation, so it is a separate action rather than another chip (`BR-077`, `BR-093`).
 */
const val JobDetailsVisitCompleteActionTag = "job-details-visit-complete-action"

/** Identifies the sheet a completion states its outcome in (`BR-077`, `BR-078`). */
const val JobDetailsVisitCompletionSheetTag = "job-details-visit-completion-sheet"

/** Identifies one outcome the sheet offers (`BR-078`). */
fun jobDetailsVisitOutcomeOptionTag(outcome: String): String =
    "job-details-visit-outcome-$outcome"

/**
 * Identifies the text `BR-077` requires with the outcome.
 *
 * It is one control for both readings the API gives it: the summary of what resulted from the attempt,
 * and the reason the Visit could not be completed (`BR-078`).
 */
const val JobDetailsVisitOutcomeSummaryTag = "job-details-visit-outcome-summary"

/** Identifies the sheet's confirmation, which is the action that completes the Visit. */
const val JobDetailsVisitCompleteConfirmTag = "job-details-visit-complete-confirm"

/** Identifies the notice that a Job is closed, so no field work may be recorded under it. */
const val JobDetailsReadOnlyNoticeTag = "job-details-read-only-notice"
/** Identifies the notice that states what the queue is still holding for this Job (`§7`). */
const val JobDetailsVisitPendingTag = "job-details-visit-pending"

/** Identifies the discard of a refused field action (`BR-032`, `BR-014`). */
fun jobDetailsVisitPendingDiscardTag(operationId: String): String =
    "job-details-visit-pending-discard-$operationId"

/** Identifies one note the queue is still holding (`§7`). */
fun jobDetailsQueuedNoteTag(operationId: String): String = "job-details-queued-note-$operationId"

/**
 * Identifies the Manage technicians action, which sits with the crew it changes (`BR-068`, `BR-069`).
 */
const val JobDetailsManageTechniciansActionTag = "job-details-action-manage-technicians"

/** Identifies the transient report of what the last action did (`BR-042`). */
const val JobDetailsActionMessageTag = "job-details-action-message"

/** Identifies the reschedule dialog and its fields. */
const val JobDetailsRescheduleDialogTag = "job-details-reschedule-dialog"
const val JobDetailsRescheduleDateTag = "job-details-reschedule-date"
const val JobDetailsRescheduleTimeTag = "job-details-reschedule-time"
const val JobDetailsRescheduleConfirmTag = "job-details-reschedule-confirm"

/** Identifies the assignment sheet, its rows and its confirmation. */
const val JobDetailsAssignmentSheetTag = "job-details-assignment-sheet"
const val JobDetailsAssignmentConfirmTag = "job-details-assignment-confirm"
const val JobDetailsAssignmentFailureTag = "job-details-assignment-failure"

fun jobDetailsAssignmentCandidateTag(membershipId: String): String =
    "job-details-assignment-candidate-$membershipId"

fun jobDetailsAssignmentLeadTag(membershipId: String): String =
    "job-details-assignment-lead-$membershipId"

/** Identifies the availability-conflict confirmation (`BR-070`). */
const val JobDetailsConflictDialogTag = "job-details-conflict-dialog"
const val JobDetailsConflictListTag = "job-details-conflict-list"
const val JobDetailsConflictConfirmTag = "job-details-conflict-confirm"

/**
 * Identifies the confirmation a consequential Job status change is asked for (`BR-058`, `BR-062`).
 *
 * It is a dialog the user answers, not a report: closing a Job or reopening a closed one is sent only
 * once the user has confirmed it.
 */
const val JobDetailsStatusConfirmDialogTag = "job-details-status-confirm-dialog"
const val JobDetailsStatusConfirmButtonTag = "job-details-status-confirm"

/**
 * One compact contextual action (`BR-066`).
 *
 * An action is drawn with the data it affects — the Job's status action beside the Job's status, the
 * reschedule beside the Visit's schedule, the crew's management beside the technicians — rather than
 * in one global row that presented every action as equally relevant to the whole screen. It is a
 * `TextButton`, the same control the contextual top bar uses for its own action
 * (`docs/decisions/011-android-contextual-top-bar.md`), and it is drawn only when the session holds
 * the capability the API enforces (`BR-006`, `BR-007`).
 */
@Composable
internal fun JobContextualAction(
    text: String,
    enabled: Boolean,
    testTag: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TextButton(
        modifier = modifier.heightIn(min = 48.dp).testTag(testTag),
        enabled = enabled,
        onClick = onClick,
    ) {
        Text(text = text, style = MaterialTheme.typography.labelLarge)
    }
}

/**
 * The Job's status control (`BR-058`): it presents the Job's status and it is the control that
 * changes it.
 *
 * The status a user wants to change is the thing they tap: the chip the header presents **is** the
 * control, rather than a second "Change status" action drawn beside it that described the same state
 * twice (`docs/tracker/020-android-job-details-status-control.md`). The chip wears the current
 * status's own dot, colour and label, so what the Job is now and what may be done to it are one
 * control, and its trailing chevron says it opens something.
 *
 * The menu is the control's own and stays compact: it opens at the chip, states where the Job is now,
 * and then lists exactly the destinations the backend reported for that status (`BR-041`, `BR-058`).
 * The client holds no second copy of the lifecycle, so no arbitrary status is offered — and a
 * destination the Job does not currently qualify for is still offered, because the API answers that
 * question with its own refusal (`BR-061`, `BR-062`) rather than the control guessing.
 *
 * A consequential destination is confirmed before it is sent ([requiresJobStatusConfirmation]), so
 * closing a Job or reopening a closed one is the user's explicit decision and is sent as **one**
 * request, never as a series of transitions walked through by the client. Cancellation is absent from
 * that list while `BR-064`'s reason catalogue is an open question, so no destructive action is folded
 * into the status control: Job cancellation stays a separate, explicitly confirmed action, which is
 * also what `BR-064` requires of it (`docs/api/job-actions.md` §3).
 */
@Composable
internal fun JobStatusAction(
    currentStatus: JobStatus,
    jobNumber: Int,
    allowedTransitions: List<JobStatus>,
    enabled: Boolean,
    onSelect: (JobStatus) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    // The chosen destination the user has still to confirm, if any. Confirming sends it and dismisses
    // the dialog without asking again; dismissing leaves the Job exactly as it was (`BR-067`).
    var confirming by rememberSaveable { mutableStateOf<JobStatus?>(null) }
    Box(modifier = modifier) {
        JobStatusPill(
            status = currentStatus,
            modifier = Modifier.testTag(JobDetailsStatusActionTag),
            leading = { StatusDot() },
            trailing = {
                Icon(
                    painter = painterResource(R.drawable.ic_chevron_down),
                    contentDescription = null,
                    modifier = Modifier.size(StatusControlIndicatorSize),
                )
            },
            onClick = { expanded = true },
            enabled = enabled,
            onClickLabel = stringResource(R.string.job_action_change_status),
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            // Where the Job is now, so the choice is made from a stated position rather than from
            // remembering a chip that has just closed. It is not a choice: the status the Job is
            // already in is not a transition, so this row does not act (`BR-058`).
            JobStatusCurrentRow(status = currentStatus)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            allowedTransitions.forEach { status ->
                JobStatusTransitionItem(status = status) {
                    expanded = false
                    if (requiresJobStatusConfirmation(currentStatus, status)) {
                        confirming = status
                    } else {
                        onSelect(status)
                    }
                }
            }
        }
    }

    confirming?.let { target ->
        JobStatusConfirmDialog(
            jobNumber = jobNumber,
            currentStatus = currentStatus,
            enabled = enabled,
            onConfirm = {
                confirming = null
                onSelect(target)
            },
            onDismiss = { confirming = null },
        )
    }
}

/**
 * Whether a destination is consequential enough to confirm before it is sent (`BR-058`).
 *
 * Two destinations change what the record means rather than where it is in the work: **closing** a
 * Job ends its field work and makes its Visit outcomes final (`BR-062`, `BR-079`), and **reopening** a
 * closed one returns it to the scheduling workflow (`BR-063`). Both are asked about; every other
 * destination is the ordinary correction the control exists for and is sent as it is chosen, because a
 * confirmation on each one would only make the menu slower to use.
 *
 * This decides an affordance, never authorization or eligibility: whether the change is allowed at all
 * is the API's answer (`BR-007`, `BR-061`, `BR-062`), and confirming the dialog cannot override it.
 */
internal fun requiresJobStatusConfirmation(
    currentStatus: JobStatus,
    targetStatus: JobStatus,
): Boolean {
    val isReopen =
        currentStatus == JobStatus.COMPLETED || currentStatus == JobStatus.CANCELED
    return targetStatus == JobStatus.COMPLETED || isReopen
}

/** The width every row of the status menu shares, so the menu stays a compact control menu. */
private val JobStatusMenuWidth = 216.dp

/** The chevron that marks the Job's status chip as opening the transitions it may move to. */
private val StatusControlIndicatorSize = 16.dp

/**
 * The status the Job is in, stated at the head of the menu.
 *
 * It is drawn from the one Job status mapping — its own dot colour and its own localized label
 * (`BR-041`, `BR-058`) — and it is marked as the current one rather than offered as a choice, so the
 * user never taps the status they are already in.
 */
@Composable
private fun JobStatusCurrentRow(status: JobStatus) {
    Row(
        modifier = Modifier
            .width(JobStatusMenuWidth)
            .heightIn(min = 40.dp)
            .testTag(JobDetailsStatusCurrentTag)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(color = jobStatusAccent(status))
        Spacer(Modifier.width(12.dp))
        Text(
            modifier = Modifier.weight(1f),
            text = stringResource(jobStatusLabel(status)),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Icon(
            painter = painterResource(R.drawable.ic_check_circle),
            contentDescription = stringResource(R.string.job_details_status_current),
            tint = jobStatusAccent(status),
            modifier = Modifier.size(StatusControlIndicatorSize),
        )
    }
}

/**
 * One status the Job may move to, offered as the status's own name and colour rather than as a chip:
 * the choice belongs to the control, so the menu stays a compact list instead of a second row of
 * chips (`docs/design/android-design-system.md`).
 */
@Composable
private fun JobStatusTransitionItem(status: JobStatus, onClick: () -> Unit) {
    DropdownMenuItem(
        modifier = Modifier
            .width(JobStatusMenuWidth)
            .testTag(jobDetailsStatusOptionTag(status.name)),
        leadingIcon = { StatusDot(color = jobStatusAccent(status)) },
        text = {
            Text(
                text = stringResource(jobStatusLabel(status)),
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        onClick = onClick,
    )
}

/**
 * The confirmation a consequential Job status change is asked for (`BR-058`, `BR-062`, `BR-063`).
 *
 * Closing a Job and reopening a closed one are decisions about what the record means, so they are put
 * to the user in the terms they decide in — which Job, and what the change does — rather than being
 * sent from a tap that closes a menu. Confirming sends the change; dismissing leaves the Job exactly
 * as it stands (`BR-067`).
 *
 * The dialog asks; it never grants. Whether the Job may actually move is the API's answer, and a
 * refusal it returns is reported the same way as any other refused action (`BR-007`, `BR-042`).
 */
@Composable
private fun JobStatusConfirmDialog(
    jobNumber: Int,
    currentStatus: JobStatus,
    enabled: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val isReopen =
        currentStatus == JobStatus.COMPLETED || currentStatus == JobStatus.CANCELED
    AlertDialog(
        modifier = Modifier.testTag(JobDetailsStatusConfirmDialogTag),
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (isReopen) {
                        R.string.job_status_confirm_reopen_title
                    } else {
                        R.string.job_status_confirm_close_title
                    },
                ),
            )
        },
        text = {
            Text(
                stringResource(
                    if (isReopen) {
                        R.string.job_status_confirm_reopen_message
                    } else {
                        R.string.job_status_confirm_close_message
                    },
                    jobNumber,
                ),
            )
        },
        confirmButton = {
            Button(
                modifier = Modifier.testTag(JobDetailsStatusConfirmButtonTag),
                enabled = enabled,
                onClick = onConfirm,
            ) {
                Text(stringResource(R.string.job_status_confirm_accept))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.job_action_cancel))
            }
        },
    )
}

/**
 * What the screen reports about the last action, as a transient Snackbar (`BR-042`).
 *
 * The report does not stay in the layout: the Job the API answered with already presents the change,
 * so leaving the report standing would say the same thing twice and hold space the record needs
 * (`BR-001`). A completed action is therefore shown briefly and released. A refusal is kept until the
 * user dismisses it, because nothing on screen reflects a change that did not happen — the refusal is
 * the action's only report.
 *
 * The appearance travels with the message rather than being recomputed where it is drawn
 * ([JobActionSnackbarVisuals]), because the same report is drawn by whichever window is on screen: the
 * screen's own host normally, and the full-size photo viewer's host while the viewer is open
 * (`docs/tracker/031-android-photo-viewer-ui.md`). One report therefore reads identically in both.
 */
@Composable
internal fun JobActionSnackbar(
    message: String,
    isError: Boolean,
    actionLabel: String?,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .testTag(JobDetailsActionMessageTag),
        shape = MaterialTheme.shapes.medium,
        color = if (isError) {
            MaterialTheme.colorScheme.errorContainer
        } else {
            MaterialTheme.colorScheme.secondaryContainer
        },
        contentColor = if (isError) {
            MaterialTheme.colorScheme.onErrorContainer
        } else {
            MaterialTheme.colorScheme.onSecondaryContainer
        },
        shadowElevation = 4.dp,
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                modifier = Modifier.weight(1f),
                text = message,
                style = MaterialTheme.typography.bodyMedium,
            )
            if (actionLabel != null) {
                TextButton(onClick = onAction) {
                    Text(actionLabel)
                }
            }
        }
    }
}

/**
 * The report of an action, and the appearance it must be drawn with (`BR-042`).
 *
 * The flag is part of the message rather than something the drawing site recalculates, because the
 * same report is drawn by whichever window is on screen — the screen's host, or the full-size photo
 * viewer's own host — and both must render it the same way (`BR-041`).
 */
internal class JobActionSnackbarVisuals(
    override val message: String,
    val isError: Boolean,
    override val actionLabel: String? = null,
    override val withDismissAction: Boolean = false,
    override val duration: SnackbarDuration,
) : SnackbarVisuals

/**
 * Hosts the report of what the last action did (`BR-042`).
 *
 * It is one composable rather than a block repeated per call site, so a report is never drawn two
 * different ways: the screen shows it at the bottom of its own layout, and the photo viewer shows it
 * inside its full-screen dialog while that is open
 * (`docs/tracker/031-android-photo-viewer-ui.md`).
 */
@Composable
internal fun JobActionSnackbarHost(hostState: SnackbarHostState, modifier: Modifier = Modifier) {
    SnackbarHost(hostState = hostState, modifier = modifier) { data ->
        JobActionSnackbar(
            message = data.visuals.message,
            isError = (data.visuals as? JobActionSnackbarVisuals)?.isError == true,
            actionLabel = data.visuals.actionLabel,
            onAction = { data.performAction() },
        )
    }
}

/** The localized message a refused action is reported with (`BR-028`, `BR-041`). */
internal fun jobActionFailureMessage(
    failure: com.servora.android.data.jobs.JobActionFailure,
): Int =
    when (failure) {
        com.servora.android.data.jobs.JobActionFailure.UNAUTHENTICATED ->
            R.string.job_action_error_unauthenticated

        com.servora.android.data.jobs.JobActionFailure.FORBIDDEN ->
            R.string.job_action_error_forbidden

        com.servora.android.data.jobs.JobActionFailure.NOT_FOUND -> R.string.job_action_error_not_found

        com.servora.android.data.jobs.JobActionFailure.VALIDATION ->
            R.string.job_action_error_validation

        com.servora.android.data.jobs.JobActionFailure.VERSION_CONFLICT ->
            R.string.job_action_error_version_conflict

        com.servora.android.data.jobs.JobActionFailure.JOB_TRANSITION_NOT_ALLOWED ->
            R.string.job_action_error_transition

        com.servora.android.data.jobs.JobActionFailure.JOB_COMPLETION_BLOCKED ->
            R.string.job_action_error_completion_blocked

        com.servora.android.data.jobs.JobActionFailure.VISIT_NOT_RESCHEDULABLE ->
            R.string.job_action_error_visit_not_reschedulable

        // The follow-up request's own two answers (`BR-FV-012`), reported through the same mapping
        // because approving a request is one of this screen's Visit-scheduling actions.
        com.servora.android.data.jobs.JobActionFailure.VISIT_REQUEST_CHANGED ->
            R.string.job_action_error_visit_request_changed

        com.servora.android.data.jobs.JobActionFailure.VISIT_REQUEST_NOT_REVIEWABLE ->
            R.string.job_action_error_visit_request_reviewed

        // The Visit's own lifecycle answers (`docs/api/job-actions.md` §7), reported separately from
        // the Job's so the technician is told which state machine refused the change (`BR-059`).
        com.servora.android.data.jobs.JobActionFailure.VISIT_TRANSITION_NOT_ALLOWED ->
            R.string.job_action_error_visit_transition

        com.servora.android.data.jobs.JobActionFailure.VISIT_SCHEDULING_CONDITION_NOT_MET ->
            R.string.job_action_error_visit_scheduling

        com.servora.android.data.jobs.JobActionFailure.JOB_CLOSED_FOR_FIELD_WORK ->
            R.string.job_action_error_job_closed

        com.servora.android.data.jobs.JobActionFailure.VISIT_OPERATION_REUSED ->
            R.string.job_action_error_visit_operation_reused

        com.servora.android.data.jobs.JobActionFailure.TECHNICIANS_NOT_ASSIGNABLE ->
            R.string.job_action_error_technicians_not_assignable

        // A removal conflict is reported by the evidence removal, which reports through the photo
        // channel; stating it here too keeps the mapping total without inventing a meaning for a code
        // this screen's actions cannot produce (`BR-042`).
        com.servora.android.data.jobs.JobActionFailure.PHOTO_ALREADY_REMOVED ->
            R.string.job_photo_error_removal_unavailable

        // The audio kind's own conflict (`ADR-018` A7), reported through the audio channel for the
        // same reason (`BR-042`).
        com.servora.android.data.jobs.JobActionFailure.AUDIO_NOTE_ALREADY_REMOVED ->
            R.string.job_audio_error_removal_unavailable

        com.servora.android.data.jobs.JobActionFailure.NETWORK -> R.string.job_action_error_network
        com.servora.android.data.jobs.JobActionFailure.SERVER -> R.string.job_action_error_server
        com.servora.android.data.jobs.JobActionFailure.UNEXPECTED -> R.string.job_action_error_unexpected
    }

/** The localized confirmation a completed action is reported with (`BR-028`). */
internal fun jobActionCompletionMessage(kind: JobActionKind): Int =
    when (kind) {
        JobActionKind.STATUS_CHANGE -> R.string.job_action_done_status
        JobActionKind.RESCHEDULE -> R.string.job_action_done_reschedule
        JobActionKind.CREATE_VISIT -> R.string.job_action_done_schedule_visit
        JobActionKind.ASSIGNMENT -> R.string.job_action_done_assignment
        JobActionKind.ACTIVITY_TEXT -> R.string.job_action_done_activity_text
        JobActionKind.ACTIVITY_TEXT_EDIT -> R.string.job_action_done_activity_text_edit
        JobActionKind.ACTIVITY_TEXT_REMOVE -> R.string.job_action_done_activity_text_remove
        JobActionKind.VISIT_STATUS_CHANGE -> R.string.job_action_done_visit_status
        JobActionKind.VISIT_COMPLETION -> R.string.job_action_done_visit_completion
        JobActionKind.FOLLOW_UP_REQUEST -> R.string.job_action_done_follow_up_request
    }

/**
 * The reschedule dialog (`BR-073`).
 *
 * It starts from the Visit's current schedule, so the user changes what needs changing, and it edits
 * the existing Visit rather than creating another one: the Visit's identity and crew are untouched.
 * The internal schedule is what is edited, because it is the one authoritative for dispatch
 * (`BR-072`).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RescheduleVisitDialog(
    visit: com.servora.android.domain.model.JobDetailsVisit,
    isSubmitting: Boolean,
    onConfirm: (start: Instant, end: Instant) -> Unit,
    onDismiss: () -> Unit,
) {
    val zone = remember { ZoneId.systemDefault() }
    val currentStart = remember(visit.scheduledStart) {
        runCatching { Instant.parse(visit.scheduledStart).atZone(zone) }.getOrNull()
    }
    val currentEnd = remember(visit.scheduledEnd) {
        runCatching { Instant.parse(visit.scheduledEnd).atZone(zone) }.getOrNull()
    }

    var date by rememberSaveable(visit.id) {
        mutableStateOf((currentStart ?: ZonedDateTime.now(zone)).toLocalDate().toString())
    }
    var hour by rememberSaveable(visit.id) {
        mutableIntStateOf((currentStart ?: ZonedDateTime.now(zone)).hour)
    }
    var minute by rememberSaveable(visit.id) {
        mutableIntStateOf((currentStart ?: ZonedDateTime.now(zone)).minute)
    }
    var durationMinutes by rememberSaveable(visit.id) {
        val minutes = if (currentStart != null && currentEnd != null) {
            java.time.Duration.between(currentStart, currentEnd).toMinutes().toInt()
        } else {
            DEFAULT_DURATION_MINUTES
        }
        mutableIntStateOf(minutes.coerceAtLeast(30))
    }

    var showDatePicker by rememberSaveable { mutableStateOf(false) }
    var showTimePicker by rememberSaveable { mutableStateOf(false) }

    val start = remember(date, hour, minute, zone) {
        LocalDate.parse(date).atTime(hour, minute).atZone(zone).toInstant()
    }
    val end = remember(start, durationMinutes) { start.plusSeconds(durationMinutes * 60L) }

    AlertDialog(
        modifier = Modifier.testTag(JobDetailsRescheduleDialogTag),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.job_reschedule_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = stringResource(R.string.job_reschedule_message),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FieldRow(
                    label = stringResource(R.string.job_reschedule_date_label),
                    value = LocalDate.parse(date).format(
                        DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
                            .withLocale(deviceLocale()),
                    ),
                    testTag = JobDetailsRescheduleDateTag,
                    onClick = { showDatePicker = true },
                )
                FieldRow(
                    label = stringResource(R.string.job_reschedule_time_label),
                    value = start.atZone(zone).format(
                        DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
                            .withLocale(deviceLocale()),
                    ),
                    testTag = JobDetailsRescheduleTimeTag,
                    onClick = { showTimePicker = true },
                )
                Text(
                    text = stringResource(R.string.job_reschedule_duration_label),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DURATION_CHOICES.forEach { choice ->
                        DurationChoice(
                            minutes = choice,
                            selected = choice == durationMinutes,
                            onSelect = { durationMinutes = choice },
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                modifier = Modifier.testTag(JobDetailsRescheduleConfirmTag),
                enabled = !isSubmitting,
                onClick = { onConfirm(start, end) },
            ) {
                Text(stringResource(R.string.job_reschedule_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.job_action_cancel))
            }
        },
    )

    if (showDatePicker) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = LocalDate.parse(date)
                .atStartOfDay(ZoneId.of("UTC"))
                .toInstant()
                .toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        state.selectedDateMillis?.let { millis ->
                            date = Instant.ofEpochMilli(millis)
                                .atZone(ZoneId.of("UTC"))
                                .toLocalDate()
                                .toString()
                        }
                        showDatePicker = false
                    },
                ) {
                    Text(stringResource(R.string.job_action_ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text(stringResource(R.string.job_action_cancel))
                }
            },
        ) {
            DatePicker(state = state)
        }
    }

    if (showTimePicker) {
        val state = rememberTimePickerState(
            initialHour = hour,
            initialMinute = minute,
            is24Hour = DateFormat.is24HourFormat(LocalContext.current),
        )
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        hour = state.hour
                        minute = state.minute
                        showTimePicker = false
                    },
                ) {
                    Text(stringResource(R.string.job_action_ok))
                }
            },
            text = { TimePicker(state = state) },
        )
    }
}

/** The default Visit length the dialog offers when the Visit has no readable schedule. */
private const val DEFAULT_DURATION_MINUTES = 120

/** The Visit lengths the dialog offers, in minutes. */
private val DURATION_CHOICES = listOf(60, 120, 180, 240, 480)

/**
 * One labelled value of the dialog that opens a picker when it is tapped.
 *
 * It is shared with the schedule sheet (`ScheduleVisitSheet`), which asks for the same four values in
 * the same way, so the two scheduling forms cannot drift into two different controls (`BR-041`).
 */
@Composable
internal fun FieldRow(
    label: String,
    value: String,
    testTag: String,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().testTag(testTag),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.secondary,
        contentColor = MaterialTheme.colorScheme.onSecondary,
        onClick = onClick,
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
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
    }
}

/** One selectable Visit length. */
@Composable
private fun DurationChoice(
    minutes: Int,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.secondary
        },
        contentColor = if (selected) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSecondary
        },
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        onClick = onSelect,
    ) {
        Text(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            text = stringResource(R.string.job_reschedule_duration_hours, minutes / 60),
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

/**
 * The assignment sheet (`BR-068`, `BR-069`).
 *
 * The user states the **whole** crew with exactly one Lead, which is what makes removing the current
 * Lead and choosing the next one a single explicit action: the API never promotes anybody on its own
 * (`BR-069`). A crew that is not exactly one Lead cannot be confirmed, so the screen never asks the
 * API a question it would have to refuse.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AssignTechniciansSheet(
    technicians: List<AssignableTechnician>?,
    assigned: List<com.servora.android.domain.model.JobDetailsTechnician>,
    failure: com.servora.android.data.jobs.JobActionFailure?,
    isSubmitting: Boolean,
    onConfirm: (List<TechnicianAssignment>) -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val initiallyAssigned = remember(assigned) {
        assigned.map { technician -> technician.membershipId }
    }
    // Held as a list rather than a set so the sheet survives a configuration change: a `Set` is not a
    // saveable type, and losing the half-made crew on a rotation would be a silent loss of the user's
    // decision.
    var selected by rememberSaveable(assigned) {
        mutableStateOf(initiallyAssigned)
    }
    var lead by rememberSaveable(assigned) {
        mutableStateOf(assigned.firstOrNull { it.isLead }?.membershipId)
    }

    ModalBottomSheet(
        modifier = Modifier.testTag(JobDetailsAssignmentSheetTag),
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().navigationBarsPadding()
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.job_assign_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(R.string.job_assign_message),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            when {
                failure != null ->
                    Column(
                        modifier = Modifier.testTag(JobDetailsAssignmentFailureTag),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = stringResource(jobActionFailureMessage(failure)),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                        TextButton(onClick = onRetry) {
                            Text(stringResource(R.string.job_action_retry))
                        }
                    }

                technicians == null ->
                    Text(
                        text = stringResource(R.string.job_assign_loading),
                        style = MaterialTheme.typography.bodyMedium,
                    )

                technicians.isEmpty() ->
                    Text(
                        text = stringResource(R.string.job_assign_empty),
                        style = MaterialTheme.typography.bodyMedium,
                    )

                else ->
                    Column(
                        modifier = Modifier.heightIn(max = 360.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        technicians.forEachIndexed { index, technician ->
                            if (index > 0) {
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            }
                            CandidateRow(
                                technician = technician,
                                isSelected = technician.membershipId in selected,
                                isLead = technician.membershipId == lead,
                                onToggle = { included ->
                                    selected = if (included) {
                                        selected + technician.membershipId
                                    } else {
                                        selected - technician.membershipId
                                    }
                                    // The Lead is always one of the assigned technicians (`BR-068`),
                                    // so removing them clears the choice rather than leaving a Lead
                                    // who is not on the Visit.
                                    if (!included && lead == technician.membershipId) {
                                        lead = selected.firstOrNull()
                                    }
                                },
                                onLead = { lead = technician.membershipId },
                            )
                        }
                    }
            }

            Button(
                modifier = Modifier.fillMaxWidth().testTag(JobDetailsAssignmentConfirmTag),
                enabled = !isSubmitting && selected.isNotEmpty() && lead != null,
                onClick = {
                    onConfirm(
                        selected.map { membershipId ->
                            TechnicianAssignment(
                                membershipId = membershipId,
                                role = if (membershipId == lead) {
                                    AssignmentRole.LEAD
                                } else {
                                    AssignmentRole.TECHNICIAN
                                },
                            )
                        },
                    )
                },
            ) {
                Text(stringResource(R.string.job_assign_confirm))
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

/** One technician the sheet may include, and the Lead choice among the included ones. */
@Composable
private fun CandidateRow(
    technician: AssignableTechnician,
    isSelected: Boolean,
    isLead: Boolean,
    onToggle: (Boolean) -> Unit,
    onLead: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = isSelected,
                role = Role.Checkbox,
                onValueChange = onToggle,
            )
            .testTag(jobDetailsAssignmentCandidateTag(technician.membershipId))
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = isSelected, onCheckedChange = null)
        Spacer(Modifier.width(8.dp))
        Text(
            modifier = Modifier.weight(1f),
            text = technician.name
                ?.takeIf { it.isNotBlank() }
                ?: stringResource(R.string.job_details_technician_name_unknown),
            style = MaterialTheme.typography.bodyLarge,
        )
        if (isSelected) {
            TextButton(
                modifier = Modifier.testTag(jobDetailsAssignmentLeadTag(technician.membershipId)),
                onClick = onLead,
            ) {
                Text(
                    text = stringResource(
                        if (isLead) R.string.assignment_role_lead else R.string.job_assign_make_lead,
                    ),
                    fontWeight = if (isLead) FontWeight.Bold else FontWeight.Normal,
                )
            }
        }
    }
}

/**
 * The availability-conflict confirmation (`BR-070`).
 *
 * It shows which Visit, which technician and which time overlap, and asks whether to proceed. In v1
 * the conflict is a warning rather than a prohibition, so confirming it applies the change — and what
 * was accepted is recorded with it. A conflict this build could not read in detail is still put to the
 * user, just without a list (`BR-042`).
 *
 * It takes the conflicts rather than the action they belong to, because the decision it presents is the
 * same one whatever produced them — a reschedule, a crew change, a working-status correction or a Visit
 * being scheduled (`BR-070`) — and the schedule screen decides about the conflicts of an approval with
 * the same dialog (`BR-041`).
 */
@Composable
internal fun ScheduleConflictDialog(
    conflicts: List<ScheduleConflict>,
    isSubmitting: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val zone = remember { ZoneId.systemDefault() }
    AlertDialog(
        modifier = Modifier.testTag(JobDetailsConflictDialogTag),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.job_conflict_title)) },
        text = {
            Column(
                modifier = Modifier.testTag(JobDetailsConflictListTag),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = stringResource(R.string.job_conflict_message),
                    style = MaterialTheme.typography.bodyMedium,
                )
                conflicts.forEach { conflict ->
                    Text(
                        text = conflictLine(conflict, zone),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                modifier = Modifier.testTag(JobDetailsConflictConfirmTag),
                enabled = !isSubmitting,
                onClick = onConfirm,
            ) {
                Text(stringResource(R.string.job_conflict_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.job_action_cancel))
            }
        },
    )
}

/** One conflicting Visit as one line: who is booked, on which job, and when. */
private fun conflictLine(conflict: ScheduleConflict, zone: ZoneId): String {
    val window = listOf(conflict.scheduledStart, conflict.scheduledEnd)
        .map { instant ->
            runCatching { Instant.parse(instant).atZone(zone) }.getOrNull()
        }
        .map { zoned ->
            zoned?.format(
                DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT, FormatStyle.SHORT)
                    .withLocale(Locale.getDefault()),
            ) ?: "?"
        }
        .joinToString("–")
    val who = conflict.technicianName ?: "?"
    return "$who · #${conflict.jobNumber} · $window"
}

/*
 * The Visit's field action (`BR-074`, `BR-075`, `BR-077`, `BR-078`).
 *
 * The two state machines stay separate: this control moves the **Visit**, and the Job's status control
 * moves the Job. Neither is drawn in place of the other (`BR-059`).
 */

/**
 * The Visit's own working-status control (`BR-074`).
 *
 * The four technician working states are drawn as **one full-width bar of four equal segments**, and the
 * state the Visit is in is the selected segment — so a technician taps the state they are in rather than
 * opening a menu, and moves the Visit in one operation whether that is forwards or backwards (`BR-074`,
 * `BR-075`). Four equal segments rather than four chips that each size to their own label: a ragged row of
 * chips wraps as soon as a label grows — in French, or at a larger text size — and the control then reads
 * as two unrelated rows of objects rather than as one control, which is what a field surface must not do
 * (`BR-012`, `BR-028`).
 *
 * The bar carries no group label of its own: each segment names the state it moves the Visit to, a screen
 * reader announces the selected one as the state the Visit holds (`Role.RadioButton` inside the row's
 * `selectableGroup`), and the section's own label above it already says when this Visit is for (`BR-028`).
 *
 * The segments are drawn from the destinations the **API** reported for this Visit, so no lifecycle rule
 * is re-implemented here (`BR-041`): a state the API did not offer is present as the Visit's current state
 * but cannot be tapped, and a Visit the API offers no destination for draws no bar at all.
 *
 * Finishing the Visit is deliberately not one of these segments (`BR-077`): completion records an outcome,
 * so it is the card's own action under this bar.
 */
@Composable
internal fun VisitWorkingStatusSelector(
    status: VisitStatus,
    allowedTransitions: List<VisitStatus>,
    enabled: Boolean,
    onSelect: (VisitStatus) -> Unit,
    modifier: Modifier = Modifier,
) {
    val states = VisitStatus.technicianWorkingStates
    val colors = workingStatusColors()
    SingleChoiceSegmentedButtonRow(
        modifier = modifier
            .fillMaxWidth()
            .testTag(JobDetailsVisitStatusSelectorTag),
    ) {
        states.forEachIndexed { index, state ->
            SegmentedButton(
                selected = state == status,
                // A segment is tappable when the API reported that destination for this Visit **and** the
                // control is not busy: the state the Visit already holds is shown as the selected segment
                // rather than offered, because moving a Visit to where it is is not a transition
                // (`BR-058`, `BR-074`).
                onClick = { onSelect(state) },
                enabled = enabled && state in allowedTransitions,
                shape = SegmentedButtonDefaults.itemShape(index = index, count = states.size),
                colors = colors,
                // No check glyph: the selected container already says where the Visit is, and four
                // checkmarks across one card would read as a task list rather than as the Visit's state
                // (`BR-012`).
                icon = {},
                // The four labels are the widest in either language, so the content padding is tighter than
                // Material's default: `Scheduled` and `In progress` keep to one line on a phone in English
                // and French alike. A label too long to fit wraps onto a second line rather than losing
                // letters, so the largest text sizes grow the bar instead of truncating it (`BR-028`).
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                modifier = Modifier.testTag(jobDetailsVisitStatusOptionTag(state.name)),
                label = {
                    Text(
                        text = stringResource(visitStatusLabel(state)),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                    )
                },
            )
        }
    }
}

/**
 * The colours the working-status bar is drawn with (`BR-074`).
 *
 * The selected segment is the state the Visit holds, and it is deliberately **not** tappable — moving a
 * Visit to where it already is is not a transition (`BR-074`) — so the disabled-and-selected colours are
 * stated as the selected ones: Material would otherwise fade the label of the one segment that says where
 * the technician is, and the current state has to be the obvious one (`BR-012`). The container stays the
 * app's own `primaryContainer`, the fill every Servora status control uses for a state that is in effect.
 *
 * A segment that is neither the Visit's state nor a destination keeps Material's own muted unselected
 * colours: it is transparent with a quiet outline, because nothing about it is actionable right now.
 */
@Composable
private fun workingStatusColors(): SegmentedButtonColors =
    SegmentedButtonDefaults.colors(
        activeContainerColor = MaterialTheme.colorScheme.primaryContainer,
        activeContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        disabledActiveContainerColor = MaterialTheme.colorScheme.primaryContainer,
        disabledActiveContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    )

/**
 * The primary action that finishes the Visit (`BR-077`).
 *
 * Completion is its own business operation rather than a status destination, so it is drawn as one
 * obvious action under the working-status bar rather than as another segment: tapping it opens the sheet
 * where the outcome `BR-077` requires is stated, and nothing is sent until the sheet is confirmed.
 */
@Composable
internal fun VisitCompleteAction(
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FilledTonalButton(
        onClick = onClick,
        enabled = enabled,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier
            .fillMaxWidth()
            .testTag(JobDetailsVisitCompleteActionTag),
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_check_circle),
            contentDescription = null,
            modifier = Modifier.size(StatusControlIndicatorSize),
        )
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.job_visit_complete_action))
    }
}

/**
 * The notice that the Job is closed, so nothing further may be recorded under it (`BR-062`, `BR-079`).
 *
 * A cancellation is drawn as a refusal — the technician may be on their way to, or standing at, work the
 * office has already called off — and says the Job was canceled rather than that something went wrong.
 * A completed Job gets the quieter notice, because its field record ending is what finishing the work
 * means. Neither is a dialog: a closed Job is information the technician needs, not a decision the
 * screen takes on their behalf (`BR-012`, `BR-042`).
 */
@Composable
internal fun JobReadOnlyNotice(
    reason: JobReadOnlyReason,
    modifier: Modifier = Modifier,
) {
    val isCanceled = reason == JobReadOnlyReason.JOB_CANCELED
    Surface(
        modifier = modifier.fillMaxWidth().testTag(JobDetailsReadOnlyNoticeTag),
        shape = MaterialTheme.shapes.large,
        color = if (isCanceled) {
            MaterialTheme.colorScheme.errorContainer
        } else {
            MaterialTheme.colorScheme.secondaryContainer
        },
        contentColor = if (isCanceled) {
            MaterialTheme.colorScheme.onErrorContainer
        } else {
            MaterialTheme.colorScheme.onSecondaryContainer
        },
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = stringResource(
                    if (isCanceled) {
                        R.string.job_details_job_canceled_title
                    } else {
                        R.string.job_details_job_completed_title
                    },
                ),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(
                    if (isCanceled) {
                        R.string.job_details_job_canceled_message
                    } else {
                        R.string.job_details_job_completed_message
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/**
 * The localized label one outcome code is presented with (`BR-028`, `BR-041`).
 *
 * The codes are the API's closed catalogue (`BR-078`), so the mapping is total and a code that is not
 * one of the four cannot reach it (`BR-042`).
 */
internal fun visitOutcomeLabel(outcome: VisitOutcome): Int =
    when (outcome) {
        VisitOutcome.RESOLVED -> R.string.job_activity_outcome_resolved
        VisitOutcome.NEEDS_FOLLOW_UP -> R.string.job_activity_outcome_needs_follow_up
        VisitOutcome.NEEDS_PARTS -> R.string.job_activity_outcome_needs_parts
        VisitOutcome.UNABLE_TO_COMPLETE -> R.string.job_activity_outcome_unable_to_complete
    }

/**
 * The one line of technician-friendly help each outcome is chosen with (`BR-028`).
 *
 * It explains what choosing the outcome records, so a technician in the field does not have to know
 * Servora's vocabulary to pick the right one: "resolved" is the work finished, "needs follow-up" is
 * another field attempt, "needs parts" is work waiting on a part, and "unable to complete" is an attempt
 * that could not be performed — the one that requires a reason.
 */
internal fun visitOutcomeSupportingText(outcome: VisitOutcome): Int =
    when (outcome) {
        VisitOutcome.RESOLVED -> R.string.job_visit_complete_outcome_resolved_support
        VisitOutcome.NEEDS_FOLLOW_UP -> R.string.job_visit_complete_outcome_needs_followup_support
        VisitOutcome.NEEDS_PARTS -> R.string.job_visit_complete_outcome_needs_parts_support
        VisitOutcome.UNABLE_TO_COMPLETE ->
            R.string.job_visit_complete_outcome_unable_support
    }

/**
 * The label the required text is asked for with (`BR-077`, `BR-078`).
 *
 * The API requires a summary with every outcome and states the same field as the **reason** when the
 * Visit could not be completed, so the sheet asks for what it is actually collecting: a reason the work
 * was not done, or a summary of what resulted from the attempt.
 */
internal fun visitOutcomeSummaryLabel(outcome: VisitOutcome?): Int =
    if (outcome == VisitOutcome.UNABLE_TO_COMPLETE) {
        R.string.job_visit_complete_reason_label
    } else {
        R.string.job_visit_complete_summary_label
    }

/** The placeholder the required text is prompted with, which follows the same reading (`BR-028`). */
internal fun visitOutcomeSummaryPlaceholder(outcome: VisitOutcome?): Int =
    if (outcome == VisitOutcome.UNABLE_TO_COMPLETE) {
        R.string.job_visit_complete_reason_placeholder
    } else {
        R.string.job_visit_complete_summary_placeholder
    }

/**
 * The sheet a completion states its outcome in (`BR-077`, `BR-078`).
 *
 * The API requires an outcome **and** a summary with a completion, so the sheet asks for both and
 * refuses to send a completion that omits either — which is what turns "the Visit is finished" into a
 * record of what resulted from the field attempt (`BR-077`). Submitting sends the completion operation
 * itself, so the state and the outcome can never disagree.
 *
 * Each outcome is offered with one line saying what choosing it records, because the technician is in
 * the field rather than reading the catalogue (`BR-012`, `BR-028`), and the required text is asked for
 * as a **reason** when the Visit could not be completed and as a **summary** otherwise, which is what
 * the API's own answer calls it (`BR-078`). The outcome codes are the API's and their labels are
 * localized; the text is the technician's own and is never translated (`BR-028`).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun VisitCompletionSheet(
    isSubmitting: Boolean,
    onConfirm: (VisitOutcome, String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var outcome by rememberSaveable { mutableStateOf<VisitOutcome?>(null) }
    var summary by remember { mutableStateOf("") }
    val trimmed = summary.trim()
    val canConfirm = outcome != null && trimmed.isNotEmpty() && !isSubmitting
    val requiresReason = outcome == VisitOutcome.UNABLE_TO_COMPLETE

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = Modifier.testTag(JobDetailsVisitCompletionSheetTag),
    ) {
        // The body scrolls and the two decisions stay on screen: the summary field is typed into, so the
        // sheet is shorter than its content while the keyboard is up, and a decision *inside* the
        // scrolling region would scroll away with it (`docs/design/android-design-system.md`).
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(R.string.job_visit_complete_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(R.string.job_visit_complete_outcome_label),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            VisitOutcomeOptions(
                selected = outcome,
                enabled = !isSubmitting,
                onSelect = { chosen -> outcome = chosen },
            )
            OutlinedTextField(
                value = summary,
                onValueChange = { text -> summary = text },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(JobDetailsVisitOutcomeSummaryTag),
                label = { Text(stringResource(visitOutcomeSummaryLabel(outcome))) },
                placeholder = {
                    Text(stringResource(visitOutcomeSummaryPlaceholder(outcome)))
                },
                minLines = 3,
                enabled = !isSubmitting,
                isError = requiresReason && trimmed.isEmpty(),
                supportingText = {
                    // The text is required of every outcome, so the sheet says so rather than letting the
                    // API refuse a completion the technician believed was complete (`BR-077`, `BR-042`).
                    Text(
                        stringResource(
                            if (requiresReason) {
                                R.string.job_visit_complete_reason_required
                            } else {
                                R.string.job_visit_complete_summary_required
                            },
                        ),
                    )
                },
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(top = 16.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onDismiss, enabled = !isSubmitting) {
                Text(stringResource(R.string.job_action_cancel))
            }
            Button(
                onClick = { outcome?.let { chosen -> onConfirm(chosen, trimmed) } },
                enabled = canConfirm,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.testTag(JobDetailsVisitCompleteConfirmTag),
            ) {
                Text(stringResource(R.string.job_visit_complete_confirm))
            }
        }
    }
}

/**
 * The four outcome types `BR-078` defines, offered as a single-choice list.
 *
 * Each one is stated with a single line saying what choosing it records, and the selected outcome is
 * drawn as selected rather than only as a ticked radio: the technician has to read what they are about
 * to record before they record it (`BR-012`, `BR-028`).
 */
@Composable
private fun VisitOutcomeOptions(
    selected: VisitOutcome?,
    enabled: Boolean,
    onSelect: (VisitOutcome) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        VisitOutcome.entries.forEach { option ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .toggleable(
                        value = option == selected,
                        enabled = enabled,
                        role = Role.RadioButton,
                        onValueChange = { onSelect(option) },
                    )
                    .testTag(jobDetailsVisitOutcomeOptionTag(option.name)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = option == selected, onClick = null, enabled = enabled)
                Spacer(Modifier.width(8.dp))
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = stringResource(visitOutcomeLabel(option)),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (option == selected) FontWeight.SemiBold else null,
                    )
                    Text(
                        text = stringResource(visitOutcomeSupportingText(option)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * What the queue is still holding for this Job (`§7`, `BR-014`).
 *
 * It is the technician's own provisional work — the Visit transition and the notes the backend has not
 * answered for — presented beside the records they will change rather than inside Job Activity, which
 * is what the backend reported (`BR-080`). Nothing here is drawn as applied: a waiting action says it
 * is waiting, and one the backend **refused** says why and offers to discard it, because `BR-014`
 * requires a failed synchronization to stay visible when the user has to decide about it (`BR-032`).
 */
@Composable
internal fun VisitFieldPendingNotice(
    action: QueuedVisitFieldAction?,
    notes: List<QueuedVisitNote>,
    onDiscardAction: (String) -> Unit,
    onDiscardNote: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (action == null && notes.isEmpty()) {
        return
    }
    val refused = action?.isAwaitingSync == false || notes.any { note -> !note.isAwaitingSync }
    Surface(
        modifier = modifier.fillMaxWidth().testTag(JobDetailsVisitPendingTag),
        shape = MaterialTheme.shapes.medium,
        color = if (refused) {
            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f)
        } else {
            MaterialTheme.colorScheme.secondary
        },
        contentColor = if (refused) {
            MaterialTheme.colorScheme.onErrorContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.job_visit_pending_title),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
            action?.let { queued ->
                PendingVisitActionRow(
                    action = queued,
                    onDiscard = { onDiscardAction(queued.operationId) },
                )
            }
            notes.forEach { note ->
                PendingNoteRow(note = note, onDiscard = { onDiscardNote(note.operationId) })
            }
        }
    }
}

/** One waiting Visit transition: what it will do, how far it has got, and why if it was refused. */
@Composable
private fun PendingVisitActionRow(
    action: QueuedVisitFieldAction,
    onDiscard: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = stringResource(
                R.string.job_visit_pending_action,
                stringResource(visitStatusLabel(action.status)),
            ),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = stringResource(pendingSyncStateText(action.state)),
            style = MaterialTheme.typography.bodySmall,
        )
        if (!action.isAwaitingSync) {
            action.failure?.let { reason ->
                Text(
                    text = stringResource(pendingFailureText(reason)),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            DiscardRefusedAction(onDiscard = onDiscard, operationId = action.operationId)
        }
    }
}

/** One waiting note: its own text, how far it has got, and why if it was refused. */
@Composable
private fun PendingNoteRow(note: QueuedVisitNote, onDiscard: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(text = note.body, style = MaterialTheme.typography.bodyMedium)
        Text(
            text = stringResource(pendingSyncStateText(note.state)),
            style = MaterialTheme.typography.bodySmall,
        )
        if (!note.isAwaitingSync) {
            note.failure?.let { reason ->
                Text(
                    text = stringResource(pendingFailureText(reason)),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            DiscardRefusedAction(onDiscard = onDiscard, operationId = note.operationId)
        }
    }
}

/** The one action a refused field action offers: the user finishes with it (`BR-032`, `BR-014`). */
@Composable
private fun DiscardRefusedAction(onDiscard: () -> Unit, operationId: String) {
    TextButton(
        onClick = onDiscard,
        modifier = Modifier.testTag(jobDetailsVisitPendingDiscardTag(operationId)),
    ) {
        Text(stringResource(R.string.job_visit_pending_discard))
    }
}

/** How far a queued operation has got, as the notice states it (`§6`, §7). */
internal fun pendingSyncStateText(state: OutboxOperationState): Int =
    when (state) {
        OutboxOperationState.PENDING -> R.string.job_visit_pending_queued
        OutboxOperationState.IN_FLIGHT -> R.string.job_visit_pending_sending
        OutboxOperationState.FAILED -> R.string.job_visit_pending_retrying
        OutboxOperationState.REJECTED -> R.string.job_visit_pending_refused
    }

/**
 * The localized reason a queued operation did not apply (`BR-028`, `BR-041`).
 *
 * The reasons are the queue's own classification of the API's answers (`§6`), so the notice says what
 * actually happened rather than "something went wrong".
 */
internal fun pendingFailureText(failure: OutboxFailureReason): Int =
    when (failure) {
        OutboxFailureReason.UNAUTHENTICATED -> R.string.job_action_error_unauthenticated
        OutboxFailureReason.NOT_AUTHORIZED -> R.string.job_action_error_forbidden
        OutboxFailureReason.STALE -> R.string.job_visit_pending_stale
        // The Job was canceled or completed elsewhere while this work waited. The technician is told
        // what happened and that the action will not apply, rather than offered a retry that can only
        // fail again (`BR-014`, `BR-032`, `BR-062`).
        OutboxFailureReason.JOB_CLOSED -> R.string.job_visit_pending_job_closed
        OutboxFailureReason.INVALID -> R.string.job_action_error_validation
        OutboxFailureReason.NOT_FOUND -> R.string.job_action_error_not_found
        OutboxFailureReason.SERVER -> R.string.job_action_error_server
        OutboxFailureReason.NETWORK -> R.string.job_action_error_network
        OutboxFailureReason.UNEXPECTED -> R.string.job_action_error_unexpected
    }

/**
 * The localized report that an action was **queued** rather than applied (`BR-014`, §7).
 *
 * It is deliberately not the "done" message: nothing has been applied, and the screen says so
 * (`BR-001`, `BR-086`).
 */
internal fun jobActionQueuedMessage(kind: JobActionKind): Int =
    when (kind) {
        JobActionKind.ACTIVITY_TEXT -> R.string.job_action_queued_activity_text
        JobActionKind.VISIT_STATUS_CHANGE -> R.string.job_action_queued_visit_status
        JobActionKind.VISIT_COMPLETION -> R.string.job_action_queued_visit_completion
        // The other actions are online-only, so nothing queues them; the mapping stays total for the
        // same reason the failure mapping does (`BR-042`).
        JobActionKind.STATUS_CHANGE,
        JobActionKind.RESCHEDULE,
        JobActionKind.ASSIGNMENT,
        JobActionKind.CREATE_VISIT,
        JobActionKind.ACTIVITY_TEXT_EDIT,
        JobActionKind.ACTIVITY_TEXT_REMOVE,
        JobActionKind.FOLLOW_UP_REQUEST,
        -> R.string.job_action_queued_generic
    }






