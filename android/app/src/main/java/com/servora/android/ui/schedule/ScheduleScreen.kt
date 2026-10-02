package com.servora.android.ui.schedule

import android.text.format.DateFormat
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.servora.android.R
import com.servora.android.domain.model.FollowUpVisitRequest
import com.servora.android.domain.model.FollowUpVisitRequestStatus
import com.servora.android.domain.model.ScheduleTechnician
import com.servora.android.domain.model.ScheduleVisit
import com.servora.android.domain.model.TechnicianAssignment
import com.servora.android.ui.components.RequestStatusPill
import com.servora.android.ui.components.StatusDot
import com.servora.android.ui.home.HomePageGutter
import com.servora.android.ui.home.HomeVisitStatusPill
import com.servora.android.ui.home.deviceLocale
import com.servora.android.ui.home.formatScheduledRange
import com.servora.android.ui.home.formattedAddressLine
import com.servora.android.ui.home.statusColor
import com.servora.android.ui.home.techniciansSummary
import com.servora.android.ui.jobs.ScheduleConflictDialog
import com.servora.android.ui.jobs.ScheduleVisitSheet
import com.servora.android.ui.jobs.jobActionFailureMessage
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale
import kotlinx.coroutines.delay

/*
 * The manager schedule: the compact top section and the selected day's agenda.
 *
 * It is a dispatch view, not a calendar: the strip chooses a nearby day, the agenda lists that day's
 * Visits chronologically, and the month calendar exists only to jump somewhere distant
 * (`Figma/src/imports/pasted_text/servora-scheduler-design.md`).
 *
 * The screen decides nothing about the work. Which Visits a day holds, who is assigned, which work is
 * unassigned and what is overdue are the API's answers (`BR-001`, `BR-042`); the screen presents them
 * in the shared Visit vocabulary and status pill the two homes already use, so a Visit is never
 * described differently on two screens (`BR-041`).
 *
 * The agenda owns the scrolling, and the top section is told whether it has started: the week strip
 * then collapses, and the day's work gets the screen while the date and the controls stay
 * (`BR-012`).
 */

/** Identifies the populated schedule. */
const val ScheduleContentTag = "schedule-content"

/** Identifies the first-read state. */
const val ScheduleLoadingTag = "schedule-loading"

/** Identifies the state shown when nothing could be read at all, and its retry action. */
const val ScheduleFailureTag = "schedule-failure"
const val ScheduleRetryTag = "schedule-retry"

/** Identifies the selected day's empty state. */
const val ScheduleEmptyDayTag = "schedule-empty-day"

/** Identifies the unassigned lane's empty state. */
const val ScheduleEmptyUnassignedTag = "schedule-empty-unassigned"

/** Identifies the pending follow-up request lane's empty state. */
const val ScheduleEmptyRequestsTag = "schedule-empty-requests"

/** Identifies one follow-up request card and its review actions. */
fun scheduleRequestTag(requestId: String): String = "schedule-request-$requestId"
fun scheduleClarifyRequestTag(requestId: String): String = "schedule-request-clarify-$requestId"
fun scheduleRejectRequestTag(requestId: String): String = "schedule-request-reject-$requestId"
fun scheduleRescheduleRequestTag(requestId: String): String = "schedule-request-reschedule-$requestId"

/**
 * Identifies the line that says a request has been returned for clarification, and is therefore still
 * awaiting a decision (`BR-FV-012`).
 */
fun scheduleRequestClarifiedTag(requestId: String): String =
    "schedule-request-clarified-$requestId"

/**
 * Identifies the clarification conversation the office reads on a request (`BR-FV-012`).
 */
fun scheduleRequestConversationTag(requestId: String): String =
    "schedule-request-conversation-$requestId"

/**
 * Identifies the action that approves a pending request by scheduling the Visit it asks for
 * (`BR-FV-005`).
 */
fun scheduleApproveRequestTag(requestId: String): String = "schedule-request-approve-$requestId"

/**
 * Identifies what the Requests lane reports about a decision it just took: an approval that created a
 * Visit, a clarification, a rejection, or why one of them was refused (`BR-FV-013`).
 */
const val ScheduleRequestsMessageTag = "schedule-requests-message"

/** Identifies the cue that separates the day's past from the work still to come. */
const val ScheduleNowCueTag = "schedule-now-cue"

/** Identifies one Visit card, in either lane, and the action that opens its Job. */
fun scheduleVisitTag(visitId: String): String = "schedule-visit-$visitId"
fun scheduleOpenJobTag(jobId: String): String = "schedule-open-$jobId"

/*
 * Metrics, from `docs/design/android-design-system.md`: the 20 dp gutter the signed-in shell uses,
 * 12 dp inside a card, and touch targets of at least 48 dp.
 *
 * This is an operational screen read at a glance, so the agenda is deliberately denser than a
 * consumer list: several Visits should be visible at once (`BR-012`). Density comes out of padding
 * and spacing, never out of the touch target or the size of the facts a dispatcher reads.
 *
 * The agenda also carries a timeline rail, so a day is read as a chronology rather than as a stack of
 * unrelated cards. It stays narrow — 2 dp of rule and a 7 dp dot — because it is orientation, not
 * content: a Visit's card is never as tall as its duration, which would waste the screen a dispatcher
 * has to work with (`BR-012`).
 */
private val ScheduleStatusDotSize = 6.dp
private val ScheduleItemSpacing = 8.dp
private val ScheduleLineIconSize = 16.dp

/*
 * The marker that leads the line saying a request has been returned for clarification: the same size as
 * a Visit card's status dot, because it says the same kind of thing about the record it is on.
 */
private val ScheduleRequestStatusDotSize = ScheduleStatusDotSize
private val ScheduleRequestStatusDotGap = 6.dp
private val ScheduleRailWidth = 12.dp
private val ScheduleRailGap = 6.dp
private val ScheduleRailLineWidth = 2.dp
private val ScheduleRailDotSize = 7.dp
private val ScheduleRailDotCenter = 22.dp
private val ScheduleNowCueHeight = 28.dp
private val ScheduleNowCueDotSize = 10.dp
private val ScheduleCardPadding = 12.dp
private val ScheduleNowRefreshMillis = 30_000L
private const val ScheduleNowCueLineAlpha = 0.4f

/**
 * How far the agenda has to be scrolled before the week strip collapses.
 *
 * A small dead zone keeps a one-pixel fling from folding the strip away, and the condition is read
 * from the list's own scroll position, so nothing here fights the gesture the manager is making
 * (`BR-012`).
 */
private val ScheduleHeaderCollapseThreshold = 16.dp

/**
 * The manager's schedule: the compact top section and the selected day's agenda.
 *
 * Everything the screen may do is driven by the state it is handed and reported back through the
 * callbacks: it holds no scheduling state of its own, so a configuration change or a process
 * recreation returns to the day, lane and filter the manager left it on.
 *
 * The device's own day is read once here and handed to the parts that need it, so the week strip and
 * the agenda's "now" cue cannot disagree about which day the manager is living through (`BR-041`).
 *
 * The agenda's scroll position lives here because two things read it: the list itself, and the top
 * section's week strip, which collapses once the day's work has the screen (`BR-012`).
 *
 * Deciding a follow-up request is what the screen **writes** (`BR-FV-004`, `BR-FV-005`): an approval
 * opens the same form the Job Details screen schedules with, and a clarification or a rejection opens a
 * confirmation that states the decision's own record (`BR-FV-013`). What the API answers is reported
 * here — the decision it applied, or why it applied none (`BR-001`, `BR-042`).
 */
@Composable
fun ScheduleScreen(
    state: ScheduleUiState,
    /**
     * Whether the session may approve a request into a Visit (`BR-FV-004`, `BR-FV-005`).
     *
     * It is both capabilities the approval route asks for — reviewing a request and scheduling a Visit —
     * so a session holding only one of them is offered no approval the API would refuse (`BR-007`,
     * `BR-011`).
     */
    canApproveVisitRequests: Boolean,
    onSelectDate: (LocalDate) -> Unit,
    onShowWeek: (LocalDate) -> Unit,
    onSelectLane: (ScheduleLane) -> Unit,
    onApplyTechnicians: (List<ScheduleTechnician>) -> Unit,
    onOpenJob: (String, String?) -> Unit,
    onOpenRequest: (String) -> Unit,
    onClarifyRequest: (FollowUpVisitRequest, String) -> Unit,
    onRejectRequest: (FollowUpVisitRequest, String) -> Unit,
    onStartApproval: (FollowUpVisitRequest) -> Unit,
    onDismissApproval: () -> Unit,
    onApproveRequest: (
        request: FollowUpVisitRequest,
        start: Instant,
        end: Instant,
        assignments: List<TechnicianAssignment>,
    ) -> Unit,
    onConfirmApproval: () -> Unit,
    onDismissApprovalConflicts: () -> Unit,
    onAcknowledgeApproval: () -> Unit,
    onAcknowledgeReview: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val zone = remember(state.timeZoneId) { zoneOf(state.timeZoneId) }
    val today = remember(zone) { LocalDate.now(zone) }
    val scheduleState = remember(state) { state.copy(lane = ScheduleLane.SCHEDULE) }
    val agendaState = rememberLazyListState()
    val density = LocalDensity.current
    val collapseThreshold = with(density) { ScheduleHeaderCollapseThreshold.roundToPx() }
    val collapsed by remember(agendaState, collapseThreshold) {
        derivedStateOf {
            agendaState.firstVisibleItemIndex > 0 ||
                agendaState.firstVisibleItemScrollOffset > collapseThreshold
        }
    }
    // Another day starts at the top of its own agenda, which also brings the week strip back: the
    // manager who moved to a date is looking at that date, not at the scroll position of the day
    // before it (`BR-041`).
    LaunchedEffect(scheduleState.selectedDate) {
        if (agendaState.firstVisibleItemIndex != 0 || agendaState.firstVisibleItemScrollOffset != 0) {
            agendaState.scrollToItem(0)
        }
    }

    // The decision the office is taking, for as long as its confirmation is open. It is a screen fact
    // like the approval form's target, so a decision is never taken from a copy of a list that has
    // moved on: the dialog is drawn from the request it names (`BR-001`, `BR-FV-013`).
    var reviewTarget by remember { mutableStateOf<RequestReviewTarget?>(null) }

    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            val selectedDate = scheduleState.selectedDate
            if (selectedDate != null) {
                ScheduleTopSection(
                    state = scheduleState,
                    selectedDate = selectedDate,
                    today = today,
                    collapsed = collapsed,
                    onSelectDate = onSelectDate,
                    onShowWeek = onShowWeek,
                    onSelectLane = onSelectLane,
                    onApplyTechnicians = onApplyTechnicians,
                    showLaneSelector = false,
                )
            }

            when {
                scheduleState.showsInitialLoading -> ScheduleLoading()
                scheduleState.showsFailure -> ScheduleFailure(onRetry = onRetry)
                else -> ScheduleAgenda(
                    state = scheduleState,
                    today = today,
                    listState = agendaState,
                    zone = zone,
                    canApproveRequests = canApproveVisitRequests,
                    onOpenJob = onOpenJob,
                    onOpenRequest = onOpenRequest,
                    // Reviewing a request is a decision with its own record, so tapping the action opens
                    // the confirmation that states it rather than sending the decision immediately
                    // (`BR-FV-013`, `BR-067`).
                    onReviewRequest = { request, decision ->
                        reviewTarget = RequestReviewTarget(request, decision)
                    },
                    onStartApproval = onStartApproval,
                )
            }
        }

        // What the last decision did is reported and released, exactly as the Job Details screen reports
        // its own actions (`BR-001`, `BR-FV-013`). A review now states its outcome too: a rejection used
        // to close the row in silence, and a decision the API refused said nothing at all — the screen
        // recorded the reason and drew none of it.
        val approvedMessage = stringResource(R.string.schedule_request_approved)
        val approvalFailureMessage = state.approvalFailure?.let { failure ->
            stringResource(jobActionFailureMessage(failure))
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
            val failureMessage = approvalFailureMessage ?: reviewFailureMessage
            val message = when {
                state.approvedRequestId != null -> approvedMessage
                failureMessage != null -> failureMessage
                reviewMessage != null -> reviewMessage
                else -> null
            } ?: return@LaunchedEffect
            try {
                snackbarHostState.showSnackbar(
                    message = message,
                    duration = if (failureMessage != null) {
                        SnackbarDuration.Long
                    } else {
                        SnackbarDuration.Short
                    },
                )
            } finally {
                onAcknowledgeApproval()
                onAcknowledgeReview()
            }
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(HomePageGutter)
                .testTag(ScheduleRequestsMessageTag),
        )
    }

    // The approval form for the request the manager decided to schedule: the same form the Job Details
    // screen schedules a Visit with, because both state the same three things (`BR-072`). It stays open
    // while the conflicts are decided, so accepting them resends the decision it holds (`BR-070`).
    state.schedulingRequest?.let { request ->
        ScheduleVisitSheet(
            titleRes = R.string.schedule_request_approve_title,
            messageRes = R.string.schedule_request_approve_message,
            confirmRes = R.string.schedule_request_approve_confirm,
            // The technician's proposal is what the form opens on, as the suggestion it is: the office
            // decides the schedule that actually applies (`BR-FV-003`, `BR-FV-004`, `BR-FV-010`).
            initialStart = instantOf(request.proposedStart),
            initialEnd = instantOf(request.proposedEnd),
            technicians = state.assignableTechnicians,
            crewFailure = state.assignableFailure,
            isSubmitting = state.isApproving,
            onRetryCrew = { onStartApproval(request) },
            onConfirm = { start, end, assignments ->
                onApproveRequest(request, start, end, assignments)
            },
            onDismiss = onDismissApproval,
        )
    }

    state.pendingApproval?.let { pending ->
        ScheduleConflictDialog(
            conflicts = pending.conflicts,
            isSubmitting = state.isApproving,
            onConfirm = onConfirmApproval,
            onDismiss = onDismissApprovalConflicts,
        )
    }

    // The two decisions that are not an approval are taken in a confirmation that states them, and the
    // text it holds is the decision's own record (`BR-FV-013`): what a clarification still needs, or why
    // a request was refused. It is drawn from the request it names, so a decision is never taken about a
    // copy of a request the list has moved past (`BR-001`, `BR-067`).
    reviewTarget?.let { target ->
        RequestReviewDialog(
            request = target.request,
            decision = target.decision,
            isSending = state.reviewingRequestId == target.request.id,
            onConfirm = { note ->
                reviewTarget = null
                when (target.decision) {
                    RequestReviewDecision.CLARIFY -> onClarifyRequest(target.request, note)
                    RequestReviewDecision.REJECT -> onRejectRequest(target.request, note)
                }
            },
            onDismiss = { reviewTarget = null },
        )
    }
}


@Composable
fun ManagerWorkScreen(
    state: ScheduleUiState,
    canApproveVisitRequests: Boolean,
    onOpenJob: (String, String?) -> Unit,
    onOpenRequest: (String) -> Unit,
    onClarifyRequest: (FollowUpVisitRequest, String) -> Unit,
    onRejectRequest: (FollowUpVisitRequest, String) -> Unit,
    onStartApproval: (FollowUpVisitRequest) -> Unit,
    onDismissApproval: () -> Unit,
    onApproveRequest: (
        request: FollowUpVisitRequest,
        start: Instant,
        end: Instant,
        assignments: List<TechnicianAssignment>,
    ) -> Unit,
    onConfirmApproval: () -> Unit,
    onDismissApprovalConflicts: () -> Unit,
    onAcknowledgeApproval: () -> Unit,
    onAcknowledgeReview: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val zone = remember(state.timeZoneId) { zoneOf(state.timeZoneId) }
    var selected by rememberSaveable { mutableStateOf(WorkTab.ALL_JOBS.name) }
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(WorkJobFilter.ALL.name) }
    var reviewTarget by remember { mutableStateOf<RequestReviewTarget?>(null) }
    val listState = rememberLazyListState()

    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            WorkTabs(
                selected = WorkTab.valueOf(selected),
                state = state,
                onSelect = { tab -> selected = tab.name },
            )
            when {
                state.showsInitialLoading -> ScheduleLoading()
                state.showsFailure -> ScheduleFailure(onRetry = onRetry)
                else -> when (WorkTab.valueOf(selected)) {
                    WorkTab.ALL_JOBS -> WorkAllJobs(
                        state = state,
                        zone = zone,
                        query = query,
                        filter = WorkJobFilter.valueOf(filter),
                        listState = listState,
                        onQueryChange = { query = it },
                        onFilterChange = { filter = it.name },
                        onOpenJob = onOpenJob,
                    )

                    WorkTab.UNASSIGNED -> WorkUnassignedJobs(
                        state = state,
                        zone = zone,
                        listState = listState,
                        onOpenJob = onOpenJob,
                    )

                    WorkTab.REQUESTS -> WorkVisitRequests(
                        state = state,
                        zone = zone,
                        listState = listState,
                        canApproveVisitRequests = canApproveVisitRequests,
                        onOpenRequest = onOpenRequest,
                        onReviewRequest = { request, decision ->
                            reviewTarget = RequestReviewTarget(request, decision)
                        },
                        onStartApproval = onStartApproval,
                    )
                }
            }
        }

        val approvedMessage = stringResource(R.string.schedule_request_approved)
        val approvalFailureMessage = state.approvalFailure?.let { failure ->
            stringResource(jobActionFailureMessage(failure))
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
            val failureMessage = approvalFailureMessage ?: reviewFailureMessage
            val message = when {
                state.approvedRequestId != null -> approvedMessage
                failureMessage != null -> failureMessage
                reviewMessage != null -> reviewMessage
                else -> null
            } ?: return@LaunchedEffect
            try {
                snackbarHostState.showSnackbar(
                    message = message,
                    duration = if (failureMessage != null) SnackbarDuration.Long else SnackbarDuration.Short,
                )
            } finally {
                onAcknowledgeApproval()
                onAcknowledgeReview()
            }
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(HomePageGutter)
                .testTag(ScheduleRequestsMessageTag),
        )
    }

    state.schedulingRequest?.let { request ->
        ScheduleVisitSheet(
            titleRes = R.string.schedule_request_approve_title,
            messageRes = R.string.schedule_request_approve_message,
            confirmRes = R.string.schedule_request_approve_confirm,
            initialStart = instantOf(request.proposedStart),
            initialEnd = instantOf(request.proposedEnd),
            technicians = state.assignableTechnicians,
            crewFailure = state.assignableFailure,
            isSubmitting = state.isApproving,
            onRetryCrew = { onStartApproval(request) },
            onConfirm = { start, end, assignments -> onApproveRequest(request, start, end, assignments) },
            onDismiss = onDismissApproval,
        )
    }

    state.pendingApproval?.let { pending ->
        ScheduleConflictDialog(
            conflicts = pending.conflicts,
            isSubmitting = state.isApproving,
            onConfirm = onConfirmApproval,
            onDismiss = onDismissApprovalConflicts,
        )
    }

    reviewTarget?.let { target ->
        RequestReviewDialog(
            request = target.request,
            decision = target.decision,
            isSending = state.reviewingRequestId == target.request.id,
            onConfirm = { note ->
                reviewTarget = null
                when (target.decision) {
                    RequestReviewDecision.CLARIFY -> onClarifyRequest(target.request, note)
                    RequestReviewDecision.REJECT -> onRejectRequest(target.request, note)
                }
            },
            onDismiss = { reviewTarget = null },
        )
    }
}

@Composable
private fun WorkTabs(
    selected: WorkTab,
    state: ScheduleUiState,
    onSelect: (WorkTab) -> Unit,
) {
    TabRow(selectedTabIndex = selected.ordinal) {
        WorkTab.entries.forEach { tab ->
            Tab(
                selected = selected == tab,
                onClick = { onSelect(tab) },
                text = {
                    Text(
                        text = when (tab) {
                            WorkTab.ALL_JOBS -> stringResource(R.string.work_tab_all_visits)
                            WorkTab.UNASSIGNED -> stringResource(
                                R.string.work_tab_unassigned_jobs_count,
                                state.unassignedCount,
                            )
                            WorkTab.REQUESTS -> stringResource(
                                R.string.work_tab_visit_requests_count,
                                state.reviewableRequestCount,
                            )
                        },
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
            )
        }
    }
}

@Composable
private fun WorkAllJobs(
    state: ScheduleUiState,
    zone: ZoneId,
    query: String,
    filter: WorkJobFilter,
    listState: LazyListState,
    onQueryChange: (String) -> Unit,
    onFilterChange: (WorkJobFilter) -> Unit,
    onOpenJob: (String, String?) -> Unit,
) {
    val schedule = state.schedule ?: return
    val visits = filterWorkVisits(
        scheduled = schedule.visits,
        unassigned = schedule.unassigned,
        query = query,
        filter = filter,
    )
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().testTag(ScheduleContentTag),
        contentPadding = PaddingValues(HomePageGutter),
        verticalArrangement = Arrangement.spacedBy(ScheduleItemSpacing),
    ) {
        item(key = "work-search") {
            WorkSearchAndFilters(
                query = query,
                filter = filter,
                onQueryChange = onQueryChange,
                onFilterChange = onFilterChange,
            )
        }
        if (state.showsRefreshingIndicator) {
            item(key = "refreshing") { ScheduleRefreshing() }
        }
        if (visits.isEmpty()) {
            item(key = "empty") {
                WorkEmptyCard(
                    title = stringResource(R.string.work_empty_jobs_title),
                    detail = stringResource(R.string.work_empty_jobs_detail),
                )
            }
        } else {
            itemsIndexed(items = visits, key = { _, visit -> visit.visitId }) { _, visit ->
                ScheduleVisitCard(visit = visit, zone = zone, showDate = true, onOpenJob = onOpenJob)
            }
        }
    }
}

@Composable
private fun WorkSearchAndFilters(
    query: String,
    filter: WorkJobFilter,
    onQueryChange: (String) -> Unit,
    onFilterChange: (WorkJobFilter) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            leadingIcon = {
                Icon(
                    painter = painterResource(R.drawable.ic_search),
                    contentDescription = null,
                    modifier = Modifier.size(ScheduleLineIconSize),
                )
            },
            placeholder = { Text(stringResource(R.string.work_search_placeholder)) },
        )
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            WorkJobFilter.entries.forEach { option ->
                val selected = option == filter
                OutlinedButton(onClick = { onFilterChange(option) }) {
                    Text(
                        text = when (option) {
                            WorkJobFilter.ALL -> stringResource(R.string.work_filter_all)
                            WorkJobFilter.SCHEDULED -> stringResource(R.string.work_filter_scheduled)
                            WorkJobFilter.UNASSIGNED -> stringResource(R.string.work_filter_unassigned)
                            WorkJobFilter.OVERDUE -> stringResource(R.string.work_filter_overdue)
                        },
                        color = if (selected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun WorkUnassignedJobs(
    state: ScheduleUiState,
    zone: ZoneId,
    listState: LazyListState,
    onOpenJob: (String, String?) -> Unit,
) {
    val schedule = state.schedule ?: return
    val unassigned = schedule.unassigned.filter { visit -> visit.technicians.isEmpty() }
    val hiddenUnassignedCount = (state.unassignedCount - schedule.unassigned.size).coerceAtLeast(0)
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().testTag(ScheduleContentTag),
        contentPadding = PaddingValues(HomePageGutter),
        verticalArrangement = Arrangement.spacedBy(ScheduleItemSpacing),
    ) {
        if (state.showsRefreshingIndicator) {
            item(key = "refreshing") { ScheduleRefreshing() }
        }
        if (unassigned.isEmpty()) {
            item(key = "empty") { ScheduleEmptyCard(lane = ScheduleLane.UNASSIGNED, tag = ScheduleEmptyUnassignedTag) }
        } else {
            itemsIndexed(items = unassigned, key = { _, visit -> visit.visitId }) { _, visit ->
                ScheduleVisitCard(visit = visit, zone = zone, showDate = true, onOpenJob = onOpenJob)
            }
        }
        if (hiddenUnassignedCount > 0) {
            item(key = "more") {
                Text(
                    text = pluralStringResource(
                        R.plurals.schedule_unassigned_more,
                        hiddenUnassignedCount,
                        hiddenUnassignedCount,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun WorkVisitRequests(
    state: ScheduleUiState,
    zone: ZoneId,
    listState: LazyListState,
    canApproveVisitRequests: Boolean,
    onOpenRequest: (String) -> Unit,
    onReviewRequest: (FollowUpVisitRequest, RequestReviewDecision) -> Unit,
    onStartApproval: (FollowUpVisitRequest) -> Unit,
) {
    val schedule = state.schedule ?: return
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().testTag(ScheduleContentTag),
        contentPadding = PaddingValues(HomePageGutter),
        verticalArrangement = Arrangement.spacedBy(ScheduleItemSpacing),
    ) {
        if (state.showsRefreshingIndicator || state.isReadingRequests) {
            item(key = "refreshing") { ScheduleRefreshing() }
        }
        val requests = state.reviewableRequests
        if (requests.isEmpty()) {
            item(key = "empty") { ScheduleEmptyCard(lane = ScheduleLane.REQUESTS, tag = ScheduleEmptyRequestsTag) }
        } else {
            itemsIndexed(items = requests, key = { _, request -> request.id }) { _, request ->
                ScheduleRequestCard(
                    request = request,
                    reviewing = state.reviewingRequestId == request.id,
                    canApprove = canApproveVisitRequests && !state.isApproving,
                    zone = zone,
                    requesterName = request.preferredRequesterName(schedule.technicians),
                    onOpen = { onOpenRequest(request.id) },
                    onClarify = { onReviewRequest(request, RequestReviewDecision.CLARIFY) },
                    onReject = { onReviewRequest(request, RequestReviewDecision.REJECT) },
                    onApprove = { onStartApproval(request) },
                )
            }
        }
    }
}

@Composable
private fun WorkEmptyCard(title: String, detail: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(text = detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** First read: nothing is known about the day yet. */
@Composable
private fun ScheduleLoading(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize().testTag(ScheduleLoadingTag),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.schedule_loading),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Neither a known day nor a readable backend: the screen reports why and offers the read again. */
@Composable
private fun ScheduleFailure(onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(HomePageGutter)
            .testTag(ScheduleFailureTag),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.schedule_error_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.home_error_message),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        TextButton(onClick = onRetry, modifier = Modifier.testTag(ScheduleRetryTag)) {
            Text(stringResource(R.string.home_retry))
        }
    }
}

/**
 * The lane the manager selected: the day's schedule, or the work that still has no crew.
 *
 * Both lanes come from the same read, so switching between them never issues a request
 * (`BR-042`): the backend decided what each lane holds, and the screen only shows one at a time.
 *
 * The rows are the API's own order (`BR-072`), with the "now" cue inserted between the Visits that
 * have started and the ones that have not. The cue is orientation for the manager's eye; it never
 * changes a Visit's status or its overdue condition, which stay the API's answers (`BR-001`), and it
 * advances with the device's clock so it keeps saying where in the day the manager is reading.
 */
@Composable
private fun ScheduleAgenda(
    state: ScheduleUiState,
    today: LocalDate,
    listState: LazyListState,
    zone: ZoneId,
    /** Whether the session may approve a request into a Visit (`BR-FV-005`). */
    canApproveRequests: Boolean,
    onOpenJob: (String, String?) -> Unit,
    onOpenRequest: (String) -> Unit,
    onReviewRequest: (FollowUpVisitRequest, RequestReviewDecision) -> Unit,
    onStartApproval: (FollowUpVisitRequest) -> Unit,
    modifier: Modifier = Modifier,
) {
    val schedule = state.schedule ?: return
    val visits = when (state.lane) {
        ScheduleLane.SCHEDULE -> schedule.visits
        ScheduleLane.UNASSIGNED -> schedule.unassigned
        ScheduleLane.REQUESTS -> emptyList()
    }
    val emptyTag = when (state.lane) {
        ScheduleLane.SCHEDULE -> ScheduleEmptyDayTag
        ScheduleLane.UNASSIGNED -> ScheduleEmptyUnassignedTag
        ScheduleLane.REQUESTS -> ScheduleEmptyRequestsTag
    }
    val now = rememberAdvancingNow()
    val rows = scheduleRows(
        visits = visits,
        nowCueIndex = if (state.showsNowCue(today)) {
            nowCueIndex(starts = visits.map { instantOf(it.scheduledStart) }, now = now)
        } else {
            null
        },
    )

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize().testTag(ScheduleContentTag),
        contentPadding = PaddingValues(
            start = HomePageGutter,
            end = HomePageGutter,
            top = 4.dp,
            bottom = HomePageGutter,
        ),
        verticalArrangement = Arrangement.spacedBy(ScheduleItemSpacing),
    ) {
        if (state.showsRefreshingIndicator) {
            item(key = "refreshing") { ScheduleRefreshing() }
        }

        if (state.lane == ScheduleLane.REQUESTS) {
            val requests = state.reviewableRequests
            if (requests.isEmpty()) {
                item(key = "empty") { ScheduleEmptyCard(lane = state.lane, tag = emptyTag) }
            } else {
                itemsIndexed(items = requests, key = { _, request -> request.id }) { _, request ->
                    ScheduleRequestCard(
                        request = request,
                        reviewing = state.reviewingRequestId == request.id,
                        // Approving creates a Visit, so it is offered only to a session the API would
                        // admit to the route, and it is not offered twice while one is in flight
                        // (`BR-007`, `BR-011`, `BR-031`).
                        canApprove = canApproveRequests && !state.isApproving,
                        zone = zone,
                        requesterName = request.preferredRequesterName(schedule.technicians),
                        onOpen = { onOpenRequest(request.id) },
                        onClarify = { onReviewRequest(request, RequestReviewDecision.CLARIFY) },
                        onReject = { onReviewRequest(request, RequestReviewDecision.REJECT) },
                        onApprove = { onStartApproval(request) },
                    )
                }
            }
        } else if (visits.isEmpty()) {
            item(key = "empty") { ScheduleEmptyCard(lane = state.lane, tag = emptyTag) }
        } else {
            itemsIndexed(items = rows, key = { _, row -> row.key }) { index, row ->
                val first = index == 0
                val last = index == rows.lastIndex
                when (row) {
                    is ScheduleAgendaRow.Visit -> ScheduleVisitRow(
                        visit = row.visit,
                        zone = zone,
                        first = first,
                        last = last,
                        onOpenJob = onOpenJob,
                    )

                    ScheduleAgendaRow.Now -> ScheduleNowCueRow(
                        now = now,
                        zone = zone,
                        first = first,
                        last = last,
                    )
                }
            }
        }

        if (state.lane == ScheduleLane.UNASSIGNED && state.unassignedCount > visits.size) {
            // The lane is capped by the API, so a long queue says how many more are waiting rather
            // than looking complete (`BR-042`).
            item(key = "more") {
                Text(
                    text = pluralStringResource(
                        R.plurals.schedule_unassigned_more,
                        state.unassignedCount - visits.size,
                        state.unassignedCount - visits.size,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * One row of the agenda: a Visit of the lane, or the cue that states where in the day the manager is.
 *
 * A row knows its own key so the list keeps a Visit in place when a read replaces the day around it
 * (`BR-042`), and so the cue never borrows a Visit's identity.
 */
private sealed interface ScheduleAgendaRow {
    val key: String

    data class Visit(val visit: ScheduleVisit) : ScheduleAgendaRow {
        override val key: String get() = visit.visitId
    }

    data object Now : ScheduleAgendaRow {
        override val key: String get() = ScheduleNowCueTag
    }
}

/**
 * The lane's rows in the order they are drawn.
 *
 * [nowCueIndex] is where the cue belongs among [visits] (`nowCueIndex` above), or `null` when the
 * lane shows no cue at all — another day, the unassigned lane, or a lane with nothing in it.
 */
private fun scheduleRows(visits: List<ScheduleVisit>, nowCueIndex: Int?): List<ScheduleAgendaRow> =
    buildList {
        visits.forEachIndexed { index, visit ->
            if (nowCueIndex == index) {
                add(ScheduleAgendaRow.Now)
            }
            add(ScheduleAgendaRow.Visit(visit))
        }
        if (nowCueIndex != null && nowCueIndex >= visits.size) {
            add(ScheduleAgendaRow.Now)
        }
    }


/** A read is in flight over content the manager is already reading. */
@Composable
private fun ScheduleRefreshing() {
    Text(
        text = stringResource(R.string.home_refreshing),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * The device's clock, read again as the screen stays open.
 *
 * The agenda places its "now" cue from this value and prints it, so the cue keeps its place in the
 * day while the manager works rather than freezing at the moment the screen was composed. It is a
 * device fact and not a schedule one: no Visit's status or overdue condition is derived from it
 * (`BR-001`).
 */
@Composable
private fun rememberAdvancingNow(): Instant {
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(ScheduleNowRefreshMillis)
            now = Instant.now()
        }
    }
    return now
}


/** A lane with nothing in it, said in the lane's own words. */
@Composable
private fun ScheduleEmptyCard(lane: ScheduleLane, tag: String) {
    Card(
        modifier = Modifier.fillMaxWidth().testTag(tag),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(
                    when (lane) {
                        ScheduleLane.SCHEDULE -> R.string.schedule_empty_day_title
                        ScheduleLane.UNASSIGNED -> R.string.schedule_empty_unassigned_title
                        ScheduleLane.REQUESTS -> R.string.schedule_empty_requests_title
                    },
                ),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(
                    when (lane) {
                        ScheduleLane.SCHEDULE -> R.string.schedule_empty_day_detail
                        ScheduleLane.UNASSIGNED -> R.string.schedule_empty_unassigned_detail
                        ScheduleLane.REQUESTS -> R.string.schedule_empty_requests_detail
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun FollowUpVisitRequest.preferredRequesterName(
    technicians: List<ScheduleTechnician>,
): String? = technicians
    .firstOrNull { technician -> technician.membershipId == requestingTechnicianMembershipId }
    ?.name
    ?.takeIf { name -> name.isNotBlank() }


/**
 * One follow-up request awaiting an office decision.
 *
 * The card carries what the office decides with: when the technician proposes the attempt, why they say
 * another one is needed, and whether they would like to carry it out. A request the office has already
 * returned for clarification says so, because it is still in the lane: `BR-FV-012` keeps it unresolved
 * "until it is later approved or rejected", so it is a request with a decision still to make rather than
 * a closed one, and a card that looked like a fresh proposal would hide that the office already asked
 * its question.
 */
@Composable
private fun ScheduleRequestCard(
    request: FollowUpVisitRequest,
    reviewing: Boolean,
    canApprove: Boolean,
    zone: ZoneId,
    requesterName: String?,
    onOpen: () -> Unit,
    onClarify: () -> Unit,
    onReject: () -> Unit,
    onApprove: () -> Unit,
) {
    val locale = deviceLocale()
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .testTag(scheduleRequestTag(request.id)),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(modifier = Modifier.padding(ScheduleCardPadding)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RequestStatusPill(status = request.status)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = formatRequestWindow(
                    request.proposedStart,
                    request.proposedEnd,
                    zone,
                    locale,
                )
                    ?: stringResource(R.string.job_details_not_scheduled),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(
                    R.string.schedule_request_job_context,
                    request.jobNumber,
                    request.jobTitle,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (request.customerName.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.schedule_request_customer_context, request.customerName),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            request.sourceVisitScheduledStart?.let { sourceStart ->
                Spacer(Modifier.height(2.dp))
                Text(
                    text = stringResource(
                        R.string.schedule_request_previous_visit,
                        formatScheduledRange(sourceStart, null, locale) ?: sourceStart,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = request.reason,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            if (request.sameTechnicianPreferred) {
                Spacer(Modifier.height(4.dp))
                IconLine(
                    iconRes = R.drawable.ic_users,
                    text = requesterName?.let { name ->
                        stringResource(R.string.schedule_request_preferred_technician, name)
                    } ?: stringResource(R.string.schedule_request_preferred_technician_unknown),
                )
            }
            // A request the office returned for clarification is not a closed one (`BR-FV-012`), and the
            // card says which of the two reviewable states it is in so the office never decides about one
            // believing it is the other (`BR-041`).
            if (request.status == FollowUpVisitRequestStatus.NEEDS_CLARIFICATION) {
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(
                        color = MaterialTheme.colorScheme.tertiary,
                        size = ScheduleRequestStatusDotSize,
                    )
                    Spacer(Modifier.width(ScheduleRequestStatusDotGap))
                    Text(
                        text = stringResource(R.string.schedule_request_needs_clarification),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.testTag(scheduleRequestClarifiedTag(request.id)),
                    )
                }
            }
            // What the office asked and what the technician answered, in the order it happened
            // (`BR-FV-012`). The office reads the same thread the technician reads, because the answer is
            // what its next decision is about (`BR-FV-013`): a question it asked and an answer it has not
            // read would leave it deciding on the same information it already had.
            if (request.showsConversation) {
                Spacer(Modifier.height(8.dp))
                FollowUpRequestConversationSection(
                    messages = request.messages,
                    readerIsRequester = false,
                    zone = zone,
                    locale = locale,
                    tag = scheduleRequestConversationTag(request.id),
                )
            }
            Spacer(Modifier.height(8.dp))
            RequestReviewActions(
                request = request,
                reviewing = reviewing,
                canApprove = canApprove,
                onClarify = onClarify,
                onReject = onReject,
                onApprove = onApprove,
            )
        }
    }
}

@Composable
private fun RequestReviewActions(
    request: FollowUpVisitRequest,
    reviewing: Boolean,
    canApprove: Boolean,
    onClarify: () -> Unit,
    onReject: () -> Unit,
    onApprove: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            IconButton(
                enabled = !reviewing,
                onClick = { expanded = true },
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_more_vert),
                    contentDescription = stringResource(R.string.schedule_request_more_actions),
                )
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                if (request.status == FollowUpVisitRequestStatus.PENDING) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.schedule_request_clarify)) },
                        enabled = !reviewing,
                        modifier = Modifier.testTag(scheduleClarifyRequestTag(request.id)),
                        onClick = {
                            expanded = false
                            onClarify()
                        },
                    )
                }
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.schedule_request_reject)) },
                    enabled = !reviewing,
                    modifier = Modifier.testTag(scheduleRejectRequestTag(request.id)),
                    onClick = {
                        expanded = false
                        onReject()
                    },
                )
            }
        }
        if (canApprove) {
            FilledTonalButton(
                modifier = Modifier.testTag(scheduleApproveRequestTag(request.id)),
                enabled = !reviewing,
                onClick = onApprove,
            ) {
                Text(stringResource(R.string.schedule_request_schedule))
            }
        }
    }
}

/**
 * One Visit of the agenda, with the rail that ties it to the Visits around it.
 *
 * The card carries only what dispatch acts on, in the order it is scanned in a second or two
 * (`BR-012`): when the attempt is, where it stands, what the work is, whose it is, who is going, and
 * where it is. It never shows the **Job's** status: the Visit status is the field attempt's own
 * (`BR-059`, `BR-074`), and presenting the other one here would describe the work as being somewhere
 * it is not.
 *
 * The whole card opens the **Job** the Visit belongs to, which is where the manager acts on it
 * (`BR-012`).
 */
@Composable
private fun ScheduleVisitRow(
    visit: ScheduleVisit,
    zone: ZoneId,
    first: Boolean,
    last: Boolean,
    onOpenJob: (String, String?) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
    ) {
        ScheduleTimelineRail(
            dotColor = statusColor(status = visit.visitStatus, isOverdue = visit.isOverdue),
            dotCenter = ScheduleRailDotCenter,
            first = first,
            last = last,
            modifier = Modifier.width(ScheduleRailWidth),
        )
        Spacer(Modifier.width(ScheduleRailGap))
        ScheduleVisitCard(visit = visit, zone = zone, onOpenJob = onOpenJob)
    }
}

/** One Visit's card, without the rail: the facts dispatch scans, and the whole card as the target. */
@Composable
private fun ScheduleVisitCard(
    visit: ScheduleVisit,
    zone: ZoneId,
    showDate: Boolean = false,
    onOpenJob: (String, String?) -> Unit,
) {
    val locale = deviceLocale()
    val propertyName = visit.address?.propertyName?.takeIf { it.isNotBlank() }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(scheduleVisitTag(visit.visitId))
            .clickable { onOpenJob(visit.jobId, visit.visitId) },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(modifier = Modifier.padding(ScheduleCardPadding)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = visit.scheduledStart?.let { start ->
                        if (showDate) {
                            formatWorkScheduledRange(start, visit.scheduledEnd, zone, locale)
                        } else {
                            formatScheduledRange(start, visit.scheduledEnd, locale)
                        }
                    } ?: stringResource(R.string.job_details_not_scheduled),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(8.dp))
                HomeVisitStatusPill(status = visit.visitStatus, isOverdue = visit.isOverdue)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = visit.jobTitle,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            // Whose the work is: the customer, and the Property when the address snapshot named one,
            // so the dispatcher reads both in one line. It is a supporting fact rather than the work
            // itself, so it stays a quieter tier than the title (`BR-041`, `BR-049`).
            Text(
                text = propertyName?.let { property ->
                    stringResource(
                        R.string.schedule_customer_property_format,
                        visit.customerName,
                        property,
                    )
                } ?: visit.customerName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            TechnicianLine(visit = visit)
            visit.address?.let { address ->
                formattedAddressLine(
                    addressLine1 = address.addressLine1,
                    addressLine2 = address.addressLine2,
                    city = address.city,
                    province = address.province,
                    postalCode = address.postalCode,
                )?.let { line ->
                    Spacer(Modifier.height(2.dp))
                    IconLine(iconRes = R.drawable.ic_map_pin, text = line)
                }
            }
        }
    }
}

/**
 * The cue that states where in the day the manager is reading, and the time it is there.
 *
 * It is drawn between the Visits that have started and the ones that have not, and it is the one row
 * that is not work: it carries no status, no crew and no link, and it is never a Visit (`BR-059`). It
 * appears only on the day the manager is living through, and only in the lane that presents that
 * day's chronology ([ScheduleLane]).
 *
 * Its dot is the one dot on the rail that is not a Visit's: it is the brand colour and larger than
 * the status dots it stands among, so "where the day is now" is never confused with "what a Visit is
 * doing" (`BR-074`).
 */
@Composable
private fun ScheduleNowCueRow(
    now: Instant,
    zone: ZoneId,
    first: Boolean,
    last: Boolean,
) {
    val line = MaterialTheme.colorScheme.primary.copy(alpha = ScheduleNowCueLineAlpha)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(ScheduleNowCueHeight)
            .testTag(ScheduleNowCueTag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ScheduleTimelineRail(
            dotColor = MaterialTheme.colorScheme.primary,
            dotSize = ScheduleNowCueDotSize,
            dotCenter = ScheduleNowCueHeight / 2,
            first = first,
            last = last,
            modifier = Modifier
                .width(ScheduleRailWidth)
                .fillMaxHeight(),
        )
        Spacer(Modifier.width(ScheduleRailGap))
        HorizontalDivider(modifier = Modifier.weight(1f), thickness = 1.dp, color = line)
        Text(
            text = stringResource(
                R.string.schedule_now_at,
                formatClockTime(instant = now, zone = zone, locale = deviceLocale()),
            ),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        HorizontalDivider(modifier = Modifier.weight(1f), thickness = 1.dp, color = line)
    }
}

/**
 * The rail a lane is read along: one row's dot, and the rule that joins it to the rows around it.
 *
 * It says that these rows are one day in time order, and nothing more, so it stays as quiet as it can:
 * the muted outline for the rule and a small dot in the row's own status colour — the colour the
 * status pill already wears (`BR-041`). A row states a time; it is never drawn as tall as its
 * duration, which would cost the manager the screen they are working on (`BR-012`).
 *
 * The rule runs through the gap between rows rather than stopping at each card, so the lane reads as
 * one chronology. The first row starts its rule at its own dot and the last one ends it there, so the
 * lane never looks as though it continues past what is on screen.
 */
@Composable
private fun ScheduleTimelineRail(
    dotColor: Color,
    dotCenter: Dp,
    first: Boolean,
    last: Boolean,
    modifier: Modifier = Modifier,
    dotSize: Dp = ScheduleRailDotSize,
) {
    val ruleColor = MaterialTheme.colorScheme.outlineVariant
    val density = LocalDensity.current
    val ruleWidth = with(density) { ScheduleRailLineWidth.toPx() }
    val dotCenterPx = with(density) { dotCenter.toPx() }
    val halfGap = with(density) { (ScheduleItemSpacing / 2).toPx() }
    Box(
        modifier = modifier
            .fillMaxHeight()
            .drawWithContent {
                val top = if (first) dotCenterPx else -halfGap
                val bottom = if (last) dotCenterPx else size.height + halfGap
                if (bottom > top) {
                    drawRect(
                        color = ruleColor,
                        topLeft = Offset(x = size.width / 2f - ruleWidth / 2f, y = top),
                        size = Size(width = ruleWidth, height = bottom - top),
                    )
                }
                drawContent()
            },
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = dotCenter - dotSize / 2)
                .size(dotSize)
                .clip(CircleShape)
                .background(dotColor),
        )
    }
}

private fun formatWorkScheduledRange(start: String, end: String?, zone: ZoneId, locale: Locale): String? {
    val date = formatScheduledDate(start, zone, locale) ?: return formatScheduledRange(start, end, locale)
    val time = formatScheduledRange(start, end, locale) ?: return date
    return "$date · $time"
}

private fun formatScheduledDate(value: String, zone: ZoneId, locale: Locale): String? =
    try {
        val pattern = DateFormat.getBestDateTimePattern(locale, "MMMEd")
        Instant.parse(value).atZone(zone).format(DateTimeFormatter.ofPattern(pattern, locale))
    } catch (_: DateTimeParseException) {
        null
    }

/**
 * Who is going to be there (`BR-068`).
 *
 * A crew longer than one line is named up to a limit and the rest is counted, so a Visit with five
 * technicians is never presented as one with two and the card never grows a line just for names
 * (`BR-012`). An unassigned Visit says so and is marked with the error role, because that is the work
 * the manager has to act on: the absence of a crew is not a status (`BR-042`), so it is presented
 * beside the technician rather than inside the status pill.
 */
@Composable
private fun TechnicianLine(visit: ScheduleVisit) {
    val unassigned = visit.technicians.isEmpty()
    val crew = crewLine(names = visit.technicians.map { it.name })
    val text = if (crew.hidden > 0 && crew.names.isNotEmpty()) {
        stringResource(
            R.string.schedule_crew_more,
            crew.names.joinToString(separator = ", "),
            crew.hidden,
        )
    } else {
        techniciansSummary(names = crew.names, crewSize = visit.technicians.size)
    }
    IconLine(iconRes = R.drawable.ic_users, text = text, emphasis = unassigned)
}

/** One line of the card: a small leading glyph, or the unassigned marker, and the fact it names. */
@Composable
private fun IconLine(iconRes: Int, text: String, emphasis: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (emphasis) {
            Box(
                modifier = Modifier
                    .size(ScheduleStatusDotSize)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.error),
            )
            Spacer(Modifier.width(6.dp))
        } else {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(ScheduleLineIconSize),
            )
            Spacer(Modifier.width(4.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = if (emphasis) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            fontWeight = if (emphasis) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
