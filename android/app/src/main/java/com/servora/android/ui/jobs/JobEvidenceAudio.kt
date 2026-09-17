package com.servora.android.ui.jobs

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.servora.android.R
import com.servora.android.data.jobs.JobAudioPlayback
import com.servora.android.data.jobs.JobAudioPlaybackProgress
import com.servora.android.domain.model.JobActivityEvent
import com.servora.android.domain.model.evidencePhaseOrNull
import kotlin.math.roundToInt

/*
 * The audio-evidence pieces the Job Activity timeline draws (`BR-091`, `ADR-018` A9).
 *
 * They are presentation only: whether a recording is playing is the device player's own answer, and
 * whether one may be removed is the capability the API enforces. Nothing here decides either (`BR-001`,
 * `BR-007`, `BR-042`).
 */

/** Identifies the play/pause control of one recording in the timeline. */
fun jobAudioPlayTag(audioNoteId: String): String = "job-activity-audio-play-$audioNoteId"

/** Identifies the elapsed and stated length of one recording in the timeline (`ADR-018` A11). */
fun jobAudioPositionTag(audioNoteId: String): String = "job-activity-audio-position-$audioNoteId"

/** Identifies the seek track of one recording in the timeline (`ADR-018` A11). */
fun jobAudioSeekTag(audioNoteId: String): String = "job-activity-audio-seek-$audioNoteId"

/** Identifies the removal control of one recording in the timeline (`BR-089`). */
fun jobAudioRemoveTag(audioNoteId: String): String = "job-activity-audio-remove-$audioNoteId"

/** Identifies one recording's note in the timeline. */
fun jobAudioActivityNoteTag(audioNoteId: String): String = "job-activity-audio-note-$audioNoteId"

/** Identifies the action a recording's note offers when it does not fit. */
fun jobAudioActivityNoteActionTag(audioNoteId: String): String =
    "job-activity-audio-note-more-$audioNoteId"

/** Identifies the confirmation one recording's removal states its reason in (`BR-089`). */
const val JobAudioRemovalDialogTag = "job-audio-removal-dialog"

/** Identifies the reason field of the removal confirmation (`BR-089`). */
const val JobAudioRemovalReasonTag = "job-audio-removal-reason"

/** Identifies the action that applies a recording's removal (`BR-089`). */
const val JobAudioRemovalConfirmTag = "job-audio-removal-confirm"

/** Identifies the action that abandons a recording's removal (`BR-089`). */
const val JobAudioRemovalCancelTag = "job-audio-removal-cancel"

/** How large the touch target of the play/pause control is, on either surface that plays a recording. */
internal val JobAudioControlTargetSize = 48.dp

/** How large the circle the play/pause glyph sits in is drawn. */
private val JobAudioControlCircleSize = 40.dp

/** How large the play/pause glyph is drawn inside its circle. */
private val JobAudioControlGlyphSize = 22.dp

/** How thick the progress ring is drawn inside the control that started the work (`BR-042`). */
private val JobAudioProgressStrokeWidth = 2.dp

/** How large a recording's removal glyph is drawn, and the ring that reports its own progress. */
private val JobAudioRemoveIconSize = 20.dp

/** The inset a recording's attachment keeps around its controls, and the gap between them. */
private val JobAudioAttachmentInset = 4.dp
private val JobAudioAttachmentSpacing = 4.dp

/** The least height a recording's attachment keeps, so its play control is comfortable one-handed. */
private val JobAudioAttachmentMinHeight = 56.dp

/**
 * How tall the seek track's own interaction area is (`ADR-018` A11).
 *
 * The track is drawn as a thin line; this is the height a thumb can actually reach, and it is the same
 * 48 dp target the play control keeps, so a technician can move through a recording one-handed
 * (`BR-012`).
 */
internal val JobAudioSeekTrackHeight = 48.dp

/** How thick the seek track is drawn: a line a playhead runs along, not a second control. */
private val JobAudioSeekTrackThickness = 6.dp

/** How many milliseconds a second is, for stating a position in whole seconds (`ADR-018` A11). */
private const val MILLIS_PER_SECOND = 1_000

/** The audio kind's own copy and tags for the removal confirmation (`BR-089`, `ADR-018` A7). */
internal val JobAudioRemovalCopy = EvidenceRemovalCopy(
    tag = JobAudioRemovalDialogTag,
    reasonTag = JobAudioRemovalReasonTag,
    confirmTag = JobAudioRemovalConfirmTag,
    cancelTag = JobAudioRemovalCancelTag,
    title = R.string.job_audio_removal_title,
    message = R.string.job_audio_removal_message,
    reasonLabel = R.string.job_audio_removal_reason_label,
    confirm = R.string.job_audio_removal_confirm,
    cancel = R.string.job_audio_removal_cancel,
)

/**
 * What the timeline's recordings are doing, and what the session may do with them (`ADR-018` A9, A11).
 *
 * It is one holder rather than eight parameters, because the facts and the actions describe one subject
 * — the recordings on this Job — and the timeline is drawn the same way whether the session may remove
 * evidence or only play it (`BR-011`).
 *
 * [progress] is the player's moving answer, held as a [State] rather than as a value: it changes ten times
 * a second while a recording plays, and what reads it is the playhead and the elapsed label of the
 * recording the player holds, so the timeline around them is not recomposed for a moving position
 * (`BR-012`).
 */
internal data class JobActivityAudio(
    /** The recording the device's player holds, and whether it is moving, or `null` (`ADR-018` A11). */
    val playback: JobAudioPlayback? = null,
    /** Where the recording the player holds has got to (`ADR-018` A11). */
    val progress: State<JobAudioPlaybackProgress?> = NoJobAudioProgress,
    /** The recording whose bytes are being read before it can play, or `null` (`BR-042`). */
    val playbackLoading: String? = null,
    /** The recording a removal is running for, or `null` (`BR-089`). */
    val removal: String? = null,
    /** Whether the session holds `evidence.audio.remove` (`BR-089`, `ADR-018` A7). */
    val canRemoveEvidence: Boolean = false,
    val onTogglePlayback: (String) -> Unit = {},
    /** Moves the recording the player holds to a position the technician chose (`ADR-018` A11). */
    val onSeek: (audioNoteId: String, positionMillis: Int) -> Unit = { _, _ -> },
    val onRemove: (String) -> Unit = {},
)

/**
 * The answer a surface draws before the player holds anything: nothing has played, so there is no
 * position to state (`A11`). It is one shared value, so every piece that defaults to it stays equal to
 * itself across recompositions.
 */
private val NoJobAudioProgress: State<JobAudioPlaybackProgress?> = mutableStateOf(null)

/**
 * One accepted recording, drawn under the Activity entry that recorded it (`BR-080`, `BR-091`).
 *
 * A photo is drawn as the photo itself; a recording cannot be, so this is what stands for it: the
 * **play control** that plays it back, its **position** — how far it has played and how long it is, that
 * length being the one the **API read from the recording's own container** (`ADR-018` A3) so a client
 * states a length it never measured — the **seek track** that moves the playhead through it (`A11`), and
 * the phase badge every evidence kind carries (`ADR-018` A4), so no two surfaces can read differently
 * (`BR-028`). Its note is read under it, as a photo's is.
 *
 * Removing it is the audio kind's own Manager-level action (`BR-089`, `ADR-018` A7), drawn only for a
 * session the API would let perform it; the recording whose removal is running reports that progress
 * instead of being drawn again (`BR-042`).
 *
 * A recording whose id this build cannot read is left undescribed rather than drawn as some other
 * recording: the entry still says what it recorded (`BR-042`).
 */
@Composable
internal fun ActivityAudioEvidence(
    event: JobActivityEvent,
    audio: JobActivityAudio,
) {
    val audioNoteId = event.audioNoteId ?: return
    // Only the recording the player holds is drawn as playing, and a paused one is held rather than
    // playing: its control returns to **Play** while its playhead keeps the position it reached
    // (`ADR-018` A11).
    val isPlaying = audio.playback?.takeIf { it.audioNoteId == audioNoteId }?.isPlaying == true

    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Surface(
            color = MaterialTheme.colorScheme.secondary,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(horizontal = JobAudioAttachmentInset)) {
                Row(
                    modifier = Modifier.heightIn(min = JobAudioAttachmentMinHeight),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(JobAudioAttachmentSpacing),
                ) {
                    JobAudioPlayControl(
                        tag = jobAudioPlayTag(audioNoteId),
                        isPlaying = isPlaying,
                        isLoading = audio.playbackLoading == audioNoteId,
                        // A recording being removed is not one to start listening to, and one whose bytes
                        // are still being read cannot be started twice (`BR-042`).
                        enabled = audio.removal != audioNoteId,
                        onClick = { audio.onTogglePlayback(audioNoteId) },
                    )

                    // Where the recording is and how long it is, stated together: the playhead moves
                    // along the track under them, and the total is the same length this entry has always
                    // stated, so one recording is not given two lengths (`BR-041`, `A11`).
                    JobAudioPositionLabel(
                        tag = jobAudioPositionTag(audioNoteId),
                        progress = audio.progress,
                        audioNoteId = audioNoteId,
                        totalSeconds = event.audioDurationSeconds,
                    )

                    Spacer(Modifier.weight(1f))

                    EvidencePhaseBadge(phase = evidencePhaseOrNull(event.audioPhase))

                    if (audio.canRemoveEvidence) {
                        JobAudioRemoveControl(
                            tag = jobAudioRemoveTag(audioNoteId),
                            isRemoving = audio.removal == audioNoteId,
                            enabled = audio.removal == null,
                            onClick = { audio.onRemove(audioNoteId) },
                        )
                    }
                }

                // The track is the attachment's own second line, drawn full width, so a thumb can be put
                // anywhere in a recording rather than on a stub beside the controls (`BR-012`, `A11`).
                JobAudioSeekTrack(
                    tag = jobAudioSeekTag(audioNoteId),
                    progress = audio.progress,
                    audioNoteId = audioNoteId,
                    // A recording being removed is not one to move through, and one the player does not
                    // hold has no timebase to seek in (`BR-042`).
                    enabled = audio.removal != audioNoteId,
                    onSeek = { positionMillis -> audio.onSeek(audioNoteId, positionMillis) },
                )
            }
        }

        val note = event.body?.takeIf { it.isNotBlank() }
        if (note != null) {
            EvidenceNote(
                note = note,
                collapseKey = audioNoteId,
                textColor = MaterialTheme.colorScheme.onSurfaceVariant,
                actionColor = MaterialTheme.colorScheme.primary,
                textTag = jobAudioActivityNoteTag(audioNoteId),
                actionTag = jobAudioActivityNoteActionTag(audioNoteId),
            )
        }
    }
}

/**
 * The play/pause control of one recording (`ADR-018` A9).
 *
 * One control is both the play and the pause, and which of the two it is drawn as comes from the device
 * player rather than from the tap that asked for it, so a recording that ends on its own returns this
 * control to **Play** (`BR-042`). While the bytes are being read it reports that it is working instead,
 * because an accepted recording is fetched on its first play and a tap still in progress must not look
 * like a tap that did nothing (`BR-012`).
 *
 * It is a filled circle in `primary` carrying an `onPrimary` glyph — the shape the device's own voice
 * notes already use — inside a 48 dp target, so the attachment reads as something to listen to before it
 * reads as anything else, and a thumb in the field hits it first time (`BR-012`). A control that cannot
 * be used is filled quietly rather than drawn as one that would work if it were tapped (`BR-042`).
 *
 * It is drawn on both surfaces that play a recording — the Add update sheet's review and the timeline —
 * so the two cannot present a different control for the same action (`BR-041`).
 */
@Composable
internal fun JobAudioPlayControl(
    tag: String,
    isPlaying: Boolean,
    isLoading: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled && !isLoading,
        modifier = modifier.size(JobAudioControlTargetSize).testTag(tag),
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                strokeWidth = JobAudioProgressStrokeWidth,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(JobAudioControlCircleSize),
            )
        } else {
            Surface(
                shape = CircleShape,
                color = if (enabled) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHighest
                },
                contentColor = if (enabled) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(JobAudioControlCircleSize),
            ) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(
                        painter = painterResource(
                            if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play,
                        ),
                        contentDescription = stringResource(
                            if (isPlaying) R.string.job_audio_pause else R.string.job_audio_play,
                        ),
                        modifier = Modifier.size(JobAudioControlGlyphSize),
                    )
                }
            }
        }
    }
}

/**
 * The action that takes accepted audio evidence out of ordinary use (`BR-089`).
 *
 * It is drawn only for a session holding `evidence.audio.remove` (`ADR-018` A7), and it reports its own
 * progress while the API is answering rather than looking like a tap that did nothing (`BR-042`).
 */
@Composable
private fun JobAudioRemoveControl(
    tag: String,
    isRemoving: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.testTag(tag),
    ) {
        if (isRemoving) {
            CircularProgressIndicator(
                strokeWidth = JobAudioProgressStrokeWidth,
                modifier = Modifier.size(JobAudioRemoveIconSize),
            )
        } else {
            Icon(
                painter = painterResource(R.drawable.ic_trash),
                contentDescription = stringResource(R.string.job_audio_remove_evidence),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(JobAudioRemoveIconSize),
            )
        }
    }
}

/**
 * Where one recording is and how long it is (`ADR-018` A11).
 *
 * The total is the **stated** length its surface already draws — the API's own reading of the container
 * (`A3`) for accepted evidence, the length this device measured for a take it still holds — so a
 * recording never states its length two ways (`BR-041`); and when that length is one this build cannot
 * read, no position is stated at all rather than a guess (`BR-042`).
 *
 * The playhead moves ten times a second while the numbers it sits beside change once a second, so the
 * seconds are *derived* from the player's position rather than composed from every tick (`A11`).
 */
@Composable
internal fun JobAudioPositionLabel(
    tag: String,
    progress: State<JobAudioPlaybackProgress?>,
    audioNoteId: String,
    totalSeconds: Int?,
    modifier: Modifier = Modifier,
) {
    val total = totalSeconds ?: return
    val elapsedSeconds by remember(total) {
        derivedStateOf {
            val held = progress.value?.takeIf { it.audioNoteId == audioNoteId }
            ((held?.positionMillis ?: 0) / MILLIS_PER_SECOND).coerceIn(0, total)
        }
    }
    Text(
        text = stringResource(
            R.string.job_audio_position_format,
            audioDurationLabel(elapsedSeconds),
            audioDurationLabel(total),
        ),
        style = MaterialTheme.typography.bodyLarge,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier.testTag(tag),
    )
}

/**
 * The seek track of one recording: a fill that follows playback and a playhead that can be moved
 * (`ADR-018` A11).
 *
 * It is the platform's own Material 3 `Slider`, so a drag, a tap on the track and the actions a slider
 * carries for accessibility all work without being rebuilt here — and it carries **no amplitude**: the
 * design's waveform is still not drawn (`A9`).
 *
 * A drag **moves the playhead as it goes**, and the player is asked to follow it: the position is what the
 * technician is choosing while they choose it, and committing only when the finger leaves would leave the
 * action a screen reader uses to move a slider previewing a position the recording was never asked to go
 * to (`BR-042`).
 *
 * A recording the player does not hold is drawn quietly rather than as a track that would not respond:
 * only the recording that is playing or paused can be moved through, and a control that cannot be used
 * is not drawn as one that would work if it were touched (`BR-042`).
 *
 * It opts into `ExperimentalMaterial3Api` for the **track** it draws: the library's own track carries a
 * stop indicator and a fixed thickness, so the played part and the remaining part are drawn here in the
 * theme's colours instead. That is the only reason for the opt-in, and it is the same kind of opt-in the
 * sheet already takes for its `ModalBottomSheet` (`ADR-018` A11).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun JobAudioSeekTrack(
    tag: String,
    progress: State<JobAudioPlaybackProgress?>,
    audioNoteId: String,
    enabled: Boolean,
    onSeek: (positionMillis: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Where a drag has taken the playhead, until it is let go: the track follows the thumb, and the
    // player is asked to move once (`A11`).
    var draggedFraction by remember { mutableStateOf<Float?>(null) }
    val held = progress.value?.takeIf { it.audioNoteId == audioNoteId }
    val durationMillis = held?.durationMillis ?: 0
    val canSeek = enabled && durationMillis > 0
    val playedFraction = if (durationMillis > 0) {
        (held?.positionMillis ?: 0).toFloat() / durationMillis
    } else {
        0f
    }
    val fraction = (draggedFraction ?: playedFraction).coerceIn(0f, 1f)
    val scheme = MaterialTheme.colorScheme
    val trackColor = scheme.onSurfaceVariant.copy(alpha = if (canSeek) 0.3f else 0.2f)
    val fillColor = if (canSeek) scheme.primary else scheme.onSurfaceVariant.copy(alpha = 0.4f)

    Slider(
        value = fraction,
        onValueChange = { value ->
            // The playhead follows the thumb and the player is asked to follow it: where the recording has
            // got to is what the technician is choosing while they choose it (`A11`).
            draggedFraction = value
            onSeek(jobAudioSeekPosition(value, durationMillis))
        },
        // The drag is over; from here the value the track draws is the player's own answer again.
        onValueChangeFinished = { draggedFraction = null },
        enabled = canSeek,
        // Only the thumb comes from the library: the track is drawn below, so its colours are this
        // feature's own (`A11`).
        colors = SliderDefaults.colors(
            thumbColor = scheme.primary,
            disabledThumbColor = fillColor,
        ),
        track = { state ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(JobAudioSeekTrackThickness)
                    .clip(CircleShape)
                    .background(trackColor),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(state.value.coerceIn(0f, 1f))
                        .fillMaxHeight()
                        .clip(CircleShape)
                        .background(fillColor),
                )
            }
        },
        modifier = modifier.fillMaxWidth().height(JobAudioSeekTrackHeight).testTag(tag),
    )
}

/**
 * The position a thumb released at [fraction] of the track asks the player to move to (`ADR-018` A11).
 *
 * The fraction is of the **player's own** length, which is the timebase a seek works in, and the result
 * is clamped to that length: a drag that ran past either end asks for the start or the end of the
 * recording rather than for a position it does not have.
 */
internal fun jobAudioSeekPosition(fraction: Float, durationMillis: Int): Int =
    if (durationMillis <= 0) {
        0
    } else {
        (fraction.coerceIn(0f, 1f) * durationMillis).roundToInt().coerceIn(0, durationMillis)
    }
