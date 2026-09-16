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
    onConfirmNote: (body: String) -> Unit,
    onTakePhoto: () -> Unit,
    onChoosePhotos: () -> Unit,
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

                        JobUpdateKind.AUDIO -> JobUpdateAudioContent()
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
 * The audio kind's own controls — the seam the recorder lands in (`BR-027`).
 *
 * Audio is a first-class kind of update in this sheet, and its controls belong here: recording,
 * playback and whatever state they keep are audio's own, so neither the note's nor the photo's layout
 * has to change when they arrive. That is the whole point of stating a kind and then drawing that
 * kind's controls.
 *
 * What it draws now is nothing, deliberately. No product rule accepts an audio recording yet: the API's
 * capability for it is **reserved and not created** (`evidence.audio.add`,
 * `docs/decisions/015-evidence-capabilities.md` D2), and there is no stored evidence, no Activity kind
 * and no playback behind it. A recorder whose recording could not be submitted would be invented
 * behaviour, and an action that cannot be performed is not offered (`BR-042`) — which is why no caller
 * offers this kind yet (`offeredUpdateKinds`). The kind and this seam exist so the recorder is an
 * addition rather than a redesign.
 */
@Composable
private fun JobUpdateAudioContent() {
    // Deliberately empty until audio evidence exists (`BR-027`, `ADR-015` D2, `BR-042`).
}

