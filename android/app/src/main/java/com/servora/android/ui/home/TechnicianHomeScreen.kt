package com.servora.android.ui.home

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.servora.android.R
import com.servora.android.domain.model.TechnicianAttentionItem
import com.servora.android.domain.model.TechnicianHome
import com.servora.android.domain.model.TechnicianHomeAddress
import com.servora.android.domain.model.TechnicianHomeVisit
import com.servora.android.ui.components.InfoCard
import com.servora.android.ui.components.OfflineNotice
import com.servora.android.ui.components.SectionLabel

/** Identifies the populated home, so a test can assert the state it must show. */
const val TechnicianHomeContentTag = "technician-home-content"

/** Identifies the first-read loading state. */
const val TechnicianHomeLoadingTag = "technician-home-loading"

/** Identifies the state shown when nothing could be read at all. */
const val TechnicianHomeFailureTag = "technician-home-failure"

/** Identifies the retry action of the failure and last-reported states. */
const val TechnicianHomeRetryTag = "technician-home-retry"

/** Identifies the notice that the day on screen is the last the server reported. */
const val TechnicianHomeLastReportedTag = "technician-home-last-reported"

/** Identifies the next-Visit card, with the action that opens the Job it is for. */
const val TechnicianHomeNextVisitTag = "technician-home-next"

/** Identifies the state shown when nothing at all is assigned to the caller. */
const val TechnicianHomeNothingTag = "technician-home-nothing"

/** Identifies one of the caller's own Visits, in any of the three lists. */
fun technicianHomeVisitTag(visitId: String): String = "technician-home-visit-$visitId"

/** Identifies the action that opens the Job a row is for. */
fun technicianHomeOpenJobTag(jobId: String): String = "technician-home-open-$jobId"

/**
 * The technician's own day: the Visit to do next, what today holds, what comes after, and what on
 * the caller's own work needs attention (`BR-010`, `BR-012`; `ADR-019` D6).
 *
 * The screen decides nothing about the work. Which Visit is next, which Visits are today's, which
 * are overdue and which day "today" is all arrive as the backend's answer, because they depend on the
 * server clock and on the caller's own assignments (`BR-001`, `BR-007`).
 *
 * A failed read never blanks the screen while the backend has already reported this day, and a day
 * served from the working set says it is the last reported one rather than presenting itself as
 * current (`BR-013`, offline standard §2, §7).
 */
@Composable
fun TechnicianHomeScreen(
    state: TechnicianHomeUiState,
    onOpenJob: (jobId: String) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val home = state.home
    when {
        home != null ->
            TechnicianHomeContent(
                home = home,
                showsRefreshingIndicator = state.showsRefreshingIndicator,
                showsLastReportedNotice = state.showsLastReportedNotice,
                showsNothingAssigned = state.showsNothingAssigned,
                onOpenJob = onOpenJob,
                onRetry = onRetry,
                modifier = modifier,
            )

        state.showsFailure -> TechnicianHomeFailure(onRetry = onRetry, modifier = modifier)

        else -> TechnicianHomeLoading(modifier = modifier)
    }
}

/** The first read, with nothing on screen yet. */
@Composable
private fun TechnicianHomeLoading(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize().testTag(TechnicianHomeLoadingTag),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.technician_home_loading),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Neither a known day nor a readable backend: the screen reports why. */
@Composable
private fun TechnicianHomeFailure(onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(HomePageGutter)
            .testTag(TechnicianHomeFailureTag),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.technician_home_error_title),
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
            modifier = Modifier.testTag(TechnicianHomeRetryTag),
            shape = MaterialTheme.shapes.medium,
        ) {
            Text(stringResource(R.string.home_retry))
        }
    }
}

/**
 * The populated day, in the order the technician needs it: what to do next, what today holds, what
 * comes after, and what on their own work needs attention (`BR-012`).
 *
 * Only the attention section is conditional in the way the product design asked for: it appears when
 * there is something to act on and is absent when there is not, rather than showing an empty
 * "nothing needs you" card that would compete with the next Visit.
 */
@Composable
private fun TechnicianHomeContent(
    home: TechnicianHome,
    showsRefreshingIndicator: Boolean,
    showsLastReportedNotice: Boolean,
    showsNothingAssigned: Boolean,
    onOpenJob: (jobId: String) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize().testTag(TechnicianHomeContentTag),
        contentPadding = PaddingValues(HomePageGutter),
        verticalArrangement = Arrangement.spacedBy(HomeSectionSpacing),
    ) {
        if (showsLastReportedNotice) {
            // The day is readable without connectivity; it says which answer it is (`BR-013`).
            item(key = "last-reported") {
                OfflineNotice(
                    message = stringResource(R.string.offline_last_reported),
                    tag = TechnicianHomeLastReportedTag,
                )
            }
        } else if (showsRefreshingIndicator) {
            // The technician keeps reading the known day while it is refreshed (`BR-013`).
            item(key = "refreshing") { TechnicianHomeRefreshing() }
        }

        val next = home.nextVisit
        when {
            next != null ->
                item(key = "next") { NextVisitCard(visit = next, onOpenJob = onOpenJob) }

            showsNothingAssigned -> item(key = "nothing") { NothingAssignedCard() }
        }

        item(key = "today-header") {
            SectionLabel(
                label = stringResource(R.string.home_today_title),
                count = home.visits.size,
            )
        }
        if (home.visits.isEmpty()) {
            item(key = "today-empty") { TodayEmptyCard() }
        } else {
            items(home.visits, key = { visit -> "today-${visit.visitId}" }) { visit ->
                VisitRow(visit = visit, onOpenJob = onOpenJob)
            }
        }

        if (home.upcomingTotal > 0) {
            item(key = "upcoming-header") {
                SectionLabel(
                    label = stringResource(R.string.technician_home_upcoming_title),
                    count = home.upcomingTotal,
                )
            }
            items(home.upcoming, key = { visit -> "upcoming-${visit.visitId}" }) { visit ->
                VisitRow(visit = visit, onOpenJob = onOpenJob)
            }
        }

        if (home.attentionTotal > 0) {
            item(key = "attention-header") {
                SectionLabel(
                    label = stringResource(R.string.home_attention_title),
                    count = home.attentionTotal,
                )
            }
            items(home.attention, key = { item -> "attention-${item.visitId}" }) { item ->
                AttentionCard(item = item, onOpenJob = onOpenJob)
            }
        }
    }
}


/**
 * A quiet line that says the known day is being refreshed.
 *
 * It is deliberately not a blocking spinner: the content behind it is usable, and the technician is
 * not interrupted by a background read (`BR-012`).
 */
@Composable
private fun TechnicianHomeRefreshing() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(modifier = Modifier.height(HomeRefreshIconSize))
        Spacer(Modifier.width(8.dp))
        Text(
            text = stringResource(R.string.home_refreshing),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Nothing is assigned to the caller: the read's own answer, presented plainly (`BR-042`). */
@Composable
private fun NothingAssignedCard() {
    InfoCard(modifier = Modifier.testTag(TechnicianHomeNothingTag)) {
        Text(
            text = stringResource(R.string.technician_home_nothing_title),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = stringResource(R.string.technician_home_nothing_detail),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** A day with no work scheduled for it. */
@Composable
private fun TodayEmptyCard() {
    InfoCard {
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
 * What to do next: the one Visit the backend reports as the caller's next (`BR-012`).
 *
 * It is deliberately the largest thing on the screen — the time first, then what the work is, where
 * it is and who else is on it — and its action opens the Job, because the Job Details screen is where
 * the work itself is performed. The screen offers no status control: changing a Visit's status is a
 * field action with its own rules (`BR-074`, `BR-066`), and it belongs where the Visit is, not on Home.
 */
@Composable
private fun NextVisitCard(
    visit: TechnicianHomeVisit,
    onOpenJob: (jobId: String) -> Unit,
) {
    Column(modifier = Modifier.testTag(TechnicianHomeNextVisitTag)) {
        SectionLabel(label = stringResource(R.string.technician_home_next_title), count = null)
        Card(
            modifier = Modifier.fillMaxWidth(),
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
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.weight(1f))
                    HomeVisitStatusPill(
                        status = visit.visitStatus,
                        isOverdue = visit.isOverdue,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = visit.jobTitle,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = visit.customerName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                addressLine(visit.address)?.let { line ->
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                crewLine(visit)?.let { line ->
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = { onOpenJob(visit.jobId) },
                    modifier = Modifier
                        .testTag(technicianHomeOpenJobTag(visit.jobId))
                        .fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Text(stringResource(R.string.technician_home_next_open))
                }
            }
        }
    }
}


/**
 * One of the caller's own Visits, compactly: when, what, for whom and where it stands.
 *
 * The whole row opens the Job the Visit belongs to, so a row is never a dead end (`BR-012`), and the
 * row carries no action of its own: the field action belongs beside the Visit on the Job's screen.
 */
@Composable
private fun VisitRow(
    visit: TechnicianHomeVisit,
    onOpenJob: (jobId: String) -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(technicianHomeVisitTag(visit.visitId))
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
                HomeVisitStatusPill(
                    status = visit.visitStatus,
                    isOverdue = visit.isOverdue,
                )
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
                text = visit.customerName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            addressLine(visit.address)?.let { line ->
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            crewLine(visit)?.let { line ->
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


/**
 * One condition on the caller's own work (`BR-072`, `BR-074`).
 *
 * The whole card opens the Job it is about, which is where the technician can act on it — including
 * resolving a Visit that never happened (`BR-012`). The kind is the backend's own code, named in
 * plain business language here (`BR-041`).
 */
@Composable
private fun AttentionCard(
    item: TechnicianAttentionItem,
    onOpenJob: (jobId: String) -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(technicianHomeVisitTag(item.visitId))
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
                modifier = Modifier.height(HomeAttentionIconSize),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.home_attention_overdue_title, item.jobNumber),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = item.jobTitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                formatScheduledTime(item.scheduledStart, deviceLocale())?.let { time ->
                    Text(
                        text = stringResource(R.string.home_attention_scheduled, time),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Icon(
                painter = painterResource(R.drawable.ic_chevron_right),
                contentDescription = stringResource(R.string.technician_home_next_open),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.height(HomeChevronSize),
            )
        }
    }
}

/**
 * The rest of the crew, when the Visit has more than the caller on it (`BR-068`).
 *
 * A technician working alone sees no line at all; a crew of two or more is named, Lead first, because
 * who is coming is what a technician needs to know before arriving. A member without a profile still
 * counts as a person on the Visit rather than being dropped (`BR-020`).
 */
@Composable
private fun crewLine(visit: TechnicianHomeVisit): String? {
    val others = visit.technicians.drop(1)
    if (others.isEmpty()) {
        return null
    }
    val names = others.mapNotNull { it.name }
    val stated = if (names.isNotEmpty()) {
        names.joinToString(separator = ", ")
    } else {
        pluralStringResource(R.plurals.home_schedule_technicians, others.size, others.size)
    }
    return stringResource(R.string.technician_home_crew_format, stated)
}

/** The address as one line, or `null` when no part of it was preserved (`BR-056`). */
private fun addressLine(address: TechnicianHomeAddress?): String? =
    address?.let {
        formattedAddressLine(
            addressLine1 = it.addressLine1,
            addressLine2 = it.addressLine2,
            city = it.city,
            province = it.province,
            postalCode = it.postalCode,
        )
    }

