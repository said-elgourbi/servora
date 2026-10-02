package com.servora.android.ui.schedule

import android.text.format.DateFormat
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.servora.android.R
import com.servora.android.domain.model.AdHocReportCustomerOption
import com.servora.android.domain.model.AdHocReportJobOption
import com.servora.android.domain.model.AdHocReportPropertyOption
import com.servora.android.domain.model.FollowUpVisitRequest
import com.servora.android.domain.model.ScheduleVisit
import com.servora.android.domain.model.VisitOutcome
import com.servora.android.domain.model.VisitStatus
import com.servora.android.ui.components.OfflineNotice
import com.servora.android.ui.components.RequestStatusPill
import com.servora.android.ui.home.HomePageGutter
import com.servora.android.ui.home.HomeTouchTarget
import com.servora.android.ui.home.HomeVisitStatusPill
import com.servora.android.ui.home.deviceLocale
import com.servora.android.ui.home.formatScheduledRange
import com.servora.android.ui.home.formatScheduledTime
import com.servora.android.ui.home.formattedAddressLine
import com.servora.android.ui.home.techniciansSummary
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/*
 * The technician's schedule: the caller's own assigned work, day by day.
 *
 * It answers a different question from the manager's schedule. The office screen asks "what is
 * happening that day, and who is doing it?" and carries the dispatch controls that follow from that;
 * this one asks "what is assigned to me, and what am I doing next?" (`BR-010`, `BR-012`). So it is
 * simpler on purpose: one day's own Visits, a week strip to look ahead, a way back to today, and a card
 * that opens the Job. There are no lanes, no technician filter and no month calendar here, and nothing
 * on it assigns, reschedules or cancels: those are dispatch actions that belong to the office screen and
 * to the API's own routes (`BR-066`, `BR-072`, `BR-073`).
 *
 * The screen decides nothing about the work. Which Visits the day holds, their statuses, their crew and
 * whether one is overdue are the API's answers for the caller's own membership (`BR-001`, `BR-009`,
 * `BR-042`); the screen presents them in the shared Visit vocabulary and status pill the two homes and
 * the office schedule already use, so a Visit is never described differently on two screens (`BR-041`).
 */

/** Identifies the populated agenda, and the states that stand in for it. */
const val TechnicianScheduleContentTag = "technician-schedule-content"
const val TechnicianScheduleLoadingTag = "technician-schedule-loading"
const val TechnicianScheduleFailureTag = "technician-schedule-failure"
const val TechnicianScheduleRetryTag = "technician-schedule-retry"
const val TechnicianScheduleEmptyDayTag = "technician-schedule-empty-day"

/** Identifies the header: the week it names, the way between weeks, and the way back to today. */
const val TechnicianScheduleWeekRangeTag = "technician-schedule-week-range"
const val TechnicianSchedulePreviousWeekTag = "technician-schedule-previous-week"
const val TechnicianScheduleNextWeekTag = "technician-schedule-next-week"
const val TechnicianScheduleTodayTag = "technician-schedule-today"

/** Identifies the day heading and the notice that the day on screen is the last reported one. */
const val TechnicianScheduleDayHeadingTag = "technician-schedule-day-heading"
const val TechnicianScheduleLastReportedTag = "technician-schedule-last-reported"

/** Identifies one Visit card. */
fun technicianScheduleVisitTag(visitId: String): String = "technician-schedule-visit-$visitId"

/** Identifies the two views My Schedule offers, and the count the requests view carries. */
const val TechnicianScheduleTabSelectorTag = "technician-schedule-tab-selector"
const val TechnicianScheduleTabScheduleTag = "technician-schedule-tab-schedule"
const val TechnicianScheduleTabRequestsTag = "technician-schedule-tab-requests"
const val TechnicianRequestsBadgeTag = "technician-requests-badge"

/** Identifies the caller's own requests: the list, one request's card, and the states it can be in. */
const val TechnicianRequestsContentTag = "technician-requests-content"
const val TechnicianRequestsLoadingTag = "technician-requests-loading"
const val TechnicianRequestsFailureTag = "technician-requests-failure"
const val TechnicianRequestsRetryTag = "technician-requests-retry"
const val TechnicianRequestsEmptyTag = "technician-requests-empty"
const val TechnicianRequestsUnrefreshedTag = "technician-requests-unrefreshed"
fun technicianRequestTag(requestId: String): String = "technician-request-$requestId"
fun technicianRequestStatusTag(requestId: String): String = "technician-request-status-$requestId"
fun technicianRequestNoteTag(requestId: String): String = "technician-request-note-$requestId"

/**
 * Identifies a request's clarification conversation, the action that answers it, and what the requests
 * view reports about an answer it just recorded (`BR-FV-012`, `BR-FV-013`).
 */
fun technicianRequestConversationTag(requestId: String): String =
    "technician-request-conversation-$requestId"

fun technicianRequestAnswerTag(requestId: String): String =
    "technician-request-answer-$requestId"

const val TechnicianRequestsMessageTag = "technician-requests-message"

/*
 * The views' own metrics. The tab row follows the office schedule's lane selector — a 32 dp pill the
 * width of its own label, 2 dp apart, inside a 48 dp target — so the two screens' view controls read
 * as one pattern (`docs/design/android-design-system.md`).
 */
private val TechnicianSegmentSpacing = 2.dp
private val TechnicianControlVisualHeight = 32.dp
private val TechnicianTabSegmentPadding = 12.dp

/*
 * Metrics, from `docs/design/android-design-system.md`: the 20 dp gutter the signed-in shell uses, 12 dp
 * inside a card, and touch targets of at least 48 dp.
 *
 * This is a screen read one-handed and in the field (`BR-012`), so the card is roomier than the office
 * schedule's: a start time in its own column, three lines of fact, and a footer that carries the status,
 * the window and the crew. The facts are the ones a technician needs before arriving — what the job is,
 * whose it is, where it is, when it is and who is coming.
 */
private val TechnicianTimeGutter = 56.dp
private val TechnicianCardSpacing = 10.dp
private val TechnicianCardPadding = 12.dp
private val TechnicianLineSpacing = 4.dp
private val TechnicianLineIconSize = 14.dp

/** The gap the same-technician line leaves between its icon and its label. */
private val RequestSameTechnicianLineIconGap = 6.dp
private val TechnicianWeekArrowSize = 20.dp

/**
 * My Schedule: the two views of the caller's own work — the schedule and their own requests.
 *
 * Everything the screen does is driven by the state it is handed and reported back through the
 * callbacks, so it holds no state of its own: a configuration change returns to the view, the day and
 * the week the technician left it on. The device's own day is read once here and handed to the parts
 * that need it, so the week strip's "today", the day heading and the way back to today cannot disagree
 * about which day the technician is living through (`BR-041`).
 *
 * The second view is the technician's own record of the extra visits they asked for and of what the
 * office did with each of them (`BR-FV-012`, `BR-FV-013`), which is how a decision reaches the
 * technician who proposed it (`docs/tracker/057-qa-issue-list-visit-workflow.md` §5.3.5). It is offered
 * only to a session that may read it, because the route is authorized by the asking capability, and
 * nothing is drawn that could not be fetched (`BR-011`). The date controls are **not** drawn beside it:
 * a request is a proposal about another attempt (`BR-FV-002`), no part of that view is scoped to the
 * selected day, and a date control over undated content is the confusion the office screen's own lane
 * question records — so the view that has no day in it carries none.
 */
@Composable
fun TechnicianScheduleScreen(
    state: TechnicianScheduleUiState,
    canRequestFollowUpVisit: Boolean,
    canReportAdHocWork: Boolean,
    onSelectTab: (TechnicianScheduleTab) -> Unit,
    onSelectDate: (LocalDate) -> Unit,
    onShowWeek: (LocalDate) -> Unit,
    onShowToday: () -> Unit,
    onOpenJob: (String, String?) -> Unit,
    onOpenRequest: (String) -> Unit,
    onReplyToRequest: (FollowUpVisitRequest, String) -> Unit,
    onAcknowledgeReply: () -> Unit,
    onOpenAdHocReport: () -> Unit,
    onDismissAdHocReport: () -> Unit,
    onAdHocReportCustomerQueryChange: (String) -> Unit,
    onAdHocReportSelectCustomer: (AdHocReportCustomerOption) -> Unit,
    onAdHocReportSelectProperty: (AdHocReportPropertyOption) -> Unit,
    onAdHocReportSelectJob: (AdHocReportJobOption) -> Unit,
    onAdHocReportWorkStartedAtChange: (Instant) -> Unit,
    onAdHocReportWorkEndedAtChange: (Instant) -> Unit,
    onAdHocReportOutcomeChange: (VisitOutcome) -> Unit,
    onAdHocReportSummaryChange: (String) -> Unit,
    onAdHocReportNotesChange: (String) -> Unit,
    onAdHocReportUnknownCustomerChange: (Boolean) -> Unit,
    onAdHocReportReportedCustomerNameChange: (String) -> Unit,
    onAdHocReportReportedCustomerPhoneChange: (String) -> Unit,
    onAdHocReportReportedCustomerAddressChange: (String) -> Unit,
    onSubmitAdHocWorkReport: () -> Unit,
    onAcknowledgeAdHocWorkReport: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val zone = remember(state.timeZoneId) { zoneOf(state.timeZoneId) }
    val today = remember(zone) { LocalDate.now(zone) }
    val locale = deviceLocale()
    val selectedDate = state.selectedDate
    val weekStart = state.displayedWeekStart
    // Which composer is open is the technician's own browsing rather than a fact about the request, so it
    // is the screen's state — the same way the office screen holds its own confirmation (`BR-012`). The
    // request itself is looked up by id, so an answer is never written against a copy the list has moved
    // past (`BR-001`, `BR-067`).
    var answeringRequestId by rememberSaveable { mutableStateOf<String?>(null) }
    val answeringRequest = answeringRequestId?.let { id ->
        state.requests.firstOrNull { request -> request.id == id }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // The two views are one tap apart whichever is open, so the technician never has to leave the
            // screen to read the answer to a request they raised (`BR-012`).
            if (canRequestFollowUpVisit) {
                TechnicianScheduleTabs(
                    tab = state.tab,
                    awaitingReviewCount = state.awaitingReviewRequestCount,
                    onSelectTab = onSelectTab,
                )
            }

            when (state.tab) {
                TechnicianScheduleTab.SCHEDULE -> {
                    // The header stays while the day scrolls: another day is one tap away, and a swipe
                    // between weeks never needs a scroll back (`BR-012`).
                    if (selectedDate != null && weekStart != null) {
                        TechnicianScheduleHeader(
                            selectedDate = selectedDate,
                            weekStart = weekStart,
                            today = today,
                            locale = locale,
                            showsTodayAction = state.showsTodayAction(today),
                            onSelectDate = onSelectDate,
                            onShowWeek = onShowWeek,
                            onShowToday = onShowToday,
                        )
                    }
                    if (canReportAdHocWork) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = HomePageGutter),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            TextButton(onClick = onOpenAdHocReport) {
                                Text(text = stringResource(R.string.technician_ad_hoc_report_action))
                            }
                        }
                    }

                    when {
                        state.showsInitialLoading -> TechnicianScheduleLoading()

                        state.showsFailure -> TechnicianScheduleFailure(onRetry = onRetry)

                        selectedDate != null -> TechnicianScheduleAgenda(
                            state = state,
                            selectedDate = selectedDate,
                            today = today,
                            locale = locale,
                            onOpenJob = onOpenJob,
                        )
                    }
                }

                TechnicianScheduleTab.REQUESTS -> TechnicianRequests(
                    state = state,
                    locale = locale,
                    zone = zone,
                    onAnswerRequest = { request -> answeringRequestId = request.id },
                    onOpenRequest = onOpenRequest,
                    onRetry = onRetry,
                )
            }
        }

        // What the last answer did is reported and released, exactly as the office screen reports its own
        // decisions (`BR-001`, `BR-FV-013`): an answer the API refused says so, and one it recorded is
        // stated as recorded rather than assumed.
        val recordedMessage = stringResource(R.string.technician_request_answer_recorded)
        val adHocRecordedMessage = stringResource(R.string.technician_ad_hoc_report_recorded)
        val adHocQueuedMessage = stringResource(R.string.technician_ad_hoc_report_queued)
        val answerFailureMessage = state.replyFailureReason?.let {
            stringResource(R.string.technician_request_answer_failed)
        }
        val adHocFailureMessage = state.adHocReportFailureReason?.let {
            stringResource(R.string.technician_ad_hoc_report_failed)
        }
        val snackbarHostState = remember { SnackbarHostState() }
        LaunchedEffect(
            state.answeredRequestId,
            state.replyFailureReason,
            state.adHocReportSubmitted,
            state.adHocReportQueued,
            state.adHocReportFailureReason,
        ) {
            val message = when {
                state.answeredRequestId != null -> recordedMessage
                state.adHocReportSubmitted -> adHocRecordedMessage
                state.adHocReportQueued -> adHocQueuedMessage
                answerFailureMessage != null -> answerFailureMessage
                adHocFailureMessage != null -> adHocFailureMessage
                else -> null
            } ?: return@LaunchedEffect
            try {
                snackbarHostState.showSnackbar(
                    message = message,
                    duration = if (answerFailureMessage != null || adHocFailureMessage != null) {
                        SnackbarDuration.Long
                    } else {
                        SnackbarDuration.Short
                    },
                )
            } finally {
                onAcknowledgeReply()
                onAcknowledgeAdHocWorkReport()
            }
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(HomePageGutter)
                .testTag(TechnicianRequestsMessageTag),
        )

        // The ad-hoc work report form (`BR-AH-001`). It is driven by the form state the ViewModel holds,
        // so the type-ahead search, the derived Property/Job options and the submission outcome are the
        // backend's answers rather than the screen's (`BR-007`, `BR-042`).
        if (state.adHocReportOpen) {
            AdHocWorkReportForm(
                state = state.adHocReportForm,
                isSubmitting = state.isSubmittingAdHocReport,
                zone = zone,
                onCustomerQueryChange = onAdHocReportCustomerQueryChange,
                onSelectCustomer = onAdHocReportSelectCustomer,
                onSelectProperty = onAdHocReportSelectProperty,
                onSelectJob = onAdHocReportSelectJob,
                onWorkStartedAtChange = onAdHocReportWorkStartedAtChange,
                onWorkEndedAtChange = onAdHocReportWorkEndedAtChange,
                onOutcomeChange = onAdHocReportOutcomeChange,
                onSummaryChange = onAdHocReportSummaryChange,
                onNotesChange = onAdHocReportNotesChange,
                onUnknownCustomerChange = onAdHocReportUnknownCustomerChange,
                onReportedCustomerNameChange = onAdHocReportReportedCustomerNameChange,
                onReportedCustomerPhoneChange = onAdHocReportReportedCustomerPhoneChange,
                onReportedCustomerAddressChange = onAdHocReportReportedCustomerAddressChange,
                onSubmit = onSubmitAdHocWorkReport,
                onDismiss = onDismissAdHocReport,
            )
        }

        answeringRequest?.let { request ->
            RequestAnswerDialog(
                request = request,
                isSending = state.isReplyingTo(request.id),
                onConfirm = { answer ->
                    answeringRequestId = null
                    onReplyToRequest(request, answer)
                },
                onDismiss = { answeringRequestId = null },
            )
        }
    }
}

/**
 * My Schedule's two views: the caller's own day, and the requests they raised.
 *
 * They are two views of one destination rather than two screens, so the control follows the office
 * schedule's lane selector (`docs/design/android-design-system.md`): a pill the width of its own
 * labels, the view in effect carrying the brand's container colour, and the requests segment carrying
 * how many of the caller's requests the office has still to answer — the count of what they are waiting
 * on (`BR-012`, `BR-FV-012`).
 */
@Composable
private fun TechnicianScheduleTabs(
    tab: TechnicianScheduleTab,
    awaitingReviewCount: Int,
    onSelectTab: (TechnicianScheduleTab) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = HomePageGutter)
            .height(HomeTouchTarget)
            .selectableGroup()
            .testTag(TechnicianScheduleTabSelectorTag),
        horizontalArrangement = Arrangement.spacedBy(TechnicianSegmentSpacing),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TechnicianScheduleTabSegment(
            label = stringResource(R.string.technician_schedule_tab_schedule),
            selected = tab == TechnicianScheduleTab.SCHEDULE,
            tag = TechnicianScheduleTabScheduleTag,
            onClick = { onSelectTab(TechnicianScheduleTab.SCHEDULE) },
        )
        TechnicianScheduleTabSegment(
            label = stringResource(R.string.technician_schedule_tab_requests),
            selected = tab == TechnicianScheduleTab.REQUESTS,
            badgeCount = awaitingReviewCount,
            tag = TechnicianScheduleTabRequestsTag,
            onClick = { onSelectTab(TechnicianScheduleTab.REQUESTS) },
        )
    }
}

/**
 * One view: the whole cell is the target, and only a [TechnicianControlVisualHeight] pill inside it is
 * drawn, so the control hits at 48 dp without weighing as much as what it holds (`BR-012`).
 */
@Composable
private fun TechnicianScheduleTabSegment(
    label: String,
    selected: Boolean,
    tag: String,
    onClick: () -> Unit,
    badgeCount: Int = 0,
) {
    Box(
        modifier = Modifier
            .fillMaxHeight()
            .clip(MaterialTheme.shapes.large)
            .selectable(
                selected = selected,
                role = Role.Tab,
                onClick = onClick,
            )
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.height(TechnicianControlVisualHeight),
            shape = MaterialTheme.shapes.large,
            color = if (selected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                Color.Transparent
            },
            contentColor = if (selected) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        ) {
            Row(
                modifier = Modifier
                    .fillMaxHeight()
                    .padding(horizontal = TechnicianTabSegmentPadding),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (badgeCount > 0) {
                    Spacer(Modifier.width(6.dp))
                    TechnicianCountBadge(count = badgeCount, selected = selected)
                }
            }
        }
    }
}

/**
 * The requests segment's count.
 *
 * It is information rather than an alarm — the answer is the office's to give, not the technician's —
 * so it is quiet while the view is not selected and takes the brand colour on the selected pill, where
 * it has to stay legible. It is drawn only when something is awaiting an answer (`BR-042`).
 */
@Composable
private fun TechnicianCountBadge(count: Int, selected: Boolean) {
    Surface(
        modifier = Modifier.testTag(TechnicianRequestsBadgeTag),
        shape = CircleShape,
        color = if (selected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.secondary
        },
        contentColor = if (selected) {
            MaterialTheme.colorScheme.onPrimary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
    ) {
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
        )
    }
}

/**
 * The header: the week the strip is on, the way between weeks and the way back to today.
 *
 * The week is named rather than the day, because the strip below it carries the days: the header says
 * which week is being read — `Sep 14–20` — and the selected day is stated by the strip and by the day
 * heading under it. The arrows move the strip a week at a time, which is the same movement a swipe
 * makes, so a technician who prefers a target to a gesture is not left out.
 */
@Composable
private fun TechnicianScheduleHeader(
    selectedDate: LocalDate,
    weekStart: LocalDate,
    today: LocalDate,
    locale: Locale,
    showsTodayAction: Boolean,
    onSelectDate: (LocalDate) -> Unit,
    onShowWeek: (LocalDate) -> Unit,
    onShowToday: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = HomePageGutter)) {
        Row(
            modifier = Modifier.fillMaxWidth().height(HomeTouchTarget),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                modifier = Modifier.testTag(TechnicianSchedulePreviousWeekTag),
                onClick = { onShowWeek(weekStart.minusWeeks(1)) },
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_chevron_left),
                    contentDescription = stringResource(
                        R.string.technician_schedule_previous_week,
                    ),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(TechnicianWeekArrowSize),
                )
            }
            Text(
                text = weekRangeLabel(weekRange(weekStart), locale),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .weight(1f)
                    .testTag(TechnicianScheduleWeekRangeTag),
            )
            IconButton(
                modifier = Modifier.testTag(TechnicianScheduleNextWeekTag),
                onClick = { onShowWeek(weekStart.plusWeeks(1)) },
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_chevron_right),
                    contentDescription = stringResource(R.string.technician_schedule_next_week),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(TechnicianWeekArrowSize),
                )
            }
            if (showsTodayAction) {
                TextButton(
                    modifier = Modifier.testTag(TechnicianScheduleTodayTag),
                    onClick = onShowToday,
                ) {
                    Text(stringResource(R.string.schedule_today))
                }
            }
        }

        ScheduleWeekStrip(
            selectedDate = selectedDate,
            displayedWeekStart = weekStart,
            today = today,
            locale = locale,
            onSelectDate = onSelectDate,
            onShowWeek = onShowWeek,
        )
        Spacer(Modifier.height(TechnicianLineSpacing))
    }
}

/**
 * The selected day: what the day is, whether it is the last reported one, and its Visits.
 *
 * The heading names the day with its date, so "today" is never a guess: the date and the weekday say
 * which day the agenda is about (`BR-028`, `BR-041`). A day whose Visits have all completed simply has
 * no emphasised row; a day with nothing assigned says so instead of showing an empty list (`BR-042`).
 */
@Composable
private fun TechnicianScheduleAgenda(
    state: TechnicianScheduleUiState,
    selectedDate: LocalDate,
    today: LocalDate,
    locale: Locale,
    onOpenJob: (String, String?) -> Unit,
) {
    val emphasizedVisitId = nextOutstandingVisitId(state.visits)
    val viewerMembershipId = state.viewerMembershipId

    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag(TechnicianScheduleContentTag),
        contentPadding = PaddingValues(
            start = HomePageGutter,
            end = HomePageGutter,
            bottom = HomePageGutter,
        ),
        verticalArrangement = Arrangement.spacedBy(TechnicianCardSpacing),
    ) {
        item(key = "day") {
            TechnicianDayHeading(
                selectedDate = selectedDate,
                today = today,
                locale = locale,
            )
        }

        if (state.showsLastReportedNotice) {
            // The day on screen is the last the backend reported rather than a current answer, and the
            // screen says so rather than presenting it as up to date (`BR-013`, offline standard §7).
            item(key = "last-reported") {
                OfflineNotice(
                    message = stringResource(R.string.offline_last_reported),
                    tag = TechnicianScheduleLastReportedTag,
                )
            }
        }

        if (state.showsEmptyDay) {
            item(key = "empty") { TechnicianScheduleEmptyDay() }
        } else {
            items(items = state.visits, key = { visit -> visit.visitId }) { visit ->
                TechnicianVisitCard(
                    visit = visit,
                    isEmphasized = visit.visitId == emphasizedVisitId,
                    viewerMembershipId = viewerMembershipId,
                    locale = locale,
                    onOpenJob = onOpenJob,
                )
            }
        }
    }
}

/**
 * The day the agenda is about, stated as the date it is.
 *
 * It is the strip's selection spelled out, so a technician who swiped away from today and tapped back
 * can see which day they are reading without counting cells (`BR-012`, `BR-041`).
 */
@Composable
private fun TechnicianDayHeading(
    selectedDate: LocalDate,
    today: LocalDate,
    locale: Locale,
) {
    val date = formattedDay(selectedDate, locale)
    val text = when (technicianDayHeading(selectedDate, today)) {
        TechnicianDayHeading.TODAY ->
            stringResource(R.string.technician_schedule_day_today, date)

        TechnicianDayHeading.TOMORROW ->
            stringResource(R.string.technician_schedule_day_tomorrow, date)

        TechnicianDayHeading.OTHER -> date
    }
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.testTag(TechnicianScheduleDayHeadingTag),
    )
}

/**
 * One Visit of the day (`BR-071`, `BR-072`).
 *
 * The card states what a technician needs before arriving, in the order they ask it (`BR-012`): the
 * time it starts, what the job is, whose it is, where it is, and then the status, the window and who is
 * coming. The whole card is the target and opens the Job the Visit belongs to, because acting on the
 * work is the Job Details screen's and the Visit's own routes (`BR-066`).
 *
 * The Visit that is next is the highlighted one, and the Visits already completed are drawn quieter —
 * a lower-contrast surface and muted lines — so the day reads "what is left" at a glance without
 * hiding what is done (`BR-012`). The emphasis is presentation only: no status is derived here, and the
 * pill states the API's own status (`BR-042`).
 */
@Composable
private fun TechnicianVisitCard(
    visit: ScheduleVisit,
    isEmphasized: Boolean,
    viewerMembershipId: String,
    locale: Locale,
    onOpenJob: (String, String?) -> Unit,
) {
    val completed = visit.visitStatus == VisitStatus.COMPLETED
    val container = if (completed) {
        MaterialTheme.colorScheme.surfaceVariant
    } else {
        MaterialTheme.colorScheme.surface
    }
    val border = if (isEmphasized) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outlineVariant
    }

    Row(modifier = Modifier.fillMaxWidth()) {
        // The start time keeps its own column, so a day is read down the times as well as across the
        // cards (`BR-012`).
        Text(
            text = visit.scheduledStart?.let { formatScheduledTime(it, locale) }
                ?: stringResource(R.string.home_schedule_time_unknown),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = if (completed) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            maxLines = 2,
            modifier = Modifier
                .width(TechnicianTimeGutter)
                .padding(top = TechnicianCardPadding, end = 8.dp),
        )

        Card(
            modifier = Modifier
                .weight(1f)
                .testTag(technicianScheduleVisitTag(visit.visitId))
                .clickable { onOpenJob(visit.jobId, visit.visitId) },
            colors = CardDefaults.cardColors(containerColor = container),
            border = BorderStroke(if (isEmphasized) 2.dp else 1.dp, border),
            shape = MaterialTheme.shapes.large,
        ) {
            Column(
                modifier = Modifier.padding(TechnicianCardPadding),
                verticalArrangement = Arrangement.spacedBy(TechnicianLineSpacing),
            ) {
                Text(
                    text = visit.jobTitle,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = customerPropertyLabel(visit),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // The preserved address the attempt is for, or no line at all when none exists: an
                // absent snapshot is an honest absence rather than a partly invented location
                // (`BR-056`, `BR-057`).
                addressLine(visit)?.let { address ->
                    Text(
                        text = address,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    HomeVisitStatusPill(
                        status = visit.visitStatus,
                        isOverdue = visit.isOverdue,
                    )
                    Spacer(Modifier.weight(1f))
                    scheduledWindow(visit, locale)?.let { window ->
                        Text(
                            text = window,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }

                TechnicianCrewLine(
                    visit = visit,
                    viewerMembershipId = viewerMembershipId,
                )
            }
        }
    }
}

/**
 * Who is coming (`BR-068`).
 *
 * A technician reading their own schedule is one of the crew, so the line says so — "You", or "You + 2"
 * when others are assigned with them — because "how many are coming with me?" is what the field asks.
 * The caller is identified by the membership the API resolved the read for rather than by a name the
 * device guessed (`BR-041`), and a crew member with no profile still counts as a person on the Visit
 * (`BR-020`).
 *
 * When the caller is not on the crew the line names the technicians who are, exactly as the office
 * schedule's crew line does, so one Visit's crew is never described two different ways (`BR-041`).
 */
@Composable
private fun TechnicianCrewLine(
    visit: ScheduleVisit,
    viewerMembershipId: String,
) {
    val crew = technicianCrew(visit, viewerMembershipId)
    val text = when {
        crew.isViewerOnCrew && crew.others == 0 ->
            stringResource(R.string.technician_schedule_you)

        crew.isViewerOnCrew -> stringResource(
            R.string.schedule_crew_more,
            stringResource(R.string.technician_schedule_you),
            crew.others,
        )

        else -> {
            val named = crewLine(names = crew.names)
            if (named.hidden > 0 && named.names.isNotEmpty()) {
                stringResource(
                    R.string.schedule_crew_more,
                    named.names.joinToString(separator = ", "),
                    named.hidden,
                )
            } else {
                techniciansSummary(names = named.names, crewSize = visit.technicians.size)
            }
        }
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            painter = painterResource(R.drawable.ic_users),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(TechnicianLineIconSize),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The first read: nothing has been answered for the day yet (`BR-013`). */
@Composable
private fun TechnicianScheduleLoading() {
    Box(
        modifier = Modifier.fillMaxSize().testTag(TechnicianScheduleLoadingTag),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(TechnicianCardSpacing),
        ) {
            CircularProgressIndicator()
            Text(
                text = stringResource(R.string.technician_schedule_loading),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Nothing could be read for this day and nothing is held locally.
 *
 * The day is named and the read can be retried, so a technician who lost connectivity can try again
 * where they are rather than having to leave the screen (`BR-012`, `BR-013`).
 */
@Composable
private fun TechnicianScheduleFailure(onRetry: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize().testTag(TechnicianScheduleFailureTag),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = HomePageGutter),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.technician_schedule_error_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(R.string.home_error_message),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(
                modifier = Modifier.testTag(TechnicianScheduleRetryTag),
                onClick = onRetry,
            ) {
                Text(stringResource(R.string.home_retry))
            }
        }
    }
}

/**
 * Nothing is assigned to the caller on this day.
 *
 * It is the read's own answer rather than an error (`BR-042`), so the screen says so plainly instead of
 * showing an empty list.
 */
@Composable
private fun TechnicianScheduleEmptyDay() {
    Surface(
        modifier = Modifier.fillMaxWidth().testTag(TechnicianScheduleEmptyDayTag),
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.large,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(vertical = 24.dp, horizontal = TechnicianCardPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = stringResource(R.string.technician_schedule_empty_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(R.string.technician_schedule_empty_detail),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The caller's own follow-up requests, newest first, and what the office decided about each of them.
 *
 * It is the technician's own record of a proposal they raised (`BR-FV-001`, `BR-FV-013`): the API
 * answers with the rows belonging to the caller's own membership, so no other technician's request is
 * reachable here and nothing is filtered by something the device chose (`BR-009`, `BR-007`). A request
 * is not a Visit and is not drawn as one (`BR-FV-002`) — the Visit an approval created is what the
 * schedule view shows (`BR-FV-005`) — and the list is read online only, so a read the backend could not
 * answer is reported rather than answered from a local copy (`BR-013`, offline standard §13).
 */
@Composable
private fun TechnicianRequests(
    state: TechnicianScheduleUiState,
    locale: Locale,
    zone: ZoneId,
    onAnswerRequest: (FollowUpVisitRequest) -> Unit,
    onOpenRequest: (String) -> Unit,
    onRetry: () -> Unit,
) {
    when {
        state.showsRequestsLoading -> TechnicianRequestsLoading()

        state.showsRequestsFailure -> TechnicianRequestsFailure(onRetry = onRetry)

        else -> LazyColumn(
            modifier = Modifier.fillMaxSize().testTag(TechnicianRequestsContentTag),
            contentPadding = PaddingValues(
                start = HomePageGutter,
                end = HomePageGutter,
                bottom = HomePageGutter,
            ),
            verticalArrangement = Arrangement.spacedBy(TechnicianCardSpacing),
        ) {
            if (state.showsRequestsUnrefreshedNotice) {
                // The list on screen is the last one the backend reported rather than a current
                // answer, and the screen says so instead of presenting it as current (`BR-013`).
                item(key = "unrefreshed") {
                    OfflineNotice(
                        message = stringResource(R.string.technician_requests_unrefreshed),
                        tag = TechnicianRequestsUnrefreshedTag,
                    )
                }
            }

            if (state.showsEmptyRequests) {
                item(key = "empty") { TechnicianRequestsEmpty() }
            } else {
                items(items = state.requests, key = { request -> request.id }) { request ->
                    TechnicianRequestCard(
                        request = request,
                        locale = locale,
                        zone = zone,
                        isReplying = state.isReplyingTo(request.id),
                        onAnswerRequest = onAnswerRequest,
                        onOpenRequest = onOpenRequest,
                    )
                }
            }
        }
    }
}

/** The first read of the caller's requests: nothing has been answered for them yet (`BR-013`). */
@Composable
private fun TechnicianRequestsLoading() {
    Box(
        modifier = Modifier.fillMaxSize().testTag(TechnicianRequestsLoadingTag),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(TechnicianCardSpacing),
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
 * The caller's requests could not be read.
 *
 * It is the honest answer rather than an empty list, and the read can be retried where the technician
 * is standing instead of by leaving the screen (`BR-012`, `BR-013`).
 */
@Composable
private fun TechnicianRequestsFailure(onRetry: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize().testTag(TechnicianRequestsFailureTag),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = HomePageGutter),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
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
                modifier = Modifier.testTag(TechnicianRequestsRetryTag),
                onClick = onRetry,
            ) {
                Text(stringResource(R.string.home_retry))
            }
        }
    }
}

/**
 * The caller has asked for no follow-up visit.
 *
 * It is the read's own answer rather than an error (`BR-042`), so the screen says so plainly instead of
 * showing an empty list.
 */
@Composable
private fun TechnicianRequestsEmpty() {
    Surface(
        modifier = Modifier.fillMaxWidth().testTag(TechnicianRequestsEmptyTag),
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.large,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(vertical = 24.dp, horizontal = TechnicianCardPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = stringResource(R.string.technician_requests_empty_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(R.string.technician_requests_empty_detail),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * One of the caller's own requests and the office's answer to it (`BR-FV-012`, `BR-FV-013`).
 *
 * The card states, in the order the technician asks it: what the office decided, the window the
 * technician proposed, why they said another attempt was needed, and — when the office answered with
 * words — what the office said, which is where a rejection's reason and a clarification's question are
 * read (`BR-FV-013`). The window is named as a **proposal**, because that is what it is: a request is not
 * an appointment, and only an approval creates a Visit (`BR-FV-002`, `BR-FV-010`) whose own schedule is
 * what the Schedule view shows (`BR-FV-005`).
 *
 * The whole card opens the request itself, because a request is a proposal about another field attempt
 * rather than the work (`BR-FV-002`): the request details destination is where it is read in full, and
 * the Job is one action away from it (`BR-066`). Customer, Job and address identity come from the
 * request projection, while the full clarification conversation stays on the details screen. When an
 * answer is owed, the card keeps the direct action that returns the request to the office (`BR-FV-012`).
 */
@Composable
private fun TechnicianRequestCard(
    request: FollowUpVisitRequest,
    locale: Locale,
    zone: ZoneId,
    isReplying: Boolean,
    onAnswerRequest: (FollowUpVisitRequest) -> Unit,
    onOpenRequest: (String) -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(technicianRequestTag(request.id))
            .clickable { onOpenRequest(request.id) },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(
            modifier = Modifier.padding(TechnicianCardPadding),
            verticalArrangement = Arrangement.spacedBy(TechnicianLineSpacing),
        ) {
            Text(
                text = request.customerName,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(
                    R.string.technician_request_job,
                    request.jobNumber,
                    request.jobTitle,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
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
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            RequestStatusPill(
                status = request.status,
                modifier = Modifier.testTag(technicianRequestStatusTag(request.id)),
            )
            RequestSummaryField(
                label = stringResource(R.string.technician_request_proposed_label),
                value = proposedWindow(request, zone, locale),
            )
            RequestSummaryField(
                label = stringResource(R.string.technician_request_reason_label),
                value = request.reason,
                maxLines = 3,
            )
            if (request.sameTechnicianPreferred) {
                RequestSameTechnicianLine()
            }
            // The office's own words are the decision's record (`BR-FV-013`), and they are the whole
            // answer to "why was my request refused?" — so they are drawn whenever the office wrote them
            // and the conversation does not already state them (`showsOfficeNote`).
            if (request.showsOfficeNote) {
                request.reviewNote?.takeIf { it.isNotBlank() }?.let { note ->
                    Text(
                        text = stringResource(R.string.technician_requests_note, note),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag(technicianRequestNoteTag(request.id)),
                    )
                }
            }
            // Answering is the technician's own move, and the only one that puts the request back in
            // front of the office (`BR-FV-012`), so the action is offered only while an answer is owed
            // and never twice while one is on its way (`BR-031`).
            if (request.awaitsAnswer) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(
                        modifier = Modifier.testTag(technicianRequestAnswerTag(request.id)),
                        enabled = !isReplying,
                        onClick = { onAnswerRequest(request) },
                    ) {
                        Text(stringResource(R.string.technician_request_answer_action))
                    }
                }
            }
        }
    }
}

@Composable
private fun RequestSummaryField(
    label: String,
    value: String,
    maxLines: Int = 2,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * What the technician proposed, stated as a proposal rather than as an appointment (`BR-FV-002`).
 *
 * A request that stated no window says so, instead of borrowing an appointment's vocabulary for a
 * proposal that never named a time (`BR-042`).
 *
 * Both surfaces that draw a request — its row in the list and the request details destination — state
 * the window this way, because it is one request's proposal and one vocabulary (`BR-041`).
 */
@Composable
internal fun proposedWindow(
    request: FollowUpVisitRequest,
    zone: ZoneId,
    locale: Locale,
): String {
    val window = formatRequestWindow(request.proposedStart, request.proposedEnd, zone, locale)
    return if (window == null) {
        stringResource(R.string.technician_requests_proposed_none)
    } else {
        window
    }
}

/**
 * The line a request carries when the technician asked to carry the follow-up themselves
 * (`BR-FV-003`).
 *
 * It states a preference the office decides about rather than an assignment: who performs the Visit is
 * the office's answer when it approves a request (`BR-FV-004`, `BR-068`), and both surfaces that draw
 * a request state the preference the same way (`BR-041`).
 */
@Composable
internal fun RequestSameTechnicianLine() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            painter = painterResource(R.drawable.ic_users),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(TechnicianLineIconSize),
        )
        Spacer(Modifier.width(RequestSameTechnicianLineIconGap))
        Text(
            text = stringResource(R.string.schedule_request_same_technician),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The Visit's own customer and property, or just the customer when no property name was preserved.
 *
 * The property is the location's own name, which is what a technician recognises on arrival; the
 * customer's name alone is the honest answer when the Property has none (`BR-049`, `BR-056`).
 */
@Composable
private fun customerPropertyLabel(visit: ScheduleVisit): String {
    val property = visit.address?.propertyName?.takeIf { it.isNotBlank() }
    return if (property == null) {
        visit.customerName
    } else {
        stringResource(R.string.schedule_customer_property_format, visit.customerName, property)
    }
}

/**
 * The address the attempt is for, as one line, or `null` when no part of it was preserved.
 *
 * It is the Visit's own preserved location, falling back to the Job's, which the API already resolved
 * (`BR-056`, `BR-057`): the device never assembles a location from a Customer record.
 */
private fun addressLine(visit: ScheduleVisit): String? =
    visit.address?.let {
        formattedAddressLine(
            addressLine1 = it.addressLine1,
            addressLine2 = it.addressLine2,
            city = it.city,
            province = it.province,
            postalCode = it.postalCode,
        )
    }

/** The Visit's scheduled window in the device's own language and zone, or `null` when it has none. */
private fun scheduledWindow(visit: ScheduleVisit, locale: Locale): String? =
    visit.scheduledStart?.let { start ->
        formatScheduledRange(start = start, end = visit.scheduledEnd, locale = locale)
    }

/**
 * The week as the header names it: its first day and its last, in the device's own language.
 *
 * A week inside one month reads `Sep 14–20`, because repeating the month says nothing; a week that
 * crosses a month names both dates, because the second one would otherwise be ambiguous (`BR-028`).
 */
@Composable
private fun weekRangeLabel(range: WeekRange, locale: Locale): String {
    val start = formattedMonthDay(range.start, locale)
    val end = if (range.crossesMonth) {
        formattedMonthDay(range.end, locale)
    } else {
        range.end.dayOfMonth.toString()
    }
    return stringResource(R.string.technician_schedule_week_range, start, end)
}

/** A day as the heading names it: its weekday and its date, in the device's own language. */
private fun formattedDay(date: LocalDate, locale: Locale): String {
    val pattern = DateFormat.getBestDateTimePattern(locale, "EEEEMMMd")
    return date.format(DateTimeFormatter.ofPattern(pattern, locale))
}

/** A month and a day, in the order the language writes them (`BR-028`). */
private fun formattedMonthDay(date: LocalDate, locale: Locale): String {
    val pattern = DateFormat.getBestDateTimePattern(locale, "MMMd")
    return date.format(DateTimeFormatter.ofPattern(pattern, locale))
}
