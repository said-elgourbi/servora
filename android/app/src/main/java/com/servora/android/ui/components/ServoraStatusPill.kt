package com.servora.android.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.servora.android.R
import com.servora.android.domain.model.FollowUpVisitRequestStatus
import com.servora.android.domain.model.JobStatus
import com.servora.android.domain.model.VisitStatus
import com.servora.android.ui.theme.stateColors

/*
 * The status chips the Servora screens present, and the labels their codes are presented with.
 *
 * They live here rather than in one feature package because more than one feature draws them: the
 * customer detail and the Job Details screen both present a Job's lifecycle status (`BR-058`), and a
 * second copy of the same colours or the same label mapping would let the two drift apart (`BR-041`).
 *
 * Job status and Visit status are two separate state machines (`BR-058`, `BR-074`, `BR-059`), so they
 * are two composables and two label functions: one is never presented in place of the other.
 *
 * A chip may also *be* the control that changes the state it presents ([onClick]), which is how the
 * Job Details screen offers the Job's status action: the status the user wants to change is the thing
 * they tap
 * (`docs/tracker/020-android-job-details-status-control.md`).
 *
 * Two more pieces of that presentation live here, because the chip and the menu the control opens both
 * use them and a second copy would let them drift apart (`BR-041`): [StatusDot], the round status
 * marker a status control leads with, and [jobStatusAccent], the single colour a Job status is named
 * with.
 */

/** The container and content colours one state is painted with, in both appearances. */
@Immutable
private data class StatusChipColors(val container: Color, val content: Color)

/** The size of the dot that leads a status control (`docs/design/android-design-system.md`). */
private val StatusDotSize = 8.dp

/**
 * The round marker a status control leads with, painted in the colour it is handed or, by default, in
 * the colour of the surface it sits on — so a dot inside a status chip states the chip's own status
 * colour without naming a colour twice (`BR-041`).
 */
@Composable
internal fun StatusDot(
    color: Color = LocalContentColor.current,
    modifier: Modifier = Modifier,
    size: Dp = StatusDotSize,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(color),
    )
}

/**
 * The colour one Job status is named with: the content colour of the chip that presents it, so a
 * control that names a status in its own row paints it with the same colour the chip wears
 * (`BR-041`, `BR-058`).
 */
@Composable
internal fun jobStatusAccent(status: JobStatus): Color = jobStatusColors(status).content

/**
 * The Job lifecycle's status chip (`BR-058`).
 *
 * The chip is drawn from the status it presents, so the state's own colour is what changes — never a
 * second palette invented for a control. When [onClick] is given, the chip is the control: the status
 * is the thing the user taps, [leading] is where the dot that marks it as a status is drawn and
 * [trailing] is where the affordance that says it opens something is drawn, and [onClickLabel] names
 * the action for a screen reader, because the chip's own label states the status and not what tapping
 * it does (`BR-028`).
 */
@Composable
internal fun JobStatusPill(
    status: JobStatus,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    onClickLabel: String? = null,
) {
    val colors = jobStatusColors(status)
    StatusChip(
        label = stringResource(jobStatusLabel(status)),
        colors = colors,
        modifier = modifier,
        leading = leading,
        trailing = trailing,
        onClick = onClick,
        enabled = enabled,
        onClickLabel = onClickLabel,
    )
}

/** The colours a Job status code is presented with, in both appearances (`BR-058`). */
@Composable
private fun jobStatusColors(status: JobStatus): StatusChipColors {
    val state = MaterialTheme.stateColors
    val scheme = MaterialTheme.colorScheme
    return when (status) {
        JobStatus.COMPLETED ->
            StatusChipColors(state.successContainer.copy(alpha = 0.28f), state.success)

        JobStatus.CANCELED ->
            StatusChipColors(scheme.errorContainer.copy(alpha = 0.4f), scheme.error)

        // A Job that has entered execution is the platform's own accent; a Job that has not is quiet,
        // because nothing has started yet (`BR-058`).
        JobStatus.ACTIVE ->
            StatusChipColors(scheme.primaryContainer, scheme.onPrimaryContainer)

        JobStatus.NEW ->
            StatusChipColors(scheme.secondary, scheme.onSurfaceVariant)
    }
}

/**
 * The Visit lifecycle's status chip (`BR-074`).
 *
 * It follows [JobStatusPill] exactly, because the two chips are the same control for two state
 * machines: the status's own colours, an optional leading dot, an optional trailing affordance, and —
 * when [onClick] is given — the chip **is** the control, with [onClickLabel] naming what tapping it
 * does because the chip's own label states the status (`BR-028`, `BR-059`).
 */
@Composable
internal fun VisitStatusPill(
    status: VisitStatus,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    onClickLabel: String? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val colors = when (status) {
        VisitStatus.COMPLETED ->
            StatusChipColors(
                MaterialTheme.stateColors.successContainer.copy(alpha = 0.28f),
                MaterialTheme.stateColors.success,
            )

        VisitStatus.CANCELED ->
            StatusChipColors(scheme.errorContainer.copy(alpha = 0.4f), scheme.error)

        VisitStatus.EN_ROUTE, VisitStatus.ON_SITE, VisitStatus.IN_PROGRESS ->
            StatusChipColors(scheme.primaryContainer, scheme.onPrimaryContainer)

        VisitStatus.DRAFT, VisitStatus.SCHEDULED ->
            StatusChipColors(scheme.secondary, scheme.onSurfaceVariant)
    }

    StatusChip(
        label = stringResource(visitStatusLabel(status)),
        colors = colors,
        modifier = modifier,
        leading = leading,
        trailing = trailing,
        onClick = onClick,
        enabled = enabled,
        onClickLabel = onClickLabel,
    )
}

/**
 * The follow-up visit request's status chip (`BR-FV-012`).
 *
 * A request is neither a Job nor a Visit — it is a proposal awaiting an office decision
 * (`BR-FV-002`) — so it is a third chip rather than a reuse of either: presenting a request's state in
 * a Visit's vocabulary would say the two are the same thing (`BR-042`, `BR-047`, `BR-059`). It follows
 * [VisitStatusPill] exactly, and its colours say only what the office has done with the request:
 * nothing decided yet is quiet, a question the office asked is the tertiary accent, the answer that
 * creates a Visit is the platform's success colour, and the answer that refuses one is the error role.
 */
@Composable
internal fun RequestStatusPill(
    status: FollowUpVisitRequestStatus,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    onClickLabel: String? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val colors = when (status) {
        FollowUpVisitRequestStatus.APPROVED ->
            StatusChipColors(
                MaterialTheme.stateColors.successContainer.copy(alpha = 0.28f),
                MaterialTheme.stateColors.success,
            )

        FollowUpVisitRequestStatus.REJECTED ->
            StatusChipColors(scheme.errorContainer.copy(alpha = 0.4f), scheme.error)

        FollowUpVisitRequestStatus.NEEDS_CLARIFICATION ->
            StatusChipColors(scheme.tertiaryContainer, scheme.onTertiaryContainer)

        FollowUpVisitRequestStatus.PENDING ->
            StatusChipColors(scheme.secondary, scheme.onSurfaceVariant)
    }

    StatusChip(
        label = stringResource(requestStatusLabel(status)),
        colors = colors,
        modifier = modifier,
        leading = leading,
        trailing = trailing,
        onClick = onClick,
        enabled = enabled,
        onClickLabel = onClickLabel,
    )
}

/**
 * One status chip: the label, the state's colours and its border, with an optional leading dot, an
 * optional trailing affordance and an optional action.
 *
 * A chip with [onClick] is a Material 3 clickable `Surface`, which is the platform's own way of
 * stating a control: it keeps the chip's measured size while reserving the touch target the platform
 * expects, and confines the ripple to the chip's shape (`docs/design/android-design-system.md`).
 */
@Composable
private fun StatusChip(
    label: String,
    colors: StatusChipColors,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    onClickLabel: String? = null,
) {
    val shape = MaterialTheme.shapes.small
    val content = colors.content
    val border = BorderStroke(1.dp, content.copy(alpha = 0.25f))
    val chip: @Composable () -> Unit = {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leading != null) {
                leading()
                Spacer(Modifier.width(6.dp))
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
            )
            if (trailing != null) {
                Spacer(Modifier.width(4.dp))
                trailing()
            }
        }
    }

    if (onClick == null) {
        Surface(
            modifier = modifier,
            shape = shape,
            color = colors.container,
            contentColor = content,
            border = border,
            content = chip,
        )
    } else {
        Surface(
            onClick = onClick,
            modifier = modifier.statusControl(onClickLabel),
            enabled = enabled,
            shape = shape,
            color = colors.container,
            contentColor = content,
            border = border,
            content = chip,
        )
    }
}

/**
 * States that a chip is a button and names what tapping it does.
 *
 * The chip's own label states the status, which is what the user is reading for, so the action is
 * named separately rather than replacing it (`BR-028`).
 */
private fun Modifier.statusControl(onClickLabel: String?): Modifier = semantics {
    role = Role.Button
    if (onClickLabel != null) {
        onClick(label = onClickLabel, action = null)
    }
}

/** The localized label a Job status code is presented with (`BR-028`, `BR-041`). */
internal fun jobStatusLabel(status: JobStatus): Int =
    when (status) {
        JobStatus.NEW -> R.string.customers_job_status_new
        JobStatus.ACTIVE -> R.string.customers_job_status_active
        JobStatus.COMPLETED -> R.string.customers_job_status_completed
        JobStatus.CANCELED -> R.string.customers_job_status_canceled
    }

/**
 * The localized label a Visit status code is presented with (`BR-028`, `BR-041`).
 *
 * The labels are the shared Visit vocabulary's, so the manager home and the Job Details screen name
 * the same code the same way.
 */
internal fun visitStatusLabel(status: VisitStatus): Int =
    when (status) {
        VisitStatus.DRAFT -> R.string.visit_status_draft
        VisitStatus.SCHEDULED -> R.string.visit_status_scheduled
        VisitStatus.EN_ROUTE -> R.string.visit_status_en_route
        VisitStatus.ON_SITE -> R.string.visit_status_on_site
        VisitStatus.IN_PROGRESS -> R.string.visit_status_in_progress
        VisitStatus.COMPLETED -> R.string.visit_status_completed
        VisitStatus.CANCELED -> R.string.visit_status_canceled
    }

/**
 * The localized label a follow-up visit request's status code is presented with (`BR-028`,
 * `BR-FV-012`).
 *
 * A clarified request is labelled with the office screen's own words for it
 * (`R.string.schedule_request_needs_clarification`), because it is **one** status: the technician's
 * record of what the office asked and the office's own card must not describe the same state in two
 * vocabularies (`BR-041`).
 */
internal fun requestStatusLabel(status: FollowUpVisitRequestStatus): Int =
    when (status) {
        FollowUpVisitRequestStatus.PENDING -> R.string.request_status_pending
        FollowUpVisitRequestStatus.NEEDS_CLARIFICATION ->
            R.string.schedule_request_needs_clarification

        FollowUpVisitRequestStatus.APPROVED -> R.string.request_status_approved
        FollowUpVisitRequestStatus.REJECTED -> R.string.request_status_rejected
    }
