package com.servora.android.ui.jobs

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.SubcomposeAsyncImage
import coil3.request.ImageRequest
import com.servora.android.R
import com.servora.android.data.jobs.JobPhotoBytesUnavailable
import com.servora.android.data.jobs.JobPhotoBytesUnavailableException
import com.servora.android.data.jobs.JobPhotoImages
import com.servora.android.domain.model.JobActivityEvent
import com.servora.android.domain.model.JobActivityKind
import com.servora.android.domain.model.EvidencePhase
import com.servora.android.domain.model.JobPhotoSyncState
import com.servora.android.domain.model.evidencePhaseOrNull

/*
 * The photo-evidence pieces the Job Details screen and the Job Activity section draw
 * (`BR-015`, `BR-027`).
 *
 * They are presentation only: what phase or note a photo carries, whether it was accepted and what an
 * upload is doing are all decided elsewhere — by the technician's own input and by the backend
 * (`BR-001`, `BR-042`).
 */

/** Identifies the large phase control of the review panel. */
const val EvidencePhaseSelectorTag = "evidence-phase-selector"

/** Identifies one phase the technician can choose. */
fun evidencePhaseOptionTag(phase: EvidencePhase): String = "evidence-phase-${phase.name}"

/** Identifies the gallery of photos the backend accepted, at the head of Job Activity. */
const val JobPhotoGalleryTag = "job-photo-gallery"

/**
 * Identifies the gallery's heading, which is also the control that shows and hides its photos.
 *
 * It is one target rather than a chevron beside a label, so a tap anywhere on the row folds the
 * gallery (`BR-012`).
 */
const val JobPhotoGalleryToggleTag = "job-photo-gallery-toggle"

/** Identifies the compact preview the folded gallery draws beside its heading. */
const val JobPhotoGalleryPreviewTag = "job-photo-gallery-preview"

/** Identifies one photo in the gallery. */
fun jobPhotoGalleryTileTag(photoId: String): String = "job-photo-gallery-$photoId"

/** Identifies the photo one Job Activity entry carries as its own evidence (`BR-015`, `BR-080`). */
fun jobPhotoActivityPhotoTag(photoId: String): String = "job-activity-photo-$photoId"

/** Identifies that photo's note inside its Job Activity entry (`BR-027`). */
fun jobPhotoActivityNoteTag(photoId: String): String = "job-activity-photo-note-$photoId"

/** Identifies the action that expands a note too long to read at once inside its entry (`BR-027`). */
fun jobPhotoActivityNoteActionTag(photoId: String): String =
    "job-activity-photo-note-action-$photoId"


/**
 * The three field-work phases, as large segmented buttons.
 *
 * They are deliberately not radio buttons: the technician taps one of three big targets with a whole
 * hand while standing at a machine, and the selected one is filled so the current choice is readable
 * at a glance (`BR-012`). The phase is the API's stable code (`BR-041`); only the label is localized.
 */
@Composable
internal fun EvidencePhaseSelector(
    selected: EvidencePhase?,
    enabled: Boolean,
    onSelect: (EvidencePhase) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().testTag(EvidencePhaseSelectorTag),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        EvidencePhase.entries.forEach { phase ->
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
                    .height(EvidencePhaseButtonHeight)
                    .testTag(evidencePhaseOptionTag(phase)),
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Text(
                        text = stringResource(evidencePhaseLabel(phase)),
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
 * (`D4b`). Until it is there — and when it cannot be drawn at all — the tile draws its own state
 * instead: a tile's phase, note and synchronization state are what the technician acts on, and a
 * missing preview must never be presented as a different photo (`BR-042`).
 *
 * A tile that cannot get its photo for want of connectivity says **that**, because offline the bytes are
 * only there when the device already holds them or the stack cached them (`D5`, `JobPhotoThumbnail`'s
 * [JobPhotoUnavailableTile]).
 *
 * [image] is `null` when there is nothing to draw, which is the state a preview shows while no request
 * was built at all.
 *
 * `contentScale` is the one thing the call sites decide, because how much of the photo is drawn is
 * what the surface is for and not a property of the stack: a **tile** crops, being a square summary of
 * the photo it stands for, while the **review panel** fits, because the technician is checking the
 * photo they are about to add and a cropped preview would hide part of what they are accepting
 * (`BR-012`, `BR-015`).
 */
@Composable
internal fun JobPhotoThumbnail(
    image: ImageRequest?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
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
                contentScale = contentScale,
                modifier = Modifier.fillMaxSize(),
                loading = { JobPhotoMissingPreview(contentDescription) },
                error = { errorState ->
                    JobPhotoUnavailableTile(
                        reason = jobPhotoUnavailableReason(errorState.result.throwable),
                        contentDescription = contentDescription,
                    )
                },
            )
        }
    }
}

/**
 * What a tile draws when its photo could not be produced (`D5`, `BR-042`).
 *
 * Offline is the state worth naming: the technician can act on it, because connecting is what makes the
 * photo readable again. Everything else — a refusal, a photo this device no longer holds — keeps the
 * camera glyph, which is also what a tile draws while its photo is still arriving; the full-size viewer
 * is where either is spelled out.
 */
@Composable
private fun JobPhotoUnavailableTile(
    reason: JobPhotoBytesUnavailable,
    contentDescription: String?,
) {
    if (reason == JobPhotoBytesUnavailable.OFFLINE) {
        Icon(
            painter = painterResource(R.drawable.ic_cloud_off),
            contentDescription = stringResource(R.string.job_photo_offline_description),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(28.dp),
        )
    } else {
        JobPhotoMissingPreview(contentDescription)
    }
}

/**
 * Why the image stack could not produce a photo (`D5`, `BR-042`).
 *
 * The stack hands the failure back to the surface that asked for it, carrying the reason the read gave
 * ([JobPhotoBytesUnavailableException]); a failure this build did not raise is not offline, because
 * anything that did reach the backend is not a connectivity problem.
 */
internal fun jobPhotoUnavailableReason(throwable: Throwable?): JobPhotoBytesUnavailable =
    (throwable as? JobPhotoBytesUnavailableException)?.reason ?: JobPhotoBytesUnavailable.UNAVAILABLE

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
internal fun EvidencePhaseBadge(phase: EvidencePhase?, modifier: Modifier = Modifier) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = modifier,
    ) {
        Text(
            text = stringResource(evidencePhaseShortLabel(phase)),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

internal val EvidencePhaseButtonHeight = 56.dp
internal val JobPhotoTileSize = 132.dp

/** The height the gallery's heading keeps, so its whole row is a comfortable target (`BR-012`). */
internal val JobPhotoGalleryHeadingHeight = 48.dp

/** How large a photo is drawn in the folded gallery's compact preview. */
internal val JobPhotoGalleryPreviewSize = 32.dp

/**
 * How many photos the folded gallery previews.
 *
 * Enough to say evidence exists without turning the heading into a second strip (`BR-012`).
 */
internal const val JobPhotoGalleryPreviewCount = 3

/**
 * The size one photo is drawn at inside a Job Activity entry.
 *
 * It crops, being a summary that stands for the photo rather than the photo itself, and it is
 * deliberately smaller than the viewer: a Job with dozens of photo entries must stay a chronological
 * account a reader scans rather than a wall of full-width pictures (`BR-012`, `BR-080`).
 */
internal val JobPhotoActivityPhotoWidth = 176.dp
internal val JobPhotoActivityPhotoHeight = 132.dp

/** How much of a photo's note is read before the technician asks for the rest (`BR-012`). */
internal const val JobPhotoNoteCollapsedLines = 3

private val JobPhotoBadgeSpacing = 6.dp
private const val JobPhotoNoteLength = 60

/**
 * The photos the backend accepted, as a collapsible gallery at the head of Job Activity (`BR-080`).
 *
 * It is a projection of the timeline, never a second list: the photos it draws are exactly the
 * `JOB_PHOTO_ADDED` entries the API returned, so no client can disagree with the backend about which
 * evidence exists (`BR-001`). Its heading says how many there are and the whole heading is the
 * control that folds the strip away, so a Job with a dozen photos does not have to push the account of
 * what happened off the screen to prove they exist (`BR-012`).
 *
 * The gallery starts folded, because the timeline draws each photo inside the entry that recorded it:
 * the section above it is for browsing the whole collection, which is a deliberate act rather than the
 * default reading order (`BR-080`). While it is folded the heading still previews its first few
 * photos, so evidence stays visible without opening anything.
 *
 * The state is presentation and belongs to this section: it survives recomposition and a configuration
 * change, and it is not persisted beyond the Job on screen (`BR-042`).
 *
 * A tile is a preview that opens the photo full size when it is tapped (`D4`), which is where the
 * design puts an attachment's viewer (`Figma/…/servora-job-details-spec.md` §11).
 */
@Composable
internal fun JobPhotoGallerySection(
    photos: List<JobActivityEvent>,
    jobId: String,
    photoImages: JobPhotoImages,
    onOpenPhoto: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Held for the Job on screen: looking at another Job starts its own gallery folded, and nothing
    // about the choice outlives the screen.
    var expanded by rememberSaveable(jobId) { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxWidth().testTag(JobPhotoGalleryTag)) {
        JobPhotoGalleryHeading(
            count = photos.size,
            preview = if (expanded) emptyList() else jobPhotoGalleryPreview(photos),
            jobId = jobId,
            photoImages = photoImages,
            expanded = expanded,
            onToggle = { expanded = !expanded },
        )

        if (expanded) {
            JobPhotoGallery(
                photos = photos,
                jobId = jobId,
                photoImages = photoImages,
                onOpenPhoto = onOpenPhoto,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

/**
 * The gallery's heading: how many photos the Job has, a compact preview while it is folded, and the
 * chevron that says the strip opens.
 *
 * Its label comes from the same `section_count_format` string every other section heading uses, so the
 * headings cannot drift apart (`BR-041`), and the chevron turns a quarter turn while the strip is
 * shown — the affordance the manager home's and the customer detail's disclosures already use
 * (`docs/design/android-design-system.md`). Its content description names the action, never the state.
 */
@Composable
private fun JobPhotoGalleryHeading(
    count: Int,
    preview: List<JobActivityEvent>,
    jobId: String,
    photoImages: JobPhotoImages,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(JobPhotoGalleryToggleTag)
            .heightIn(min = JobPhotoGalleryHeadingHeight)
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onToggle),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            modifier = Modifier.weight(1f),
            text = stringResource(
                R.string.section_count_format,
                stringResource(R.string.job_photo_gallery_label),
                count,
            ),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        if (preview.isNotEmpty()) {
            Row(
                modifier = Modifier.testTag(JobPhotoGalleryPreviewTag),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                preview.forEach { event ->
                    JobPhotoThumbnail(
                        image = photoImages.jobPhotoThumbnail(jobId, event.photoId ?: event.id),
                        // The preview stands for photos the heading already counts, and the row it sits
                        // in is the control: announcing an unlabelled copy of each would say the same
                        // thing three times (`BR-028`).
                        contentDescription = null,
                        modifier = Modifier.size(JobPhotoGalleryPreviewSize),
                    )
                }
            }
        }

        Icon(
            painter = painterResource(R.drawable.ic_chevron_right),
            contentDescription = stringResource(
                if (expanded) {
                    R.string.job_photo_gallery_collapse
                } else {
                    R.string.job_photo_gallery_expand
                },
            ),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .size(18.dp)
                .rotate(if (expanded) 90f else 0f),
        )
    }
}

/**
 * The horizontal strip of photos the backend accepted, in the order Job Activity reports them.
 *
 * A tile is a preview that opens the photo full size when it is tapped (`D4`). The strip scrolls, so a
 * Job with more photos than fit says so by continuing rather than by shrinking its tiles.
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
        modifier = modifier.fillMaxWidth(),
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
            EvidencePhaseBadge(
                phase = evidencePhaseOrNull(event.photoPhase),
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

/**
 * One evidence record's note, with the action a note that does not fit needs (`BR-012`, `BR-027`).
 *
 * It is **both kinds'** note treatment — a photo's and a recording's — because an evidence note is one
 * idea: a piece of text an author recorded with the evidence, read where that evidence is drawn. A
 * second treatment could only read differently from this one (`BR-041`).
 *
 * Whether a note needs the action is answered by the layout it is actually drawn in — its language, its
 * own length and the font scale all decide it — rather than by a character count a different device would
 * get wrong, and a note that fits draws no action at all, so a short one reserves no room. The action
 * is a text action rather than a filled button: it is a small, secondary control under the note, and
 * Material keeps its target at 48 dp even at this size (`BR-012`).
 *
 * [maxExpandedHeight] bounds the whole note where something else has to keep its room — the viewer's
 * photo, which must not be pushed off the screen — and is `null` where the note simply grows inside
 * something that already scrolls, as a Job Activity entry does.
 *
 * The expansion belongs to the evidence record it was asked for ([collapseKey]), so paging on or looking
 * at another entry's evidence starts the next note collapsed.
 */
@Composable
internal fun EvidenceNote(
    note: String,
    collapseKey: Any?,
    textColor: Color,
    actionColor: Color,
    modifier: Modifier = Modifier,
    maxExpandedHeight: Dp? = null,
    textTag: String? = null,
    actionTag: String? = null,
) {
    var expanded by remember(collapseKey) { mutableStateOf(false) }
    // Whether this note has more to read. It is kept while expanded, which is what leaves the note's
    // own action available to collapse it again.
    var hasMore by remember(collapseKey) { mutableStateOf(false) }

    Column(modifier = modifier) {
        Text(
            text = note,
            style = MaterialTheme.typography.bodyMedium,
            color = textColor,
            maxLines = if (expanded) Int.MAX_VALUE else JobPhotoNoteCollapsedLines,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { layout -> if (!expanded) hasMore = layout.hasVisualOverflow },
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (expanded && maxExpandedHeight != null) {
                        Modifier
                            .heightIn(max = maxExpandedHeight)
                            .verticalScroll(rememberScrollState())
                    } else {
                        Modifier
                    },
                )
                .testTagOrNone(textTag),
        )
        if (hasMore) {
            TextButton(
                onClick = { expanded = !expanded },
                contentPadding = PaddingValues(horizontal = 8.dp),
                modifier = Modifier.testTagOrNone(actionTag),
            ) {
                Text(
                    text = stringResource(
                        if (expanded) {
                            R.string.job_photo_notes_less
                        } else {
                            R.string.job_photo_notes_more
                        },
                    ),
                    style = MaterialTheme.typography.labelLarge,
                    color = actionColor,
                )
            }
        }
    }
}

/** [tag] as a test tag, or nothing when the call site does not label the node. */
private fun Modifier.testTagOrNone(tag: String?): Modifier =
    if (tag == null) this else testTag(tag)

/** The photos Job Activity reports, as the gallery draws them (`BR-080`). */
internal fun jobActivityPhotos(activity: List<JobActivityEvent>?): List<JobActivityEvent> =
    activity.orEmpty().filter { event ->
        event.kind == JobActivityKind.JOB_PHOTO_ADDED && event.photoId != null
    }

/**
 * The few photos the folded gallery previews.
 *
 * It is the same collection in the same order, only shortened: the preview exists so evidence stays
 * visible while the section is folded away, and it is never a second list (`BR-001`, `BR-080`).
 */
internal fun jobPhotoGalleryPreview(photos: List<JobActivityEvent>): List<JobActivityEvent> =
    photos.take(JobPhotoGalleryPreviewCount)

/** A note trimmed to what a tile can show. */
internal fun jobPhotoNoteSnippet(note: String): String =
    if (note.length <= JobPhotoNoteLength) note else "${note.take(JobPhotoNoteLength - 1)}…"

/** The localized label of a phase the technician chooses from. */
internal fun evidencePhaseLabel(phase: EvidencePhase): Int =
    when (phase) {
        EvidencePhase.BEFORE_WORK -> R.string.evidence_phase_before
        EvidencePhase.DURING_WORK -> R.string.evidence_phase_during
        EvidencePhase.AFTER_WORK -> R.string.evidence_phase_after
    }

/**
 * The label a badge carries.
 *
 * It is the same word the selector uses, so the badge and the choice the technician made cannot read
 * differently (`BR-028`). A photo whose phase cannot be read says so rather than showing a guess.
 */
internal fun evidencePhaseShortLabel(phase: EvidencePhase?): Int =
    phase?.let { evidencePhaseLabel(it) } ?: R.string.evidence_phase_unknown

/** The localized report of what an upload is doing, as the tray shows it (`§7`). */
internal fun jobPhotoStateLabel(state: JobPhotoSyncState): Int =
    when (state) {
        JobPhotoSyncState.QUEUED -> R.string.evidence_state_queued
        JobPhotoSyncState.UPLOADING -> R.string.evidence_state_uploading
        JobPhotoSyncState.RETRYING -> R.string.evidence_state_retrying
        JobPhotoSyncState.REFUSED -> R.string.evidence_state_refused
    }

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
        JobPhotoFailure.EXPORT_UNREADABLE -> R.string.job_photo_error_export_unreadable
        JobPhotoFailure.EXPORT_FAILED -> R.string.job_photo_error_export_failed
        JobPhotoFailure.SHARE_UNAVAILABLE -> R.string.job_photo_error_share_unavailable
        JobPhotoFailure.SAVE_PERMISSION_DENIED -> R.string.job_photo_error_save_permission
        JobPhotoFailure.REMOVAL_NOT_PERMITTED -> R.string.job_photo_error_removal_not_permitted
        JobPhotoFailure.REMOVAL_NO_LONGER_AVAILABLE ->
            R.string.job_photo_error_removal_unavailable

        JobPhotoFailure.REMOVAL_UNREACHABLE -> R.string.job_photo_error_removal_unreachable
        JobPhotoFailure.REMOVAL_FAILED -> R.string.job_photo_error_removal_failed
    }

/** The localized report of what the last photo action did (`§7`). */
internal fun jobPhotoMessageText(message: JobPhotoMessage): Int =
    when (message) {
        JobPhotoMessage.QUEUED -> R.string.job_photo_saved_message
        JobPhotoMessage.SAVED_TO_DEVICE -> R.string.job_photo_saved_to_device_message
        JobPhotoMessage.SHARED -> R.string.job_photo_shared_message
        JobPhotoMessage.EVIDENCE_REMOVED -> R.string.job_photo_removed_message
    }

