package com.servora.android.ui.jobs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.servora.android.R
import com.servora.android.data.jobs.JobPhotoImages
import com.servora.android.domain.model.JobPhotoPhase
import com.servora.android.domain.model.PendingJobPhoto

/**
 * The review panel a capture is confirmed in (`BR-012`, `BR-027`).
 *
 * It is deliberately short: the photo, one optional note and three large phase buttons, with the
 * phase already at the value the technician used last, so a run of photos of the same phase is one
 * tap each. Nothing here decides whether the photo is kept — that is the technician's "Add" — and
 * nothing claims the backend accepted it, because it has not been sent yet (`BR-001`, `BR-031`).
 *
 * The photo is already recorded on the device when this panel opens, so dismissing it does not lose
 * the capture: discarding is the explicit action that removes it (`BR-014`).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun JobPhotoReviewSheet(
    photo: PendingJobPhoto,
    initialPhase: JobPhotoPhase,
    isBusy: Boolean,
    photoImages: JobPhotoImages,
    onConfirm: (JobPhotoPhase, String?) -> Unit,
    onDiscard: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var phase by rememberSaveable(photo.photoId) { mutableStateOf(initialPhase) }
    var note by rememberSaveable(photo.photoId) { mutableStateOf(photo.note.orEmpty()) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = Modifier.testTag(JobPhotoReviewSheetTag),
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
                text = stringResource(R.string.job_photo_review_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )

            JobPhotoThumbnail(
                load = { photoImages.localThumbnail(photo.localPath) },
                contentDescription = stringResource(R.string.job_photo_image_description),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(JobPhotoReviewPreviewHeight)
                    .testTag(JobPhotoReviewPreviewTag),
            )

            Text(
                text = stringResource(R.string.job_photo_phase_label),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            JobPhotoPhaseSelector(
                selected = phase,
                enabled = !isBusy,
                onSelect = { selected -> phase = selected },
            )

            OutlinedTextField(
                value = note,
                onValueChange = { text -> note = text },
                enabled = !isBusy,
                label = { Text(stringResource(R.string.job_photo_note_label)) },
                placeholder = { Text(stringResource(R.string.job_photo_note_placeholder)) },
                minLines = 2,
                maxLines = JobPhotoNoteMaxLines,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(JobPhotoReviewNoteTag),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TextButton(
                    onClick = onDiscard,
                    enabled = !isBusy,
                    modifier = Modifier
                        .weight(1f)
                        .height(JobPhotoPhaseButtonHeight)
                        .testTag(JobPhotoReviewDiscardTag),
                ) {
                    Text(stringResource(R.string.job_photo_review_discard))
                }
                Button(
                    onClick = { onConfirm(phase, note.trim().takeIf { it.isNotEmpty() }) },
                    enabled = !isBusy,
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier
                        .weight(1f)
                        .height(JobPhotoPhaseButtonHeight)
                        .testTag(JobPhotoReviewConfirmTag),
                ) {
                    Text(stringResource(R.string.job_photo_review_confirm))
                }
            }
        }
    }
}
