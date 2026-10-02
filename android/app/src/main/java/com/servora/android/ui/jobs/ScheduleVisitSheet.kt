package com.servora.android.ui.jobs

import android.text.format.DateFormat
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.servora.android.R
import com.servora.android.data.jobs.JobActionFailure
import com.servora.android.domain.model.AssignableTechnician
import com.servora.android.domain.model.AssignmentRole
import com.servora.android.domain.model.TechnicianAssignment
import com.servora.android.ui.home.deviceLocale
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * The form that schedules a Visit (`BR-068`, `BR-070`, `BR-071`, `BR-072`).
 *
 * One form serves the two ways a Visit is added to a Job — scheduling one directly on the Job, and
 * approving a technician's follow-up request into one (`BR-FV-005`) — because both operations state the
 * same three things: when the field attempt is, how long it runs, and who carries it. One form keeps one
 * answer to "what does scheduling a Visit require?" instead of two that can drift apart (`BR-041`,
 * `BR-072`).
 *
 * The form asks for **one** visit date and the two times that day, because a field attempt starts and
 * ends on the same day: a second date would offer a choice the product does not have. The internal start
 * and end the API is given are both built from that one date, and the end time must be later than the
 * start time (`BR-072`).
 *
 * What the API needs is what the form asks for. `BR-072` requires a valid internal window and a crew
 * with **exactly one** Lead (`BR-068`), so a form missing either cannot be submitted and says why,
 * rather than sending a request the API would refuse (`BR-007`, `BR-042`). The times are the device's
 * own, converted to instants before they are sent: the API stores and exchanges UTC instants and owns
 * what a schedule means (`BR-041`).
 *
 * The technicians come from the caller's own read, because the sheet never decides who may be assigned
 * (`BR-024`); a read that failed is reported with its own retry, exactly as the crew sheet reports one.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ScheduleVisitSheet(
    @StringRes titleRes: Int,
    @StringRes messageRes: Int,
    @StringRes confirmRes: Int,
    /** The window the form opens with — a request's proposal — or `null` for the next full hour. */
    initialStart: Instant?,
    initialEnd: Instant?,
    /** The technicians the crew may be composed of, or `null` while they are being read. */
    technicians: List<AssignableTechnician>?,
    /** Why the technicians could not be read, or `null`. */
    crewFailure: JobActionFailure?,
    isSubmitting: Boolean,
    onRetryCrew: () -> Unit,
    onConfirm: (start: Instant, end: Instant, assignments: List<TechnicianAssignment>) -> Unit,
    onDismiss: () -> Unit,
) {
    val zone = remember { ZoneId.systemDefault() }
    val proposalStart = remember(initialStart, zone) {
        readInstant(initialStart, zone)
            ?: ZonedDateTime.now(zone).plusHours(1).truncatedTo(ChronoUnit.HOURS)
    }
    val proposalEnd = remember(initialEnd, zone, proposalStart) {
        readInstant(initialEnd, zone) ?: proposalStart.plusMinutes(DEFAULT_SCHEDULE_MINUTES)
    }

    // The one day the field attempt is on. A proposal contributes its own time to each end, never a
    // second date, because a Visit starts and ends on the same day.
    var visitDate by rememberSaveable(initialStart) {
        mutableStateOf(proposalStart.toLocalDate().toString())
    }
    var startHour by rememberSaveable(initialStart) { mutableIntStateOf(proposalStart.hour) }
    var startMinute by rememberSaveable(initialStart) { mutableIntStateOf(proposalStart.minute) }
    var endHour by rememberSaveable(initialEnd) { mutableIntStateOf(proposalEnd.hour) }
    var endMinute by rememberSaveable(initialEnd) { mutableIntStateOf(proposalEnd.minute) }
    // Held as a list rather than a set so the sheet survives a configuration change: a `Set` is not a
    // saveable type, and losing a half-made crew would be a silent loss of the user's decision.
    var selected by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var lead by rememberSaveable { mutableStateOf<String?>(null) }
    var pickingDate by rememberSaveable { mutableStateOf(false) }
    var pickingStartTime by rememberSaveable { mutableStateOf(false) }
    var pickingEndTime by rememberSaveable { mutableStateOf(false) }
    // Whether the user has stated anything yet. The form opens on a proposed window and an empty crew,
    // and telling someone who has just opened it what they have not chosen yet reads as an error they
    // made (`BR-042`); it says why it cannot be submitted once they have actually changed something.
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
        ScheduleVisitDraft(
            start = schedule.start,
            end = schedule.end,
            crew = crewOf(selected = selected, lead = lead),
        )
    }
    val problem = if (touched) draft?.problem else null

    ModalBottomSheet(
        modifier = Modifier.testTag(ScheduleVisitSheetTag),
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().navigationBarsPadding()
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(titleRes),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(messageRes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            FieldRow(
                label = stringResource(R.string.schedule_visit_date_label),
                value = formattedScheduleDate(visitDate, deviceLocale()),
                testTag = ScheduleVisitDateTag,
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
                testTag = ScheduleVisitStartTimeTag,
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
                testTag = ScheduleVisitEndTimeTag,
                onPickTime = {
                    touched = true
                    pickingEndTime = true
                },
            )

            problem?.let { refusal ->
                Text(
                    modifier = Modifier.testTag(ScheduleVisitProblemTag),
                    text = stringResource(scheduleVisitProblemMessage(refusal)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text(
                text = stringResource(R.string.schedule_visit_crew_label),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            when {
                crewFailure != null ->
                    Column(
                        modifier = Modifier.testTag(ScheduleVisitCrewFailureTag),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = stringResource(jobActionFailureMessage(crewFailure)),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                        TextButton(onClick = onRetryCrew) {
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
                        modifier = Modifier.heightIn(max = 280.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        technicians.forEachIndexed { index, technician ->
                            if (index > 0) {
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            }
                            ScheduleVisitCrewRow(
                                technician = technician,
                                isSelected = technician.membershipId in selected,
                                isLead = technician.membershipId == lead,
                                onToggle = { included ->
                                    touched = true
                                    selected = if (included) {
                                        selected + technician.membershipId
                                    } else {
                                        selected - technician.membershipId
                                    }
                                    // The Lead is always one of the crew (`BR-068`), so removing them
                                    // clears the choice rather than leaving a Lead who is not on the Visit.
                                    if (!included && lead == technician.membershipId) {
                                        lead = selected.firstOrNull()
                                    }
                                },
                                onLead = {
                                    touched = true
                                    lead = technician.membershipId
                                },
                            )
                        }
                    }
            }

            Button(
                modifier = Modifier.fillMaxWidth().testTag(ScheduleVisitConfirmTag),
                // The API refuses a Visit scheduled without a valid window or a crew with exactly one
                // Lead (`BR-068`, `BR-072`), so the sheet does not ask a question whose answer it
                // already knows (`BR-042`).
                enabled = !isSubmitting && draft?.problem == null,
                onClick = {
                    val ready = draft ?: return@Button
                    onConfirm(ready.start, ready.end, ready.crew)
                },
            ) {
                Text(stringResource(confirmRes))
            }
            Spacer(Modifier.height(12.dp))
        }
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

/** Identifies the sheet that schedules a Visit, and the values it asks for (`BR-072`). */
const val ScheduleVisitSheetTag = "schedule-visit-sheet"
const val ScheduleVisitDateTag = "schedule-visit-date"
const val ScheduleVisitStartTimeTag = "schedule-visit-start-time"
const val ScheduleVisitEndTimeTag = "schedule-visit-end-time"
const val ScheduleVisitProblemTag = "schedule-visit-problem"
const val ScheduleVisitCrewFailureTag = "schedule-visit-crew-failure"
const val ScheduleVisitConfirmTag = "schedule-visit-confirm"

/** Identifies one technician the sheet may include, and the Lead choice among the included ones. */
fun scheduleVisitTechnicianTag(membershipId: String): String =
    "schedule-visit-technician-$membershipId"

fun scheduleVisitLeadTag(membershipId: String): String = "schedule-visit-lead-$membershipId"

/** The Visit length a schedule opens with when it has no proposal to open on. */
internal const val DEFAULT_SCHEDULE_MINUTES = 120L

/**
 * What a schedule form is holding: the window a Visit would be given and the crew it would carry
 * (`BR-068`, `BR-072`).
 *
 * It exists so the rule "what makes a Visit schedulable?" is stated once, in one place a test can
 * check, instead of living in the enabled-state of a button (`BR-041`). [problem] is what the API would
 * refuse the request for; it is `null` exactly when the form may be submitted.
 */
internal data class ScheduleVisitDraft(
    val start: Instant,
    val end: Instant,
    val crew: List<TechnicianAssignment>,
) {
    /** Why this draft cannot be submitted, or `null` when it may be (`BR-068`, `BR-072`). */
    val problem: ScheduleVisitProblem?
        get() = when {
            // `BR-072`: the internal scheduled end must be after its start. The API validates the same
            // rule, so the form refuses to ask a question it already knows the answer to (`BR-042`).
            !end.isAfter(start) -> ScheduleVisitProblem.END_NOT_AFTER_START
            // `BR-072`: a Visit cannot become `SCHEDULED` without at least one technician.
            crew.isEmpty() -> ScheduleVisitProblem.NO_TECHNICIANS
            // `BR-068`: exactly one of the assigned technicians is the Lead.
            crew.count { assignment -> assignment.role == AssignmentRole.LEAD } != 1 ->
                ScheduleVisitProblem.NO_LEAD

            else -> null
        }
}

/** Why the values a schedule form holds cannot be submitted (`BR-068`, `BR-072`). */
internal enum class ScheduleVisitProblem {
    /** The end is not after the start. */
    END_NOT_AFTER_START,

    /** No technician is chosen for the Visit. */
    NO_TECHNICIANS,

    /** The chosen crew does not hold exactly one Lead. */
    NO_LEAD,
}

/** The localized message a problem is reported with (`BR-028`, `BR-041`). */
@StringRes
private fun scheduleVisitProblemMessage(problem: ScheduleVisitProblem): Int =
    when (problem) {
        ScheduleVisitProblem.END_NOT_AFTER_START -> R.string.schedule_visit_problem_end
        ScheduleVisitProblem.NO_TECHNICIANS -> R.string.schedule_visit_problem_crew
        ScheduleVisitProblem.NO_LEAD -> R.string.schedule_visit_problem_lead
    }

/**
 * The crew a form's selection describes (`BR-068`).
 *
 * The list keeps the order the form holds, so the crew that is sent is the crew that was chosen, and
 * the Lead is the technician the user named rather than the first name in the list.
 */
private fun crewOf(
    selected: List<String>,
    lead: String?,
): List<TechnicianAssignment> =
    selected.map { membershipId ->
        TechnicianAssignment(
            membershipId = membershipId,
            role = if (membershipId == lead) AssignmentRole.LEAD else AssignmentRole.TECHNICIAN,
        )
    }



/**
 * One technician the sheet may include, and the Lead choice among the included ones.
 *
 * The Lead is chosen from the crew that is actually included, because `BR-068` allows exactly one Lead
 * among the technicians a Visit carries.
 */
@Composable
private fun ScheduleVisitCrewRow(
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
            .testTag(scheduleVisitTechnicianTag(technician.membershipId))
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
                modifier = Modifier.testTag(scheduleVisitLeadTag(technician.membershipId)),
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
 * One time of the day a form states, in the device's own zone and conventions (`BR-072`, `BR-028`).
 *
 * Shared by the two forms that state a window — scheduling a Visit and proposing a follow-up one
 * (`BR-FV-003`) — so both read a time back the same way (`BR-041`).
 */
@Composable
internal fun ScheduleTimeField(
    label: String,
    date: String,
    hour: Int,
    minute: Int,
    zone: ZoneId,
    testTag: String,
    onPickTime: () -> Unit,
) {
    FieldRow(
        label = label,
        value = formattedScheduleTime(
            date = date,
            hour = hour,
            minute = minute,
            zone = zone,
            locale = deviceLocale(),
        ),
        testTag = testTag,
        onClick = onPickTime,
    )
}


/** A date already chosen is what the picker opens on. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ScheduleDatePicker(
    initialDate: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val state = rememberDatePickerState(
        initialSelectedDateMillis = java.time.LocalDate.parse(initialDate)
            .atStartOfDay(ZoneId.of("UTC"))
            .toInstant()
            .toEpochMilli(),
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    // The platform's picker answers the day the user chose in their own calendar as UTC
                    // milliseconds at midnight, which is how it is read back here (`BR-041`).
                    val picked = state.selectedDateMillis?.let { millis ->
                        Instant.ofEpochMilli(millis).atZone(ZoneId.of("UTC")).toLocalDate()
                    }
                    if (picked != null) {
                        onPick(picked.toString())
                    }
                },
            ) {
                Text(stringResource(R.string.job_action_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.job_action_cancel))
            }
        },
    ) {
        DatePicker(state = state)
    }
}

/** A time already chosen is what the picker opens on. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ScheduleTimePicker(
    initialHour: Int,
    initialMinute: Int,
    onPick: (hour: Int, minute: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val state = rememberTimePickerState(
        initialHour = initialHour,
        initialMinute = initialMinute,
        is24Hour = DateFormat.is24HourFormat(LocalContext.current),
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onPick(state.hour, state.minute) }) {
                Text(stringResource(R.string.job_action_ok))
            }
        },
        text = { TimePicker(state = state) },
    )
}

/** The instant a date and a time name in the device's own zone, or `null` when it is unreadable. */
private fun zonedInstant(
    date: String,
    hour: Int,
    minute: Int,
    zone: ZoneId,
): Instant? =
    runCatching {
        java.time.LocalDate.parse(date).atTime(hour, minute).atZone(zone).toInstant()
    }.getOrNull()

/**
 * The internal window a visit date and its two times name, in the device's own zone (`BR-072`).
 *
 * Both ends are built from the **one** visit date, because a field attempt starts and ends on the same
 * day: the end time never carries a date of its own, so the end can only be later than the start by
 * being a later time on that day (`BR-072`).
 */
internal data class ScheduleVisitWindow(val start: Instant, val end: Instant)

/**
 * The window the form is holding, or `null` when the date or a time cannot be read.
 *
 * It is the one place the two instants are constructed, so "a Visit is one day" is a rule a test can
 * check rather than a detail of the layout that presents it (`BR-041`).
 */
internal fun scheduleVisitWindow(
    date: String,
    startHour: Int,
    startMinute: Int,
    endHour: Int,
    endMinute: Int,
    zone: ZoneId,
): ScheduleVisitWindow? {
    val start = zonedInstant(date = date, hour = startHour, minute = startMinute, zone = zone)
        ?: return null
    val end = zonedInstant(date = date, hour = endHour, minute = endMinute, zone = zone)
        ?: return null
    return ScheduleVisitWindow(start = start, end = end)
}

/** The instant a proposal names in the device's own zone, or `null` when it is absent or unreadable. */
private fun readInstant(instant: Instant?, zone: ZoneId): ZonedDateTime? = instant?.atZone(zone)

/** A date a form holds, in the language and conventions of the device (`BR-028`). */
internal fun formattedScheduleDate(date: String, locale: Locale): String =
    runCatching {
        java.time.LocalDate.parse(date).format(
            DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale),
        )
    }.getOrElse { date }

/** A time a form holds, in the language and conventions of the device (`BR-028`). */
private fun formattedScheduleTime(
    date: String,
    hour: Int,
    minute: Int,
    zone: ZoneId,
    locale: Locale,
): String =
    runCatching {
        java.time.LocalDate.parse(date).atTime(hour, minute).atZone(zone).format(
            DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale),
        )
    }.getOrElse { "%02d:%02d".format(hour, minute) }

