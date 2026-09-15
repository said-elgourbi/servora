package com.servora.android.ui.jobs

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.servora.android.R
import com.servora.android.data.jobs.JobPhotoImages
import com.servora.android.domain.model.JobActivityEvent
import com.servora.android.domain.model.JobPhotoPhase
import com.servora.android.domain.model.PendingJobPhoto
import me.saket.telephoto.zoomable.coil3.ZoomableAsyncImage

/*
 * A photo, opened from the Job's gallery or from the capture tray (`D4`, `BR-015`, `BR-027`).
 *
 * The tiles are previews and the design asks an attachment to open an appropriate preview when it is
 * tapped (`Figma/src/imports/pasted_text/servora-job-details-spec.md` §11), so this is where the
 * technician actually reads the evidence they recorded: one photo at a time, at the resolution the
 * screen can hold, with the phase it was taken in and the note they wrote.
 *
 * It is presentation only. It decides nothing about the photo — not whether the session may read it
 * (the API answers that, `BR-007`), not what a photo is, and not what its phase means.
 */

/** Identifies the full-size viewer. */
const val JobPhotoViewerTag = "job-photo-viewer"

/** Identifies the photo the viewer is showing. */
const val JobPhotoViewerImageTag = "job-photo-viewer-image"

/** Identifies the state shown while the photo is being read. */
const val JobPhotoViewerLoadingTag = "job-photo-viewer-loading"

/** Identifies the report shown when the photo could not be read (`BR-042`). */
const val JobPhotoViewerUnavailableTag = "job-photo-viewer-unavailable"

/** Identifies the action that closes the viewer. */
const val JobPhotoViewerCloseTag = "job-photo-viewer-close"

/**
 * The photo the viewer draws, and where that photo's bytes are.
 *
 * It carries identity and provenance only — whether the backend holds the photo or the device still
 * does, and the phase and note the record already states — so the viewer never becomes a second copy
 * of the evidence (`BR-001`).
 */
internal sealed interface ViewedJobPhoto {
    /** The photo's identifier, which is the same one the device and the backend use (`§1`). */
    val photoId: String

    /** The field-work phase the photo was taken in, or `null` when this build cannot read it. */
    val phase: JobPhotoPhase?

    /** The technician's note, or `null` when they wrote none. */
    val note: String?

    /** A photo the technician recorded that the backend has not accepted yet (`§9`). */
    data class Pending(
        override val photoId: String,
        val localPath: String,
        override val phase: JobPhotoPhase?,
        override val note: String?,
    ) : ViewedJobPhoto

    /** A photo the backend holds (`BR-015`). */
    data class Stored(
        override val photoId: String,
        val jobId: String,
        override val phase: JobPhotoPhase?,
        override val note: String?,
    ) : ViewedJobPhoto
}

/**
 * The photo [photoId] names, resolved from the records that already state it.
 *
 * `null` means neither the device nor the reported Activity holds that photo any more, so the viewer
 * shows nothing rather than an image it cannot prove is the right one (`BR-042`).
 *
 * The device's own copy wins while it exists: it is the exact bytes the technician recorded, and the
 * backend's copy carries the same id, because the API records the device's operation id as the photo's
 * id (`docs/api/job-photos.md` §1).
 */
internal fun viewedJobPhoto(
    jobId: String,
    photoId: String,
    pendingPhotos: List<PendingJobPhoto>,
    activity: List<JobActivityEvent>?,
): ViewedJobPhoto? {
    val pending = pendingPhotos.firstOrNull { photo -> photo.photoId == photoId }
    if (pending != null) {
        return ViewedJobPhoto.Pending(
            photoId = pending.photoId,
            localPath = pending.localPath,
            phase = pending.phase,
            note = pending.note?.takeIf { it.isNotBlank() },
        )
    }

    val event = activity.orEmpty().firstOrNull { event -> event.photoId == photoId } ?: return null
    return ViewedJobPhoto.Stored(
        photoId = photoId,
        jobId = jobId,
        phase = jobPhotoPhaseOrNull(event.photoPhase),
        note = event.body?.takeIf { it.isNotBlank() },
    )
}

/**
 * What the viewer is showing for the photo it asked for (`BR-042`).
 *
 * The gesture layer draws the photo but does not report how its read ended, so the viewer keeps its
 * own three states rather than inferring one from pixels: the photo is being read, it is on screen, or
 * it cannot be shown at all.
 */
private enum class JobPhotoViewerImageState {
    /** The stack is still reading the photo. */
    READING,

    /** The photo is on screen, and the gesture layer draws it. */
    SHOWN,

    /** The photo could not be read, so the viewer says so instead of drawing something else. */
    UNAVAILABLE,
}

/**
 * One photo, full size, over the screen that holds it.
 *
 * The photo is drawn by the image stack, from the request this feature answers with
 * ([JobPhotoImages]): the device's own bytes while it still holds them, the backend's evidence
 * otherwise — so a session that may not read the evidence is refused by the API here exactly as it is
 * refused on a tile, and a photo that cannot be read or decoded is reported instead of being replaced
 * by another picture (`BR-007`, `BR-042`). Because the stack keeps what it decoded, opening the same
 * photo again is drawn from memory rather than read a second time (`D4b`).
 *
 * Since Phase 4d the photo is **zoomable and pannable** (`D9`): a pinch zooms, a drag pans within the
 * photo's bounds, a double-tap goes to the library's zoom ceiling and no scale goes below "fit". The
 * gestures belong to the photo's own area, which is the screen between the phase badge and the note,
 * so the badge, the note and the close action are untouched by them, and closing is unchanged — this
 * viewer's own action or the platform's back gesture.
 *
 * It is closed by its own action or by the platform's back gesture, and it holds nothing after that:
 * the photo's phase, note and bytes remain the record's and the device's (`BR-001`).
 */
@Composable
internal fun JobPhotoViewer(
    photo: ViewedJobPhoto,
    photoImages: JobPhotoImages,
    onDismiss: () -> Unit,
) {
    val image = when (photo) {
        is ViewedJobPhoto.Pending -> photoImages.localFullSize(photo.localPath)
        is ViewedJobPhoto.Stored -> photoImages.jobPhotoFullSize(photo.jobId, photo.photoId)
    }

    /*
     * The reading / shown / cannot-be-shown state the viewer presents, observed from the stack's own
     * result. The gesture layer has no slot for it, and the request the stack executes is the same one
     * the port answered with, so the read is listened to rather than repeated: Coil reports every
     * request's outcome through it, the memory and disk cache included, so a photo that comes back from
     * the cache still leaves the reading state (`BR-042`).
     */
    var imageState by remember(image) { mutableStateOf(JobPhotoViewerImageState.READING) }
    val request = remember(image) {
        image?.newBuilder()
            ?.listener(
                onSuccess = { _, _ -> imageState = JobPhotoViewerImageState.SHOWN },
                onError = { _, _ -> imageState = JobPhotoViewerImageState.UNAVAILABLE },
            )
            ?.build()
    }

    Dialog(
        onDismissRequest = onDismiss,
        // The photo takes the screen: the platform's default dialog width would leave it in a strip
        // (`D4`).
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.fillMaxSize().testTag(JobPhotoViewerTag),
        ) {
            Column(modifier = Modifier.fillMaxSize().systemBarsPadding()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    JobPhotoPhaseBadge(phase = photo.phase)
                    Spacer(Modifier.weight(1f))
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(48.dp).testTag(JobPhotoViewerCloseTag),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_close),
                            contentDescription = stringResource(R.string.job_photo_viewer_close),
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }

                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    if (request == null) {
                        // Nothing to draw at all: no session to read the photo under, or an
                        // implementation that draws none (`BR-042`).
                        JobPhotoViewerUnavailable()
                    } else {
                        ZoomableAsyncImage(
                            model = request,
                            contentDescription = stringResource(R.string.job_photo_image_description),
                            contentScale = ContentScale.Fit,
                            // The photo's own area, and the whole of it is the gesture surface: a pinch
                            // zooms, a drag pans within bounds and a double-tap goes to the zoom
                            // ceiling (`D9`). The badge, the note and the close action are outside this
                            // area, so zooming never puts them out of reach, and the dialog's closing
                            // is unaffected.
                            //
                            // The tag marks the photo only once the stack has drawn it, so a photo that
                            // could not be read is reported by the state below rather than by a node
                            // that says it is there.
                            modifier = Modifier
                                .fillMaxSize()
                                .then(
                                    if (imageState == JobPhotoViewerImageState.SHOWN) {
                                        Modifier.testTag(JobPhotoViewerImageTag)
                                    } else {
                                        Modifier
                                    },
                                ),
                        )
                        when (imageState) {
                            JobPhotoViewerImageState.READING -> CircularProgressIndicator(
                                strokeWidth = 2.dp,
                                modifier = Modifier
                                    .size(32.dp)
                                    .testTag(JobPhotoViewerLoadingTag),
                            )

                            JobPhotoViewerImageState.UNAVAILABLE -> JobPhotoViewerUnavailable()
                            JobPhotoViewerImageState.SHOWN -> Unit
                        }
                    }
                }

                val note = photo.note
                if (note != null) {
                    Text(
                        text = note,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp)
                            .padding(bottom = 20.dp),
                    )
                }
            }
        }
    }
}

/**
 * The report that the photo cannot be shown (`BR-042`).
 *
 * It is what the viewer draws when the stack was given nothing to load, and what it draws when the
 * stack's own read failed: a photo that cannot be read or decoded says so rather than showing a
 * different picture, and it carries the same test tag in both cases because the technician sees the
 * same thing.
 */
@Composable
private fun JobPhotoViewerUnavailable() {
    Text(
        text = stringResource(R.string.job_photo_viewer_unavailable),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .padding(horizontal = 24.dp)
            .testTag(JobPhotoViewerUnavailableTag),
    )
}

