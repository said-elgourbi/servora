package com.servora.android.ui.schedule

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/*
 * The week strip: the seven days of one week, with the selected day standing out.
 *
 * It was the manager schedule's own control and is now shared, because both schedules navigate days
 * the same way: a strip of the nearby ones plus the week the selection falls in. The two screens
 * present different work, but which day a swipe reaches and what a tap selects is one question, and a
 * second copy of this pager would let the two answers drift (`dev.md` §1).
 */

/** Identifies the week strip. */
const val ScheduleWeekStripTag = "schedule-week-strip"

fun scheduleDayTag(date: LocalDate): String = "schedule-day-$date"

/**
 * The height the strip takes when it is shown.
 *
 * It is internal because the manager schedule collapses the strip once its agenda is scrolled, so the
 * height that animation runs to is the height the strip is drawn at (`BR-012`).
 */
internal val ScheduleWeekStripHeight = 48.dp

private val ScheduleDayCellWidth = 44.dp
private val ScheduleDayCellHeight = 48.dp
private val ScheduleDayMarkerSize = 5.dp

/*
 * The strip is a horizontal pager of weeks, so a reader swipes through nearby weeks the way the
 * design asks for. The anchor page is the week the screen opened on; a week is one page, so the page
 * a week sits on is always `anchor + weeks between them`, and the page count leaves the calendar's
 * own reach either side of it.
 */
private const val ScheduleWeekAnchorPage = 10_000
private const val ScheduleWeekPageCount = 20_001

/**
 * The seven days of one week, with the selected day standing out.
 *
 * The strip is a pager of weeks: swiping moves it a week at a time in either direction, so the days
 * around the selected one are always one gesture away and the month calendar is not needed to reach
 * them. Tapping a day selects it and shows its agenda; swiping does not change the selected day, so
 * the reader's place is never moved by a gesture they did not mean as a choice
 * (`Figma/src/imports/pasted_text/servora-scheduler-design.md`).
 */
@Composable
internal fun ScheduleWeekStrip(
    selectedDate: LocalDate,
    displayedWeekStart: LocalDate,
    today: LocalDate,
    locale: Locale,
    onSelectDate: (LocalDate) -> Unit,
    onShowWeek: (LocalDate) -> Unit,
) {
    // The week the strip opened on: a page is a week away from it, so the mapping between pages and
    // weeks never depends on the selected day.
    val anchorWeek = remember { displayedWeekStart }
    val pagerState = rememberPagerState(initialPage = ScheduleWeekAnchorPage) {
        ScheduleWeekPageCount
    }

    // The strip follows the state: a date chosen elsewhere — another week's Today action, another
    // screen — brings its week into view.
    LaunchedEffect(displayedWeekStart) {
        val target = ScheduleWeekAnchorPage +
            ChronoUnit.WEEKS.between(anchorWeek, displayedWeekStart).toInt()
        if (target != pagerState.settledPage) {
            pagerState.animateScrollToPage(target)
        }
    }
    // A swipe reports the week it landed on, so the screen's own idea of the strip stays in step
    // with it and a later recomposition does not scroll back.
    LaunchedEffect(pagerState, anchorWeek) {
        snapshotFlow { pagerState.settledPage }.collect { page ->
            onShowWeek(anchorWeek.plusWeeks((page - ScheduleWeekAnchorPage).toLong()))
        }
    }

    Box(modifier = Modifier.clipToBounds()) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxWidth()
                .height(ScheduleWeekStripHeight)
                .testTag(ScheduleWeekStripTag),
        ) { page ->
            val week = anchorWeek.plusWeeks((page - ScheduleWeekAnchorPage).toLong())
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                weekDaysOf(week).forEach { date ->
                    ScheduleDayCell(
                        date = date,
                        selected = date == selectedDate,
                        isToday = date == today,
                        locale = locale,
                        onClick = { onSelectDate(date) },
                    )
                }
            }
        }
    }
}

/**
 * One day of the strip: its weekday, its date, and the marker that says it is today.
 *
 * The selected day is filled with the brand colour so it is unmistakable among its neighbours; a day
 * that is not selected keeps the page background, and today carries a dot rather than a second fill
 * so "today" and "selected" stay two different facts (`docs/design/android-design-system.md`).
 */
@Composable
private fun ScheduleDayCell(
    date: LocalDate,
    selected: Boolean,
    isToday: Boolean,
    locale: Locale,
    onClick: () -> Unit,
) {
    val background = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        Color.Transparent
    }
    val onBackground = if (selected) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val marker = when {
        isToday && selected -> MaterialTheme.colorScheme.onPrimary
        isToday -> MaterialTheme.colorScheme.primary
        else -> Color.Transparent
    }

    Column(
        modifier = Modifier
            .width(ScheduleDayCellWidth)
            .height(ScheduleDayCellHeight)
            .clip(MaterialTheme.shapes.large)
            .background(background)
            .clickable(onClick = onClick)
            .testTag(scheduleDayTag(date)),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = formattedWeekday(date, locale),
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) onBackground else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        Text(
            text = date.dayOfMonth.toString(),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = onBackground,
        )
        Box(
            modifier = Modifier
                .size(ScheduleDayMarkerSize)
                .clip(CircleShape)
                .background(marker),
        )
    }
}

/** A weekday's abbreviated name, in the language the device is set to (`BR-028`). */
private fun formattedWeekday(date: LocalDate, locale: Locale): String {
    val pattern = DateFormat.getBestDateTimePattern(locale, "EEE")
    return date.format(DateTimeFormatter.ofPattern(pattern, locale))
}
