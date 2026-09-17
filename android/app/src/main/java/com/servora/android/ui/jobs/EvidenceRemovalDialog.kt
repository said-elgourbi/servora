package com.servora.android.ui.jobs

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/**
 * The confirmation a removal states its reason in (`BR-067`, `BR-088`, `BR-089`).
 *
 * Removing accepted evidence is a decision about a historical record, so it is confirmed explicitly:
 * the dialog says what it does, why a reason is required, and what it does **not** do — the evidence is
 * not deleted, its record and history are preserved, and the removal itself is recorded (`BR-088`,
 * `BR-089`). The reason is the only input, and the action that applies the removal is disabled until one
 * is given, because the API refuses a removal without a reason and a dialog that could send one would
 * only produce a refusal.
 *
 * **One dialog, one kind's copy.** Photos and audio notes are separate kinds with separate capabilities
 * and separate routes (`ADR-018` A7), but the decision they ask the manager to make is the same
 * operation, so the confirmation is drawn once and each kind supplies its own localized text and its own
 * test tags ([copy]) rather than a second dialog that could drift from this one (`BR-041`, `dev.md` §1).
 */
@Composable
internal fun EvidenceRemovalDialog(
    copy: EvidenceRemovalCopy,
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
                text = stringResource(copy.title),
                modifier = Modifier.testTag(copy.tag),
            )
        },
        text = {
            Column {
                Text(
                    text = stringResource(copy.message),
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = reason,
                    onValueChange = { text -> reason = text },
                    enabled = !isRemoving,
                    label = { Text(stringResource(copy.reasonLabel)) },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                        .testTag(copy.reasonTag),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(reason) },
                enabled = canConfirm,
                modifier = Modifier.testTag(copy.confirmTag),
            ) {
                Text(stringResource(copy.confirm))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isRemoving,
                modifier = Modifier.testTag(copy.cancelTag),
            ) {
                Text(stringResource(copy.cancel))
            }
        },
    )
}

/**
 * The copy and the test tags one kind's removal confirmation is drawn with.
 *
 * The tags are the kind's own, so a UI test names the dialog of the evidence kind it is exercising
 * (`qa.md` §6.2); the text is the kind's own, because the manager is told which kind they are removing
 * (`BR-028`, `BR-041`).
 */
internal data class EvidenceRemovalCopy(
    val tag: String,
    val reasonTag: String,
    val confirmTag: String,
    val cancelTag: String,
    @StringRes val title: Int,
    @StringRes val message: Int,
    @StringRes val reasonLabel: Int,
    @StringRes val confirm: Int,
    @StringRes val cancel: Int,
)
