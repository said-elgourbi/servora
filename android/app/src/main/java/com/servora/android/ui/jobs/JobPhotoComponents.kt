package com.servora.android.ui.jobs

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.SubcomposeAsyncImage
import coil3.request.ImageRequest
import com.servora.android.R
import com.servora.android.data.jobs.JobPhotoImages
import com.servora.android.domain.model.JobActivityEvent
import com.servora.android.domain.model.JobActivityKind
import com.servora.android.domain.model.JobPhotoPhase
import com.servora.android.domain.model.JobPhotoSyncState
import com.servora.android.domain.model.PendingJobPhoto

/*
 * The photo-evidence pieces the Job Details screen and the Job Activity section draw
 * (`BR-015`, `BR-027`).
 *
 * They are presentation only: what phase or note a photo carries, whether it was accepted and what an
 * upload is doing are all decided elsewhere — by the technician's own input and by the backend
 * (`BR-001`, `BR-042`).
 */

/** Identifies the large phase control of the review panel. */
const val JobPhotoPhaseSelectorTag = "job-photo-phase-selector"

/** Identifies one phase the technician can choose. */
fun jobPhotoPhaseOptionTag(phase: JobPhotoPhase): String = "job-photo-phase-${phase.name}"

/** Identifies the gallery of photos the backend accepted, at the head of Job Activity. */
const val JobPhotoGalleryTag = "job-photo-gallery"

/** Identifies one photo in the gallery. */
fun jobPhotoGalleryTileTag(photoId: String): String = "job-photo-gallery-$photoId"


/**
 * The three field-work phases, as large segmented buttons.
 *
 * They are deliberately not radio buttons: the technician taps one of three big targets with a whole
 * hand while standing at a machine, and the selected one is filled so the current choice is readable
 * at a glance (`BR-012`). The phase is the API's stable code (`BR-041`); only the label is localized.
 */
@Composable
internal fun JobPhotoPhaseSelector(
    selected: JobPhotoPhase?,
    enabled: Boolean,
    onSelect: (JobPhotoPhase) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().testTag(JobPhotoPhaseSelectorTag),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        JobPhotoPhase.entries.forEach { phase ->
            val isSelected = phase == selected
            Surface(
                onClick = { onSelect(phase) },
                enabled = enabled,
                shape = MaterialTheme.shapes.large,
                color = if (isSelected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
                contentColor = if (isSelected) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                border = if (isSelected) {
                    null
                } else {
                    BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                },
                modifier = Modifier
                    .weight(1f)
                    .height(JobPhotoPhaseButtonHeight)
                    .testTag(jobPhotoPhaseOptionTag(phase)),
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Text(
                        text = stringResource(jobPhotoPhaseLabel(phase)),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
            }
        }
    }
}

/**
 * One photo, drawn by the image stack.
 *
 * The preview is the stack's own draw of the request this feature answered with
 * ([JobPhotoImages]): the library decodes it at the size it is drawn, turns it by the photo's own EXIF
 * orientation and caches the result, so the same photo is neither downloaded nor decoded twice
 * (`D4b`). Until it is there — and when it cannot be drawn at all — the camera glyph is shown instead:
 * a tile's phase, note and state are what the technician acts on, and a missing preview must never be
 * presented as a different photo (`BR-042`).
 *
 * [image] is `null` when there is nothing to draw, which is the same state a photo that fails to load
 * reaches.
 */
@Composable
internal fun JobPhotoThumbnail(
    image: ImageRequest?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (image == null) {
            JobPhotoMissingPreview(contentDescription)
        } else {
            SubcomposeAsyncImage(
                model = image,
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                loading = { JobPhotoMissingPreview(contentDescription) },
                error = { JobPhotoMissingPreview(contentDescription) },
            )
        }
    }
}

/** The camera glyph a tile draws while its photo is not there, and when it cannot be drawn. */
@Composable
private fun JobPhotoMissingPreview(contentDescription: String?) {
    Icon(
        painter = painterResource(R.drawable.ic_camera),
        contentDescription = contentDescription,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(28.dp),
    )
}

/** The badge that names the phase a photo was taken in, drawn over the photo. */
@Composable
internal fun JobPhotoPhaseBadge(phase: JobPhotoPhase?, modifier: Modifier = Modifier) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = modifier,
    ) {
        Text(
            text = stringResource(jobPhotoPhaseShortLabel(phase)),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

internal val JobPhotoPhaseButtonHeight = 56.dp
internal val JobPhotoTileSize = 132.dp
private val JobPhotoBadgeSpacing = 6.dp
private const val JobPhotoNoteLength = 60

/**
 * The gallery of photos the backend accepted, in the order Job Activity reports them (`BR-080`).
 *
 * It is a projection of the timeline, never a second list: the photos it draws are exactly the
 * `JOB_PHOTO_ADDED` entries the API returned, so no client can disagree with the backend about which
 * evidence exists (`BR-001`). A tile is a preview that opens the photo full size when it is tapped
 * (`D4`), which is where the design puts an attachment's viewer (`Figma/…/servora-job-details-spec.md`
 * §11).
 */
@Composable
internal fun JobPhotoGallery(
    photos: List<JobActivityEvent>,
    jobId: String,
    photoImages: JobPhotoImages,
    onOpenPhoto: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        modifier = modifier.fillMaxWidth().testTag(JobPhotoGalleryTag),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(items = photos, key = { event -> event.id }) { event ->
            JobPhotoGalleryTile(
                event = event,
                jobId = jobId,
                photoImages = photoImages,
                onOpen = onOpenPhoto,
            )
        }
    }
}

@Composable
private fun JobPhotoGalleryTile(
    event: JobActivityEvent,
    jobId: String,
    photoImages: JobPhotoImages,
    onOpen: (String) -> Unit,
) {
    val photoId = event.photoId ?: event.id
    Column(
        modifier = Modifier
            .width(JobPhotoTileSize)
            .testTag(jobPhotoGalleryTileTag(photoId))
            // The tile's whole column is the control, so the note under it opens the photo too and the
            // target is bigger than the picture (`BR-012`). The label names the action for a screen
            // reader, because the photo's own description says what it is, not what tapping it does
            // (`BR-028`).
            .clickable(onClickLabel = stringResource(R.string.job_photo_viewer_open)) {
                onOpen(photoId)
            },
        verticalArrangement = Arrangement.spacedBy(JobPhotoBadgeSpacing),
    ) {
        Box {
            JobPhotoThumbnail(
                image = photoImages.jobPhotoThumbnail(jobId, photoId),
                contentDescription = stringResource(R.string.job_photo_image_description),
                modifier = Modifier
                    .size(JobPhotoTileSize)
                    .clip(MaterialTheme.shapes.large),
            )
            JobPhotoPhaseBadge(
                phase = jobPhotoPhaseOrNull(event.photoPhase),
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(8.dp),
            )
        }
        val note = event.body?.takeIf { it.isNotBlank() }
        if (note != null) {
            Text(
                text = jobPhotoNoteSnippet(note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** The photos Job Activity reports, as the gallery draws them (`BR-080`). */
internal fun jobActivityPhotos(activity: List<JobActivityEvent>?): List<JobActivityEvent> =
    activity.orEmpty().filter { event ->
        event.kind == JobActivityKind.JOB_PHOTO_ADDED && event.photoId != null
    }

/** The phase code the API reported, or `null` when this build cannot read it (`BR-042`). */
internal fun jobPhotoPhaseOrNull(code: String?): JobPhotoPhase? =
    code?.let { value -> JobPhotoPhase.entries.firstOrNull { it.name == value } }

/** A note trimmed to what a tile can show. */
internal fun jobPhotoNoteSnippet(note: String): String =
    if (note.length <= JobPhotoNoteLength) note else "${note.take(JobPhotoNoteLength - 1)}…"

/** The localized label of a phase the technician chooses from. */
internal fun jobPhotoPhaseLabel(phase: JobPhotoPhase): Int =
    when (phase) {
        JobPhotoPhase.BEFORE_WORK -> R.string.job_photo_phase_before
        JobPhotoPhase.DURING_WORK -> R.string.job_photo_phase_during
        JobPhotoPhase.AFTER_WORK -> R.string.job_photo_phase_after
    }

/**
 * The label a badge carries.
 *
 * It is the same word the selector uses, so the badge and the choice the technician made cannot read
 * differently (`BR-028`). A photo whose phase cannot be read says so rather than showing a guess.
 */
internal fun jobPhotoPhaseShortLabel(phase: JobPhotoPhase?): Int =
    phase?.let { jobPhotoPhaseLabel(it) } ?: R.string.job_photo_phase_unknown

/** The localized report of what an upload is doing, as the tray shows it (`§7`). */
internal fun jobPhotoStateLabel(state: JobPhotoSyncState): Int =
    when (state) {
        JobPhotoSyncState.QUEUED -> R.string.job_photo_state_queued
        JobPhotoSyncState.UPLOADING -> R.string.job_photo_state_uploading
        JobPhotoSyncState.RETRYING -> R.string.job_photo_state_retrying
        JobPhotoSyncState.REFUSED -> R.string.job_photo_state_refused
    }

/** Whether the technician may still remove the photo locally (`BR-027`, §9). */
internal fun PendingJobPhoto.isRemovable(): Boolean = !submitted

/** The localized reason a photo action did not complete (`BR-042`). */
internal fun jobPhotoFailureMessage(failure: JobPhotoFailure): Int =
    when (failure) {
        JobPhotoFailure.CAPTURE_FAILED -> R.string.job_photo_error_capture_failed
        JobPhotoFailure.NOT_SIGNED_IN -> R.string.job_photo_error_not_signed_in
        JobPhotoFailure.NOT_QUEUED -> R.string.job_photo_error_not_queued
        JobPhotoFailure.ALREADY_SUBMITTED -> R.string.job_photo_error_already_submitted
        JobPhotoFailure.PHOTO_TYPE_NOT_ACCEPTED -> R.string.job_photo_error_type_not_accepted
        JobPhotoFailure.PHOTO_TOO_LARGE -> R.string.job_photo_error_too_large
        JobPhotoFailure.PHOTO_NOT_SAVED -> R.string.job_photo_error_not_saved
        JobPhotoFailure.PHOTO_NOT_READ -> R.string.job_photo_error_not_read
        JobPhotoFailure.PICKER_UNAVAILABLE -> R.string.job_photo_error_picker_unavailable
    }

/** The localized report of what the last photo action did (`§7`). */
internal fun jobPhotoMessageText(message: JobPhotoMessage): Int =
    when (message) {
        JobPhotoMessage.QUEUED -> R.string.job_photo_saved_message
    }

