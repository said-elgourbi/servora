package com.servora.android.ui.home

import android.text.format.DateFormat
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.servora.android.R
import com.servora.android.domain.model.ManagerAttentionItem
import com.servora.android.domain.model.ManagerAttentionKind
import com.servora.android.domain.model.ManagerHome
import com.servora.android.domain.model.ManagerHomeAddress
import com.servora.android.domain.model.ManagerHomeTodaySummary
import com.servora.android.domain.model.ManagerHomeVisit
import com.servora.android.domain.model.VisitStatus
import com.servora.android.ui.components.InfoCard
import com.servora.android.ui.components.SectionLabel
import com.servora.android.ui.theme.stateColors
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.FormatStyle
import java.util.Locale

/** Identifies the populated home, so a test can assert the state it must show. */
const val ManagerHomeContentTag = "manager-home-content"

/** Identifies the first-read loading state. */
const val ManagerHomeLoadingTag = "manager-home-loading"

/** Identifies the state shown when nothing could be read at all. */
const val ManagerHomeFailureTag = "manager-home-failure"

/** Identifies the retry action of the failure and unrefreshed states. */
const val ManagerHomeRetryTag = "manager-home-retry"

/** Identifies the notice shown while cached content is on screen but could not be refreshed. */
const val ManagerHomeUnrefreshedTag = "manager-home-unrefreshed"

/** Identifies the subtle indicator shown while cached content is refreshed behind the scenes. */
const val ManagerHomeRefreshingTag = "manager-home-refreshing"

/** Identifies the positive "nothing needs you" state (`home_attention_all_clear_*`). */
const val ManagerHomeAllClearTag = "manager-home-all-clear"

/** Identifies the Today summary card. */
const val ManagerHomeTodayTag = "manager-home-today"

/** Identifies the "See all" action of today's schedule. */
const val ManagerHomeSeeAllTag = "manager-home-see-all"

/** Identifies the empty state of today's schedule. */
const val ManagerHomeScheduleEmptyTag = "manager-home-schedule-empty"

/** Identifies one attention card, so a test can drive the navigation it offers. */
fun managerHomeAttentionItemTag(jobId: String): String = "manager-home-attention-$jobId"

/**
 * Identifies the "Needs attention" heading, which is also the control that shows and hides its cards.
 */
const val ManagerHomeAttentionToggleTag = "manager-home-attention-toggle"

/** Identifies one row of today's schedule. */
fun managerHomeVisitTag(visitId: String): String = "manager-home-visit-$visitId"

/*
 * Metrics the manager home is built from, from `docs/design/android-design-system.md`: the 20 dp
 * page gutter the signed-in shell uses, 16 dp inside a card, and 48 dp touch targets for actions.
 */
private val HomePageGutter = 20.dp
private val HomeSectionSpacing = 20.dp
private val HomeTouchTarget = 48.dp
private val HomeAttentionIconSize = 20.dp
private val HomeChevronSize = 18.dp
private val HomeStatusDotSize = 8.dp
private val HomeRefreshIconSize = 14.dp

/**
 * The manager home: what needs the manager, how today is going, and what is happening next
 * (`BR-010`).
 *
 * The screen decides nothing about the operation. Which Visits need attention, which day "today" is,
 * and what the day's counts are all arrive as the backend's answer, because they depend on the
 * server clock rather than on this device's (`BR-001`, `BR-042`).
 *
 * A failed read never blanks the screen while the backend has already reported this data: the cached
 * state stays visible and the failure is reported beside it (`BR-013`, `BR-031`).
 */
@Composable
fun ManagerHomeScreen(
    state: ManagerHomeUiState,
    onOpenJob: (jobId: String) -> Unit,
    onOpenSchedule: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val home = state.home
    when {
        home != null ->
            ManagerHomeContent(
                home = home,
                showsRefreshingIndicator = state.showsRefreshingIndicator,
                showsUnrefreshedNotice = state.showsUnrefreshedNotice,
                onOpenJob = onOpenJob,
                onOpenSchedule = onOpenSchedule,
                onRetry = onRetry,
                modifier = modifier,
            )

        state.showsFailure -> ManagerHomeFailure(onRetry = onRetry, modifier = modifier)

        else -> ManagerHomeLoading(modifier = modifier)
    }
}

/** The header the home destination shows in the shell's contextual top bar (`BR-010`). */
@Immutable
data class ManagerHomeHeader(
    val title: String,
    val subtitle: String,
)

/**
 * The greeting and date line the manager home puts in the shell's top bar.
 *
 * The shell already draws exactly one contextual top bar
 * (`docs/decisions/011-android-contextual-top-bar.md`), so the header belongs there rather than in a
 * second bar above the content. The name is the backend's answer for the signed-in member; the date
 * is the device's local date, because that is the day the manager is asking about. The home's *data*
 * is for the day the backend resolved from this device's time zone.
 */
@Composable
fun managerHomeHeader(displayName: String?): ManagerHomeHeader {
    val title = when (greetingPeriod(LocalTime.now().hour)) {
        GreetingPeriod.MORNING -> greetingResource(
            named = R.string.home_greeting_morning_named,
            anonymous = R.string.home_greeting_morning,
            displayName = displayName,
        )

        GreetingPeriod.AFTERNOON -> greetingResource(
            named = R.string.home_greeting_afternoon_named,
            anonymous = R.string.home_greeting_afternoon,
            displayName = displayName,
        )

        GreetingPeriod.EVENING -> greetingResource(
            named = R.string.home_greeting_evening_named,
            anonymous = R.string.home_greeting_evening,
            displayName = displayName,
        )
    }
    return ManagerHomeHeader(title = title, subtitle = homeDateLine(LocalDate.now(), deviceLocale()))
}

/** A greeting with the member's name when the backend reported one, and without it when it did not. */
@Composable
private fun greetingResource(named: Int, anonymous: Int, displayName: String?): String =
    if (displayName.isNullOrBlank()) {
        stringResource(anonymous)
    } else {
        stringResource(named, displayName)
    }

/**
 * The current local date, in the order the language writes it.
 *
 * The skeleton is resolved by the platform rather than by a fixed pattern: "MMMM d" reads
 * "September 7" in English and has to become "7 septembre" in French, which a hard-coded pattern
 * would not produce (`BR-028`).
 */
@Composable
private fun homeDateLine(date: LocalDate, locale: Locale): String {
    val pattern = DateFormat.getBestDateTimePattern(locale, "EEEEMMMMd")
    return date.format(DateTimeFormatter.ofPattern(pattern, locale))
}

/**
 * The language the device is set to, read from the configuration so a change to it recomposes the
 * screen instead of leaving it formatted in the previous language.
 */
@Composable
private fun deviceLocale(): Locale {
    val locales = LocalConfiguration.current.locales
    return if (locales.isEmpty) Locale.ROOT else locales[0]
}

/** The first read, with nothing on screen yet. */
@Composable
private fun ManagerHomeLoading(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize().testTag(ManagerHomeLoadingTag),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.home_loading),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Neither a cached answer nor a readable backend: the screen reports why. */
@Composable
private fun ManagerHomeFailure(onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(HomePageGutter)
            .testTag(ManagerHomeFailureTag),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.home_error_title),
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
        Button(
            onClick = onRetry,
            modifier = Modifier.testTag(ManagerHomeRetryTag),
            shape = MaterialTheme.shapes.medium,
        ) {
            Text(stringResource(R.string.home_retry))
        }
    }
}

/**
 * The populated home, in the order the manager needs it: what needs a decision, how the day is
 * going, then what is happening next.
 */
@Composable
private fun ManagerHomeContent(
    home: ManagerHome,
    showsRefreshingIndicator: Boolean,
    showsUnrefreshedNotice: Boolean,
    onOpenJob: (jobId: String) -> Unit,
    onOpenSchedule: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    /*
     * Whether the "Needs attention" cards are shown. The section opens expanded while it fits a
     * screenful, and collapsed once it would push today's summary and schedule out of sight
     * (`attentionSectionStartsExpanded`). The manager's own toggle is kept across a configuration
     * change, because collapsing a long list is a decision they should not have to repeat.
     */
    var attentionExpanded by rememberSaveable {
        mutableStateOf(attentionSectionStartsExpanded(home.attention.size))
    }

    LazyColumn(
        modifier = modifier.fillMaxSize().testTag(ManagerHomeContentTag),
        contentPadding = PaddingValues(HomePageGutter),
        verticalArrangement = Arrangement.spacedBy(HomeSectionSpacing),
    ) {
        if (showsUnrefreshedNotice) {
            item(key = "unrefreshed") { UnrefreshedNotice(onRetry = onRetry) }
        } else if (showsRefreshingIndicator) {
            // The manager keeps reading what is known while it is refreshed (`BR-013`).
            item(key = "refreshing") { RefreshingIndicator() }
        }

        item(key = "attention-header") {
            AttentionSectionHeader(
                count = home.attentionTotal,
                showsToggle = home.attention.isNotEmpty(),
                expanded = attentionExpanded,
                onToggle = { attentionExpanded = !attentionExpanded },
            )
        }
        if (home.attention.isEmpty()) {
            item(key = "attention-none") { AllClearCard() }
        } else if (attentionExpanded) {
            items(home.attention, key = { item -> "${item.kind}-${item.jobId}" }) { item ->
                AttentionCard(item = item, onOpenJob = onOpenJob)
            }
        }

        item(key = "today") { TodayCard(summary = home.today) }

        item(key = "schedule-label") { ScheduleHeader(onOpenSchedule = onOpenSchedule) }
        if (home.visits.isEmpty()) {
            item(key = "schedule-none") { ScheduleEmptyCard() }
        } else {
            items(home.visits, key = { visit -> visit.visitId }) { visit ->
                VisitCard(visit = visit, onOpenJob = onOpenJob)
            }
        }
    }
}

/**
 * What the screen says when the backend reported this data but could not be reached again.
 *
 * It never blocks the content: the manager keeps working from what is known, and knows that this is
 * not the newest answer (`BR-013`, `BR-031`).
 */
@Composable
private fun UnrefreshedNotice(onRetry: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().testTag(ManagerHomeUnrefreshedTag),
        color = MaterialTheme.colorScheme.secondary,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, top = 8.dp, end = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                modifier = Modifier.weight(1f),
                text = stringResource(R.string.home_unrefreshed_notice),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondary,
            )
            TextButton(
                onClick = onRetry,
                modifier = Modifier.testTag(ManagerHomeRetryTag),
            ) {
                Text(stringResource(R.string.home_retry))
            }
        }
    }
}

/**
 * A quiet line that says the known state is being refreshed.
 *
 * It is deliberately not a blocking spinner: the content behind it is usable, and the manager is not
 * interrupted by a background read (`BR-012`).
 */
@Composable
private fun RefreshingIndicator() {
    Row(
        modifier = Modifier.fillMaxWidth().testTag(ManagerHomeRefreshingTag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(HomeRefreshIconSize))
        Spacer(Modifier.width(8.dp))
        Text(
            text = stringResource(R.string.home_refreshing),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The calm state: nothing needs the manager right now.
 *
 * The section is neither hidden nor left as an empty block, so the manager can tell "nothing needs
 * me" apart from "this did not load" (`BR-012`).
 */
@Composable
private fun AllClearCard() {
    InfoCard(modifier = Modifier.testTag(ManagerHomeAllClearTag)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painter = painterResource(R.drawable.ic_check_circle),
                contentDescription = null,
                tint = MaterialTheme.stateColors.success,
                modifier = Modifier.size(HomeAttentionIconSize),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = stringResource(R.string.home_attention_all_clear_title),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Text(
            text = stringResource(R.string.home_attention_all_clear_detail),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The "Needs attention" heading, which is also the control that shows and hides its cards.
 *
 * The count is the backend's `attentionTotal`, not the number of cards drawn, so a collapsed section
 * still reports the whole operation (`BR-001`, `BR-041`) and it comes from the same
 * `section_count_format` string the other sections' labels use, so the headings cannot drift apart.
 *
 * The whole row is the touch target and keeps the design system's 48 dp minimum
 * (`docs/design/android-design-system.md`). The chevron turns a quarter turn while the cards are
 * shown, the same affordance the customer detail's archived-Property disclosure uses. Nothing needs
 * attention means nothing to toggle, so the heading carries no chevron and no click.
 */
@Composable
private fun AttentionSectionHeader(
    count: Int,
    showsToggle: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(ManagerHomeAttentionToggleTag)
            .heightIn(min = HomeTouchTarget)
            .clip(MaterialTheme.shapes.medium)
            .clickable(enabled = showsToggle, onClick = onToggle),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            modifier = Modifier.weight(1f),
            text = stringResource(
                R.string.section_count_format,
                stringResource(R.string.home_attention_title),
                count,
            ),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (showsToggle) {
            Icon(
                painter = painterResource(R.drawable.ic_chevron_right),
                contentDescription = stringResource(
                    if (expanded) {
                        R.string.home_attention_collapse
                    } else {
                        R.string.home_attention_expand
                    },
                ),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(HomeChevronSize)
                    .rotate(if (expanded) 90f else 0f),
            )
        }
    }
}

/**
 * One condition that needs the manager.
 *
 * The whole card is the touch target and it always goes somewhere: a condition the manager could not
 * act on from would be a dead end (`BR-012`). It opens the **Job** the condition is about, which is
 * the Job Details destination — the product target `docs/tracker/016-android-manager-home.md`
 * recorded and deferred until that destination existed.
 */
@Composable
private fun AttentionCard(
    item: ManagerAttentionItem,
    onOpenJob: (jobId: String) -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(managerHomeAttentionItemTag(item.jobId))
            .clickable { onOpenJob(item.jobId) },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shape = MaterialTheme.shapes.large,
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_alert_circle),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(HomeAttentionIconSize),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(attentionTitle(item), item.jobNumber),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(
                        R.string.home_attention_detail_format,
                        item.jobTitle,
                        item.customerName,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                item.scheduledStart?.let { scheduledStart ->
                    formatScheduledTime(scheduledStart, deviceLocale())?.let { time ->
                        Text(
                            text = stringResource(R.string.home_attention_scheduled, time),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Icon(
                painter = painterResource(R.drawable.ic_chevron_right),
                contentDescription = stringResource(R.string.home_attention_open),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(HomeChevronSize),
            )
        }
    }
}

/**
 * How one condition is named in plain business language (`BR-012`).
 *
 * Each kind is the backend's answer (`BR-060`, `BR-061`, `BR-072`, `BR-074`); the screen only names
 * it, and the Job number makes it specific.
 */
private fun attentionTitle(item: ManagerAttentionItem): Int =
    when (item.kind) {
        ManagerAttentionKind.VISIT_OVERDUE -> R.string.home_attention_overdue_title
        ManagerAttentionKind.JOB_PENDING_REVIEW -> R.string.home_attention_review_title
        ManagerAttentionKind.JOB_NEEDS_SCHEDULING -> R.string.home_attention_scheduling_title
    }

/**
 * How today is going: the day's work and how much of it is done.
 *
 * The counts are the backend's (`BR-074`) and the exception counts are deliberately absent: those are
 * the "Needs attention" section's, and repeating them here would count the same Visit twice.
 */
@Composable
private fun TodayCard(summary: ManagerHomeTodaySummary) {
    Column(modifier = Modifier.testTag(ManagerHomeTodayTag)) {
        SectionLabel(label = stringResource(R.string.home_today_title), count = null)
        InfoCard {
            Text(
                text = pluralStringResource(
                    R.plurals.home_today_visits,
                    summary.total,
                    summary.total,
                ),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                TodayStat(
                    value = summary.completed,
                    label = stringResource(R.string.home_today_completed),
                )
                TodayStat(
                    value = summary.inProgress,
                    label = stringResource(R.string.home_today_in_progress),
                )
                TodayStat(
                    value = summary.upcoming,
                    label = stringResource(R.string.home_today_upcoming),
                )
            }
        }
    }
}

/** One count of the day's summary: the number first, then what it counts. */
@Composable
private fun TodayStat(value: Int, label: String) {
    Column {
        Text(
            text = value.toString(),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Today's schedule heading, with the way to the full schedule.
 *
 * "See all" is a real destination (the Schedule area) rather than a control that does nothing: the
 * home shows the day, and the schedule is where the day is worked on.
 */
@Composable
private fun ScheduleHeader(onOpenSchedule: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            SectionLabel(label = stringResource(R.string.home_schedule_title), count = null)
        }
        TextButton(
            onClick = onOpenSchedule,
            modifier = Modifier.testTag(ManagerHomeSeeAllTag),
        ) {
            Text(
                text = stringResource(R.string.home_schedule_see_all),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

/** A day with no work scheduled for it. */
@Composable
private fun ScheduleEmptyCard() {
    InfoCard(modifier = Modifier.testTag(ManagerHomeScheduleEmptyTag)) {
        Text(
            text = stringResource(R.string.home_schedule_empty_title),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = stringResource(R.string.home_schedule_empty_detail),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * One Visit of today's schedule.
 *
 * The row carries only what the manager acts on — when, who, what, where, and where the attempt
 * stands — and the whole row opens the **Job** the Visit belongs to, so the row is never a dead end
 * (`BR-012`).
 */
@Composable
private fun VisitCard(
    visit: ManagerHomeVisit,
    onOpenJob: (jobId: String) -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(managerHomeVisitTag(visit.visitId))
            .clickable { onOpenJob(visit.jobId) },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = formatScheduledTime(visit.scheduledStart, deviceLocale())
                        ?: stringResource(R.string.home_schedule_time_unknown),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.weight(1f))
                VisitStatusPill(visit)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = visit.jobTitle,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = technicianSummary(visit),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            visit.address?.let { address ->
                formattedAddress(address)?.let { line ->
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * Where the Visit stands, in the shared Visit vocabulary (`BR-074`).
 *
 * The one derived condition the pill also presents is overdue, and it takes the backend's answer
 * rather than comparing the schedule against this device's clock (`BR-001`).
 */
@Composable
private fun VisitStatusPill(visit: ManagerHomeVisit) {
    val overdue = visit.isOverdue
    val color = when {
        overdue -> MaterialTheme.colorScheme.error
        visit.visitStatus == VisitStatus.COMPLETED -> MaterialTheme.stateColors.success
        visit.visitStatus == VisitStatus.EN_ROUTE ||
            visit.visitStatus == VisitStatus.ON_SITE ||
            visit.visitStatus == VisitStatus.IN_PROGRESS -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        color = MaterialTheme.colorScheme.secondary,
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(HomeStatusDotSize)
                    .background(color = color, shape = CircleShape),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = stringResource(visitStatusLabel(visit)),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = color,
            )
        }
    }
}

/**
 * The localized label a Visit status code is presented with (`BR-028`, `BR-041`), including the one
 * derived condition the pill also presents: overdue takes the backend's answer rather than comparing
 * the schedule against this device's clock (`BR-001`).
 */
private fun visitStatusLabel(visit: ManagerHomeVisit): Int =
    if (visit.isOverdue) {
        R.string.home_visit_status_overdue
    } else {
        when (visit.visitStatus) {
            VisitStatus.DRAFT -> R.string.visit_status_draft
            VisitStatus.SCHEDULED -> R.string.visit_status_scheduled
            VisitStatus.EN_ROUTE -> R.string.visit_status_en_route
            VisitStatus.ON_SITE -> R.string.visit_status_on_site
            VisitStatus.IN_PROGRESS -> R.string.visit_status_in_progress
            VisitStatus.COMPLETED -> R.string.visit_status_completed
            VisitStatus.CANCELED -> R.string.visit_status_canceled
            VisitStatus.NO_SHOW -> R.string.visit_status_no_show
        }
    }

/**
 * Who is going to be there.
 *
 * Assigned technicians are named, Lead first, as the backend ordered them (`BR-068`). A Visit with
 * nobody assigned says so, and a Visit whose assigned members have no profile yet reports how many
 * are assigned rather than claiming the work is unassigned (`BR-020`).
 */
@Composable
private fun technicianSummary(visit: ManagerHomeVisit): String {
    val names = visit.technicians.mapNotNull { it.name }
    if (names.isNotEmpty()) {
        return names.joinToString(separator = ", ")
    }
    if (visit.technicians.isEmpty()) {
        return stringResource(R.string.customers_job_unassigned)
    }
    return pluralStringResource(
        R.plurals.home_schedule_technicians,
        visit.technicians.size,
        visit.technicians.size,
    )
}

/** The Visit's address as one line, or `null` when no part of it was preserved. */
private fun formattedAddress(address: ManagerHomeAddress): String? {
    val parts = listOfNotNull(
        address.addressLine1,
        address.addressLine2,
        address.city,
        address.province,
        address.postalCode,
    )
    return parts.takeIf { it.isNotEmpty() }?.joinToString(separator = ", ")
}

/**
 * The scheduled time in the device's own locale and time zone.
 *
 * Returns `null` when the value is not an instant this build can read, so the row omits the time
 * rather than showing a fabricated one (`BR-042`).
 */
private fun formatScheduledTime(scheduledStart: String, locale: Locale): String? =
    try {
        Instant.parse(scheduledStart)
            .atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale))
    } catch (unreadable: DateTimeParseException) {
        null
    }
