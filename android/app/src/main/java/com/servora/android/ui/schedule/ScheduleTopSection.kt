package com.servora.android.ui.schedule

import android.text.format.DateFormat
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.servora.android.R
import com.servora.android.domain.model.ScheduleTechnician
import com.servora.android.ui.home.HomeChevronSize
import com.servora.android.ui.home.HomePageGutter
import com.servora.android.ui.home.HomeTouchTarget
import com.servora.android.ui.home.deviceLocale
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/*
 * The schedule's top section: the date header, the week strip and the compact control row.
 *
 * The section is arranged by what a dispatcher needs in what order. The agenda is the screen's
 * primary content, so the controls above it are deliberately as light and as short as a 48 dp touch
 * target allows (`BR-012`): a date that is a date selector, a week strip that is the nearby-date
 * navigation, and one row holding the two things that narrow the day — the lane being read and the
 * technicians shown.
 *
 * The strip collapses once the agenda is scrolled (the screen hands that in): the date, its Today
 * action and the control row all stay, so moving to another day or another lane never needs a scroll
 * back (`BR-012`).
 *
 * The strip is the primary way to reach a nearby day and the header is a date selector, so the month
 * calendar is only a jump-to-date mechanism and never a second scheduling surface
 * (`Figma/src/imports/pasted_text/servora-scheduler-design.md`).
 */

/** Identifies the date header, the month calendar it opens and the way back to today. */
const val ScheduleDateHeaderTag = "schedule-date-header"
const val ScheduleDatePickerTag = "schedule-date-picker"
const val ScheduleTodayTag = "schedule-today"

/** Identifies the compact control row: the Schedule / Unassigned selector and its count badge. */
const val ScheduleLaneSelectorTag = "schedule-lane-selector"
const val ScheduleLaneScheduleTag = "schedule-lane-schedule"
const val ScheduleLaneUnassignedTag = "schedule-lane-unassigned"
const val ScheduleLaneRequestsTag = "schedule-lane-requests"
const val ScheduleUnassignedBadgeTag = "schedule-unassigned-badge"
const val ScheduleRequestsBadgeTag = "schedule-requests-badge"

/** Identifies the technician filter, the sheet it opens, the options and the sheet's own actions. */
const val ScheduleTechnicianFilterTag = "schedule-technician-filter"
const val ScheduleTechnicianSheetTag = "schedule-technician-sheet"
const val ScheduleTechnicianAllTag = "schedule-technician-all"
const val ScheduleTechnicianSearchTag = "schedule-technician-search"
const val ScheduleTechnicianEmptyTag = "schedule-technician-empty"
const val ScheduleTechnicianApplyTag = "schedule-technician-apply"
const val ScheduleTechnicianClearTag = "schedule-technician-clear"

fun scheduleTechnicianOptionTag(membershipId: String): String =
    "schedule-technician-$membershipId"

/*
 * Metrics, from `docs/design/android-design-system.md`: a touch target is at least 48 dp, and the
 * height a control takes is height the day's work does not get.
 *
 * The two are reconciled the Material way: the *target* is 48 dp and the *surface* drawn inside it is
 * smaller, so the selector, the filter chip and a day cell all hit comfortably while reading as
 * controls rather than as buttons. The strip is the tallest part of the section, and it is the part
 * that collapses once the agenda is scrolled.
 */
private val ScheduleControlVisualHeight = 32.dp
private val ScheduleFilterIconSize = 16.dp
private val ScheduleSegmentSpacing = 2.dp
private val ScheduleSegmentPadding = 10.dp
private val ScheduleControlsSpacing = 8.dp
private val ScheduleSectionSpacing = 4.dp
private val ScheduleSearchHeight = 44.dp
private val ScheduleFieldHeight = 48.dp
private val ScheduleSheetSpacing = 12.dp
private val ScheduleSheetHeaderPadding = 12.dp
private val ScheduleTechnicianListHeight = 320.dp
private const val ScheduleSelectedRowAlpha = 0.10f

/** The zone the device named, or the device's own when an identifier cannot be resolved. */
internal fun zoneOf(timeZoneId: String): ZoneId =
    runCatching { ZoneId.of(timeZoneId) }.getOrElse { ZoneId.systemDefault() }

/**
 * The date header, the week strip and the compact control row.
 *
 * It is the persistent top of the screen: the agenda underneath scrolls on its own, so the day and
 * the controls that change it never scroll away and a dispatcher can always move to another date,
 * another lane or another technician without first scrolling back (`BR-012`).
 *
 * [collapsed] is the screen's answer to "has the agenda been scrolled?", handed in rather than
 * decided here so the section owns no scrolling of its own. The **week strip** is what collapses: the
 * selected date, its Today action and the control row all stay, so the day is still named and still
 * changeable while the agenda has the screen.
 *
 * [today] is the device's own day, read once by the screen, so the strip's "today" marker and the
 * agenda's "now" cue are the same day (`BR-041`).
 */
@Composable
internal fun ScheduleTopSection(
    state: ScheduleUiState,
    selectedDate: LocalDate,
    today: LocalDate,
    collapsed: Boolean,
    onSelectDate: (LocalDate) -> Unit,
    onShowWeek: (LocalDate) -> Unit,
    onSelectLane: (ScheduleLane) -> Unit,
    onApplyTechnicians: (List<ScheduleTechnician>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val locale = deviceLocale()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = HomePageGutter),
    ) {
        ScheduleDateHeader(
            selectedDate = selectedDate,
            locale = locale,
            showsTodayAction = selectedDate != today,
            onSelectDate = onSelectDate,
            onSelectToday = { onSelectDate(today) },
        )
        // The strip keeps its state while it is collapsed: the pager stays composed and is clipped away
        // rather than disposed, so expanding it returns to the week the manager was on instead of
        // scrolling forward from the anchor week.
        val stripHeight by animateDpAsState(
            targetValue = if (collapsed) 0.dp else ScheduleWeekStripHeight,
            label = "schedule-week-strip",
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(stripHeight)
                .clipToBounds()
                // A collapsed strip is not merely invisible: its days are unreachable while it is away,
                // so a screen reader never offers a date nobody can see (`BR-012`).
                .then(if (collapsed) Modifier.clearAndSetSemantics {} else Modifier),
        ) {
            ScheduleWeekStrip(
                selectedDate = selectedDate,
                displayedWeekStart = state.displayedWeekStart ?: selectedDate,
                today = today,
                locale = locale,
                onSelectDate = onSelectDate,
                onShowWeek = onShowWeek,
            )
        }
        Spacer(Modifier.height(ScheduleSectionSpacing))
        ScheduleControlRow(
            lane = state.lane,
            unassignedCount = state.unassignedCount,
            requestCount = state.pendingRequestCount,
            selected = state.technicianFilters,
            technicians = state.schedule?.technicians.orEmpty(),
            onSelectLane = onSelectLane,
            onApplyTechnicians = onApplyTechnicians,
        )
    }
}

/**
 * The two controls that narrow the day, on one row: which lane is being read, and whose work is
 * shown.
 *
 * They are one row because they are both secondary to the agenda, and because 48 dp is the smallest
 * a control can be and still be hit (`BR-012`). The target is [HomeTouchTarget] and each control
 * draws a smaller surface inside its own target, so the row reads as filtering rather than as a pair
 * of actions.
 */
@Composable
private fun ScheduleControlRow(
    lane: ScheduleLane,
    unassignedCount: Int,
    requestCount: Int,
    selected: List<ScheduleTechnician>,
    technicians: List<ScheduleTechnician>,
    onSelectLane: (ScheduleLane) -> Unit,
    onApplyTechnicians: (List<ScheduleTechnician>) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(HomeTouchTarget),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ScheduleLaneSelector(
            lane = lane,
            unassignedCount = unassignedCount,
            requestCount = requestCount,
            onSelectLane = onSelectLane,
        )
        Spacer(Modifier.width(ScheduleControlsSpacing))
        ScheduleTechnicianFilter(
            selected = selected,
            technicians = technicians,
            onApply = onApplyTechnicians,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * The selected day, as the way to any other date.
 *
 * Tapping it opens the platform's month calendar, which is the jump-to-date mechanism: choosing a
 * date there closes it, moves the strip to that date's week and shows its agenda
 * (`Figma/src/imports/pasted_text/servora-scheduler-design.md`). The day is also named in full, so
 * the header itself answers which day the agenda describes.
 *
 * "Today" is offered only when another day is selected, and it is not a second calendar: it selects
 * the day the device is in, which the strip then shows (`BR-042`).
 */
@Composable
private fun ScheduleDateHeader(
    selectedDate: LocalDate,
    locale: Locale,
    showsTodayAction: Boolean,
    onSelectDate: (LocalDate) -> Unit,
    onSelectToday: () -> Unit,
) {
    var pickingDate by rememberSaveable { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = HomeTouchTarget),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .clip(MaterialTheme.shapes.medium)
                .clickable { pickingDate = true }
                .testTag(ScheduleDateHeaderTag)
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = formattedDate(selectedDate, locale),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.width(6.dp))
            Icon(
                painter = painterResource(R.drawable.ic_chevron_down),
                contentDescription = stringResource(R.string.schedule_date_pick),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(HomeChevronSize),
            )
        }
        if (showsTodayAction) {
            TextButton(onClick = onSelectToday, modifier = Modifier.testTag(ScheduleTodayTag)) {
                Text(stringResource(R.string.schedule_today))
            }
        }
    }

    if (pickingDate) {
        ScheduleDatePickerDialog(
            selectedDate = selectedDate,
            onDismiss = { pickingDate = false },
            onSelect = { date ->
                pickingDate = false
                onSelectDate(date)
            },
        )
    }
}

/**
 * The platform's month calendar, used only to jump to a date.
 *
 * The picker works in UTC calendar dates, so the chosen value is read back as the date it names
 * rather than converted through a zone: the API resolves the day's instants from the date
 * (`docs/api/schedule.md`), and shifting it by this device's offset would select a different day.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScheduleDatePickerDialog(
    selectedDate: LocalDate,
    onDismiss: () -> Unit,
    onSelect: (LocalDate) -> Unit,
) {
    val pickerState = rememberDatePickerState(
        initialSelectedDateMillis = selectedDate
            .atStartOfDay(ZoneOffset.UTC)
            .toInstant()
            .toEpochMilli(),
    )

    DatePickerDialog(
        modifier = Modifier.testTag(ScheduleDatePickerTag),
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    val chosen = pickerState.selectedDateMillis
                    if (chosen == null) {
                        onDismiss()
                    } else {
                        onSelect(millisToLocalDate(chosen))
                    }
                },
            ) {
                Text(stringResource(R.string.schedule_date_picker_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.schedule_date_picker_cancel))
            }
        },
    ) {
        DatePicker(state = pickerState)
    }
}

/** The calendar date the date picker's UTC millisecond value names. */
private fun millisToLocalDate(millis: Long): LocalDate =
    Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()

/** A date in the order the language writes it, resolved by the platform (`BR-028`). */
private fun formattedDate(date: LocalDate, locale: Locale): String {
    val pattern = DateFormat.getBestDateTimePattern(locale, "MMMMdyyyy")
    return date.format(DateTimeFormatter.ofPattern(pattern, locale))
}

/**
 * The Schedule / Unassigned selector, as a compact segmented control.
 *
 * Both are the manager's own views of the same read: the day's scheduled Visits, and the work that
 * still has no crew. They are two views of one screen rather than two actions, so the control is a
 * pill the width of its own labels: the lane in effect carries the brand's container colour and the
 * other one is quiet text. The Unassigned segment carries the backend's count when there is any,
 * because "what still needs somebody?" is the question that must not be missed (`BR-042`).
 */
@Composable
private fun ScheduleLaneSelector(
    lane: ScheduleLane,
    unassignedCount: Int,
    requestCount: Int,
    onSelectLane: (ScheduleLane) -> Unit,
) {
    Row(
        modifier = Modifier
            .height(HomeTouchTarget)
            .selectableGroup()
            .testTag(ScheduleLaneSelectorTag),
        horizontalArrangement = Arrangement.spacedBy(ScheduleSegmentSpacing),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ScheduleLaneSegment(
            label = stringResource(R.string.schedule_lane_schedule),
            selected = lane == ScheduleLane.SCHEDULE,
            tag = ScheduleLaneScheduleTag,
            onClick = { onSelectLane(ScheduleLane.SCHEDULE) },
        )
        ScheduleLaneSegment(
            label = stringResource(R.string.schedule_lane_unassigned),
            selected = lane == ScheduleLane.UNASSIGNED,
            badgeCount = unassignedCount,
            badgeTag = ScheduleUnassignedBadgeTag,
            tag = ScheduleLaneUnassignedTag,
            onClick = { onSelectLane(ScheduleLane.UNASSIGNED) },
        )
        ScheduleLaneSegment(
            label = stringResource(R.string.schedule_lane_requests),
            selected = lane == ScheduleLane.REQUESTS,
            badgeCount = requestCount,
            badgeTag = ScheduleRequestsBadgeTag,
            tag = ScheduleLaneRequestsTag,
            onClick = { onSelectLane(ScheduleLane.REQUESTS) },
        )
    }
}

/**
 * One lane: the whole cell is the target, and only a [ScheduleControlVisualHeight] pill inside it is
 * drawn, so the control hits at 48 dp without weighing as much as what it holds (`BR-012`).
 */
@Composable
private fun ScheduleLaneSegment(
    label: String,
    selected: Boolean,
    tag: String,
    onClick: () -> Unit,
    badgeCount: Int = 0,
    badgeTag: String = ScheduleUnassignedBadgeTag,
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
            modifier = Modifier.height(ScheduleControlVisualHeight),
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
                    .padding(horizontal = ScheduleSegmentPadding),
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
                    ScheduleCountBadge(count = badgeCount, selected = selected, tag = badgeTag)
                }
            }
        }
    }
}

/**
 * The Unassigned segment's count.
 *
 * It is the error role while the segment is not selected — the work needs somebody — and the brand
 * colour while it is, so the badge stays legible on the selected pill.
 */
@Composable
private fun ScheduleCountBadge(count: Int, selected: Boolean, tag: String) {
    Surface(
        modifier = Modifier.testTag(tag),
        shape = CircleShape,
        color = if (selected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.error
        },
        contentColor = if (selected) {
            MaterialTheme.colorScheme.onPrimary
        } else {
            MaterialTheme.colorScheme.onError
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
 * The technician filter: a compact chip that states the filter in effect and opens the sheet that
 * changes it.
 *
 * The day is the whole organization by default and the selected technicians' work once any are
 * chosen, which is the question a dispatcher asks most after "what is happening today". The chip
 * summarises rather than listing — one name, or how many (`BR-012`) — and a filter that is in effect
 * is visible without opening anything: the chip takes the brand's container colour and its icon goes
 * with it, so a manager can tell at a glance that the day is narrowed (`BR-042`).
 */
@Composable
private fun ScheduleTechnicianFilter(
    selected: List<ScheduleTechnician>,
    technicians: List<ScheduleTechnician>,
    onApply: (List<ScheduleTechnician>) -> Unit,
    modifier: Modifier = Modifier,
) {
    var choosing by rememberSaveable { mutableStateOf(false) }
    val summary = technicianFilterSummary(selected)
    val active = summary !is TechnicianFilterSummary.All

    Box(
        modifier = modifier
            .height(HomeTouchTarget)
            .clip(MaterialTheme.shapes.large)
            .clickable { choosing = true }
            .testTag(ScheduleTechnicianFilterTag),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(ScheduleControlVisualHeight),
            shape = MaterialTheme.shapes.large,
            color = if (active) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.secondary
            },
            contentColor = if (active) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSecondary
            },
            border = if (active) {
                null
            } else {
                BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            },
        ) {
            Row(
                modifier = Modifier
                    .fillMaxHeight()
                    .padding(horizontal = ScheduleSegmentPadding),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_users),
                    contentDescription = null,
                    modifier = Modifier.size(ScheduleFilterIconSize),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = when (summary) {
                        TechnicianFilterSummary.All ->
                            stringResource(R.string.schedule_filter_all)

                        is TechnicianFilterSummary.One ->
                            summary.technician.name
                                ?: stringResource(R.string.schedule_filter_unnamed)

                        is TechnicianFilterSummary.Many -> pluralStringResource(
                            R.plurals.schedule_filter_selected_technicians,
                            summary.count,
                            summary.count,
                        )
                    },
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(4.dp))
                Icon(
                    painter = painterResource(R.drawable.ic_chevron_down),
                    contentDescription = stringResource(R.string.schedule_filter_open),
                    modifier = Modifier.size(HomeChevronSize),
                )
            }
        }
    }

    if (choosing) {
        ScheduleTechnicianSheet(
            selected = selected,
            technicians = technicians,
            onApply = { chosen ->
                // Applying commits the draft and closes: the day the manager asked for is the answer,
                // and the sheet standing over it would hide the change it just asked for (`BR-012`).
                // Clear is the exception, because it asks for the whole organization and leaves the
                // sheet open so a different filter can be chosen without reopening it (`BR-067`).
                choosing = false
                onApply(chosen)
            },
            onDismiss = { choosing = false },
        )
    }
}


/**
 * The filter's own list, as a selection list: the whole organization first, then the organization's
 * technicians, each with a checkbox and the whole row as the target.
 *
 * The options are the API's own list for the day's read (`BR-024`, `BR-068`), so the filter can only
 * offer work the caller can already see and the screen never decides for itself who is a technician
 * (`BR-042`).
 *
 * The selection is a **draft**: tapping a technician toggles them and nothing is read until the
 * manager applies, so one technician, a small group or the whole team can be chosen before the day is
 * asked for again (`BR-012`). The search narrows the options that are offered and never the selection
 * that was made, so choosing somebody and then searching for somebody else keeps both. A company with
 * many technicians stays usable because the list scrolls on its own with the actions still below it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScheduleTechnicianSheet(
    selected: List<ScheduleTechnician>,
    technicians: List<ScheduleTechnician>,
    onApply: (List<ScheduleTechnician>) -> Unit,
    onDismiss: () -> Unit,
) {
    // Opening the sheet states the filter that is in effect; the draft is what the manager leaves
    // behind, so a dismissed sheet changes nothing (`BR-067`).
    var draftIds by remember { mutableStateOf(selected.map { it.membershipId }) }
    var query by remember { mutableStateOf("") }
    val options = remember(technicians, draftIds, query) {
        orderTechnicianOptions(
            technicians = technicians,
            selectedIds = draftIds.toSet(),
            query = query,
        )
    }

    ModalBottomSheet(
        modifier = Modifier.testTag(ScheduleTechnicianSheetTag),
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(modifier = Modifier.imePadding()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = HomePageGutter,
                        end = ScheduleSheetHeaderPadding,
                        top = ScheduleSheetHeaderPadding,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.schedule_filter_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = stringResource(R.string.schedule_filter_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(
                        painter = painterResource(R.drawable.ic_close),
                        contentDescription = stringResource(R.string.schedule_filter_close),
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(HomeChevronSize),
                    )
                }
            }

            ScheduleTechnicianSearch(query = query, onQueryChange = { query = it })
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = ScheduleTechnicianListHeight)
                    .padding(horizontal = HomePageGutter),
            ) {
                // The whole organization stays offered while searching: clearing a filter is not the
                // same question as finding a technician, and a search must never be the only way back.
                item(key = ScheduleTechnicianAllTag) {
                    ScheduleTechnicianOption(
                        label = stringResource(R.string.schedule_filter_all),
                        selected = draftIds.isEmpty(),
                        tag = ScheduleTechnicianAllTag,
                        // `All technicians` is no selection at all, so choosing it clears the draft
                        // rather than naming everybody (`BR-068`).
                        onClick = { draftIds = emptyList() },
                    )
                }
                if (options.isEmpty() && query.isNotBlank()) {
                    item(key = ScheduleTechnicianEmptyTag) {
                        Text(
                            text = stringResource(R.string.schedule_filter_no_results),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag(ScheduleTechnicianEmptyTag)
                                .padding(vertical = 16.dp),
                        )
                    }
                }
                items(items = options, key = { it.membershipId }) { technician ->
                    ScheduleTechnicianOption(
                        label = technician.name
                            ?: stringResource(R.string.schedule_filter_unnamed),
                        selected = technician.membershipId in draftIds,
                        tag = scheduleTechnicianOptionTag(technician.membershipId),
                        // Choosing a technician while the whole organization is shown switches to
                        // that explicit selection: `All` and a name cannot both be in effect.
                        onClick = {
                            draftIds = if (technician.membershipId in draftIds) {
                                draftIds - technician.membershipId
                            } else {
                                draftIds + technician.membershipId
                            }
                        },
                    )
                }
            }

            Spacer(Modifier.height(ScheduleSheetSpacing))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = HomePageGutter, vertical = ScheduleSheetHeaderPadding),
                horizontalArrangement = Arrangement.spacedBy(ScheduleSheetSpacing),
            ) {
                if (draftIds.isNotEmpty()) {
                    OutlinedButton(
                        modifier = Modifier
                            .weight(1f)
                            .height(ScheduleFieldHeight)
                            .testTag(ScheduleTechnicianClearTag),
                        onClick = {
                            // Clear empties the draft and asks for the whole organization at once,
                            // staying open so a different filter can be chosen — the route the
                            // customer filter sheet already takes, so both screens behave alike.
                            draftIds = emptyList()
                            onApply(emptyList())
                        },
                        shape = MaterialTheme.shapes.large,
                    ) {
                        Text(stringResource(R.string.schedule_filter_clear))
                    }
                }
                Button(
                    modifier = Modifier
                        .weight(1f)
                        .height(ScheduleFieldHeight)
                        .testTag(ScheduleTechnicianApplyTag),
                    onClick = { onApply(chosenTechnicians(technicians, draftIds)) },
                    shape = MaterialTheme.shapes.large,
                ) {
                    Text(
                        text = if (draftIds.isEmpty()) {
                            stringResource(R.string.schedule_filter_apply)
                        } else {
                            stringResource(R.string.schedule_filter_apply_count, draftIds.size)
                        },
                    )
                }
            }
        }
    }
}

/**
 * The filter's search field.
 *
 * It is a filled field rather than an outlined one: it narrows the list directly beneath it, so it
 * reads as part of that list instead of as a form of its own
 * (`docs/design/android-design-system.md`).
 */
@Composable
private fun ScheduleTechnicianSearch(query: String, onQueryChange: (String) -> Unit) {
    TextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = ScheduleSearchHeight)
            .testTag(ScheduleTechnicianSearchTag)
            .padding(horizontal = HomePageGutter, vertical = 8.dp),
        singleLine = true,
        shape = MaterialTheme.shapes.large,
        placeholder = { Text(stringResource(R.string.schedule_filter_search)) },
        leadingIcon = {
            Icon(
                painter = painterResource(R.drawable.ic_search),
                contentDescription = null,
                modifier = Modifier.size(ScheduleFilterIconSize),
            )
        },
        colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.secondary,
            unfocusedContainerColor = MaterialTheme.colorScheme.secondary,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
        ),
    )
}

/**
 * One option of the filter sheet: a checkbox, a name, and the whole row as the target.
 *
 * The row owns the selection and its checkbox reports it rather than taking the tap, so a technician
 * is chosen anywhere along the row (`BR-012`). `All technicians` is a row like any other: the
 * selection pattern says it is in effect, not a different colour of text.
 */
@Composable
private fun ScheduleTechnicianOption(
    label: String,
    selected: Boolean,
    tag: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = HomeTouchTarget)
            .clip(MaterialTheme.shapes.medium)
            .background(
                if (selected) {
                    MaterialTheme.colorScheme.primary.copy(alpha = ScheduleSelectedRowAlpha)
                } else {
                    Color.Transparent
                },
            )
            .toggleable(
                value = selected,
                role = Role.Checkbox,
                onValueChange = { onClick() },
            )
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = selected, onCheckedChange = null)
        Spacer(Modifier.width(12.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
