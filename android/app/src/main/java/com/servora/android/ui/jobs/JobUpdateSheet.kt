package com.servora.android.ui.jobs

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.servora.android.R

/** Identifies the sheet the one Add update action opens (`BR-027`). */
const val JobUpdateSheetTag = "job-update-sheet"

/** Identifies the sheet's kind of update: a note, which is the everyday case (`BR-027`). */
const val JobUpdateNoteKindTag = "job-update-note"

/** Identifies the sheet's kind of update: a photo (`BR-015`). */
const val JobUpdatePhotoKindTag = "job-update-photo"

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

/**
 * The one place a Job Activity update is added (`BR-012`, `BR-015`, `BR-027`).
 *
 * A technician has **one** action for everything that goes onto a Job's Activity, because in the
 * field the work is "record what happened" rather than "choose a feature": this sheet states what
 * kind of update it is — a note, drawn ready to type in because it is the everyday case, or a photo —
 * and then, for a photo, which of the two sources it comes from (`D3`): the device's camera, or the
 * device's own photo picker for photos the technician already has. Each hands over to the flow the
 * photo slice owns. Nothing here decides what the API accepts (`BR-001`, `BR-042`, `BR-067`).
 *
 * The two kinds are drawn on the capabilities the API enforces for them: a note belongs to a Visit
 * (`POST /jobs/:id/visits/:visitId/notes`), so a Job with no represented Visit is offered none, and
 * adding photo evidence is its own capability (`evidence.photo.add`), held by the technician who
 * records it (`BR-006`, `BR-009`, `BR-015`, `BR-051`). What the session may add at all is decided
 * before this sheet is drawn (`BR-007`).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun JobUpdateSheet(
    canWriteNote: Boolean,
    canAddPhoto: Boolean,
    isSubmitting: Boolean,
    onConfirmNote: (body: String) -> Unit,
    onTakePhoto: () -> Unit,
    onChoosePhotos: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var note by rememberSaveable { mutableStateOf("") }
    // The note is the kind in effect where a note is possible, because it is the everyday case; a Job
    // with no represented Visit can only be given a photo (`BR-015`, `BR-051`).
    var photoInEffect by rememberSaveable { mutableStateOf(!canWriteNote) }
    val noteField = remember { FocusRequester() }
    val trimmed = note.trim()

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

            // The kinds of update, as large targets (`BR-012`). Tapping the note puts the cursor in
            // its field; tapping the photo states the photo kind, whose two sources are then offered
            // below (`D3`).
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (canWriteNote) {
                    JobUpdateKind(
                        iconRes = R.drawable.ic_file_text,
                        labelRes = R.string.job_activity_update_note,
                        selected = !photoInEffect,
                        enabled = !isSubmitting,
                        onSelect = {
                            photoInEffect = false
                            noteField.requestFocus()
                        },
                        modifier = Modifier.weight(1f).testTag(JobUpdateNoteKindTag),
                    )
                }
                if (canAddPhoto) {
                    JobUpdateKind(
                        iconRes = R.drawable.ic_camera,
                        labelRes = R.string.job_photo_add_action,
                        // A Job with no represented Visit can take no note here, so the photo is the
                        // kind in effect rather than the alternative to one (`BR-015`, `BR-051`).
                        selected = !canWriteNote || photoInEffect,
                        enabled = !isSubmitting,
                        onSelect = { photoInEffect = true },
                        modifier = Modifier.weight(1f).testTag(JobUpdatePhotoKindTag),
                    )
                }
            }

            if (canAddPhoto && photoInEffect) {
                // The two sources the decision approves (`D3`), as large targets of the kind row's own
                // height. Each closes the sheet and hands over to the flow that owns it: the camera the
                // slice already drives, or the device's own photo picker, which asks for no storage or
                // media permission (`BR-015`).
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    JobUpdateKind(
                        iconRes = R.drawable.ic_camera,
                        labelRes = R.string.job_update_photo_camera,
                        selected = false,
                        enabled = !isSubmitting,
                        onSelect = onTakePhoto,
                        modifier = Modifier.weight(1f).testTag(JobUpdateTakePhotoTag),
                    )
                    JobUpdateKind(
                        iconRes = R.drawable.ic_photo_library,
                        labelRes = R.string.job_update_photo_library,
                        selected = false,
                        enabled = !isSubmitting,
                        onSelect = onChoosePhotos,
                        modifier = Modifier.weight(1f).testTag(JobUpdateChoosePhotosTag),
                    )
                }
            } else if (canWriteNote) {
                OutlinedTextField(
                    value = note,
                    onValueChange = { text -> note = text },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(noteField)
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
                        onClick = { onConfirmNote(trimmed) },
                        enabled = trimmed.isNotEmpty() && !isSubmitting,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.testTag(JobUpdateSaveTag),
                    ) {
                        Text(stringResource(R.string.job_activity_save_update))
                    }
                }
            }
        }
    }
}

/**
 * One kind of update, as a large target stating what is being added.
 *
 * It is the height of the photo slice's phase buttons because it is the same decision made with the
 * same hand — one big target, filled when it is the kind in effect (`BR-012`). Only the label is
 * localized; what each kind writes stays the API's own contract (`BR-028`, `BR-041`).
 */
@Composable
private fun JobUpdateKind(
    @DrawableRes iconRes: Int,
    @StringRes labelRes: Int,
    selected: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onSelect,
        enabled = enabled,
        shape = MaterialTheme.shapes.large,
        color = if (selected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        contentColor = if (selected) {
            MaterialTheme.colorScheme.onPrimary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        modifier = modifier.height(JobPhotoPhaseButtonHeight),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painter = painterResource(iconRes),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = stringResource(labelRes),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        }
    }
}
