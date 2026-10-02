package com.servora.android.ui.schedule

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.servora.android.R
import com.servora.android.domain.model.FollowUpVisitRequest

/** The two review decisions that are not an approval (`BR-FV-004`). */
enum class RequestReviewDecision {
    /** The office says it needs more before it can decide, and the request stays open (`BR-FV-012`). */
    CLARIFY,

    /** The office refuses the request, and no Visit is created (`BR-FV-012`). */
    REJECT,
}

/** The decision the office is taking about [request], for as long as the confirmation is open. */
@Immutable
data class RequestReviewTarget(
    val request: FollowUpVisitRequest,
    val decision: RequestReviewDecision,
)

/** Identifies the field a review decision's record is stated in, and the action that sends it. */
fun scheduleReviewNoteTag(requestId: String): String = "schedule-request-review-note-$requestId"
fun scheduleReviewConfirmTag(requestId: String): String = "schedule-request-review-confirm-$requestId"

/** Identifies the confirmations' own titles, so a test can tell the two apart. */
const val ScheduleClarifyRequestDialogTag = "schedule-request-clarify-dialog"
const val ScheduleRejectRequestDialogTag = "schedule-request-reject-dialog"

/**
 * The confirmation a Clarify or a Reject is taken in, and the record it is taken with (`BR-FV-013`).
 *
 * Both decisions are business decisions about a request someone else raised, so both are confirmed
 * explicitly and both carry the office's own words: a clarification says what is still needed, and a
 * rejection says why the request was refused. The note was previously never sent
 * (`docs/tracker/057-qa-issue-list-visit-workflow.md` §5.2), which recorded the decision without its
 * reason and left *Clarify* with nothing to say for itself beyond a row that disappeared.
 *
 * **A rejection requires its reason; a clarification does not.** `BR-FV-013` requires a rejection to
 * record "that no further Visit is required or why the request was refused", so the action that sends
 * one is disabled until something is written. No rule states a note requirement for a clarification, so
 * its note is offered rather than required: the route's `note` is optional and a client is not the place
 * to invent a stricter rule (`BR-042`). Whether a clarification should require its question is recorded
 * as an open question in the tracker rather than assumed here.
 *
 * The typed text is held here, so a configuration change while the manager is writing does not throw the
 * decision away — the same courtesy the sheets' drafts extend to longer field notes.
 */
@Composable
internal fun RequestReviewDialog(
    request: FollowUpVisitRequest,
    decision: RequestReviewDecision,
    isSending: Boolean,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var note by rememberSaveable(request.id, decision.name) { mutableStateOf("") }
    val requiresNote = decision == RequestReviewDecision.REJECT
    val canConfirm = !isSending && (!requiresNote || note.isNotBlank())

    AlertDialog(
        onDismissRequest = { if (!isSending) onDismiss() },
        title = {
            Text(
                text = stringResource(decision.titleRes()),
                modifier = Modifier.testTag(decision.dialogTag()),
            )
        },
        text = {
            Column {
                Text(
                    text = stringResource(decision.messageRes()),
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = note,
                    onValueChange = { text -> note = text },
                    enabled = !isSending,
                    label = { Text(stringResource(decision.noteLabelRes())) },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                        .testTag(scheduleReviewNoteTag(request.id)),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(note) },
                enabled = canConfirm,
                modifier = Modifier.testTag(scheduleReviewConfirmTag(request.id)),
            ) {
                Text(stringResource(decision.confirmRes()))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isSending) {
                Text(stringResource(R.string.schedule_request_review_cancel))
            }
        },
    )
}

/*
 * Each decision names its own copy. They are two decisions about one record, not two phrasings of one,
 * so they are mapped rather than merged, and every mapping above is exhaustive over the enum so a third
 * decision cannot be added without its own words (`BR-028`, `BR-041`).
 */

private fun RequestReviewDecision.titleRes(): Int =
    when (this) {
        RequestReviewDecision.CLARIFY -> R.string.schedule_request_clarify_title
        RequestReviewDecision.REJECT -> R.string.schedule_request_reject_title
    }

private fun RequestReviewDecision.messageRes(): Int =
    when (this) {
        RequestReviewDecision.CLARIFY -> R.string.schedule_request_clarify_message
        RequestReviewDecision.REJECT -> R.string.schedule_request_reject_message
    }

private fun RequestReviewDecision.noteLabelRes(): Int =
    when (this) {
        RequestReviewDecision.CLARIFY -> R.string.schedule_request_clarify_note
        RequestReviewDecision.REJECT -> R.string.schedule_request_reject_note
    }

private fun RequestReviewDecision.confirmRes(): Int =
    when (this) {
        RequestReviewDecision.CLARIFY -> R.string.schedule_request_clarify_confirm
        RequestReviewDecision.REJECT -> R.string.schedule_request_reject_confirm
    }

private fun RequestReviewDecision.dialogTag(): String =
    when (this) {
        RequestReviewDecision.CLARIFY -> ScheduleClarifyRequestDialogTag
        RequestReviewDecision.REJECT -> ScheduleRejectRequestDialogTag
    }
