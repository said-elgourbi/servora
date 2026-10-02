package com.servora.android.ui.jobs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.servora.android.R
import com.servora.android.ui.home.deviceLocale
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

/**
 * The form a technician submits a follow-up Visit request with (`BR-FV-001`, `BR-FV-003`).
 *
 * It asks for exactly what `BR-FV-003` says a request may carry and what the route accepts: **when**
 * the technician proposes the field attempt should be, **why** another one is needed, and whether they
 * would like to carry it out themselves. It asks for no crew, because who performs the follow-up is the
 * office's decision (`BR-FV-004`) — a request is a proposal, not a scheduled Visit (`BR-FV-002`), and
 * the copy says so rather than presenting the window as a confirmed appointment (`BR-FV-010`).
 *
 * One date and two times, for the same reason the schedule form asks for them: a field attempt starts
 * and ends on the same day (`BR-072`). The date and time controls are the schedule form's own, so the
 * two ways a window is stated cannot drift apart (`BR-041`).
 *
 * The form refuses to submit a request the API would refuse — one with no reason, or one whose end is
 * not after its start — and says why, instead of sending it (`BR-007`, `BR-042`).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RequestFollowUpVisitSheet(
    isSubmitting: Boolean,
    onConfirm: (reason: String, start: Instant, end: Instant, sameTechnicianPreferred: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val zone = remember { ZoneId.systemDefault() }
    // The form opens on the next full hour and a two-hour window: a proposal has nothing to open on,
    // and an empty form would ask the technician to state a window before they can state a reason.
    val proposalStart = remember(zone) {
        ZonedDateTime.now(zone).plusHours(1).truncatedTo(ChronoUnit.HOURS)
    }
    val proposalEnd = remember(proposalStart) {
        proposalStart.plusMinutes(DEFAULT_SCHEDULE_MINUTES)
    }

    var visitDate by rememberSaveable { mutableStateOf(proposalStart.toLocalDate().toString()) }
    var startHour by rememberSaveable { mutableIntStateOf(proposalStart.hour) }
    var startMinute by rememberSaveable { mutableIntStateOf(proposalStart.minute) }
    var endHour by rememberSaveable { mutableIntStateOf(proposalEnd.hour) }
    var endMinute by rememberSaveable { mutableIntStateOf(proposalEnd.minute) }
    var reason by rememberSaveable { mutableStateOf("") }
    var sameTechnicianPreferred by rememberSaveable { mutableStateOf(false) }
    var pickingDate by rememberSaveable { mutableStateOf(false) }
    var pickingStartTime by rememberSaveable { mutableStateOf(false) }
    var pickingEndTime by rememberSaveable { mutableStateOf(false) }
    // Whether the technician has stated anything yet. Telling someone who has just opened the form what
    // they have not filled in yet reads as an error they made (`BR-042`), so it says what is missing
    // once they have actually changed something.
    var touched by rememberSaveable { mutableStateOf(false) }

    val window = scheduleVisitWindow(
        date = visitDate,
        startHour = startHour,
        startMinute = startMinute,
        endHour = endHour,
        endMinute = endMinute,
        zone = zone,
    )
    val draft = window?.let { schedule ->
        RequestFollowUpDraft(
            start = schedule.start,
            end = schedule.end,
            reason = reason,
            sameTechnicianPreferred = sameTechnicianPreferred,
        )
    }
    val problem = if (touched) draft?.problem else null

    ModalBottomSheet(
        modifier = Modifier.testTag(RequestFollowUpSheetTag),
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        // The body scrolls and the action stays on screen. The sheet is shorter than its content while
        // the keyboard is up, so an action *inside* the scrolling region would scroll away with it and
        // have to be reached by dismissing the keyboard (`docs/design/android-design-system.md`).
        Column(
            modifier = Modifier.fillMaxWidth().weight(1f, fill = false)
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.request_follow_up_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(R.string.request_follow_up_message),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedTextField(
                value = reason,
                // The API accepts a bounded reason, so the form cannot hold one it would refuse
                // (`BR-042`).
                onValueChange = { text ->
                    touched = true
                    reason = text.take(REQUEST_FOLLOW_UP_REASON_MAX_LENGTH)
                },
                modifier = Modifier.fillMaxWidth().testTag(RequestFollowUpReasonTag),
                enabled = !isSubmitting,
                label = { Text(stringResource(R.string.request_follow_up_reason_label)) },
                placeholder = { Text(stringResource(R.string.request_follow_up_reason_placeholder)) },
                minLines = 3,
                maxLines = 5,
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            FieldRow(
                label = stringResource(R.string.schedule_visit_date_label),
                value = formattedScheduleDate(visitDate, deviceLocale()),
                testTag = RequestFollowUpDateTag,
                onClick = {
                    touched = true
                    pickingDate = true
                },
            )
            ScheduleTimeField(
                label = stringResource(R.string.schedule_visit_start_time_label),
                date = visitDate,
                hour = startHour,
                minute = startMinute,
                zone = zone,
                testTag = RequestFollowUpStartTimeTag,
                onPickTime = {
                    touched = true
                    pickingStartTime = true
                },
            )
            ScheduleTimeField(
                label = stringResource(R.string.schedule_visit_end_time_label),
                date = visitDate,
                hour = endHour,
                minute = endMinute,
                zone = zone,
                testTag = RequestFollowUpEndTimeTag,
                onPickTime = {
                    touched = true
                    pickingEndTime = true
                },
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    // The whole row is the control, so a tap anywhere on the label changes the answer
                    // rather than only the box (`BR-011`).
                    .toggleable(
                        value = sameTechnicianPreferred,
                        enabled = !isSubmitting,
                        role = Role.Checkbox,
                        onValueChange = { checked -> sameTechnicianPreferred = checked },
                    )
                    .testTag(RequestFollowUpSameTechnicianTag),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = sameTechnicianPreferred,
                    onCheckedChange = null,
                    enabled = !isSubmitting,
                )
                Text(
                    text = stringResource(R.string.schedule_request_same_technician),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            problem?.let { refusal ->
                Text(
                    modifier = Modifier.testTag(RequestFollowUpProblemTag),
                    text = stringResource(requestFollowUpProblemMessage(refusal)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        Button(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)
                .testTag(RequestFollowUpConfirmTag),
            // The route requires a reason and an end after the start, so the form does not ask a
            // question whose answer it already knows (`BR-042`).
            enabled = !isSubmitting && draft?.problem == null,
            onClick = {
                val ready = draft ?: return@Button
                onConfirm(
                    ready.reason.trim(),
                    ready.start,
                    ready.end,
                    ready.sameTechnicianPreferred,
                )
            },
        ) {
            Text(stringResource(R.string.request_follow_up_confirm))
        }
        Spacer(Modifier.height(12.dp))
    }

    if (pickingDate) {
        ScheduleDatePicker(
            initialDate = visitDate,
            onPick = { picked ->
                visitDate = picked
                pickingDate = false
            },
            onDismiss = { pickingDate = false },
        )
    }
    if (pickingStartTime) {
        ScheduleTimePicker(
            initialHour = startHour,
            initialMinute = startMinute,
            onPick = { hour, minute ->
                startHour = hour
                startMinute = minute
                pickingStartTime = false
            },
            onDismiss = { pickingStartTime = false },
        )
    }
    if (pickingEndTime) {
        ScheduleTimePicker(
            initialHour = endHour,
            initialMinute = endMinute,
            onPick = { hour, minute ->
                endHour = hour
                endMinute = minute
                pickingEndTime = false
            },
            onDismiss = { pickingEndTime = false },
        )
    }
}

/** The longest reason the request route accepts (`BR-FV-003`). */
internal const val REQUEST_FOLLOW_UP_REASON_MAX_LENGTH = 2000

/** Identifies the follow-up request form and the values it asks for (`BR-FV-001`, `BR-FV-003`). */
const val RequestFollowUpSheetTag = "request-follow-up-sheet"
const val RequestFollowUpDateTag = "request-follow-up-date"
const val RequestFollowUpStartTimeTag = "request-follow-up-start-time"
const val RequestFollowUpEndTimeTag = "request-follow-up-end-time"
const val RequestFollowUpReasonTag = "request-follow-up-reason"
const val RequestFollowUpSameTechnicianTag = "request-follow-up-same-technician"
const val RequestFollowUpProblemTag = "request-follow-up-problem"
const val RequestFollowUpConfirmTag = "request-follow-up-confirm"

/**
 * What the request form is holding: the window proposed, why another field attempt is needed and
 * whether the same technician would like to carry it out (`BR-FV-003`).
 *
 * It is separate from the screen so what a proposal must carry is a rule a test can check rather than a
 * detail of the layout that presents it (`BR-041`).
 */
internal data class RequestFollowUpDraft(
    val start: Instant,
    val end: Instant,
    val reason: String,
    val sameTechnicianPreferred: Boolean,
) {
    /** Why this proposal cannot be submitted, or `null` when it may (`BR-FV-003`). */
    val problem: RequestFollowUpProblem?
        get() = when {
            reason.isBlank() -> RequestFollowUpProblem.NO_REASON
            // A proposal states a window like any other, so its end is after its start (`BR-072`). The
            // API validates the same rule, so the form refuses to ask a question it can already answer
            // (`BR-042`).
            !end.isAfter(start) -> RequestFollowUpProblem.END_NOT_AFTER_START

            else -> null
        }
}

/** Why the values a follow-up request form holds cannot be submitted (`BR-FV-003`). */
internal enum class RequestFollowUpProblem {
    /** No reason was stated, and the API requires one. */
    NO_REASON,

    /** The proposed end is not after its start. */
    END_NOT_AFTER_START,
}

/** The localized message a problem is reported with (`BR-028`, `BR-041`). */
internal fun requestFollowUpProblemMessage(problem: RequestFollowUpProblem): Int =
    when (problem) {
        RequestFollowUpProblem.NO_REASON -> R.string.request_follow_up_problem_reason
        // The end-before-start rule is a window's own rule, so it is reported with the wording the
        // scheduling form already uses rather than a second name for one fact (`BR-041`).
        RequestFollowUpProblem.END_NOT_AFTER_START -> R.string.schedule_visit_problem_end
    }
