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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.servora.android.R
import com.servora.android.domain.model.ScheduleVisit
import com.servora.android.domain.model.VisitStatus
import com.servora.android.ui.components.OfflineNotice
import com.servora.android.ui.home.HomePageGutter
import com.servora.android.ui.home.HomeTouchTarget
import com.servora.android.ui.home.HomeVisitStatusPill
import com.servora.android.ui.home.deviceLocale
import com.servora.android.ui.home.formatScheduledRange
import com.servora.android.ui.home.formatScheduledTime
import com.servora.android.ui.home.formattedAddressLine
import com.servora.android.ui.home.techniciansSummary
import java.time.LocalDate
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
private val TechnicianWeekArrowSize = 20.dp

/**
 * The technician's schedule: the week strip, the selected day's heading and its agenda.
 *
 * Everything the screen does is driven by the state it is handed and reported back through the
 * callbacks, so it holds no scheduling state of its own: a configuration change returns to the day and
 * week the technician left it on. The device's own day is read once here and handed to the parts that
 * need it, so the week strip's "today", the day heading and the way back to today cannot disagree about
 * which day the technician is living through (`BR-041`).
 */
@Composable
fun TechnicianScheduleScreen(
    state: TechnicianScheduleUiState,
    onSelectDate: (LocalDate) -> Unit,
    onShowWeek: (LocalDate) -> Unit,
    onShowToday: () -> Unit,
    onOpenJob: (String) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val zone = remember(state.timeZoneId) { zoneOf(state.timeZoneId) }
    val today = remember(zone) { LocalDate.now(zone) }
    val locale = deviceLocale()
    val selectedDate = state.selectedDate
    val weekStart = state.displayedWeekStart

    Column(modifier = modifier.fillMaxSize()) {
        // The header stays while the day scrolls: another day is one tap away, and a swipe between
        // weeks never needs a scroll back (`BR-012`).
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
    onOpenJob: (String) -> Unit,
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
    onOpenJob: (String) -> Unit,
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
                .clickable { onOpenJob(visit.jobId) },
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
