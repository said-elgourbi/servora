package com.servora.android.ui.jobs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.servora.android.R
import com.servora.android.domain.model.JobAudioSyncState
import com.servora.android.domain.model.PendingJobAudioNote
import com.servora.android.domain.model.isDiscardable
import com.servora.android.domain.model.isRefusedUpload
import com.servora.android.domain.model.isRemovable

/**
 * The pending-recording notice (`BR-091`, `ADR-018`).
 *
 * A recording the API has not answered for is the technician's own working state, so it is reported
 * where the photo tray is reported: at the bottom of the Job, above the action that adds another update
 * (`BR-012`). It is a **notice** rather than a tray because the sheet adds one update at a time, so a
 * Job holds at most one unattached recording — there is nothing to page through, only something to
 * finish or drop (`JobAudioSession`).
 *
 * What it offers is the whole of what an unaccepted recording needs: **Attach**, which queues the
 * upload, and the discard that clears it from the device. A queued or retrying upload offers neither,
 * because the backend may already hold it (`BR-014`, §9).
 */
@Composable
internal fun JobAudioNotice(
    notes: List<PendingJobAudioNote>,
    uploads: Map<String, JobAudioSyncState>,
    isAttaching: Boolean,
    onAttach: () -> Unit,
    onRemove: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 8.dp,
        modifier = modifier.fillMaxWidth().testTag(JobAudioNoticeTag),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.job_audio_notice_title),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            notes.forEach { note ->
                JobAudioNoticeRow(
                    note = note,
                    upload = uploads[note.audioNoteId],
                    isAttaching = isAttaching,
                    onAttach = onAttach,
                    onRemove = { onRemove(note.audioNoteId) },
                )
            }
        }
    }
}

/**
 * One reported recording: its length, what the device is doing with it, and the action it leaves.
 *
 * The length is the device's own measure of the take. The length the API reads from the recording's
 * container is the authoritative one (`ADR-018` A3) and is what the Job's Activity states once the upload
 * lands; until then this is what the technician has to go on.
 */
@Composable
private fun JobAudioNoticeRow(
    note: PendingJobAudioNote,
    upload: JobAudioSyncState?,
    isAttaching: Boolean,
    onAttach: () -> Unit,
    onRemove: () -> Unit,
) {
    val refused = note.isRefusedUpload(upload)
    val discardable = note.isDiscardable(upload)

    Row(
        modifier = Modifier.fillMaxWidth().testTag(jobAudioNoticeRowTag(note.audioNoteId)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_mic),
            contentDescription = stringResource(R.string.job_audio_description),
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(JobAudioNoticeIconSize),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = audioDurationLabel(note.durationSeconds),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = if (note.submitted) {
                    stringResource(jobAudioStateLabel(upload ?: JobAudioSyncState.QUEUED))
                } else {
                    stringResource(R.string.job_audio_notice_not_attached)
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (note.submitted) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.testTag(jobAudioNoticeStateTag(note.audioNoteId)),
            )
        }
        // An unattached recording is one tap from being saved, because the notice is the only place it is
        // visible once the sheet is closed (`BR-012`, `BR-014`).
        if (note.isRemovable()) {
            TextButton(
                onClick = onAttach,
                enabled = !isAttaching,
                modifier = Modifier.testTag(jobAudioNoticeAttachTag(note.audioNoteId)),
            ) {
                Text(stringResource(R.string.job_audio_attach))
            }
        }
        if (discardable) {
            IconButton(
                onClick = onRemove,
                modifier = Modifier.testTag(jobAudioNoticeRemoveTag(note.audioNoteId)),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_close),
                    contentDescription = stringResource(
                        if (refused) R.string.job_audio_discard else R.string.job_audio_remove,
                    ),
                    modifier = Modifier.size(JobAudioNoticeRemoveIconSize),
                )
            }
        }
    }
}
