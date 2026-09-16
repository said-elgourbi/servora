package com.servora.android.ui.jobs

import androidx.compose.animation.core.SnapSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.servora.android.R
import com.servora.android.data.jobs.JobPhotoImages
import com.servora.android.domain.model.JobActivityEvent
import com.servora.android.domain.model.JobPhotoPhase
import com.servora.android.domain.model.JobPhotoSyncState
import com.servora.android.domain.model.PendingJobPhoto
import me.saket.telephoto.zoomable.ZoomableImageState
import me.saket.telephoto.zoomable.coil3.ZoomableAsyncImage
import me.saket.telephoto.zoomable.rememberZoomableImageState

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

/** Identifies the pages the viewer swipes between (`D11`). */
const val JobPhotoViewerPagerTag = "job-photo-viewer-pager"

/** Identifies the report of which photo of how many is on screen (`D11`). */
const val JobPhotoViewerPositionTag = "job-photo-viewer-position"

/** Identifies the action that saves the photo on the device (`D12`). */
const val JobPhotoViewerSaveTag = "job-photo-viewer-save"

/** Identifies the action that hands the photo to another application (`D13`). */
const val JobPhotoViewerShareTag = "job-photo-viewer-share"

/**
 * Identifies the upload state a pending photo reports inside the viewer (`§7`).
 *
 * It is its own tag rather than the tray's, because the tray is still composed behind the viewer: the
 * same photo can be on screen in both, and one tag would name two nodes.
 */
fun jobPhotoViewerStateTag(photoId: String): String = "job-photo-viewer-state-$photoId"

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
 * The Job's photos as the viewer pages through them (`D11`).
 *
 * The order is the order Job Details presents them in: the evidence the backend holds — the order Job
 * Activity reports it, which is the order the gallery above the Activity draws — and then the photos
 * this device still holds, oldest first, which is the order the tray lists them. A photo present in
 * both sets is listed once, so a swipe can never land on the same photo twice, and which copy of it is
 * read stays [viewedJobPhoto]'s decision: the device's own bytes while they exist, the backend's
 * evidence once they do not (`§5`, `§9`).
 *
 * A photo that cannot be placed by either record is left out rather than shown as something else
 * (`BR-042`).
 */
internal fun viewedJobPhotoSequence(
    jobId: String,
    pendingPhotos: List<PendingJobPhoto>,
    activity: List<JobActivityEvent>?,
): List<ViewedJobPhoto> {
    val accepted = jobActivityPhotos(activity).map { event -> event.photoId ?: event.id }
    val acceptedIds = accepted.toSet()
    val pending = pendingPhotos.map { photo -> photo.photoId }
    return (accepted + pending.filterNot { photoId -> photoId in acceptedIds })
        .mapNotNull { photoId -> viewedJobPhoto(jobId, photoId, pendingPhotos, activity) }
}

/** The page [photoId] is on, or `null` when the sequence no longer holds that photo (`BR-042`). */
internal fun jobPhotoViewerInitialPage(photos: List<ViewedJobPhoto>, photoId: String): Int? =
    photos.indexOfFirst { photo -> photo.photoId == photoId }.takeIf { page -> page >= 0 }

/**
 * Whether a swipe may page the viewer while the photo on screen is zoomed by `zoomFraction` (`D11`).
 *
 * Paging is allowed only while the photo is at "fit", which is what keeps this swipe and `D9`'s pan
 * from competing for the same gesture: a zoomed photo pans within its own bounds, and the technician
 * swipes on to the next photo once it is back at fit. `null` is a photo the stack has not laid out
 * yet — there is nothing to pan either, so a swipe pages.
 */
internal fun jobPhotoViewerPagingEnabled(zoomFraction: Float?): Boolean =
    zoomFraction == null || zoomFraction <= VIEWER_ZOOM_PAGING_MAX

/** The zoom fraction at or below which a photo counts as being at fit, so paging is allowed (`D11`). */
private const val VIEWER_ZOOM_PAGING_MAX = 0.1f

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
 * The Job's photos, full size, over the screen Job Details shows them from (`D4`, `D11`, `D12`, `D13`).
 *
 * The photo on screen is drawn by the image stack, from the request this feature answers with
 * ([JobPhotoImages]): the device's own bytes while it still holds them, the backend's evidence
 * otherwise — so a session that may not read the evidence is refused by the API here exactly as it is
 * refused on a tile, and a photo that cannot be read or decoded is reported instead of being replaced
 * by another picture (`BR-007`, `BR-042`). Because the stack keeps what it decoded, opening the same
 * photo again is drawn from memory rather than read a second time (`D4b`).
 *
 * Since Phase 4d the photo is **zoomable and pannable** (`D9`): a pinch zooms, a drag pans within the
 * photo's bounds, a double-tap goes to the library's zoom ceiling and no scale goes below "fit".
 * Since Phase 4e the viewer also **pages** (`D11`): the photos it was handed are swiped left and right
 * in the order Job Details presents them, and a swipe pages only while the photo on screen is at fit —
 * a zoomed photo pans, which is what keeps the two gestures unambiguous. The gesture surface is still
 * the photo's own area, so the phase badge, the position, the note and the close action are outside
 * it, and closing is unchanged: this viewer's own action or the platform's back gesture.
 *
 * The note belongs to the photo it describes and pages with it. The badge, the position and the two
 * evidence actions follow whichever photo is on screen, and saving a photo on the device or handing it
 * to another application are the technician's own explicit actions (`D12`, `D13`, `BR-027`).
 *
 * [photos] is never empty: the caller opens the viewer only for a photo the sequence holds.
 */
@Composable
internal fun JobPhotoViewer(
    photos: List<ViewedJobPhoto>,
    initialPage: Int,
    uploads: Map<String, JobPhotoSyncState>,
    canExportEvidence: Boolean,
    onSave: (String) -> Unit,
    onShare: (String) -> Unit,
    photoImages: JobPhotoImages,
    onDismiss: () -> Unit,
) {
    val pagerState = rememberPagerState(initialPage = initialPage) { photos.size }
    // The chrome follows the page being looked at rather than the one the pager has settled on, so a
    // badge, a position or an action never names a photo that is halfway off the screen.
    val currentPage = pagerState.currentPage.coerceIn(photos.indices)
    val currentPhoto = photos[currentPage]

    /*
     * How far the photo on screen is zoomed, reported by that page (`D11`). It decides whether a swipe
     * pages the viewer or pans the photo, so it is held here rather than inside a page: only the
     * settled page reports, and a page that is no longer settled clears it.
     */
    var zoomFraction by remember { mutableStateOf<Float?>(null) }

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
                    JobPhotoPhaseBadge(phase = currentPhoto.phase)
                    if (photos.size > 1) {
                        // The position says which of the Job's photos is on screen, which is what makes
                        // the swipe legible (`D11`).
                        Text(
                            text = stringResource(
                                R.string.job_photo_viewer_position,
                                currentPage + 1,
                                photos.size,
                            ),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            modifier = Modifier
                                .padding(start = 12.dp)
                                .testTag(JobPhotoViewerPositionTag),
                        )
                    }
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

                HorizontalPager(
                    state = pagerState,
                    // A zoomed photo pans within its own bounds; only a photo at fit is swiped on to
                    // the next one (`D11`).
                    userScrollEnabled = jobPhotoViewerPagingEnabled(zoomFraction),
                    modifier = Modifier.weight(1f).fillMaxWidth().testTag(JobPhotoViewerPagerTag),
                ) { page ->
                    JobPhotoViewerPage(
                        photo = photos[page],
                        isSettledPage = page == pagerState.settledPage,
                        uploadState = uploads[photos[page].photoId],
                        photoImages = photoImages,
                        onZoomFraction = { fraction -> zoomFraction = fraction },
                    )
                }

                if (canExportEvidence) {
                    JobPhotoViewerExportActions(
                        onSave = { onSave(currentPhoto.photoId) },
                        onShare = { onShare(currentPhoto.photoId) },
                    )
                }
            }
        }
    }
}

/**
 * One page of the viewer: one photo with its own gesture surface, the note it was recorded with, and —
 * for a photo the backend has not accepted yet — the state its upload is in (`D11`, `D9`, `§7`).
 *
 * The settled page reports its own zoom to the chrome, and a page the pager has left forgets it rather
 * than reappearing magnified — the library's own recipe for a pager.
 */
@Composable
private fun JobPhotoViewerPage(
    photo: ViewedJobPhoto,
    isSettledPage: Boolean,
    uploadState: JobPhotoSyncState?,
    photoImages: JobPhotoImages,
    onZoomFraction: (Float?) -> Unit,
) {
    val image = when (photo) {
        is ViewedJobPhoto.Pending -> photoImages.localFullSize(photo.localPath)
        is ViewedJobPhoto.Stored -> photoImages.jobPhotoFullSize(photo.jobId, photo.photoId)
    }

    // The reading / shown / cannot-be-shown state, observed from the stack's own result: the gesture
    // layer has no slot for it, and the request it executes is the one the port answered with, so a
    // photo that comes back from the memory or disk cache still leaves the reading state (`BR-042`).
    var imageState by remember(image) { mutableStateOf(JobPhotoViewerImageState.READING) }
    val request = remember(image) {
        image?.newBuilder()
            ?.listener(
                onSuccess = { _, _ -> imageState = JobPhotoViewerImageState.SHOWN },
                onError = { _, _ -> imageState = JobPhotoViewerImageState.UNAVAILABLE },
            )
            ?.build()
    }
    val zoomableState = rememberZoomableImageState()

    // A page the pager has left forgets its zoom, so swiping back into a photo starts it at fit (`D11`).
    LaunchedEffect(isSettledPage) {
        if (!isSettledPage) {
            zoomableState.zoomableState.resetZoom(animationSpec = SnapSpec())
        }
    }

    // What decides whether a swipe pages or pans is the settled page's zoom (`D11`); a page that is not
    // the settled one reports nothing, so a stale fraction cannot leave paging disabled.
    LaunchedEffect(zoomableState, isSettledPage) {
        if (isSettledPage) {
            snapshotFlow { zoomableState.zoomableState.zoomFraction }.collect { fraction ->
                onZoomFraction(fraction)
            }
        } else {
            onZoomFraction(null)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            if (request == null) {
                // Nothing to draw at all: no session to read the photo under, or an implementation that
                // draws none (`BR-042`).
                JobPhotoViewerUnavailable()
            } else {
                ZoomableAsyncImage(
                    model = request,
                    state = zoomableState,
                    contentDescription = stringResource(R.string.job_photo_image_description),
                    contentScale = ContentScale.Fit,
                    // The photo's own area is the gesture surface: a pinch zooms, a drag pans within
                    // bounds and a double-tap goes to the zoom ceiling (`D9`). The badge, the position,
                    // the note and the two actions are outside it, and closing is unaffected. The tag
                    // marks the photo only once the stack has drawn it.
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
            // The note pages with its photo, and a long one scrolls inside a bounded area rather than
            // pushing the photo out of the viewer (`BR-012`).
            Text(
                text = note,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = JobPhotoViewerNoteMaxHeight)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
                    .padding(top = 8.dp),
            )
        }

        // A photo the backend has not accepted yet says so, as its tray tile does (`§7`), so evidence
        // the office holds is never confused with a photo that is only on this device.
        if (photo is ViewedJobPhoto.Pending && uploadState != null) {
            Text(
                text = stringResource(jobPhotoStateLabel(uploadState)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(top = 4.dp)
                    .testTag(jobPhotoViewerStateTag(photo.photoId)),
            )
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

/**
 * The two ways a photo leaves Servora: it is saved into the device's own gallery, or handed to another
 * application through the platform's share sheet (`D12`, `D13`).
 *
 * Both are the technician's explicit actions, and both are drawn only when the session holds the
 * capability the API enforces for reading evidence (`BR-006`, `BR-011`, `BR-015`). Nothing here
 * records, edits or deletes a photo (`BR-027`).
 */
@Composable
private fun JobPhotoViewerExportActions(onSave: () -> Unit, onShare: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        JobPhotoViewerExportAction(
            tag = JobPhotoViewerSaveTag,
            icon = R.drawable.ic_download,
            label = R.string.job_photo_viewer_save,
            onClick = onSave,
        )
        JobPhotoViewerExportAction(
            tag = JobPhotoViewerShareTag,
            icon = R.drawable.ic_share,
            label = R.string.job_photo_viewer_share,
            onClick = onShare,
        )
    }
}

/** One evidence action: a target the height of the close action, with its glyph beside its label. */
@Composable
private fun RowScope.JobPhotoViewerExportAction(
    tag: String,
    icon: Int,
    label: Int,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.weight(1f).height(JobPhotoViewerActionHeight).testTag(tag),
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = stringResource(label),
            modifier = Modifier.padding(start = 8.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The height the viewer's evidence actions are drawn at, so both match the close action's target. */
internal val JobPhotoViewerActionHeight = 48.dp

/** The most of the screen a note may take before it scrolls, so the photo keeps the room it needs. */
private val JobPhotoViewerNoteMaxHeight = 160.dp

