package com.servora.android.ui.jobs

import androidx.compose.animation.core.SnapSpec
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.servora.android.R
import com.servora.android.data.jobs.JobPhotoBytesUnavailable
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
 * technician actually reads the evidence they recorded: one photo at a time, full size, with the phase
 * it was taken in and the note they wrote.
 *
 * It is a dedicated media surface rather than another form screen: a black ground that is the same in
 * both appearances, the photo filling it, and the smallest chrome that still says which photo this is
 * and what can be done with it (`docs/tracker/031-android-photo-viewer-ui.md`).
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

/**
 * Identifies the report shown when the photo is not on this device and cannot be read without
 * connectivity (`D5`, `BR-013`).
 *
 * It has its own tag because it is its own statement: the technician can act on it by reconnecting,
 * which is not true of the generic failure.
 */
const val JobPhotoViewerOfflineTag = "job-photo-viewer-offline"

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

/** Identifies the action that takes accepted evidence out of ordinary use (`BR-089`). */
const val JobPhotoViewerRemoveTag = "job-photo-viewer-remove"

/** Identifies the confirmation a removal states its reason in (`BR-089`). */
const val JobPhotoRemovalDialogTag = "job-photo-removal-dialog"

/** Identifies the reason field of the removal confirmation (`BR-089`). */
const val JobPhotoRemovalReasonTag = "job-photo-removal-reason"

/** Identifies the action that applies the removal (`BR-089`). */
const val JobPhotoRemovalConfirmTag = "job-photo-removal-confirm"

/** Identifies the action that abandons the removal (`BR-089`). */
const val JobPhotoRemovalCancelTag = "job-photo-removal-cancel"

/** Identifies the label above the photo's note (`BR-027`). */
const val JobPhotoViewerNoteLabelTag = "job-photo-viewer-note-label"

/** Identifies the photo's note itself (`BR-027`). */
const val JobPhotoViewerNoteTag = "job-photo-viewer-note"

/** Identifies the action that expands or collapses a note too long to read at once (`BR-027`). */
const val JobPhotoViewerNoteMoreTag = "job-photo-viewer-note-more"

/**
 * Identifies the upload state a pending photo reports inside the viewer (`§7`).
 *
 * It is its own tag rather than the tray's, because the tray is still composed behind the viewer: the
 * same photo can be on screen in both, and one tag would name two nodes.
 */
fun jobPhotoViewerStateTag(photoId: String): String = "job-photo-viewer-state-$photoId"

/*
 * The viewer's own ground and ink. They are deliberately not theme colours: the viewer is a media
 * surface, so a photo is read as the photo rather than as a card of the light or dark screen it was
 * opened from, and the chrome keeps its contrast over any photo underneath it (`BR-028`, `BR-042`).
 */
private val JobPhotoViewerBackground = Color.Black
private val JobPhotoViewerContent = Color.White
private val JobPhotoViewerMutedContent = Color.White.copy(alpha = 0.72f)

/**
 * The band the top bar is drawn on.
 *
 * A flat translucent black rather than a gradient, so the contrast the controls have over the photo is
 * a property of the viewer and not of whichever photo happens to be under them (`BR-042`).
 */
private val JobPhotoViewerBarScrim = Color.Black.copy(alpha = 0.62f)

/** The target every top-bar control is drawn at, so the close action and the evidence actions match. */
private val JobPhotoViewerControlSize = 48.dp

/** The most of the screen an expanded note may take before it scrolls, so the photo keeps its room. */
private val JobPhotoViewerNoteMaxHeight = 160.dp

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
 * own states rather than inferring one from pixels: the photo is being read, it is on screen, it could
 * not be shown at all, or it is not available without connectivity (`D5`).
 */
private enum class JobPhotoViewerImageState {
    /** The stack is still reading the photo. */
    READING,

    /** The photo is on screen, and the gesture layer draws it. */
    SHOWN,

    /** The photo could not be read, so the viewer says so instead of drawing something else. */
    UNAVAILABLE,

    /**
     * The photo is not on this device and the backend could not be reached, so it cannot be read
     * offline (`D5`, `BR-013`).
     */
    UNAVAILABLE_OFFLINE,
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
 * The photo is the screen. It is drawn on the viewer's own black ground — the same in both appearances,
 * because this is a media surface rather than a form — and fills everything between the system bars and
 * the note; the top bar and the badge are drawn **over** it rather than around it, so they cost the photo
 * nothing (`docs/tracker/031-android-photo-viewer-ui.md`).
 *
 * The chrome is three small things, each where it belongs rather than in one bar: the photo's phase
 * badge over the photo's own bottom-left corner, a top bar whose close action is on the left, the
 * position (`1 / 4`) in the centre and the two evidence actions on the right, and — under the photo —
 * the note the photo was recorded with (`BR-027`, `BR-028`). Every control is drawn in the viewer's own
 * ink over a scrim, so its contrast comes from the viewer rather than from the photo beneath it, and
 * every one of them is a 48 dp target (`BR-012`).
 *
 * Since Phase 4d the photo is **zoomable and pannable** (`D9`): a pinch zooms, a drag pans within the
 * photo's bounds, a double-tap goes to the library's zoom ceiling and no scale goes below "fit".
 * Since Phase 4e the viewer also **pages** (`D11`): the photos it was handed are swiped left and right
 * in the order Job Details presents them, and a swipe pages only while the photo on screen is at fit —
 * a zoomed photo pans, which is what keeps the two gestures unambiguous. The gesture surface is the
 * photo's own area, so the badge, the top bar and the note stay outside it, and closing is unchanged:
 * this viewer's own action or the platform's back gesture.
 *
 * The badge, the position, the note and the pending-upload state follow whichever photo is on screen.
 * Saving a photo on the device or handing it to another application are the technician's own explicit
 * actions (`D12`, `D13`, `BR-027`), and what one of them is doing or did is reported here, over the
 * photo: this viewer is a window of its own, so a report the screen hosted would be drawn behind it
 * (`BR-042`).
 *
 * [photos] is never empty: the caller opens the viewer only for a photo the sequence holds.
 * [reportHostState] is the screen's own, so one report is presented by whichever window is on screen.
 */
@Composable
internal fun JobPhotoViewer(
    photos: List<ViewedJobPhoto>,
    initialPage: Int,
    uploads: Map<String, JobPhotoSyncState>,
    canExportEvidence: Boolean,
    canRemoveEvidence: Boolean,
    export: JobPhotoExportAction?,
    removal: String?,
    onSave: (String) -> Unit,
    onShare: (String) -> Unit,
    onRemove: (String, String) -> Unit,
    photoImages: JobPhotoImages,
    reportHostState: SnackbarHostState,
    onDismiss: () -> Unit,
) {
    val pagerState = rememberPagerState(initialPage = initialPage) { photos.size }
    // The chrome follows the page being looked at rather than the one the pager has settled on, so a
    // badge, a position or an action never names a photo that is halfway off the screen.
    val currentPage = pagerState.currentPage.coerceIn(photos.indices)
    val currentPhoto = photos[currentPage]

    /*
     * The photo the removal is being confirmed for, or `null` while no confirmation is open.
     *
     * The decision is the manager's, so the reason and the confirmation are stated before the API is
     * asked to apply anything (`BR-067`, `BR-089`), and the dialog names the photo it is about.
     */
    var removalTargetId by remember { mutableStateOf<String?>(null) }

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
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(JobPhotoViewerBackground)
                .testTag(JobPhotoViewerTag),
        ) {
            Column(modifier = Modifier.fillMaxSize().systemBarsPadding()) {
                // The photo's own area: the page fills it, and the chrome that belongs to the photo
                // itself is drawn over it rather than taking room from it.
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    HorizontalPager(
                        state = pagerState,
                        // A zoomed photo pans within its own bounds; only a photo at fit is swiped on
                        // to the next one (`D11`).
                        userScrollEnabled = jobPhotoViewerPagingEnabled(zoomFraction),
                        modifier = Modifier.fillMaxSize().testTag(JobPhotoViewerPagerTag),
                    ) { page ->
                        JobPhotoViewerPage(
                            photo = photos[page],
                            isSettledPage = page == pagerState.settledPage,
                            photoImages = photoImages,
                            onZoomFraction = { fraction -> zoomFraction = fraction },
                        )
                    }

                    JobPhotoViewerTopBar(
                        // The position says which of the Job's photos is on screen, which is what
                        // makes the swipe legible (`D11`). A single photo has nothing to be positioned
                        // among, so it is not drawn at all.
                        position = if (photos.size > 1) {
                            stringResource(
                                R.string.job_photo_viewer_position,
                                currentPage + 1,
                                photos.size,
                            )
                        } else {
                            null
                        },
                        canExportEvidence = canExportEvidence,
                        canRemoveEvidence = canRemoveEvidence,
                        export = export,
                        removal = removal,
                        onSave = { onSave(currentPhoto.photoId) },
                        onShare = { onShare(currentPhoto.photoId) },
                        onRemove = { removalTargetId = currentPhoto.photoId },
                        onClose = onDismiss,
                        modifier = Modifier.align(Alignment.TopCenter),
                    )

                    JobPhotoPhaseBadge(
                        phase = currentPhoto.phase,
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(16.dp),
                    )
                }

                // A note belongs to the photo on screen: it is drawn under the photo rather than over
                // it, and a photo with no note reserves nothing (`BR-012`, `BR-027`).
                currentPhoto.note?.let { note ->
                    JobPhotoViewerNote(note = note, photoId = currentPhoto.photoId)
                }

                // A photo the backend has not accepted yet says so, as its tray tile does (`§7`), so
                // evidence the office holds is never confused with a photo that is only on this device.
                if (currentPhoto is ViewedJobPhoto.Pending) {
                    val uploadState = uploads[currentPhoto.photoId]
                    if (uploadState != null) {
                        Text(
                            text = stringResource(jobPhotoStateLabel(uploadState)),
                            style = MaterialTheme.typography.labelSmall,
                            color = JobPhotoViewerMutedContent,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp)
                                .padding(bottom = 12.dp)
                                .testTag(jobPhotoViewerStateTag(currentPhoto.photoId)),
                        )
                    }
                }
            }

            // What an evidence action is doing, or what it did, is drawn over the photo in this window:
            // the viewer is on top of the screen, so a report the screen hosted would be a save the
            // technician never saw (`BR-042`).
            JobActionSnackbarHost(
                hostState = reportHostState,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 12.dp),
            )
        }
    }

    // The confirmation is a window of its own over the viewer, because the removal is a decision about
    // recorded evidence: the manager states why, and nothing is applied until they confirm it
    // (`BR-067`, `BR-089`).
    removalTargetId?.let { photoId ->
        JobPhotoRemovalDialog(
            isRemoving = removal == photoId,
            onConfirm = { reason ->
                removalTargetId = null
                onRemove(photoId, reason)
            },
            onDismiss = { removalTargetId = null },
        )
    }
}

/**
 * The confirmation a removal states its reason in (`BR-067`, `BR-089`).
 *
 * Removing accepted evidence is a decision about a historical record, so it is confirmed explicitly:
 * the dialog says what it does, why a reason is required, and what it does **not** do — the photo is
 * not deleted, its record and history are preserved, and the removal itself is recorded
 * (`BR-088`, `BR-089`). The reason is the only input, and the action that applies the removal is
 * disabled until one is given, because the API refuses a removal without a reason and a dialog that
 * could send one would only produce a refusal.
 */
@Composable
private fun JobPhotoRemovalDialog(
    isRemoving: Boolean,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var reason by rememberSaveable { mutableStateOf("") }
    val canConfirm = reason.isNotBlank() && !isRemoving

    AlertDialog(
        onDismissRequest = { if (!isRemoving) onDismiss() },
        title = {
            Text(
                text = stringResource(R.string.job_photo_removal_title),
                modifier = Modifier.testTag(JobPhotoRemovalDialogTag),
            )
        },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.job_photo_removal_message),
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = reason,
                    onValueChange = { reason = it },
                    enabled = !isRemoving,
                    label = { Text(stringResource(R.string.job_photo_removal_reason_label)) },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                        .testTag(JobPhotoRemovalReasonTag),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(reason) },
                enabled = canConfirm,
                modifier = Modifier.testTag(JobPhotoRemovalConfirmTag),
            ) {
                Text(stringResource(R.string.job_photo_removal_confirm))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isRemoving,
                modifier = Modifier.testTag(JobPhotoRemovalCancelTag),
            ) {
                Text(stringResource(R.string.job_photo_removal_cancel))
            }
        },
    )
}

/**
 * One page of the viewer: one photo, filling the page, with its own gesture surface (`D11`, `D9`).
 *
 * The page is the photo and nothing else: the phase badge, the top bar and the note belong to the photo
 * on screen and are drawn by the viewer around it, so nothing is laid out over the evidence or competes
 * with the gestures for a touch.
 *
 * The settled page reports its own zoom to the chrome, and a page the pager has left forgets it rather
 * than reappearing magnified — the library's own recipe for a pager.
 */
@Composable
private fun JobPhotoViewerPage(
    photo: ViewedJobPhoto,
    isSettledPage: Boolean,
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
    // The failure itself carries *why* it failed, which is what tells a photo that is only missing
    // because this device is offline from one that cannot be shown at all (`D5`, `BR-013`).
    var imageState by remember(image) { mutableStateOf(JobPhotoViewerImageState.READING) }
    val request = remember(image) {
        image?.newBuilder()
            ?.listener(
                onSuccess = { _, _ -> imageState = JobPhotoViewerImageState.SHOWN },
                onError = { _, result ->
                    imageState = when (jobPhotoUnavailableReason(result.throwable)) {
                        JobPhotoBytesUnavailable.OFFLINE ->
                            JobPhotoViewerImageState.UNAVAILABLE_OFFLINE

                        JobPhotoBytesUnavailable.UNAVAILABLE ->
                            JobPhotoViewerImageState.UNAVAILABLE
                    }
                },
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

    Box(
        modifier = Modifier.fillMaxSize(),
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
                // bounds and a double-tap goes to the zoom ceiling (`D9`). The badge, the top bar and
                // the note are outside it, and closing is unaffected. The tag marks the photo only
                // once the stack has drawn it.
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
                    color = JobPhotoViewerContent,
                    strokeWidth = 2.dp,
                    modifier = Modifier
                        .size(32.dp)
                        .testTag(JobPhotoViewerLoadingTag),
                )

                JobPhotoViewerImageState.UNAVAILABLE -> JobPhotoViewerUnavailable()
                JobPhotoViewerImageState.UNAVAILABLE_OFFLINE -> JobPhotoViewerUnavailableOffline()
                JobPhotoViewerImageState.SHOWN -> Unit
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
    JobPhotoViewerReport(
        message = stringResource(R.string.job_photo_viewer_unavailable),
        tag = JobPhotoViewerUnavailableTag,
    )
}

/**
 * The report that the photo is not readable **offline** (`D5`, `BR-013`).
 *
 * Offline, evidence is only on the device when the technician captured it here or has already opened
 * it, so a photo that is in neither place is a state they can act on — connecting makes it readable
 * again — rather than a failure of the record. It is its own report, with its own tag, because it says
 * something the generic one does not (`BR-042`).
 */
@Composable
private fun JobPhotoViewerUnavailableOffline() {
    JobPhotoViewerReport(
        message = stringResource(R.string.job_photo_viewer_unavailable_offline),
        tag = JobPhotoViewerOfflineTag,
    )
}

/** One report the viewer draws over the photo area, in the muted ink its chrome uses (`BR-028`). */
@Composable
private fun JobPhotoViewerReport(message: String, tag: String) {
    Text(
        text = message,
        style = MaterialTheme.typography.bodyMedium,
        color = JobPhotoViewerMutedContent,
        modifier = Modifier
            .padding(horizontal = 24.dp)
            .testTag(tag),
    )
}

/**
 * The viewer's top bar: the close action, the photo's position, and the evidence actions (`D11`, `D12`,
 * `D13`, `BR-089`).
 *
 * It is an overlay on the photo rather than a row above it, so the photo keeps the whole screen; what
 * makes it readable over any photo is [JobPhotoViewerBarScrim], and the position is centred in the
 * screen rather than between the two groups, so it reads as a position and not as a label of either.
 *
 * The evidence actions are drawn only for a session the API would let perform them (`BR-006`, `BR-007`,
 * `BR-011`, `BR-015`): the two that read the photo's bytes need `evidence.view`, and removing recorded
 * evidence needs the Manager-level `evidence.photo.remove` (`BR-089`). While one of them is running it
 * reports that it is working, so a slow read cannot be asked for twice and a tap still in progress does
 * not look like a tap that did nothing (`BR-042`).
 */
@Composable
private fun JobPhotoViewerTopBar(
    position: String?,
    canExportEvidence: Boolean,
    canRemoveEvidence: Boolean,
    export: JobPhotoExportAction?,
    removal: String?,
    onSave: () -> Unit,
    onShare: () -> Unit,
    onRemove: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(JobPhotoViewerBarScrim)
            .padding(horizontal = 4.dp),
    ) {
        JobPhotoViewerControl(
            tag = JobPhotoViewerCloseTag,
            icon = R.drawable.ic_close,
            label = R.string.job_photo_viewer_close,
            working = false,
            enabled = true,
            onClick = onClose,
            modifier = Modifier.align(Alignment.CenterStart),
        )

        if (position != null) {
            Text(
                text = position,
                style = MaterialTheme.typography.titleSmall,
                color = JobPhotoViewerContent,
                maxLines = 1,
                modifier = Modifier
                    .align(Alignment.Center)
                    .testTag(JobPhotoViewerPositionTag),
            )
        }

        if (canExportEvidence || canRemoveEvidence) {
            Row(modifier = Modifier.align(Alignment.CenterEnd)) {
                if (canExportEvidence) {
                    JobPhotoViewerControl(
                        tag = JobPhotoViewerSaveTag,
                        icon = R.drawable.ic_download,
                        label = R.string.job_photo_viewer_save,
                        working = export == JobPhotoExportAction.SAVE,
                        enabled = export == null && removal == null,
                        onClick = onSave,
                    )
                    JobPhotoViewerControl(
                        tag = JobPhotoViewerShareTag,
                        icon = R.drawable.ic_share,
                        label = R.string.job_photo_viewer_share,
                        working = export == JobPhotoExportAction.SHARE,
                        enabled = export == null && removal == null,
                        onClick = onShare,
                    )
                }
                if (canRemoveEvidence) {
                    // Removing is drawn last, so it is the outermost action and never sits under the
                    // technician's thumb on the way to Save or Share (`BR-012`).
                    JobPhotoViewerControl(
                        tag = JobPhotoViewerRemoveTag,
                        icon = R.drawable.ic_trash,
                        label = R.string.job_photo_viewer_remove,
                        working = removal != null,
                        enabled = removal == null && export == null,
                        onClick = onRemove,
                    )
                }
            }
        }
    }
}

/**
 * One control of the top bar: its glyph, or the progress of the action it started.
 *
 * The action's own localized name is carried as the content description, because the glyph tells a
 * sighted technician what the action is and must tell a screen reader the same thing (`BR-028`).
 */
@Composable
private fun JobPhotoViewerControl(
    tag: String,
    icon: Int,
    label: Int,
    working: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled && !working,
        modifier = modifier.size(JobPhotoViewerControlSize).testTag(tag),
    ) {
        if (working) {
            CircularProgressIndicator(
                color = JobPhotoViewerContent,
                strokeWidth = 2.dp,
                modifier = Modifier.size(20.dp),
            )
        } else {
            Icon(
                painter = painterResource(icon),
                contentDescription = stringResource(label),
                tint = JobPhotoViewerContent,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

/**
 * The note the photo on screen was recorded with (`BR-027`).
 *
 * It is labelled, so a paragraph under a photo reads as the technician's note rather than as more photo
 * chrome, and it is under the photo rather than over it, so nothing is written across the evidence.
 *
 * A note that does not fit is read a few lines at a time with an explicit **More**, and a very long one
 * scrolls inside its own bounded area rather than pushing the photo out: the photo is the subject of
 * this screen (`BR-012`, `BR-015`). How a note is read — when it needs the action, how much of it is
 * shown at once — is the shared photo-note piece (`JobPhotoNote`), so this viewer and the timeline read
 * a note the same way.
 *
 * The expansion belongs to the photo it was asked for: paging on starts the next note collapsed.
 */
@Composable
private fun JobPhotoViewerNote(note: String, photoId: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(top = 12.dp, bottom = 8.dp),
    ) {
        Text(
            text = stringResource(R.string.job_photo_viewer_notes_label),
            style = MaterialTheme.typography.labelMedium,
            color = JobPhotoViewerMutedContent,
            modifier = Modifier.testTag(JobPhotoViewerNoteLabelTag),
        )
        JobPhotoNote(
            note = note,
            collapseKey = photoId,
            textColor = JobPhotoViewerContent,
            actionColor = JobPhotoViewerContent,
            maxExpandedHeight = JobPhotoViewerNoteMaxHeight,
            modifier = Modifier.padding(top = 4.dp),
            textTag = JobPhotoViewerNoteTag,
            actionTag = JobPhotoViewerNoteMoreTag,
        )
    }
}

