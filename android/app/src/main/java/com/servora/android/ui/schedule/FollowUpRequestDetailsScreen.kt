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
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.servora.android.R
import com.servora.android.domain.model.FollowUpVisitRequest
import com.servora.android.ui.components.InfoCard
import com.servora.android.ui.components.OfflineNotice
import com.servora.android.ui.components.RequestStatusPill
import com.servora.android.ui.components.SectionLabel
import com.servora.android.ui.home.HomePageGutter
import com.servora.android.ui.home.deviceLocale
import java.time.ZoneId
import com.servora.android.ui.home.formattedAddressLine
import java.util.Locale

/*
 * One of the technician's own follow-up requests, read in full.
 *
 * The Requests list is the technician's index of what they asked for; touching a row opens the request
 * itself rather than the Job (`docs/tracker/057-qa-issue-list-visit-workflow.md` §5.12). A request is a
 * proposal about another field attempt, not an appointment (`BR-FV-002`), so the surface about it is a
 * surface about the proposal and the office's decision on it: what was proposed, why another attempt is
 * needed, what the office decided and what it said, and the conversation that decision was taken in
 * (`BR-FV-012`, `BR-FV-013`). The Job the proposal is for is one action away, because the work itself is
 * the Job's (`BR-066`).
 *
 * Everything drawn here is the API's own answer for the caller's own request (`BR-001`, `BR-009`,
 * `BR-041`): the status, the window, the office's words and the conversation are the backend's, and
 * nothing on this screen derives a second copy of them.
 */

/** Identifies one request's own record, as the destination for it draws it. */
fun technicianRequestDetailTag(requestId: String): String =
    "technician-request-detail-$requestId"

const val FollowUpRequestDetailsLoadingTag = "technician-request-detail-loading"
const val FollowUpRequestDetailsFailureTag = "technician-request-detail-failure"
const val FollowUpRequestDetailsRetryTag = "technician-request-detail-retry"
const val FollowUpRequestDetailsUnavailableTag = "technician-request-detail-unavailable"
const val FollowUpRequestDetailsMessageTag = "technician-request-detail-message"

/** Identifies the action that leaves the request for the Job it was raised on (`BR-066`). */
fun technicianRequestDetailOpenJobTag(requestId: String): String =
    "technician-request-detail-open-job-$requestId"

/** The gap between the request's own record and the actions that act on it. */
private val RequestDetailSpacing = 12.dp

/**
 * One of the caller's own requests, opened from the Requests list.
 *
 * The request is looked up by id in the list the API answered with rather than carried as a value, so
 * an answer the backend recorded replaces what the screen shows without the destination holding a copy
 * of its own (`BR-001`, `BR-067`). A request the list does not hold is never invented: the screen states
 * which of the three answers the read gave — nothing yet, a failure, or a request that is not one of
 * the caller's own (`BR-042`).
 */
@Composable
internal fun FollowUpRequestDetailsScreen(
    state: TechnicianScheduleUiState,
    requestId: String,
    onAnswerRequest: (FollowUpVisitRequest, String) -> Unit,
    onAcknowledgeReply: () -> Unit,
    onOpenJob: (String) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val zone = remember(state.timeZoneId) { zoneOf(state.timeZoneId) }
    val locale = deviceLocale()

    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // The list the request was read from is the last answer the backend gave rather than a
            // current one, and the screen says so instead of presenting the request as current
            // (`BR-013`, offline standard §7).
            if (state.showsRequestsUnrefreshedNotice) {
                OfflineNotice(
                    message = stringResource(R.string.technician_requests_unrefreshed),
                    tag = TechnicianRequestsUnrefreshedTag,
                    modifier = Modifier.padding(
                        start = HomePageGutter,
                        end = HomePageGutter,
                        top = HomePageGutter,
                    ),
                )
            }

            when (val content = requestDetailsContent(state, requestId)) {
                RequestDetailsContent.Reading -> RequestDetailsLoading()

                RequestDetailsContent.Failed -> RequestDetailsFailure(onRetry = onRetry)

                RequestDetailsContent.Unavailable -> RequestDetailsUnavailable()

                is RequestDetailsContent.Request -> RequestRecord(
                    request = content.request,
                    zone = zone,
                    locale = locale,
                    isReplying = state.isReplyingTo(requestId),
                    onAnswerRequest = onAnswerRequest,
                    onOpenJob = onOpenJob,
                )
            }
        }

        // What the last answer did is reported and released, exactly as the list reports its own
        // (`BR-001`, `BR-FV-013`): an answer the API refused says so, and one it recorded is stated as
        // recorded rather than assumed. The composer closes as soon as the answer is sent, so the screen
        // reports the outcome rather than the composer claiming it.
        val recordedMessage = stringResource(R.string.technician_request_answer_recorded)
        val answerFailureMessage = state.replyFailureReason?.let {
            stringResource(R.string.technician_request_answer_failed)
        }
        val snackbarHostState = remember { SnackbarHostState() }
        LaunchedEffect(state.answeredRequestId, state.replyFailureReason) {
            val message = when {
                state.answeredRequestId != null -> recordedMessage
                answerFailureMessage != null -> answerFailureMessage
                else -> null
            } ?: return@LaunchedEffect
            try {
                snackbarHostState.showSnackbar(
                    message = message,
                    duration = if (answerFailureMessage != null) {
                        SnackbarDuration.Long
                    } else {
                        SnackbarDuration.Short
                    },
                )
            } finally {
                onAcknowledgeReply()
            }
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(HomePageGutter)
                .testTag(FollowUpRequestDetailsMessageTag),
        )
    }
}

/**
 * The request's own record: the office's decision on it, what the technician proposed, why they said
 * another attempt was needed, and the ways to act on it (`BR-FV-012`, `BR-FV-013`).
 */
@Composable
private fun RequestRecord(
    request: FollowUpVisitRequest,
    zone: ZoneId,
    locale: Locale,
    isReplying: Boolean,
    onAnswerRequest: (FollowUpVisitRequest, String) -> Unit,
    onOpenJob: (String) -> Unit,
) {
    // Which composer is open is the technician's own browsing rather than a fact about the request, so
    // it is this screen's state — and the request is looked up by id, so an answer is never written
    // against a copy the list has moved past (`BR-001`, `BR-067`).
    var isAnswering by rememberSaveable(request.id) { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(HomePageGutter),
        verticalArrangement = Arrangement.spacedBy(RequestDetailSpacing),
    ) {
        InfoCard(modifier = Modifier.testTag(technicianRequestDetailTag(request.id))) {
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
            request.address?.let { address ->
                formattedAddressLine(
                    address.addressLine1,
                    address.addressLine2,
                    address.city,
                    address.province,
                    address.postalCode,
                )?.let { line ->
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            SectionLabel(
                label = stringResource(R.string.technician_request_status_label),
                count = null,
            )
            RequestStatusPill(
                status = request.status,
                modifier = Modifier.testTag(technicianRequestStatusTag(request.id)),
            )
            RequestDetailField(
                label = stringResource(R.string.technician_request_proposed_label),
                value = proposedWindow(request, zone, locale),
            )
            request.sourceVisitScheduledStart?.let { sourceStart ->
                formatRequestWindow(sourceStart, null, zone, locale)?.let { sourceVisit ->
                    RequestDetailField(
                        label = stringResource(R.string.technician_request_source_visit_label),
                        value = sourceVisit,
                    )
                }
            }
            RequestDetailField(
                label = stringResource(R.string.technician_request_reason_label),
                value = request.reason,
            )
            RequestDetailField(
                label = stringResource(R.string.technician_request_preference_label),
                value = if (request.sameTechnicianPreferred) {
                    stringResource(R.string.schedule_request_same_technician)
                } else {
                    stringResource(R.string.technician_request_preference_none)
                },
            )
            if (request.showsOfficeNote) {
                request.reviewNote?.takeIf { it.isNotBlank() }?.let { note ->
                    RequestDetailField(
                        label = stringResource(R.string.technician_request_office_note_label),
                        value = note,
                        modifier = Modifier.testTag(technicianRequestNoteTag(request.id)),
                    )
                }
            }
        }

        // What the office asked and what the technician answered, in the order it happened
        // (`BR-FV-012`). The exchange is the request's own history, so it stays readable after the
        // office decides (`BR-067`).
        if (request.showsConversation) {
            InfoCard {
                FollowUpRequestConversationSection(
                    messages = request.messages,
                    readerIsRequester = true,
                    zone = zone,
                    locale = locale,
                    tag = technicianRequestConversationTag(request.id),
                )
            }
        }

        // The work is the Job's (`BR-066`), so leaving the request for it is its own action rather than
        // what touching the request does. Answering is the technician's own move and the only one that
        // puts the request back in front of the office (`BR-FV-012`), so it is offered only while an
        // answer is owed and never twice while one is on its way (`BR-031`).
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(
                modifier = Modifier.testTag(technicianRequestDetailOpenJobTag(request.id)),
                onClick = { onOpenJob(request.jobId) },
            ) {
                Text(stringResource(R.string.technician_request_detail_open_job))
            }
            if (request.awaitsAnswer) {
                TextButton(
                    modifier = Modifier.testTag(technicianRequestAnswerTag(request.id)),
                    enabled = !isReplying,
                    onClick = { isAnswering = true },
                ) {
                    Text(stringResource(R.string.technician_request_answer_action))
                }
            }
        }
    }

    if (isAnswering) {
        RequestAnswerDialog(
            request = request,
            isSending = isReplying,
            onConfirm = { answer ->
                isAnswering = false
                onAnswerRequest(request, answer)
            },
            onDismiss = { isAnswering = false },
        )
    }
}

@Composable
private fun RequestDetailField(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = value, style = MaterialTheme.typography.bodyLarge)
    }
}

/**
 * The caller's own requests have not answered yet: the request is read out of that list, so nothing can
 * be stated about it before the list answers (`BR-042`, `BR-013`).
 */
@Composable
internal fun RequestDetailsLoading() {
    Box(
        modifier = Modifier.fillMaxSize().testTag(FollowUpRequestDetailsLoadingTag),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(RequestDetailSpacing),
        ) {
            CircularProgressIndicator()
            Text(
                text = stringResource(R.string.technician_requests_loading),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The caller's own requests could not be read — which is the read this request is resolved from — so it
 * is reported and can be retried where the technician is standing rather than by leaving the screen
 * (`BR-012`, `BR-013`).
 */
@Composable
internal fun RequestDetailsFailure(onRetry: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize().testTag(FollowUpRequestDetailsFailureTag),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = HomePageGutter),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(RequestDetailSpacing),
        ) {
            Text(
                text = stringResource(R.string.technician_requests_error_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(R.string.home_error_message),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(
                modifier = Modifier.testTag(FollowUpRequestDetailsRetryTag),
                onClick = onRetry,
            ) {
                Text(stringResource(R.string.home_retry))
            }
        }
    }
}

/**
 * The read answered and this request is not one of the caller's own (`BR-FV-001`, `BR-009`).
 *
 * It is the list's own answer rather than an error, so it is stated plainly and nothing is invented to
 * fill the screen (`BR-042`).
 */
@Composable
private fun RequestDetailsUnavailable() {
    Box(
        modifier = Modifier.fillMaxSize().testTag(FollowUpRequestDetailsUnavailableTag),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = HomePageGutter),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(RequestDetailSpacing),
        ) {
            Text(
                text = stringResource(R.string.technician_request_detail_unavailable_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(R.string.technician_request_detail_unavailable_detail),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

