package com.servora.android.ui.home

import android.text.format.DateFormat
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.servora.android.R
import com.servora.android.domain.model.VisitStatus
import com.servora.android.ui.theme.stateColors
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.FormatStyle
import java.util.Locale

/*
 * The presentation both home screens share.
 *
 * The manager home and the technician home answer different questions and are laid out differently
 * (`BR-010`, `BR-012`), but they show the same facts: a greeting and the date, a Visit's status in the
 * one shared vocabulary, a schedule in the device's own locale, and an address as one line. Defining
 * each of those once here is what keeps the two screens from presenting the same Visit differently
 * (`BR-041`). Layout *hierarchy* is deliberately not shared.
 */

/*
 * Metrics both homes are built from, from `docs/design/android-design-system.md`: the 20 dp page
 * gutter the signed-in shell uses, 16 dp inside a card, and 48 dp touch targets for actions.
 */
internal val HomePageGutter = 20.dp
internal val HomeSectionSpacing = 20.dp
internal val HomeTouchTarget = 48.dp
internal val HomeAttentionIconSize = 20.dp
internal val HomeChevronSize = 18.dp
internal val HomeStatusDotSize = 8.dp
internal val HomeRefreshIconSize = 14.dp

/** The header a home destination shows in the shell's contextual top bar (`BR-010`). */
@Immutable
data class HomeHeader(
    val title: String,
    val subtitle: String,
)

/**
 * The greeting and date line a home puts in the shell's top bar.
 *
 * The shell already draws exactly one contextual top bar
 * (`docs/decisions/011-android-contextual-top-bar.md`), so the header belongs there rather than in a
 * second bar above the content. The name is the backend's answer for the signed-in member; the date
 * is the device's local date, because that is the day the caller is asking about. Either home's
 * *data* is for the day the backend resolved from this device's time zone.
 */
@Composable
fun homeHeader(displayName: String?): HomeHeader {
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
    return HomeHeader(title = title, subtitle = homeDateLine(LocalDate.now(), deviceLocale()))
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
private fun homeDateLine(date: LocalDate, locale: Locale): String {
    val pattern = DateFormat.getBestDateTimePattern(locale, "EEEEMMMMd")
    return date.format(DateTimeFormatter.ofPattern(pattern, locale))
}

/**
 * The language the device is set to, read from the configuration so a change to it recomposes the
 * screen instead of leaving it formatted in the previous language.
 */
@Composable
internal fun deviceLocale(): Locale {
    val locales = LocalConfiguration.current.locales
    return if (locales.isEmpty) Locale.ROOT else locales[0]
}

/**
 * The scheduled time in the device's own locale and time zone.
 *
 * Returns `null` when the value is not an instant this build can read, so a row omits the time
 * rather than showing a fabricated one (`BR-042`).
 */
internal fun formatScheduledTime(scheduledStart: String, locale: Locale): String? =
    try {
        Instant.parse(scheduledStart)
            .atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale))
    } catch (unreadable: DateTimeParseException) {
        null
    }

/** The address parts as one line, or `null` when no part of it was preserved (`BR-056`). */
internal fun formattedAddressLine(
    addressLine1: String?,
    addressLine2: String?,
    city: String?,
    province: String?,
    postalCode: String?,
): String? {
    val parts = listOfNotNull(addressLine1, addressLine2, city, province, postalCode)
    return parts.takeIf { it.isNotEmpty() }?.joinToString(separator = ", ")
}

/**
 * A Visit's status as a home row presents it (`BR-074`), with the derived overdue condition.
 *
 * It is not the status chip in `ui/components` (`VisitStatusPill`): that one presents a **status**
 * and nothing else, while a home row also presents the one *derived condition* over a Visit's
 * schedule, which takes the backend's answer rather than comparing the schedule against this
 * device's clock (`BR-001`). Both homes present it the same way, so a Visit is never described
 * differently on the manager's screen and the technician's (`BR-041`).
 */
@Composable
internal fun HomeVisitStatusPill(
    status: VisitStatus,
    isOverdue: Boolean,
    modifier: Modifier = Modifier,
) {
    val color = statusColor(status = status, isOverdue = isOverdue)
    Surface(
        modifier = modifier,
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
                text = stringResource(homeVisitStatusLabel(status = status, isOverdue = isOverdue)),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = color,
            )
        }
    }
}

/**
 * The colour a Visit's status is presented in.
 *
 * It is the design system's own mapping (`docs/design/android-design-system.md`): the error role for
 * the derived overdue condition, the success role for a completed attempt, the brand colour for work
 * under way, and the muted role for anything still to come.
 */
@Composable
internal fun statusColor(status: VisitStatus, isOverdue: Boolean) = when {
    isOverdue -> MaterialTheme.colorScheme.error
    status == VisitStatus.COMPLETED -> MaterialTheme.stateColors.success
    status == VisitStatus.EN_ROUTE ||
        status == VisitStatus.ON_SITE ||
        status == VisitStatus.IN_PROGRESS -> MaterialTheme.colorScheme.primary

    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

/**
 * The localized label a Visit status code is presented with (`BR-028`, `BR-041`), including the one
 * derived condition the pill also presents: overdue takes the backend's answer rather than comparing
 * the schedule against this device's clock (`BR-001`).
 */
internal fun homeVisitStatusLabel(status: VisitStatus, isOverdue: Boolean): Int =
    if (isOverdue) {
        R.string.home_visit_status_overdue
    } else {
        when (status) {
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

