package com.servora.android.ui.schedule

import android.text.format.DateFormat
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import com.servora.android.domain.model.AdHocReportCustomerOption
import com.servora.android.domain.model.AdHocReportJobOption
import com.servora.android.domain.model.AdHocReportPropertyOption
import com.servora.android.domain.model.VisitOutcome
import com.servora.android.ui.jobs.visitOutcomeLabel
import com.servora.android.ui.jobs.visitOutcomeSupportingText
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale


/**
 * The field-friendly form a technician reports ad-hoc work with (`BR-AH-001`, `BR-AH-009`).
 *
 * It discovers the Customer/Property/Job rather than typing ids, and it captures the canonical choices
 * or the reported provenance facts, never both. The outcome is the canonical Visit outcome vocabulary
 * (`BR-078`), the work window is entered with local date and time controls (`BR-072`), and the form
 * refuses to submit a report the API would refuse (`BR-007`, `BR-042`).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AdHocWorkReportForm(
    state: AdHocReportFormState,
    isSubmitting: Boolean,
    zone: ZoneId,
    onCustomerQueryChange: (String) -> Unit,
    onSelectCustomer: (AdHocReportCustomerOption) -> Unit,
    onSelectProperty: (AdHocReportPropertyOption) -> Unit,
    onSelectJob: (AdHocReportJobOption) -> Unit,
    onWorkStartedAtChange: (java.time.Instant) -> Unit,
    onWorkEndedAtChange: (java.time.Instant) -> Unit,
    onOutcomeChange: (VisitOutcome) -> Unit,
    onSummaryChange: (String) -> Unit,
    onNotesChange: (String) -> Unit,
    onUnknownCustomerChange: (Boolean) -> Unit,
    onReportedCustomerNameChange: (String) -> Unit,
    onReportedCustomerPhoneChange: (String) -> Unit,
    onReportedCustomerAddressChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = Modifier.testTag(AdHocReportFormTag),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(R.string.technician_ad_hoc_report_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )

            if (!state.unknownCustomer) {
                AdHocCustomerSearch(
                    query = state.customerQuery,
                    options = state.customerOptions,
                    isSearching = state.isSearchingCustomers,
                    selected = state.selectedCustomer,
                    onQueryChange = onCustomerQueryChange,
                    onSelect = onSelectCustomer,
                )
            }

            TextButton(
                onClick = { onUnknownCustomerChange(!state.unknownCustomer) },
                modifier = Modifier.testTag(AdHocReportUnknownCustomerTag),
            ) {
                Text(
                    text = stringResource(
                        if (state.unknownCustomer) {
                            R.string.technician_ad_hoc_report_use_customer_search
                        } else {
                            R.string.technician_ad_hoc_report_unknown_customer
                        },
                    ),
                )
            }

            if (state.unknownCustomer) {
                OutlinedTextField(
                    value = state.reportedCustomerName,
                    onValueChange = onReportedCustomerNameChange,
                    label = { Text(stringResource(R.string.technician_ad_hoc_report_reported_name)) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(AdHocReportCustomerNameTag),
                )
                OutlinedTextField(
                    value = state.reportedCustomerPhone,
                    onValueChange = onReportedCustomerPhoneChange,
                    label = { Text(stringResource(R.string.technician_ad_hoc_report_reported_phone)) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(AdHocReportCustomerPhoneTag),
                )
                OutlinedTextField(
                    value = state.reportedCustomerAddress,
                    onValueChange = onReportedCustomerAddressChange,
                    label = { Text(stringResource(R.string.technician_ad_hoc_report_reported_address)) },
                    minLines = 2,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(AdHocReportCustomerAddressTag),
                )
            } else if (state.selectedCustomer != null) {
                AdHocPropertyPicker(
                    options = state.propertyOptions,
                    isReading = state.isReadingProperties,
                    selected = state.selectedProperty,
                    onSelect = onSelectProperty,
                )
                AdHocJobPicker(
                    options = state.jobOptions,
                    isReading = state.isReadingJobs,
                    selected = state.selectedJob,
                    onSelect = onSelectJob,
                )
            }

            AdHocWorkWindow(
                startedAt = state.workStartedAt,
                endedAt = state.workEndedAt,
                zone = zone,
                onStartedAtChange = onWorkStartedAtChange,
                onEndedAtChange = onWorkEndedAtChange,
            )

            AdHocOutcomePicker(
                selected = state.outcome,
                onSelect = onOutcomeChange,
            )


            OutlinedTextField(
                value = state.summary,
                onValueChange = onSummaryChange,
                label = { Text(stringResource(R.string.technician_ad_hoc_report_summary)) },
                minLines = 2,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(AdHocReportSummaryTag),
            )
            OutlinedTextField(
                value = state.notes,
                onValueChange = onNotesChange,
                label = { Text(stringResource(R.string.technician_ad_hoc_report_notes)) },
                minLines = 2,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(AdHocReportNotesTag),
            )

            state.problem?.let { problem ->
                Text(
                    text = stringResource(adHocReportProblemMessage(problem)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Row(modifier = Modifier.fillMaxWidth()) {
                TextButton(enabled = !isSubmitting, onClick = onDismiss) {
                    Text(stringResource(R.string.schedule_date_picker_cancel))
                }
                Spacer(Modifier.weight(1f))
                Button(
                    enabled = state.canSubmit && !isSubmitting,
                    onClick = onSubmit,
                    modifier = Modifier.testTag(AdHocReportSubmitTag),
                ) {
                    if (isSubmitting) {
                        CircularProgressIndicator(
                            modifier = Modifier.height(18.dp).width(18.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Text(stringResource(R.string.technician_ad_hoc_report_submit))
                    }
                }
            }
        }
    }
}

/** The localized message a submission problem is reported with (`BR-028`, `BR-041`). */
internal fun adHocReportProblemMessage(problem: AdHocReportProblem): Int =
    when (problem) {
        AdHocReportProblem.NO_SUMMARY -> R.string.technician_ad_hoc_report_problem_summary
        AdHocReportProblem.END_NOT_AFTER_START -> R.string.schedule_visit_problem_end
    }

const val AdHocReportFormTag = "ad-hoc-report-form"
const val AdHocReportUnknownCustomerTag = "ad-hoc-report-unknown-customer"
const val AdHocReportCustomerNameTag = "ad-hoc-report-customer-name"
const val AdHocReportCustomerPhoneTag = "ad-hoc-report-customer-phone"
const val AdHocReportCustomerAddressTag = "ad-hoc-report-customer-address"
const val AdHocReportSummaryTag = "ad-hoc-report-summary"
const val AdHocReportNotesTag = "ad-hoc-report-notes"
const val AdHocReportSubmitTag = "ad-hoc-report-submit"


/** The type-ahead Customer search and the selected Customer (`BR-AH-009`). */
@Composable
private fun AdHocCustomerSearch(
    query: String,
    options: List<AdHocReportCustomerOption>,
    isSearching: Boolean,
    selected: AdHocReportCustomerOption?,
    onQueryChange: (String) -> Unit,
    onSelect: (AdHocReportCustomerOption) -> Unit,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        label = { Text(stringResource(R.string.technician_ad_hoc_report_customer)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    if (isSearching) {
        CircularProgressIndicator(modifier = Modifier.height(20.dp).width(20.dp), strokeWidth = 2.dp)
    } else if (selected == null && options.isNotEmpty()) {
        Column {
            options.forEach { option ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(option) }
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(text = option.displayName, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

/** The active Properties of the selected Customer, one of which may be chosen (`BR-050`). */
@Composable
private fun AdHocPropertyPicker(
    options: List<AdHocReportPropertyOption>,
    isReading: Boolean,
    selected: AdHocReportPropertyOption?,
    onSelect: (AdHocReportPropertyOption) -> Unit,
) {
    Column {
        Text(
            text = stringResource(R.string.technician_ad_hoc_report_property),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        when {
            isReading -> CircularProgressIndicator(
                modifier = Modifier.height(20.dp).width(20.dp),
                strokeWidth = 2.dp,
            )

            options.isEmpty() -> Text(
                text = stringResource(R.string.technician_ad_hoc_report_no_property),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            else -> options.forEach { option ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .toggleable(
                            value = option == selected,
                            role = Role.RadioButton,
                            onValueChange = { onSelect(option) },
                        )
                        .heightIn(min = 44.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = option == selected, onClick = null)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = option.name
                            ?: stringResource(R.string.technician_ad_hoc_report_property_unnamed),
                    )
                }
            }
        }
    }
}


/** The Jobs of the selected Customer, one of which may be named the related work. */
@Composable
private fun AdHocJobPicker(
    options: List<AdHocReportJobOption>,
    isReading: Boolean,
    selected: AdHocReportJobOption?,
    onSelect: (AdHocReportJobOption) -> Unit,
) {
    Column {
        Text(
            text = stringResource(R.string.technician_ad_hoc_report_related_work),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        when {
            isReading -> CircularProgressIndicator(
                modifier = Modifier.height(20.dp).width(20.dp),
                strokeWidth = 2.dp,
            )

            options.isEmpty() -> Text(
                text = stringResource(R.string.technician_ad_hoc_report_no_related_work),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            else -> options.forEach { option ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .toggleable(
                            value = option == selected,
                            role = Role.RadioButton,
                            onValueChange = { onSelect(option) },
                        )
                        .heightIn(min = 44.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = option == selected, onClick = null)
                    Spacer(Modifier.width(8.dp))
                    Text(text = "#${option.jobNumber} · ${option.title}")
                }
            }
        }
    }
}

/** The canonical Visit outcome picker (`BR-078`). */
@Composable
private fun AdHocOutcomePicker(
    selected: VisitOutcome,
    onSelect: (VisitOutcome) -> Unit,
) {
    Column {
        Text(
            text = stringResource(R.string.technician_ad_hoc_report_outcome),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        VisitOutcome.entries.forEach { option ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(
                        value = option == selected,
                        role = Role.RadioButton,
                        onValueChange = { onSelect(option) },
                    )
                    .heightIn(min = 48.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = option == selected, onClick = null)
                Spacer(Modifier.width(8.dp))
                Column {
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


/** The work window's local date and time controls (`BR-072`). */
@Composable
private fun AdHocWorkWindow(
    startedAt: Instant,
    endedAt: Instant,
    zone: ZoneId,
    onStartedAtChange: (Instant) -> Unit,
    onEndedAtChange: (Instant) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.technician_ad_hoc_report_work_window),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        AdHocDateTimeRow(
            label = stringResource(R.string.technician_ad_hoc_report_started),
            instant = startedAt,
            zone = zone,
            onDateChange = { date ->
                val zoned = startedAt.atZone(zone)
                onStartedAtChange(ZonedDateTime.of(date, zoned.toLocalTime(), zone).toInstant())
            },
            onTimeChange = { hour, minute ->
                val zoned = startedAt.atZone(zone)
                onStartedAtChange(
                    ZonedDateTime.of(zoned.toLocalDate(), java.time.LocalTime.of(hour, minute), zone)
                        .toInstant(),
                )
            },
        )
        AdHocDateTimeRow(
            label = stringResource(R.string.technician_ad_hoc_report_ended),
            instant = endedAt,
            zone = zone,
            onDateChange = { date ->
                val zoned = endedAt.atZone(zone)
                onEndedAtChange(ZonedDateTime.of(date, zoned.toLocalTime(), zone).toInstant())
            },
            onTimeChange = { hour, minute ->
                val zoned = endedAt.atZone(zone)
                onEndedAtChange(
                    ZonedDateTime.of(zoned.toLocalDate(), java.time.LocalTime.of(hour, minute), zone)
                        .toInstant(),
                )
            },
        )
    }
}

@Composable
private fun AdHocDateTimeRow(
    label: String,
    instant: Instant,
    zone: ZoneId,
    onDateChange: (LocalDate) -> Unit,
    onTimeChange: (hour: Int, minute: Int) -> Unit,
) {
    val zoned = remember(instant, zone) { instant.atZone(zone) }
    var pickingDate by rememberSaveable { mutableStateOf(false) }
    var pickingTime by rememberSaveable { mutableStateOf(false) }
    val locale = Locale.getDefault()

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.width(96.dp),
        )
        TextButton(onClick = { pickingDate = true }) {
            Text(
                text = zoned.toLocalDate().format(
                    DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale),
                ),
            )
        }
        TextButton(onClick = { pickingTime = true }) {
            Text(
                text = zoned.toLocalTime().format(
                    DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale),
                ),
            )
        }
    }

    if (pickingDate) {
        AdHocDatePicker(initialDate = zoned.toLocalDate(), onPick = { onDateChange(it) }, onDismiss = { pickingDate = false })
    }
    if (pickingTime) {
        AdHocTimePicker(
            initialHour = zoned.hour,
            initialMinute = zoned.minute,
            onPick = { hour, minute -> onTimeChange(hour, minute) },
            onDismiss = { pickingTime = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AdHocDatePicker(
    initialDate: LocalDate,
    onPick: (LocalDate) -> Unit,
    onDismiss: () -> Unit,
) {
    val state = rememberDatePickerState(
        initialSelectedDateMillis = initialDate.atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli(),
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    val picked = state.selectedDateMillis?.let { millis ->
                        Instant.ofEpochMilli(millis).atZone(ZoneId.of("UTC")).toLocalDate()
                    }
                    if (picked != null) {
                        onPick(picked)
                    }
                },
            ) {
                Text(stringResource(R.string.job_action_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.job_action_cancel)) }
        },
    ) {
        DatePicker(state = state)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AdHocTimePicker(
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

