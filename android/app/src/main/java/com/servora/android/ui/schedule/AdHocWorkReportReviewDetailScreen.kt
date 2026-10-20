package com.servora.android.ui.schedule

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.servora.android.R
import com.servora.android.data.customers.CustomersFailureReason
import com.servora.android.domain.model.AdHocReportJobOption
import com.servora.android.domain.model.AdHocWorkReport
import com.servora.android.domain.model.AdHocWorkReportStatus
import com.servora.android.domain.model.isPending
import com.servora.android.ui.components.InfoCard
import com.servora.android.ui.components.SectionLabel
import com.servora.android.ui.home.HomePageGutter
import com.servora.android.ui.home.deviceLocale
import com.servora.android.ui.home.formatScheduledRange
import com.servora.android.ui.home.formattedAddressLine
import com.servora.android.ui.jobs.visitOutcomeLabel

fun adHocReportDetailTag(reportId: String): String = "ad-hoc-report-detail-$reportId"

const val AdHocReportRejectDialogTag = "ad-hoc-report-reject-dialog"
const val AdHocReportConvertDialogTag = "ad-hoc-report-convert-dialog"
const val AdHocReportLinkDialogTag = "ad-hoc-report-link-dialog"

fun adHocReportReviewNoteTag(action: String): String = "ad-hoc-report-review-note-$action"
fun adHocReportReviewConfirmTag(action: String): String = "ad-hoc-report-review-confirm-$action"

/** A manager's full review surface for one ad-hoc work report (`BR-AH-002`). */
@Composable
internal fun AdHocWorkReportReviewDetailScreen(
    state: ScheduleUiState,
    reportId: String,
    onReject: (AdHocWorkReport, String) -> Unit,
    onLink: (AdHocWorkReport, String, String) -> Unit,
    onConvert: (AdHocWorkReport, String, String?, String) -> Unit,
    onOpenJob: (String) -> Unit,
    onAcknowledgeReview: () -> Unit,
    onRetry: () -> Unit,
    onOpenLinkJobs: (AdHocWorkReport) -> Unit,
    onSearchLinkJobs: (AdHocWorkReport, String) -> Unit,
    onSelectLinkJob: (String) -> Unit,
    permissions: com.servora.android.ui.customers.CustomerPermissionsUiState,
    onOpenLocation: () -> Unit,
    onSearchCustomers: (String) -> Unit,
    onLoadProperties: (String) -> Unit,
    onResolveLocation: (AdHocWorkReport, com.servora.android.data.schedule.ResolveAdHocLocationRequestDto) -> Unit,
    modifier: Modifier = Modifier,
) {
    val report = state.adHocReports.firstOrNull { it.id == reportId }
    var action by remember { mutableStateOf<AdHocReportReviewAction?>(null) }

    Box(modifier = modifier.fillMaxSize()) {
        when {
            report != null -> AdHocReportRecord(
                report = report,
                reviewing = state.reviewingAdHocReportId == report.id,
                onReject = { action = AdHocReportReviewAction.REJECT },
                onLink = { action = AdHocReportReviewAction.LINK },
                onConvert = { action = AdHocReportReviewAction.CONVERT },
                onResolve = { onOpenLocation(); action = AdHocReportReviewAction.RESOLVE },
                canResolve = (permissions.canOpenCustomers && (permissions.canViewProperties || permissions.canCreateProperty)) ||
                    (report.customerId == null && permissions.canCreateCustomer && permissions.canCreateProperty),
                onOpenJob = { report.createdJobId?.let(onOpenJob) },
            )

            !state.hasReadAdHocReports || state.isReadingAdHocReports -> AdHocReportLoading()

            state.adHocReportReadFailureReason != null -> AdHocReportFailure(onRetry = onRetry)

            else -> AdHocReportUnavailable()
        }

        val failureMessage = state.adHocReviewFailureReason?.let {
            stringResource(R.string.ad_hoc_report_review_failed)
        }
        val rejectedMessage = when (state.adHocReviewOutcome) {
            AdHocWorkReportStatus.REJECTED -> stringResource(R.string.ad_hoc_report_rejected)
            else -> null
        }
        val snackbarHostState = remember { SnackbarHostState() }
        LaunchedEffect(state.adHocReviewOutcome, state.adHocReviewFailureReason) {
            val message = failureMessage ?: rejectedMessage ?: return@LaunchedEffect
            try {
                snackbarHostState.showSnackbar(
                    message = message,
                    duration = if (failureMessage != null) SnackbarDuration.Long else SnackbarDuration.Short,
                )
            } finally {
                onAcknowledgeReview()
            }
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter).padding(HomePageGutter),
        )
    }

    val current = report
    val currentAction = action
    if (current != null && currentAction != null) {
        when (currentAction) {
            AdHocReportReviewAction.RESOLVE -> AdHocLocationDialog(
                report = current, state = state, permissions = permissions,
                onSearch = onSearchCustomers, onProperties = onLoadProperties,
                onConfirm = { onResolveLocation(current, it) }, onDismiss = { action = null },
            )
            AdHocReportReviewAction.REJECT -> AdHocReportRejectDialog(
                isSending = state.reviewingAdHocReportId == current.id,
                onConfirm = { note ->
                    action = null
                    onReject(current, note)
                },
                onDismiss = { action = null },
            )

            AdHocReportReviewAction.LINK -> AdHocReportLinkDialog(
                report = current,
                jobOptions = state.adHocReviewJobOptions,
                isReadingJobs = state.isReadingAdHocReviewJobOptions,
                jobsFailure = state.adHocReviewJobOptionsFailureReason,
                query = state.adHocReviewJobQuery,
                selectedJobId = state.adHocReviewSelectedJobId,
                isSending = state.reviewingAdHocReportId == current.id,
                onOpen = { onOpenLinkJobs(current) },
                onQueryChange = { query -> onSearchLinkJobs(current, query) },
                onSelectJob = onSelectLinkJob,
                onConfirm = { jobId, note ->
                    action = null
                    onLink(current, jobId, note)
                },
                onDismiss = { action = null },
            )

            AdHocReportReviewAction.CONVERT -> AdHocReportConvertDialog(
                isSending = state.reviewingAdHocReportId == current.id,
                onConfirm = { title, description, note ->
                    action = null
                    onConvert(current, title, description, note)
                },
                onDismiss = { action = null },
            )
        }
    }
}

private enum class AdHocReportReviewAction { REJECT, CONVERT, LINK, RESOLVE }


@Composable
private fun AdHocReportRecord(
    report: AdHocWorkReport,
    reviewing: Boolean,
    onReject: () -> Unit,
    onLink: () -> Unit,
    onConvert: () -> Unit,
    onResolve: () -> Unit,
    canResolve: Boolean,
    onOpenJob: () -> Unit,
) {
    val locale = deviceLocale()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(HomePageGutter),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        InfoCard(modifier = Modifier.testTag(adHocReportDetailTag(report.id))) {
            Text(
                text = report.customerName ?: report.reportedCustomerName
                    ?: stringResource(R.string.ad_hoc_report_unknown_customer),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            SectionLabel(label = stringResource(R.string.ad_hoc_report_status_label), count = null)
            Text(
                text = stringResource(adHocWorkReportStatusLabel(report.status)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (report.propertyAddress != null) {
                val address = report.propertyAddress
                AdHocReportField(
                    label = stringResource(R.string.ad_hoc_report_location_label),
                    value = formattedAddressLine(
                        addressLine1 = address.addressLine1,
                        addressLine2 = address.addressLine2,
                        city = address.city,
                        province = address.province,
                        postalCode = address.postalCode,
                    ) ?: stringResource(R.string.ad_hoc_report_unknown_location),
                )
            } else {
                AdHocReportField(
                    label = stringResource(R.string.ad_hoc_report_location_label),
                    value = report.reportedCustomerAddress
                        ?: stringResource(R.string.ad_hoc_report_unknown_location),
                )
            }
            if (report.knownJobNumber != null) {
                AdHocReportField(
                    label = stringResource(R.string.ad_hoc_report_known_job_label),
                    value = report.knownJobTitle?.let { title ->
                        stringResource(R.string.ad_hoc_report_known_job_value, report.knownJobNumber, title)
                    } ?: stringResource(R.string.ad_hoc_report_known_job_number_only, report.knownJobNumber),
                )
            }
            formatScheduledRange(report.workStartedAt, report.workEndedAt, locale)?.let { window ->
                AdHocReportField(
                    label = stringResource(R.string.ad_hoc_report_window_label),
                    value = window,
                )
            }
            AdHocReportField(
                label = stringResource(R.string.ad_hoc_report_outcome_label),
                value = stringResource(visitOutcomeLabel(report.outcomeCode)),
            )
            AdHocReportField(
                label = stringResource(R.string.ad_hoc_report_summary_label),
                value = report.summary,
            )
            report.notes?.let { notes ->
                AdHocReportField(label = stringResource(R.string.ad_hoc_report_notes_label), value = notes)
            }
            if (report.reportedCustomerName != null) {
                AdHocReportField(label = stringResource(R.string.ad_hoc_customer_name), value = report.reportedCustomerName)
            }
            if (report.reportedCustomerAddress != null && report.propertyAddress != null) {
                AdHocReportField(label = stringResource(R.string.ad_hoc_street), value = report.reportedCustomerAddress)
            }
            if (report.reportedCustomerPhone != null) {
                AdHocReportField(
                    label = stringResource(R.string.ad_hoc_report_reported_phone_label),
                    value = report.reportedCustomerPhone,
                )
            }
            report.reviewNote?.let { note ->
                AdHocReportField(
                    label = stringResource(R.string.ad_hoc_report_review_note_label),
                    value = note,
                )
            }
        }

        if (report.isPending) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                // The office may link to the technician's `knownJobId` or choose a different Job
                // (`BR-AH-002`), so the action is offered for every pending report rather than only
                // when a known Job exists.
                TextButton(
                    enabled = !reviewing,
                    onClick = onLink,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.ad_hoc_report_link_action))
                }
                if (report.customerId != null && report.propertyId != null) {
                    TextButton(
                        enabled = !reviewing,
                        onClick = onConvert,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.ad_hoc_report_convert_action))
                    }
                } else if (canResolve) {
                    TextButton(enabled = !reviewing, onClick = onResolve, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.ad_hoc_resolve_location))
                    }
                }
                TextButton(
                    enabled = !reviewing,
                    onClick = onReject,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = stringResource(R.string.ad_hoc_report_reject_action),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}


@Composable
private fun AdHocReportField(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = value, style = MaterialTheme.typography.bodyLarge)
    }
}


@Composable
private fun AdHocReportRejectDialog(
    isSending: Boolean,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var note by rememberSaveable { mutableStateOf("") }
    val canConfirm = !isSending && note.isNotBlank()
    AlertDialog(
        onDismissRequest = { if (!isSending) onDismiss() },
        modifier = Modifier.imePadding(),
        title = {
            Text(
                text = stringResource(R.string.ad_hoc_report_reject_title),
                modifier = Modifier.testTag(AdHocReportRejectDialogTag),
            )
        },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.ad_hoc_report_reject_message),
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    enabled = !isSending,
                    label = { Text(stringResource(R.string.ad_hoc_report_reject_note)) },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                        .testTag(adHocReportReviewNoteTag("reject")),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(note) },
                enabled = canConfirm,
                modifier = Modifier.testTag(adHocReportReviewConfirmTag("reject")),
            ) {
                Text(stringResource(R.string.ad_hoc_report_reject_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isSending) {
                Text(stringResource(R.string.ad_hoc_report_cancel))
            }
        },
    )
}


@Composable
private fun AdHocReportLinkDialog(
    report: AdHocWorkReport,
    jobOptions: List<AdHocReportJobOption>,
    isReadingJobs: Boolean,
    jobsFailure: CustomersFailureReason?,
    query: String,
    selectedJobId: String?,
    isSending: Boolean,
    onOpen: () -> Unit,
    onQueryChange: (String) -> Unit,
    onSelectJob: (String) -> Unit,
    onConfirm: (String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    var note by rememberSaveable { mutableStateOf("") }
    // Load the Jobs when the dialog opens; a search keystroke re-reads them through the ViewModel
    // (`BR-AH-002`, `BR-001`).
    LaunchedEffect(Unit) { onOpen() }
    val canConfirm = !isSending && selectedJobId != null
    AlertDialog(
        onDismissRequest = { if (!isSending) onDismiss() },
        modifier = Modifier.imePadding(),
        title = {
            Text(
                text = stringResource(R.string.ad_hoc_report_link_title),
                modifier = Modifier.testTag(AdHocReportLinkDialogTag),
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                // A known Job is only the default choice: the office may pick any other Job the backend
                // offers (`BR-AH-002`). A report without a Customer searches the whole organization.
                if (report.customerId == null) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = onQueryChange,
                        enabled = !isSending,
                        label = { Text(stringResource(R.string.ad_hoc_report_link_search_label)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                when {
                    isReadingJobs -> Box(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }

                    jobsFailure != null -> Text(
                        text = stringResource(R.string.ad_hoc_report_link_jobs_failed),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )

                    jobOptions.isEmpty() -> Text(
                        text = stringResource(R.string.ad_hoc_report_link_no_jobs),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )

                    else -> Column(Modifier.verticalScroll(rememberScrollState())) {
                        jobOptions.forEach { job ->
                            val selected = job.id == selectedJobId
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = !isSending) { onSelectJob(job.id) }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(
                                    selected = selected,
                                    onClick = { onSelectJob(job.id) },
                                    enabled = !isSending,
                                )
                                Text(
                                    text = stringResource(
                                        R.string.ad_hoc_report_known_job_value,
                                        job.jobNumber,
                                        job.title,
                                    ),
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.padding(start = 8.dp),
                                )
                            }
                        }
                    }
                }
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    enabled = !isSending,
                    label = { Text(stringResource(R.string.ad_hoc_report_link_note)) },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                        .testTag(adHocReportReviewNoteTag("link")),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(requireNotNull(selectedJobId), note) },
                enabled = canConfirm,
                modifier = Modifier.testTag(adHocReportReviewConfirmTag("link")),
            ) {
                Text(stringResource(R.string.ad_hoc_report_link_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isSending) {
                Text(stringResource(R.string.ad_hoc_report_cancel))
            }
        },
    )
}


@Composable
private fun AdHocReportConvertDialog(
    isSending: Boolean,
    onConfirm: (String, String?, String) -> Unit,
    onDismiss: () -> Unit,
) {
    var title by rememberSaveable { mutableStateOf("") }
    var description by rememberSaveable { mutableStateOf("") }
    var note by rememberSaveable { mutableStateOf("") }
    val canConfirm = !isSending && title.isNotBlank()
    AlertDialog(
        onDismissRequest = { if (!isSending) onDismiss() },
        modifier = Modifier.imePadding(),
        title = {
            Text(
                text = stringResource(R.string.ad_hoc_report_convert_title),
                modifier = Modifier.testTag(AdHocReportConvertDialogTag),
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.ad_hoc_report_convert_message),
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    enabled = !isSending,
                    label = { Text(stringResource(R.string.ad_hoc_report_convert_title_label)) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(adHocReportReviewNoteTag("convert-title")),
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    enabled = !isSending,
                    label = { Text(stringResource(R.string.ad_hoc_report_convert_description_label)) },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    enabled = !isSending,
                    label = { Text(stringResource(R.string.ad_hoc_report_convert_note_label)) },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(title, description, note) },
                enabled = canConfirm,
                modifier = Modifier.testTag(adHocReportReviewConfirmTag("convert")),
            ) {
                Text(stringResource(R.string.ad_hoc_report_convert_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isSending) {
                Text(stringResource(R.string.ad_hoc_report_cancel))
            }
        },
    )
}


@Composable
private fun AdHocReportLoading() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = stringResource(R.string.schedule_loading),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AdHocReportFailure(onRetry: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.padding(HomePageGutter),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.ad_hoc_report_detail_failed_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            TextButton(onClick = onRetry) {
                Text(stringResource(R.string.home_retry))
            }
        }
    }
}

@Composable
private fun AdHocReportUnavailable() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = stringResource(R.string.ad_hoc_report_detail_unavailable),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Localized label for a report status (`BR-AH-005`, `BR-028`). */
internal fun adHocWorkReportStatusLabel(status: AdHocWorkReportStatus): Int =
    when (status) {
        AdHocWorkReportStatus.PENDING -> R.string.ad_hoc_report_status_pending
        AdHocWorkReportStatus.LINKED -> R.string.ad_hoc_report_status_linked
        AdHocWorkReportStatus.CONVERTED -> R.string.ad_hoc_report_status_converted
        AdHocWorkReportStatus.REJECTED -> R.string.ad_hoc_report_status_rejected
    }
