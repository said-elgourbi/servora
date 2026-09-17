package com.servora.android.ui.jobs

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.servora.android.R
import com.servora.android.domain.model.EvidencePhase
import com.servora.android.domain.model.PendingJobAudioNote

/** Identifies the sheet the one Add update action opens (`BR-027`). */
const val JobUpdateSheetTag = "job-update-sheet"

/**
 * Identifies the sheet's kind selector: the kinds the sheet chooses between, as one exclusive choice.
 *
 * A test uses it to tell a kind's own segment — inside the selector — from the controls of the kind in
 * effect, which are drawn outside it. That difference is the hierarchy the sheet exists to state
 * (`BR-012`).
 */
const val JobUpdateKindSelectorTag = "job-update-kind-selector"

/** Identifies the sheet's kind of update: a note, which is the everyday case (`BR-027`). */
const val JobUpdateNoteKindTag = "job-update-note"

/** Identifies the sheet's kind of update: a photo (`BR-015`). */
const val JobUpdatePhotoKindTag = "job-update-photo"

/** Identifies the sheet's kind of update: an audio recording (`BR-027`). Not offered yet. */
const val JobUpdateAudioKindTag = "job-update-audio"

/** Identifies the photo kind's own controls: the label over the sources a photo comes from (`D3`). */
const val JobUpdatePhotoSourcesTag = "job-update-photo-sources"

/** Identifies the sheet's photo source: the device's camera (`D3`). */
const val JobUpdateTakePhotoTag = "job-update-take-photo"

/** Identifies the sheet's photo source: the device's own photo picker (`D3`). */
const val JobUpdateChoosePhotosTag = "job-update-choose-photos"

/** Identifies the sheet's note, where a text update is typed (`BR-027`). */
const val JobUpdateNoteTag = "job-update-note-field"

/** Identifies the sheet's save action for a note. */
const val JobUpdateSaveTag = "job-update-save"

/** Identifies the sheet's cancel action. */
const val JobUpdateCancelTag = "job-update-cancel"

/** Identifies the audio kind's record action (`BR-091`). */
const val JobUpdateRecordAudioTag = "job-update-record-audio"

/** Identifies the audio kind's stop action. */
const val JobUpdateStopAudioTag = "job-update-stop-audio"

/** Identifies the audio kind's statement that the microphone is live. */
const val JobUpdateAudioRecordingTag = "job-update-audio-recording"

/** Identifies the audio kind's review of a recording that has not been attached yet. */
const val JobUpdateAudioReviewTag = "job-update-audio-review"

/** Identifies the audio kind's note field, typed while reviewing the take. */
const val JobUpdateAudioNoteTag = "job-update-audio-note"

/** Identifies the delete that drops a take so another can be recorded (`Delete/re-record`). */
const val JobUpdateDiscardAudioTag = "job-update-discard-audio"

/** Identifies the action that attaches the recording to the Job's Activity (`BR-091`). */
const val JobUpdateAttachAudioTag = "job-update-attach-audio"

/*
 * Metrics.
 *
 * A kind's segment holds its glyph over its label and is never shorter than a comfortable target for
 * one-handed use; a photo source is a row of the same order. Both grow when a label needs it, because
 * a localized label is longer than the English one and a reader may set a larger text size (`BR-028`).
 */

private val JobUpdateKindSegmentMinHeight = 76.dp
private val JobUpdatePhotoSourceRowMinHeight = 56.dp
private val JobUpdateKindSegmentSpacing = 8.dp
private val JobUpdateKindIconSize = 20.dp
private val JobUpdatePhotoSourceIconSize = 22.dp
private val JobUpdateRowChevronSize = 18.dp

/** The tag one kind's segment carries. */
private fun updateKindTag(kind: JobUpdateKind): String = when (kind) {
    JobUpdateKind.NOTE -> JobUpdateNoteKindTag
    JobUpdateKind.PHOTO -> JobUpdatePhotoKindTag
    JobUpdateKind.AUDIO -> JobUpdateAudioKindTag
}

/**
 * The one place a Job Activity update is added (`BR-012`, `BR-015`, `BR-027`).
 *
 * A technician has **one** action for everything that goes onto a Job's Activity, because in the field
 * the work is "record what happened" rather than "choose a feature". This sheet is that action's
 * hierarchy: it states the **kind** of update first, with the kinds as peers in one selector, and then
 * draws only the controls that kind needs. A note is the everyday case, so it is the kind in effect and
 * its field is ready to type in (`BR-012`); a photo states where it comes from, because the device
 * offers two sources (`D3`); audio is a first-class kind whose controls are not drawn yet
 * (`JobUpdateAudioContent`).
 *
 * The sheet coordinates: which kind is in effect, and the submit state every kind shares. Each kind
 * owns its own controls and its own draft (`JobUpdateNoteContent`, `JobUpdatePhotoContent`), so a kind
 * grows without touching the others — which is what makes another kind an addition rather than a
 * redesign. What the session may add at all is decided before this sheet is drawn (`BR-007`), and each
 * kind is offered on the capability the API enforces for it: a note belongs to a Visit
 * (`POST /jobs/:id/visits/:visitId/notes`) and takes the Job update capability, while adding photo
 * evidence takes `evidence.photo.add` (`BR-006`, `BR-015`, `BR-051`, `ADR-015`). Nothing here decides
 * what the API accepts (`BR-001`, `BR-042`, `BR-067`).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun JobUpdateSheet(
    canWriteNote: Boolean,
    canAddPhoto: Boolean,
    canAddAudio: Boolean,
    isSubmitting: Boolean,
    audioDraft: PendingJobAudioNote?,
    isRecordingAudio: Boolean,
    isAttachingAudio: Boolean,
    audioPhase: EvidencePhase,
    onConfirmNote: (body: String) -> Unit,
    onTakePhoto: () -> Unit,
    onChoosePhotos: () -> Unit,
    onSelectAudioPhase: (EvidencePhase) -> Unit,
    onStartAudioRecording: () -> Unit,
    onStopAudioRecording: () -> Unit,
    onDiscardAudioDraft: () -> Unit,
    onAttachAudio: (String?) -> Unit,
    onMicrophoneDenied: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val kinds = remember(canWriteNote, canAddPhoto, canAddAudio) {
        offeredUpdateKinds(
            canWriteNote = canWriteNote,
            canAddPhoto = canAddPhoto,
            canAddAudio = canAddAudio,
        )
    }
    // The kind in effect: a note where one can be taken, because it is the everyday case (`BR-012`),
    // and otherwise the first kind the Job can be given at all — a Job with no represented Visit can
    // only be given evidence (`BR-015`, `BR-051`). A kind this session is not offered is not the kind
    // in effect either (`BR-042`).
    var chosenKind by rememberSaveable { mutableStateOf(JobUpdateKind.NOTE) }
    val kind = kinds.firstOrNull { it == chosenKind } ?: kinds.firstOrNull()

    // Each kind keeps its draft while the sheet is open, so a technician who looks at the photo sources
    // and comes back to their note does not lose what they were typing. The draft stays the kind's own
    // state; only the sheet outlives the switch.
    val kindStates = rememberSaveableStateHolder()

    // Tapping a kind asks that kind's own control to take the focus — the note's field is the one kind
    // with a keyboard. The request is applied after the kind is drawn, so it always lands on a control
    // that exists rather than on one that has just left the layout (`BR-042`).
    val noteField = remember { FocusRequester() }
    var noteFieldRequested by remember { mutableStateOf(false) }
    LaunchedEffect(kind, noteFieldRequested) {
        if (noteFieldRequested && kind == JobUpdateKind.NOTE) {
            noteField.requestFocus()
            noteFieldRequested = false
        }
    }

    ModalBottomSheet(
        onDismissRequest = {
            if (!isSubmitting) onDismiss()
        },
        sheetState = sheetState,
        modifier = Modifier.testTag(JobUpdateSheetTag),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(R.string.job_activity_add_update),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )

            // No kind to state means no sheet content: an update nobody can add is not presented
            // (`BR-042`).
            if (kind != null) {
                JobUpdateKindSelector(
                    kinds = kinds,
                    selected = kind,
                    enabled = !isSubmitting,
                    onSelect = { selected ->
                        chosenKind = selected
                        if (selected == JobUpdateKind.NOTE) noteFieldRequested = true
                    },
                )

                kindStates.SaveableStateProvider(kind) {
                    when (kind) {
                        JobUpdateKind.NOTE -> JobUpdateNoteContent(
                            isSubmitting = isSubmitting,
                            focusRequester = noteField,
                            onConfirm = onConfirmNote,
                            onDismiss = onDismiss,
                        )

                        JobUpdateKind.PHOTO -> JobUpdatePhotoContent(
                            enabled = !isSubmitting,
                            onTakePhoto = onTakePhoto,
                            onChoosePhotos = onChoosePhotos,
                        )

                        JobUpdateKind.AUDIO -> JobUpdateAudioContent(
                            draft = audioDraft,
                            isRecording = isRecordingAudio,
                            isAttaching = isAttachingAudio,
                            phase = audioPhase,
                            onSelectPhase = onSelectAudioPhase,
                            onStartRecording = onStartAudioRecording,
                            onStopRecording = onStopAudioRecording,
                            onDiscard = onDiscardAudioDraft,
                            onAttach = onAttachAudio,
                            onMicrophoneDenied = onMicrophoneDenied,
                        )
                    }
                }
            }
        }
    }
}

/**
 * The kinds of update, as the sheet's one mutually exclusive choice (`BR-012`).
 *
 * The kinds are peers in one segmented control rather than a list of actions, so the technician reads
 * one question — *what am I adding?* — and the controls of the kind in effect read as that kind's own.
 * The selected segment carries the brand fill (`primary` on `onPrimary`); the others carry the quiet
 * neutral fill the customer form's segmented control uses, with a hairline outline, so selection never
 * rests on colour alone (`ADR-007` D6, `docs/design/android-design-system.md`).
 *
 * The segments share the sheet's width and take the height their labels need — a localized label is
 * longer than the English one, and a reader may set a larger text size — so three peers are never
 * squeezed into a row that fits only one language (`BR-028`).
 */
@Composable
private fun JobUpdateKindSelector(
    kinds: List<JobUpdateKind>,
    selected: JobUpdateKind,
    enabled: Boolean,
    onSelect: (JobUpdateKind) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.job_activity_update_type_label),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        // `IntrinsicSize.Min` gives every segment the height of the tallest label, so the row reads as
        // one control rather than as three buttons that happen to sit side by side.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
                .selectableGroup()
                .testTag(JobUpdateKindSelectorTag),
            horizontalArrangement = Arrangement.spacedBy(JobUpdateKindSegmentSpacing),
        ) {
            kinds.forEach { kind ->
                JobUpdateKindSegment(
                    kind = kind,
                    selected = kind == selected,
                    enabled = enabled,
                    onSelect = { onSelect(kind) },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                )
            }
        }
    }
}

/** One kind's segment: its glyph over its label, brand-filled while it is the kind in effect. */
@Composable
private fun JobUpdateKindSegment(
    kind: JobUpdateKind,
    selected: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .clip(MaterialTheme.shapes.large)
            .selectable(
                selected = selected,
                enabled = enabled,
                role = Role.RadioButton,
                onClick = onSelect,
            )
            .testTag(updateKindTag(kind)),
        shape = MaterialTheme.shapes.large,
        color = if (selected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.secondary
        },
        contentColor = if (selected) {
            MaterialTheme.colorScheme.onPrimary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        border = if (selected) {
            null
        } else {
            BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = JobUpdateKindSegmentMinHeight)
                .padding(horizontal = 8.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                painter = painterResource(updateKindIconRes(kind)),
                contentDescription = null,
                modifier = Modifier.size(JobUpdateKindIconSize),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(updateKindLabelRes(kind)),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * The photo kind's own controls: where the photo comes from (`D3`).
 *
 * The two sources are subordinate to the photo kind — they are *how* a photo is taken, not further
 * things to add — so they are drawn as quiet rows under a label that states that relationship, rather
 * than as more targets the size of the kinds above them (`BR-012`). Each one hands over to the flow the
 * photo slice owns: the camera the slice already drives, or the device's own photo picker, which asks
 * for no storage or media permission (`BR-015`).
 */
@Composable
private fun JobUpdatePhotoContent(
    enabled: Boolean,
    onTakePhoto: () -> Unit,
    onChoosePhotos: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.job_update_photo_sources_label),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag(JobUpdatePhotoSourcesTag),
        )
        Spacer(Modifier.height(4.dp))
        JobUpdatePhotoSourceRow(
            iconRes = R.drawable.ic_camera,
            labelRes = R.string.job_update_photo_camera,
            enabled = enabled,
            onClick = onTakePhoto,
            modifier = Modifier.testTag(JobUpdateTakePhotoTag),
        )
        JobUpdatePhotoSourceRow(
            iconRes = R.drawable.ic_photo_library,
            labelRes = R.string.job_update_photo_library,
            enabled = enabled,
            onClick = onChoosePhotos,
            modifier = Modifier.testTag(JobUpdateChoosePhotosTag),
        )
    }
}

/**
 * One of the photo kind's two sources, as a subordinate row.
 *
 * It carries what the kind needs and nothing that would rank it with one: a leading glyph, its label,
 * and the chevron that says it opens something away from the sheet. No fill, no border and no card, so
 * the row reads as part of the photo kind rather than as a fourth action (`BR-012`). The whole row is
 * the target — never a small control beside the label — because it is used one-handed in the field.
 */
@Composable
private fun JobUpdatePhotoSourceRow(
    @DrawableRes iconRes: Int,
    @StringRes labelRes: Int,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = JobUpdatePhotoSourceRowMinHeight)
            .clip(MaterialTheme.shapes.medium)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(JobUpdatePhotoSourceIconSize),
        )
        Text(
            text = stringResource(labelRes),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Icon(
            painter = painterResource(R.drawable.ic_chevron_right),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(JobUpdateRowChevronSize),
        )
    }
}

/**
 * The note kind's own controls: the text field, and the two actions that commit or drop it (`BR-027`).
 *
 * The draft lives here because it is the note's, and the label, the placeholder and the rule that a
 * blank note cannot be saved are the ones this composer always had: text is the everyday update, so
 * nothing about it is decided again here. Only the field is exposed to the sheet, so tapping the note
 * kind can put the cursor in it (`JobUpdateSheet`).
 */
@Composable
private fun JobUpdateNoteContent(
    isSubmitting: Boolean,
    focusRequester: FocusRequester,
    onConfirm: (body: String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var note by rememberSaveable { mutableStateOf("") }
    val trimmed = note.trim()

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        OutlinedTextField(
            value = note,
            onValueChange = { text -> note = text },
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester)
                .testTag(JobUpdateNoteTag),
            label = { Text(stringResource(R.string.job_activity_text_label)) },
            placeholder = { Text(stringResource(R.string.job_activity_text_placeholder)) },
            minLines = 4,
            enabled = !isSubmitting,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                onClick = onDismiss,
                enabled = !isSubmitting,
                modifier = Modifier.testTag(JobUpdateCancelTag),
            ) {
                Text(stringResource(R.string.job_activity_cancel_update))
            }
            Button(
                onClick = { onConfirm(trimmed) },
                enabled = trimmed.isNotEmpty() && !isSubmitting,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.testTag(JobUpdateSaveTag),
            ) {
                Text(stringResource(R.string.job_activity_save_update))
            }
        }
    }
}

/**
 * The audio kind's own controls: record, stop, review, delete/re-record and attach (`BR-091`, `ADR-018`).
 *
 * It is the design's own list (`Figma/…/servora-job-details-spec.md` §Audio), and it is drawn as one
 * block because audio is one update: the phase is stated first — the same vocabulary a photo carries
 * (`ADR-018` A4) — and then the take itself.
 *
 * The three states it draws are the three a recording can be in, and each shows only its own controls:
 * the microphone is **live** (Stop), there is **no take yet** (Record, the one control that asks for the
 * microphone permission), or the take is **on this device** and is reviewed before it is attached
 * (`BR-014`). Deleting the take is what makes another possible, so *delete/re-record* is one step back to
 * the first state rather than a second recorder beside the first.
 *
 * **Playback is not here yet.** The recording is a file on this device, and playing it back is tracker
 * 035's Phase 9c, which adds the player to the sheet and to the Job's timeline together — one port for one
 * platform stack (`ADR-018` A9) rather than a second one here. What the review states instead is the
 * take's length, which is what the technician decides to keep or drop on.
 */
@Composable
private fun JobUpdateAudioContent(
    draft: PendingJobAudioNote?,
    isRecording: Boolean,
    isAttaching: Boolean,
    phase: EvidencePhase,
    onSelectPhase: (EvidencePhase) -> Unit,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
    onDiscard: () -> Unit,
    onAttach: (note: String?) -> Unit,
    onMicrophoneDenied: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // The note belongs to the take, so it is keyed by it: a re-record starts with an empty note rather
    // than with the word the technician wrote about the take they deleted (`BR-042`).
    var note by rememberSaveable(draft?.audioNoteId) { mutableStateOf(draft?.note.orEmpty()) }
    // Recording is the one kind that needs a permission, and it is asked for here — when the technician
    // asks to record, not at launch (`BR-012`).
    val record = rememberJobAudioRecorder(onGranted = onStartRecording, onDenied = onMicrophoneDenied)

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.evidence_phase_label),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        EvidencePhaseSelector(
            selected = phase,
            enabled = !isAttaching,
            onSelect = onSelectPhase,
        )

        when {
            isRecording -> {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = JobUpdateAudioActionHeight)
                        .testTag(JobUpdateAudioRecordingTag),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_mic),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(JobUpdateAudioIconSize),
                    )
                    Text(
                        text = stringResource(R.string.job_audio_recording),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Button(
                    onClick = onStopRecording,
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(JobUpdateAudioActionHeight)
                        .testTag(JobUpdateStopAudioTag),
                ) {
                    Text(stringResource(R.string.job_audio_stop))
                }
            }

            draft == null -> {
                Button(
                    onClick = record,
                    enabled = !isAttaching,
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(JobUpdateAudioActionHeight)
                        .testTag(JobUpdateRecordAudioTag),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_mic),
                        contentDescription = null,
                        modifier = Modifier.size(JobUpdateAudioIconSize),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.job_audio_record))
                }
                Text(
                    text = stringResource(R.string.job_audio_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            else -> {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(JobUpdateAudioReviewTag),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_mic),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(JobUpdateAudioIconSize),
                    )
                    Text(
                        text = audioDurationLabel(draft.durationSeconds),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                OutlinedTextField(
                    value = note,
                    onValueChange = { text -> note = text },
                    enabled = !isAttaching,
                    label = { Text(stringResource(R.string.job_photo_note_label)) },
                    minLines = 2,
                    maxLines = 3,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(JobUpdateAudioNoteTag),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    TextButton(
                        onClick = onDiscard,
                        enabled = !isAttaching,
                        modifier = Modifier
                            .weight(1f)
                            .height(EvidencePhaseButtonHeight)
                            .testTag(JobUpdateDiscardAudioTag),
                    ) {
                        Text(stringResource(R.string.job_audio_delete))
                    }
                    Button(
                        onClick = { onAttach(note) },
                        enabled = !isAttaching,
                        shape = MaterialTheme.shapes.large,
                        modifier = Modifier
                            .weight(1f)
                            .height(EvidencePhaseButtonHeight)
                            .testTag(JobUpdateAttachAudioTag),
                    ) {
                        Text(stringResource(R.string.job_audio_attach))
                    }
                }
            }
        }
    }
}

/** The height a recording action keeps, so the kind's controls are comfortable one-handed (`BR-012`). */
private val JobUpdateAudioActionHeight = 56.dp

/** How large the microphone glyph is drawn in the audio kind's own controls. */
private val JobUpdateAudioIconSize = 20.dp

