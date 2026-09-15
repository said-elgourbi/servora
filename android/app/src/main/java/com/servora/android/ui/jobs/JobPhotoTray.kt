package com.servora.android.ui.jobs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.servora.android.R
import com.servora.android.data.jobs.JobPhotoImages
import com.servora.android.domain.model.JobPhotoSyncState
import com.servora.android.domain.model.PendingJobPhoto

/*
 * The capture tray (`BR-015`, `BR-027`).
 *
 * It is built for repeated capture in the field (`BR-012`): the tray keeps the camera one tap away
 * after every photo, and a photo can be removed until the moment it is submitted.
 *
 * An entry that has been submitted stops offering removal and reports the upload's own state instead,
 * because from that point on the backend may already hold the evidence (`BR-014`, §9).
 */

/** Identifies the tray that holds the photos not yet accepted by the backend. */
const val JobPhotoTrayTag = "job-photo-tray"

/** Identifies the tray's "take another photo" action. */
const val JobPhotoTrayCaptureTag = "job-photo-tray-capture"

/** Identifies the tray's save action. */
const val JobPhotoTraySubmitTag = "job-photo-tray-submit"

/** Identifies the count of photos that are not saved yet. */
const val JobPhotoTrayHintTag = "job-photo-tray-hint"

/** Identifies one pending photo in the tray. */
fun jobPhotoPendingTileTag(photoId: String): String = "job-photo-pending-$photoId"

/** Identifies the X that removes a photo that has not been submitted. */
fun jobPhotoPendingRemoveTag(photoId: String): String = "job-photo-pending-remove-$photoId"

/** Identifies the upload state a submitted photo reports. */
fun jobPhotoPendingStateTag(photoId: String): String = "job-photo-pending-state-$photoId"

/** Identifies the review panel and its parts. */
const val JobPhotoReviewSheetTag = "job-photo-review-sheet"
const val JobPhotoReviewPreviewTag = "job-photo-review-preview"
const val JobPhotoReviewNoteTag = "job-photo-review-note"
const val JobPhotoReviewConfirmTag = "job-photo-review-confirm"
const val JobPhotoReviewDiscardTag = "job-photo-review-discard"

/** The height the captured photo is reviewed at, and the most lines its note is shown in. */
internal val JobPhotoReviewPreviewHeight = 220.dp
internal const val JobPhotoNoteMaxLines = 3

private val JobPhotoTrayTileSize = 108.dp

/**
 * The tray: what the technician captured and the backend has not accepted yet (`§9`).
 *
 * A tile opens its photo full size when it is tapped, exactly as an accepted photo's tile does
 * (`D4`): what the technician recorded is still theirs to inspect while it waits, and every tile in
 * the tray is a photo whose bytes are still on this device.
 */
@Composable
internal fun JobPhotoTray(
    photos: List<PendingJobPhoto>,
    uploads: Map<String, JobPhotoSyncState>,
    isSubmitting: Boolean,
    onCapture: () -> Unit,
    onSubmit: () -> Unit,
    onRemove: (String) -> Unit,
    onOpen: (String) -> Unit,
    photoImages: JobPhotoImages,
    modifier: Modifier = Modifier,
) {
    val unsaved = photos.count { it.isRemovable() }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 8.dp,
        modifier = modifier.fillMaxWidth().testTag(JobPhotoTrayTag),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.job_photo_tray_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = stringResource(R.string.job_photo_tray_unsaved, unsaved),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag(JobPhotoTrayHintTag),
                )
            }

            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(items = photos, key = { photo -> photo.photoId }) { photo ->
                    JobPhotoPendingTile(
                        photo = photo,
                        state = uploads[photo.photoId],
                        photoImages = photoImages,
                        onRemove = { onRemove(photo.photoId) },
                        onOpen = { onOpen(photo.photoId) },
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    onClick = onCapture,
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier
                        .weight(1f)
                        .height(JobPhotoPhaseButtonHeight)
                        .testTag(JobPhotoTrayCaptureTag),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_camera),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = stringResource(R.string.job_photo_tray_capture),
                        modifier = Modifier.padding(start = 8.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Button(
                    onClick = onSubmit,
                    enabled = unsaved > 0 && !isSubmitting,
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier
                        .weight(1f)
                        .height(JobPhotoPhaseButtonHeight)
                        .testTag(JobPhotoTraySubmitTag),
                ) {
                    if (isSubmitting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Text(
                            text = stringResource(R.string.job_photo_tray_submit),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/**
 * One pending photo: its bytes, the phase it was taken in, its note, and either the X that removes it
 * or the upload state the queue reports for it (`§7`).
 */
@Composable
private fun JobPhotoPendingTile(
    photo: PendingJobPhoto,
    state: JobPhotoSyncState?,
    photoImages: JobPhotoImages,
    onRemove: () -> Unit,
    onOpen: () -> Unit,
) {
    Column(
        modifier = Modifier
            .width(JobPhotoTrayTileSize)
            .testTag(jobPhotoPendingTileTag(photo.photoId))
            // The whole tile opens the photo full size, which is the design's rule for an attachment
            // (`D4`). The X that removes a photo before it is submitted is its own control and takes
            // its own taps.
            .clickable(onClickLabel = stringResource(R.string.job_photo_viewer_open), onClick = onOpen),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box {
            JobPhotoThumbnail(
                image = photoImages.localThumbnail(photo.localPath),
                contentDescription = stringResource(R.string.job_photo_image_description),
                modifier = Modifier.size(JobPhotoTrayTileSize),
            )
            JobPhotoPhaseBadge(
                phase = photo.phase,
                modifier = Modifier.align(Alignment.BottomStart).padding(6.dp),
            )
            if (photo.isRemovable()) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.align(Alignment.TopEnd).padding(4.dp),
                ) {
                    IconButton(
                        onClick = onRemove,
                        modifier = Modifier
                            .size(32.dp)
                            .testTag(jobPhotoPendingRemoveTag(photo.photoId)),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_close),
                            contentDescription = stringResource(R.string.job_photo_remove),
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
        }

        val note = photo.note?.takeIf { it.isNotBlank() }
        if (note != null) {
            Text(
                text = jobPhotoNoteSnippet(note),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (photo.submitted) {
            Text(
                text = stringResource(jobPhotoStateLabel(state ?: JobPhotoSyncState.QUEUED)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag(jobPhotoPendingStateTag(photo.photoId)),
            )
        }
    }
}


