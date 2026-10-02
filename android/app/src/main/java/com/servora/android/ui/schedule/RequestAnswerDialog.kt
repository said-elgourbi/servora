package com.servora.android.ui.schedule

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
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
import com.servora.android.R
import com.servora.android.domain.model.FollowUpVisitRequest
import com.servora.android.domain.model.FollowUpVisitRequestMessageAuthorKind

/** Identifies the composer a requester answers a returned request in, and the action that sends it. */
fun technicianRequestAnswerFieldTag(requestId: String): String =
    "technician-request-answer-field-$requestId"

fun technicianRequestAnswerSendTag(requestId: String): String =
    "technician-request-answer-send-$requestId"

fun technicianRequestAnswerQuestionTag(requestId: String): String =
    "technician-request-answer-question-$requestId"

const val TechnicianRequestAnswerDialogTag = "technician-request-answer-dialog"

/**
 * The composer in which a requester answers a request the office returned for clarification
 * (`BR-FV-012`).
 *
 * Answering is one operation that appends the answer to the request's conversation and puts the request
 * back in front of the office, so it is confirmed here and its outcome is reported by the screen rather
 * than presented as taken before the API holds it (`BR-001`, `BR-067`).
 *
 * **The answer is required.** The office returned the request because it asked something, and an answer
 * that says nothing is not an answer, so the action that sends one stays disabled until something is
 * written — the same courtesy the rejection confirmation extends to its reason (`BR-FV-013`, `BR-042`).
 * What the office asked is repeated beside the field, so the technician writes against the question
 * rather than from memory (`BR-012`).
 *
 * The typed text is held here, so a configuration change while the technician is writing does not throw
 * their answer away.
 */
@Composable
internal fun RequestAnswerDialog(
    request: FollowUpVisitRequest,
    isSending: Boolean,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var answer by rememberSaveable(request.id) { mutableStateOf("") }
    val canConfirm = !isSending && answer.isNotBlank()
    val question = request.messages
        .lastOrNull { it.authorKind == FollowUpVisitRequestMessageAuthorKind.OFFICE }
        ?.body
        ?.takeIf { it.isNotBlank() }
        ?: request.reviewNote?.takeIf { it.isNotBlank() }

    AlertDialog(
        onDismissRequest = { if (!isSending) onDismiss() },
        modifier = Modifier.testTag(TechnicianRequestAnswerDialogTag),
        title = { Text(stringResource(R.string.technician_request_answer_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(RequestAnswerLineSpacing)) {
                Text(
                    text = stringResource(R.string.technician_request_answer_message),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                question?.let { asked ->
                    Text(
                        text = stringResource(R.string.technician_request_answer_question, asked),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.testTag(
                            technicianRequestAnswerQuestionTag(request.id),
                        ),
                    )
                }
                OutlinedTextField(
                    value = answer,
                    onValueChange = { answer = it },
                    label = { Text(stringResource(R.string.technician_request_answer_label)) },
                    enabled = !isSending,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(technicianRequestAnswerFieldTag(request.id)),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = canConfirm,
                onClick = { onConfirm(answer.trim()) },
                modifier = Modifier.testTag(technicianRequestAnswerSendTag(request.id)),
            ) {
                Text(stringResource(R.string.technician_request_answer_send))
            }
        },
        dismissButton = {
            TextButton(enabled = !isSending, onClick = onDismiss) {
                Text(stringResource(R.string.schedule_request_review_cancel))
            }
        },
    )
}

private val RequestAnswerLineSpacing = 12.dp
