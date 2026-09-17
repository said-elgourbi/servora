package com.servora.android.ui.jobs

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.servora.android.R
import com.servora.android.data.jobs.JobPhotoImages
import com.servora.android.data.offline.ReadSource
import com.servora.android.domain.model.JobActivityEvent
import com.servora.android.domain.model.JobActivityKind
import com.servora.android.domain.model.JobStatus
import com.servora.android.domain.model.VisitStatus
import com.servora.android.domain.model.evidencePhaseOrNull
import com.servora.android.ui.components.OfflineNotice
import com.servora.android.ui.components.SectionLabel
import com.servora.android.ui.components.initials
import com.servora.android.ui.components.jobStatusLabel
import com.servora.android.ui.components.visitStatusLabel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Identifies the Job Activity section (`BR-080`). */
const val JobActivityTag = "job-activity"

/** Identifies the state shown when the Job has no activity yet. */
const val JobActivityEmptyTag = "job-activity-empty"

/** Identifies the state shown when the activity could not be read. */
const val JobActivityFailureTag = "job-activity-failure"

/**
 * Identifies the notice that the timeline on screen is the last one the backend reported, not a
 * current answer (`offline-first-architecture.md` §7, `D5`).
 */
const val JobActivityLastReportedTag = "job-activity-last-reported"

/** Identifies one activity entry. */
fun jobActivityEventTag(eventId: String): String = "job-activity-event-$eventId"

private val ActivityRailWidth = 28.dp
private val ActivityMarkerSize = 28.dp
private val ActivityDotSize = 8.dp
private val ActivityLineWidth = 2.dp
private val ActivityRailSpacing = 8.dp
private val ActivityRowSpacing = 20.dp

/*
 * The Job Activity section of the Job Details screen (`BR-080`).
 *
 * It is a projection of authoritative records and is never a second list of its own: the photo gallery
 * it draws is built from the `JOB_PHOTO_ADDED` entries the API returned, and the timeline below it is
 * the API's own order (`BR-001`, `BR-080`).
 *
 * Adding an update is the technician's action, and it is offered as the screen's one Add update
 * action rather than beside the Activity: an update is a note or a photo, and two entry points for
 * one job made the technician choose a place before choosing what they were recording (`BR-012`,
 * `BR-015`, `BR-027`).
 */

/**
 * The Job's unified, chronological activity, newest first (`BR-080`).
 *
 * It projects one timeline over the Job's own events and every Visit's events, so a manager reads a
 * single account of what happened rather than one list per Visit. A Job-level entry carries no Visit;
 * a Visit-level entry states its Visit as the human-readable sequence `Visit 1`, `Visit 2`, … — never
 * a database id. Each entry is a title/action, with the member, time and Visit context as secondary
 * metadata, and a marker on a connecting vertical line (`Figma/src/screens/JobDetails.tsx`).
 *
 * The rail is deliberately quiet and narrow: it organizes the entries rather than competing with
 * them, and the content it introduces keeps the width a phone can read (`BR-012`).
 *
 * Photos the backend accepted are gathered into a collapsible gallery above the timeline, because a
 * photo is browsed as a collection (`BR-015`); each photo's entry also draws the photo itself, since
 * the Activity is the account of what happened and an entry that only said "Added a photo" would
 * leave the technician's evidence out of it (`BR-080`, `BR-027`). Both are the same records.
 */
@Composable
internal fun JobActivitySection(
    state: JobDetailsUiState,
    onRetry: () -> Unit,
    onOpenPhoto: (String) -> Unit,
    photoImages: JobPhotoImages = JobPhotoImages.None,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth().testTag(JobActivityTag)) {
        SectionLabel(
            label = stringResource(R.string.job_activity_label),
            count = state.activity?.takeIf { it.isNotEmpty() }?.size,
        )

        if (state.activitySource == ReadSource.WORKING_SET && state.activity != null) {
            // The photos this timeline shows, and the entries that name them, are the last the backend
            // reported: the section says so rather than letting a local copy read as a fresh answer
            // (`offline-first-architecture.md` §7, `D5`).
            OfflineNotice(
                message = stringResource(R.string.offline_last_reported),
                tag = JobActivityLastReportedTag,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        when {
            state.showsActivityLoading -> ActivityLoading()
            state.activityFailure != null -> ActivityFailure(onRetry = onRetry)
            state.activity.isNullOrEmpty() -> ActivityEmpty()
            else -> {
                val photos = jobActivityPhotos(state.activity)
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    // The photos the backend accepted, read as a collection above the account of what
                    // happened (`BR-080`). Photos still held by this device are the tray's, because the
                    // backend has not accepted them yet (`BR-001`).
                    if (photos.isNotEmpty()) {
                        JobPhotoGallerySection(
                            photos = photos,
                            jobId = state.jobId,
                            photoImages = photoImages,
                            onOpenPhoto = onOpenPhoto,
                        )
                    }
                    ActivityTimeline(
                        events = state.activity,
                        jobId = state.jobId,
                        photoImages = photoImages,
                        onOpenPhoto = onOpenPhoto,
                    )
                }
            }
        }
    }
}

@Composable
private fun ActivityLoading() {
    Box(modifier = Modifier.fillMaxWidth().height(72.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(modifier = Modifier.size(24.dp))
    }
}

@Composable
private fun ActivityFailure(onRetry: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().testTag(JobActivityFailureTag)) {
        Text(
            text = stringResource(R.string.job_activity_error_message),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = onRetry) {
            Text(stringResource(R.string.job_details_retry))
        }
    }
}

@Composable
private fun ActivityEmpty() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(JobActivityEmptyTag)
            .padding(vertical = 8.dp),
    ) {
        Text(
            text = stringResource(R.string.job_activity_empty_title),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = stringResource(R.string.job_activity_empty_message),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
/** The timeline itself: one marker and connecting line per entry, newest first. */
@Composable
private fun ActivityTimeline(
    events: List<JobActivityEvent>,
    jobId: String,
    photoImages: JobPhotoImages,
    onOpenPhoto: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        events.forEachIndexed { index, event ->
            ActivityEventRow(
                event = event,
                isLast = index == events.lastIndex,
                jobId = jobId,
                photoImages = photoImages,
                onOpenPhoto = onOpenPhoto,
            )
        }
    }
}

@Composable
private fun ActivityEventRow(
    event: JobActivityEvent,
    isLast: Boolean,
    jobId: String,
    photoImages: JobPhotoImages,
    onOpenPhoto: (String) -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        // The rail draws the connecting line behind the marker, so the line runs marker to marker
        // rather than being a separate list decoration (`Figma/src/screens/JobDetails.tsx`).
        Box(
            modifier = Modifier.width(ActivityRailWidth).fillMaxHeight(),
            contentAlignment = Alignment.TopCenter,
        ) {
            if (!isLast) {
                Box(
                    modifier = Modifier
                        .width(ActivityLineWidth)
                        .fillMaxHeight()
                        .padding(top = ActivityMarkerSize)
                        .background(MaterialTheme.colorScheme.outlineVariant),
                )
            }
            ActivityMarker(event)
        }
        Spacer(Modifier.width(ActivityRailSpacing))
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(bottom = if (isLast) 0.dp else ActivityRowSpacing)
                .testTag(jobActivityEventTag(event.id)),
        ) {
            Text(
                text = activityTitle(event),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = activityMetadata(event),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // A photo is evidence, so it is drawn in the entry that recorded it rather than described
            // by it (`BR-015`, `BR-080`); every other entry carries its own text.
            if (event.kind == JobActivityKind.JOB_PHOTO_ADDED) {
                ActivityPhotoEvidence(
                    event = event,
                    jobId = jobId,
                    photoImages = photoImages,
                    onOpenPhoto = onOpenPhoto,
                )
            } else {
                activityContent(event)?.let { content ->
                    Text(
                        text = content,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * The photo one Activity entry carries (`BR-015`, `BR-027`, `BR-080`).
 *
 * The entry states what happened; this is the evidence it happened to, drawn in that entry rather than
 * only in the gallery above the timeline. It is the same photo the gallery and the viewer draw, read
 * through the same image stack and its cache, so nothing is fetched twice — and tapping it opens the
 * same viewer on the same page a tap on a gallery tile does (`D4`, `D11`).
 *
 * The thumbnail crops, being a summary that stands for the photo, and it is fixed at a size a reader
 * recognizes without one entry taking the screen (`BR-012`). Its phase is the badge the gallery, the
 * tray and the viewer already draw, so no two surfaces can read differently (`BR-028`), and the note
 * is read under the photo it belongs to — with the rest behind an explicit action when it does not
 * fit, so one entry cannot push the account of what happened out of sight.
 */
@Composable
private fun ActivityPhotoEvidence(
    event: JobActivityEvent,
    jobId: String,
    photoImages: JobPhotoImages,
    onOpenPhoto: (String) -> Unit,
) {
    // A photo whose id this build cannot read is left undescribed rather than drawn as some other
    // photo: the entry still says what it recorded (`BR-042`).
    val photoId = event.photoId ?: return

    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .width(JobPhotoActivityPhotoWidth)
                .height(JobPhotoActivityPhotoHeight)
                .testTag(jobPhotoActivityPhotoTag(photoId))
                // The photo is the target, and the label names the action for a screen reader: the
                // photo's own description says what it is, not what tapping it does (`BR-028`).
                .clickable(onClickLabel = stringResource(R.string.job_photo_viewer_open)) {
                    onOpenPhoto(photoId)
                },
        ) {
            JobPhotoThumbnail(
                image = photoImages.jobPhotoThumbnail(jobId, photoId),
                contentDescription = stringResource(R.string.job_photo_image_description),
                modifier = Modifier.fillMaxSize(),
            )
            EvidencePhaseBadge(
                phase = evidencePhaseOrNull(event.photoPhase),
                modifier = Modifier.align(Alignment.BottomStart).padding(6.dp),
            )
        }

        val note = event.body?.takeIf { it.isNotBlank() }
        if (note != null) {
            JobPhotoNote(
                note = note,
                collapseKey = photoId,
                textColor = MaterialTheme.colorScheme.onSurfaceVariant,
                actionColor = MaterialTheme.colorScheme.primary,
                textTag = jobPhotoActivityNoteTag(photoId),
                actionTag = jobPhotoActivityNoteActionTag(photoId),
            )
        }
    }
}

/**
 * The marker each entry aligns to: a technician's initials for a note, a quiet dot for a system event
 * (`BR-020`, `Figma/src/screens/JobDetails.tsx`).
 */
@Composable
private fun ActivityMarker(event: JobActivityEvent) {
    if (event.kind == JobActivityKind.VISIT_NOTE_ADDED) {
        Box(
            modifier = Modifier
                .size(ActivityMarkerSize)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.secondary),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = initials(event.actorName.orEmpty()),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSecondary,
            )
        }
    } else {
        Box(modifier = Modifier.size(ActivityMarkerSize), contentAlignment = Alignment.Center) {
            Box(
                modifier = Modifier
                    .size(ActivityDotSize)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.onSurfaceVariant),
            )
        }
    }
}
/** The primary text: the action the entry records, or a note's own text (`BR-080`). */
@Composable
private fun activityTitle(event: JobActivityEvent): String =
    when (event.kind) {
        JobActivityKind.JOB_STATUS_CHANGED -> {
            val label = localizedJobStatus(event.toStatus)
            if (label != null) {
                stringResource(R.string.job_activity_job_status_changed, label)
            } else {
                stringResource(R.string.job_activity_job_status_changed_generic)
            }
        }

        JobActivityKind.VISIT_STATUS_CHANGED -> {
            val label = localizedVisitStatus(event.toStatus)
            if (label != null) {
                stringResource(R.string.job_activity_visit_status_changed, label)
            } else {
                stringResource(R.string.job_activity_visit_status_changed_generic)
            }
        }

        JobActivityKind.JOB_PROPERTY_CHANGED ->
            stringResource(R.string.job_activity_property_changed)

        JobActivityKind.JOB_CUSTOMER_CHANGED ->
            stringResource(R.string.job_activity_customer_changed)

        JobActivityKind.VISIT_SCHEDULED ->
            stringResource(R.string.job_activity_visit_scheduled)

        JobActivityKind.VISIT_RESCHEDULED ->
            stringResource(R.string.job_activity_visit_rescheduled)

        JobActivityKind.VISIT_TECHNICIAN_ASSIGNED ->
            stringResource(
                R.string.job_activity_technician_assigned,
                technicianName(event),
                localizedRole(event.roleCode),
            )

        JobActivityKind.VISIT_TECHNICIAN_REMOVED ->
            stringResource(R.string.job_activity_technician_removed, technicianName(event))

        JobActivityKind.VISIT_TECHNICIAN_ROLE_CHANGED ->
            stringResource(
                R.string.job_activity_technician_role_changed,
                technicianName(event),
                localizedRole(event.previousRoleCode),
                localizedRole(event.roleCode),
            )

        JobActivityKind.VISIT_OUTCOME_RECORDED -> {
            val outcome = localizedOutcome(event.outcomeCode)
            if (outcome != null) {
                stringResource(R.string.job_activity_outcome_recorded, outcome)
            } else {
                stringResource(R.string.job_activity_outcome_recorded_generic)
            }
        }

        JobActivityKind.VISIT_NOTE_ADDED -> event.body.orEmpty()

        // A photo is evidence (`BR-015`): the entry names what happened, and the photo it recorded is
        // drawn under it with the phase it was taken in as its own badge — so the phase is stated once,
        // on the photo, rather than twice (`BR-012`, `BR-028`). Its note belongs to the photo and is
        // read there.
        JobActivityKind.JOB_PHOTO_ADDED -> stringResource(R.string.job_photo_activity_added)

        // Removing evidence is its own event (`BR-089`): it names what happened, and the photo is not
        // drawn, because it is no longer in ordinary use.
        JobActivityKind.JOB_PHOTO_REMOVED -> stringResource(R.string.job_photo_activity_removed)
    }

/** The optional secondary text an entry carries: an outcome's summary, or a removal's reason. */
@Composable
private fun activityContent(event: JobActivityEvent): String? =
    when (event.kind) {
        JobActivityKind.VISIT_OUTCOME_RECORDED ->
            event.outcomeSummary?.takeIf { it.isNotBlank() }

        // A removal states why the evidence was taken out of use (`BR-089`), and that reason is what
        // the entry has to say beyond the fact (`BR-080`).
        JobActivityKind.JOB_PHOTO_REMOVED ->
            event.photoRemovalReason?.takeIf { it.isNotBlank() }

        // A photo's note is drawn under the photo itself (`ActivityPhotoEvidence`).
        JobActivityKind.JOB_PHOTO_ADDED -> null

        else -> null
    }

/** The secondary metadata: the Visit context (when there is one), the member and the time. */
@Composable
private fun activityMetadata(event: JobActivityEvent): String {
    val zone = remember { ZoneId.systemDefault() }
    val time = runCatching { Instant.parse(event.recordedAt).atZone(zone) }
        .getOrNull()
        ?.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(deviceLocale()))
        ?: stringResource(R.string.job_details_time_unknown)
    return buildList {
        event.visitSequence?.let { add(stringResource(R.string.job_activity_visit_format, it)) }
        event.actorName?.takeIf { it.isNotBlank() }?.let { add(it) }
        add(time)
    }.joinToString(" · ")
}

/** A technician's name, with the profile-less fallback a missing member already uses (`BR-020`). */
@Composable
private fun technicianName(event: JobActivityEvent): String =
    event.technicianName ?: stringResource(R.string.job_details_technician_name_unknown)

@Composable
private fun localizedJobStatus(code: String?): String? =
    code?.let { value -> JobStatus.entries.firstOrNull { it.name == value } }
        ?.let { stringResource(jobStatusLabel(it)) }

@Composable
private fun localizedVisitStatus(code: String?): String? =
    code?.let { value -> VisitStatus.entries.firstOrNull { it.name == value } }
        ?.let { stringResource(visitStatusLabel(it)) }

@Composable
private fun localizedRole(code: String?): String =
    when (code) {
        "LEAD" -> stringResource(R.string.assignment_role_lead)
        "TECHNICIAN" -> stringResource(R.string.assignment_role_technician)
        else -> code.orEmpty()
    }

@Composable
private fun localizedOutcome(code: String?): String? =
    when (code) {
        "RESOLVED" -> stringResource(R.string.job_activity_outcome_resolved)
        "NEEDS_PARTS" -> stringResource(R.string.job_activity_outcome_needs_parts)
        "NEEDS_FOLLOWUP" -> stringResource(R.string.job_activity_outcome_needs_followup)
        "NEEDS_QUOTE_APPROVAL" ->
            stringResource(R.string.job_activity_outcome_needs_quote_approval)

        "UNABLE_TO_COMPLETE" ->
            stringResource(R.string.job_activity_outcome_unable_to_complete)

        else -> null
    }


