package com.servora.android.ui.schedule

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.servora.android.R
import com.servora.android.domain.model.FollowUpVisitRequest
import com.servora.android.domain.model.FollowUpVisitRequestStatus
import com.servora.android.domain.model.TechnicianAssignment
import com.servora.android.ui.components.InfoCard
import com.servora.android.ui.components.RequestStatusPill
import com.servora.android.ui.components.SectionLabel
import com.servora.android.ui.home.HomePageGutter
import com.servora.android.ui.home.deviceLocale
import com.servora.android.ui.jobs.ScheduleConflictDialog
import com.servora.android.ui.jobs.ScheduleVisitSheet
import com.servora.android.ui.jobs.jobActionFailureMessage
import java.time.Instant

fun managerRequestDetailTag(requestId: String): String = "manager-request-detail-$requestId"
fun managerRequestDetailReviewTag(requestId: String): String = "manager-request-detail-review-$requestId"

/** A manager's full review surface for one follow-up Visit request. */
@Composable
internal fun ManagerFollowUpRequestDetailsScreen(
    state: ScheduleUiState,
    requestId: String,
    canApprove: Boolean,
    onClarifyRequest: (FollowUpVisitRequest, String) -> Unit,
    onRejectRequest: (FollowUpVisitRequest, String) -> Unit,
    onStartApproval: (FollowUpVisitRequest) -> Unit,
    onDismissApproval: () -> Unit,
    onApproveRequest: (FollowUpVisitRequest, Instant, Instant, List<TechnicianAssignment>) -> Unit,
    onConfirmApproval: () -> Unit,
    onDismissApprovalConflicts: () -> Unit,
    onAcknowledgeApproval: () -> Unit,
    onAcknowledgeReview: () -> Unit,
    onOpenJob: (String) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val request = state.visitRequests.firstOrNull { it.id == requestId }
    var reviewTarget by remember { mutableStateOf<RequestReviewDecision?>(null) }

    Box(modifier = modifier.fillMaxSize()) {
        when {
            request != null -> ManagerRequestRecord(
                request = request,
                zone = zoneOf(state.timeZoneId),
                reviewing = state.reviewingRequestId == request.id,
                approving = state.isApproving,
                canApprove = canApprove,
                onReview = { reviewTarget = it },
                onApprove = { onStartApproval(request) },
                onOpenJob = { onOpenJob(request.jobId) },
            )
            !state.hasReadRequests || state.isReadingRequests -> RequestDetailsLoading()
            state.requestReadFailureReason != null -> RequestDetailsFailure(onRetry = onRetry)
            else -> ManagerRequestDetailsUnavailable()
        }

        val approvedMessage = stringResource(R.string.schedule_request_approved)
        val approvalFailureMessage = state.approvalFailure?.let {
            stringResource(jobActionFailureMessage(it))
        }
        val reviewMessage = when (state.reviewedRequestStatus) {
            FollowUpVisitRequestStatus.NEEDS_CLARIFICATION ->
                stringResource(R.string.schedule_request_returned)
            FollowUpVisitRequestStatus.REJECTED -> stringResource(R.string.schedule_request_rejected)
            else -> null
        }
        val reviewFailureMessage = state.reviewFailureReason?.let {
            stringResource(R.string.schedule_request_decision_failed)
        }
        val snackbarHostState = remember { SnackbarHostState() }
        LaunchedEffect(
            state.approvedRequestId,
            state.approvalFailure,
            state.reviewedRequestStatus,
            state.reviewFailureReason,
        ) {
            val failure = approvalFailureMessage ?: reviewFailureMessage
            val message = when {
                state.approvedRequestId != null -> approvedMessage
                failure != null -> failure
                reviewMessage != null -> reviewMessage
                else -> null
            } ?: return@LaunchedEffect
            try {
                snackbarHostState.showSnackbar(
                    message = message,
                    duration = if (failure != null) SnackbarDuration.Long else SnackbarDuration.Short,
                )
            } finally {
                onAcknowledgeApproval()
                onAcknowledgeReview()
            }
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter).padding(HomePageGutter),
        )
    }

    state.schedulingRequest?.takeIf { it.id == requestId }?.let { schedulingRequest ->
        ScheduleVisitSheet(
            titleRes = R.string.schedule_request_approve_title,
            messageRes = R.string.schedule_request_approve_message,
            confirmRes = R.string.schedule_request_approve_confirm,
            initialStart = instantOf(schedulingRequest.proposedStart),
            initialEnd = instantOf(schedulingRequest.proposedEnd),
            technicians = state.assignableTechnicians,
            crewFailure = state.assignableFailure,
            isSubmitting = state.isApproving,
            onRetryCrew = { onStartApproval(schedulingRequest) },
            onConfirm = { start, end, assignments ->
                onApproveRequest(schedulingRequest, start, end, assignments)
            },
            onDismiss = onDismissApproval,
        )
    }

    state.pendingApproval?.takeIf { it.requestId == requestId }?.let { pending ->
        ScheduleConflictDialog(
            conflicts = pending.conflicts,
            isSubmitting = state.isApproving,
            onConfirm = onConfirmApproval,
            onDismiss = onDismissApprovalConflicts,
        )
    }

    val currentRequest = request
    val decision = reviewTarget
    if (currentRequest != null && decision != null) {
        RequestReviewDialog(
            request = currentRequest,
            decision = decision,
            isSending = state.reviewingRequestId == currentRequest.id,
            onConfirm = { note ->
                reviewTarget = null
                when (decision) {
                    RequestReviewDecision.CLARIFY -> onClarifyRequest(currentRequest, note)
                    RequestReviewDecision.REJECT -> onRejectRequest(currentRequest, note)
                }
            },
            onDismiss = { reviewTarget = null },
        )
    }
}

@Composable
private fun ManagerRequestRecord(
    request: FollowUpVisitRequest,
    zone: java.time.ZoneId,
    reviewing: Boolean,
    approving: Boolean,
    canApprove: Boolean,
    onReview: (RequestReviewDecision) -> Unit,
    onApprove: () -> Unit,
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
        InfoCard(modifier = Modifier.testTag(managerRequestDetailTag(request.id))) {
            Text(
                text = request.customerName,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(
                    R.string.technician_request_job,
                    request.jobNumber,
                    request.jobTitle,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SectionLabel(
                label = stringResource(R.string.technician_request_status_label),
                count = null,
            )
            RequestStatusPill(status = request.status)
            ManagerRequestField(
                label = stringResource(R.string.technician_request_proposed_label),
                value = proposedWindow(request, zone, locale),
            )
            request.sourceVisitScheduledStart?.let { sourceStart ->
                formatRequestWindow(sourceStart, null, zone, locale)?.let { sourceVisit ->
                    ManagerRequestField(
                        label = stringResource(R.string.technician_request_source_visit_label),
                        value = sourceVisit,
                    )
                }
            }
            ManagerRequestField(
                label = stringResource(R.string.technician_request_reason_label),
                value = request.reason,
            )
            ManagerRequestField(
                label = stringResource(R.string.technician_request_preference_label),
                value = if (request.sameTechnicianPreferred) {
                    stringResource(R.string.schedule_request_same_technician)
                } else {
                    stringResource(R.string.technician_request_preference_none)
                },
            )
            request.reviewNote?.takeIf { it.isNotBlank() }?.let { note ->
                ManagerRequestField(
                    label = stringResource(R.string.technician_request_office_note_label),
                    value = note,
                )
            }
        }

        if (request.showsConversation) {
            InfoCard {
                FollowUpRequestConversationSection(
                    messages = request.messages,
                    readerIsRequester = false,
                    zone = zone,
                    locale = locale,
                    tag = scheduleRequestConversationTag(request.id),
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = onOpenJob) {
                Text(stringResource(R.string.technician_request_detail_open_job))
            }
        }
        if (request.isAwaitingReview) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (request.status == FollowUpVisitRequestStatus.PENDING) {
                    TextButton(
                        enabled = !reviewing && !approving,
                        onClick = { onReview(RequestReviewDecision.CLARIFY) },
                    ) {
                        Text(stringResource(R.string.schedule_request_clarify))
                    }
                }
                TextButton(
                    enabled = !reviewing && !approving,
                    onClick = { onReview(RequestReviewDecision.REJECT) },
                ) {
                    Text(stringResource(R.string.schedule_request_reject))
                }
                if (canApprove) {
                    TextButton(
                        modifier = Modifier.testTag(managerRequestDetailReviewTag(request.id)),
                        enabled = !reviewing && !approving,
                        onClick = onApprove,
                    ) {
                        Text(stringResource(R.string.schedule_request_approve))
                    }
                }
            }
        }
    }
}

@Composable
private fun ManagerRequestField(label: String, value: String) {
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
private fun ManagerRequestDetailsUnavailable() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.padding(HomePageGutter),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.technician_request_detail_unavailable_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(R.string.manager_request_detail_unavailable_detail),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
