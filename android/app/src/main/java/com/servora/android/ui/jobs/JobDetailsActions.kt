package com.servora.android.ui.jobs

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.unit.dp
import android.text.format.DateFormat
import com.servora.android.R
import com.servora.android.domain.model.AssignableTechnician
import com.servora.android.domain.model.AssignmentRole
import com.servora.android.domain.model.JobStatus
import com.servora.android.domain.model.ScheduleConflict
import com.servora.android.domain.model.TechnicianAssignment
import com.servora.android.ui.components.JobStatusPill
import com.servora.android.ui.components.StatusDot
import com.servora.android.ui.components.jobStatusAccent
import com.servora.android.ui.components.jobStatusLabel
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
 * and then lists exactly the transitions the backend reported for that status (`BR-041`, `BR-058`).
 * The client holds no second copy of the lifecycle, so no arbitrary status is offered and a Job with
 * no permitted transition is not drawn with this control at all. Cancellation is absent from that list
 * while `BR-064`'s reason catalogue is an open question, so no destructive action is folded into the
 * status control: Job cancellation stays a separate, explicitly confirmed action, which is also what
 * `BR-064` requires of it (`docs/api/job-actions.md` §3).
 */
@Composable
internal fun JobStatusAction(
    currentStatus: JobStatus,
    allowedTransitions: List<JobStatus>,
    enabled: Boolean,
    onSelect: (JobStatus) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
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
                    onSelect(status)
                }
            }
        }
    }
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
 * What the screen reports about the last action, as a transient Snackbar (`BR-042`).
 *
 * The report does not stay in the layout: the Job the API answered with already presents the change,
 * so leaving the report standing would say the same thing twice and hold space the record needs
 * (`BR-001`). A completed action is therefore shown briefly and released. A refusal is kept until the
 * user dismisses it, because nothing on screen reflects a change that did not happen — the refusal is
 * the action's only report.
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

        com.servora.android.data.jobs.JobActionFailure.JOB_REVIEW_CONDITION_NOT_MET ->
            R.string.job_action_error_review_condition

        com.servora.android.data.jobs.JobActionFailure.JOB_CANCELLATION_UNAVAILABLE ->
            R.string.job_action_error_cancellation_unavailable

        com.servora.android.data.jobs.JobActionFailure.VISIT_NOT_RESCHEDULABLE ->
            R.string.job_action_error_visit_not_reschedulable

        com.servora.android.data.jobs.JobActionFailure.TECHNICIANS_NOT_ASSIGNABLE ->
            R.string.job_action_error_technicians_not_assignable

        com.servora.android.data.jobs.JobActionFailure.NETWORK -> R.string.job_action_error_network
        com.servora.android.data.jobs.JobActionFailure.SERVER -> R.string.job_action_error_server
        com.servora.android.data.jobs.JobActionFailure.UNEXPECTED -> R.string.job_action_error_unexpected
    }

/** The localized confirmation a completed action is reported with (`BR-028`). */
internal fun jobActionCompletionMessage(kind: JobActionKind): Int =
    when (kind) {
        JobActionKind.STATUS_CHANGE -> R.string.job_action_done_status
        JobActionKind.RESCHEDULE -> R.string.job_action_done_reschedule
        JobActionKind.ASSIGNMENT -> R.string.job_action_done_assignment
        JobActionKind.ACTIVITY_TEXT -> R.string.job_action_done_activity_text
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

/** One labelled value of the dialog that opens a picker when it is tapped. */
@Composable
private fun FieldRow(
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
 */
@Composable
internal fun ScheduleConflictDialog(
    pending: PendingJobAction,
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
                pending.conflicts.forEach { conflict ->
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
