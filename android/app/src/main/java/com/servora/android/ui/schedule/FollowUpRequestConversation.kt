package com.servora.android.ui.schedule

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.servora.android.R
import com.servora.android.domain.model.FollowUpVisitRequest
import com.servora.android.domain.model.FollowUpVisitRequestMessage
import com.servora.android.domain.model.FollowUpVisitRequestMessageAuthorKind
import com.servora.android.domain.model.FollowUpVisitRequestStatus
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/*
 * A follow-up request's clarification conversation (`BR-FV-012`): what the office asked and what the
 * requester answered, as both surfaces present it, with the presentation decisions kept out of the
 * composable so they can be asserted without a device.
 *
 * None of it decides anything about the request. Which status it is in, what the office wrote, whether
 * it awaits an answer and what the conversation holds are the API's own answers (`BR-001`, `BR-041`,
 * `BR-042`); these only say how the request the screen was handed is drawn.
 */

/**
 * Whether a request's card states the office's own note (`BR-FV-013`).
 *
 * The note is the record of the office's current decision — a refusal's reason, an approval's
 * instruction, or the question it returned the request with. Once a clarification's question is a
 * message of the request's conversation, the conversation is where it is drawn, so the card does not
 * state the same sentence twice (`BR-041`). A note written by a decision that closed the request is
 * always stated: it is the whole answer to "why was my request refused?", and it belongs to no
 * conversation.
 */
internal val FollowUpVisitRequest.showsOfficeNote: Boolean
    get() {
        if (reviewNote.isNullOrBlank()) {
            return false
        }
        // A conversation that already holds the sentence states it, and a terminal decision's own note is
        // always stated: it is the whole answer to "why was my request refused?" (`BR-FV-013`).
        return messages.isEmpty() ||
            status == FollowUpVisitRequestStatus.APPROVED ||
            status == FollowUpVisitRequestStatus.REJECTED
    }

/**
 * Whether a request's card states its clarification conversation (`BR-FV-012`).
 *
 * A request that was never returned for clarification has none, and a card that drew an empty one would
 * present every proposal as an exchange with the office (`BR-042`).
 */
internal val FollowUpVisitRequest.showsConversation: Boolean
    get() = messages.isNotEmpty()

/**
 * Who a conversation message is attributed to, as the reader's own surface states it (`BR-FV-012`).
 *
 * The same message is "You" to the technician who wrote it and "Technician" to the office reading it, so
 * the label follows the reader's own side rather than the text alone.
 */
internal enum class FollowUpConversationSpeaker {
    /** The reader's own answer. */
    YOU,

    /** The office that reviews the request. */
    OFFICE,

    /** The technician who raised the request, to a reader who is not them. */
    TECHNICIAN,
}

/** The speaker [authorKind] names, for a reader who is the requester or is not. */
internal fun conversationSpeaker(
    authorKind: FollowUpVisitRequestMessageAuthorKind,
    readerIsRequester: Boolean,
): FollowUpConversationSpeaker =
    when (authorKind) {
        FollowUpVisitRequestMessageAuthorKind.REQUESTER ->
            if (readerIsRequester) {
                FollowUpConversationSpeaker.YOU
            } else {
                FollowUpConversationSpeaker.TECHNICIAN
            }

        FollowUpVisitRequestMessageAuthorKind.OFFICE -> FollowUpConversationSpeaker.OFFICE
    }

/** Whether [authorKind] is the side currently reading the exchange. */
internal fun conversationMessageBelongsToReader(
    authorKind: FollowUpVisitRequestMessageAuthorKind,
    readerIsRequester: Boolean,
): Boolean =
    when (authorKind) {
        FollowUpVisitRequestMessageAuthorKind.REQUESTER -> readerIsRequester
        FollowUpVisitRequestMessageAuthorKind.OFFICE -> !readerIsRequester
    }

/** The label a speaker is stated with (`BR-028`, `BR-041`). */
internal fun conversationSpeakerLabel(speaker: FollowUpConversationSpeaker): Int =
    when (speaker) {
        FollowUpConversationSpeaker.YOU -> R.string.follow_up_conversation_you
        FollowUpConversationSpeaker.OFFICE -> R.string.follow_up_conversation_office
        FollowUpConversationSpeaker.TECHNICIAN -> R.string.follow_up_conversation_technician
    }

/**
 * The moment [message] records, in the device's own language and zone, or `null` when this build cannot
 * read it (`BR-028`, `BR-042`).
 *
 * The message's own recorded time is presented rather than compared: nothing on a screen decides
 * anything from it (`BR-001`).
 */
internal fun conversationMoment(
    message: FollowUpVisitRequestMessage,
    zone: ZoneId,
    locale: Locale,
): String? =
    instantOf(message.recordedAt)?.let { instant ->
        instant
            .atZone(zone)
            .format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withLocale(locale))
    }

/** Identifies one message inside the container its screen tags (`BR-FV-012`). */
fun requestConversationMessageTag(containerTag: String, messageId: String): String =
    "$containerTag-$messageId"

/**
 * A collapsible request exchange. The header keeps lists and detail screens compact; expanding reveals
 * the request's chat-like history without changing what the backend said (`BR-FV-012`).
 */
@Composable
internal fun FollowUpRequestConversationSection(
    messages: List<FollowUpVisitRequestMessage>,
    readerIsRequester: Boolean,
    zone: ZoneId,
    locale: Locale,
    tag: String,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable(tag) { mutableStateOf(false) }
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.technician_request_detail_conversation_label),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = messages.size.toString(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(6.dp))
            Icon(
                painter = painterResource(
                    if (expanded) {
                        R.drawable.ic_chevron_down
                    } else {
                        R.drawable.ic_chevron_right
                    },
                ),
                contentDescription = null,
                modifier = Modifier.size(FollowUpExchangeIconSize),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (expanded) {
            FollowUpRequestConversation(
                messages = messages,
                readerIsRequester = readerIsRequester,
                zone = zone,
                locale = locale,
                tag = tag,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

/**
 * A request's clarification conversation, oldest first, each message saying who wrote it and when
 * (`BR-FV-012`, `BR-013`).
 *
 * Both surfaces draw the same thread, because the exchange is the request's own history rather than one
 * side's view of it: the technician reads the office's question beside their own answers, and the office
 * reads the same thread with the technician's answers attributed to the technician. [readerIsRequester]
 * is what makes "You" mean the reader rather than a fixed author (`BR-041`).
 *
 * Nothing here borrows a Visit's vocabulary and nothing is drawn as an appointment: a request is a
 * proposal about another field attempt (`BR-FV-002`).
 */
@Composable
internal fun FollowUpRequestConversation(
    messages: List<FollowUpVisitRequestMessage>,
    readerIsRequester: Boolean,
    zone: ZoneId,
    locale: Locale,
    tag: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().testTag(tag),
        verticalArrangement = Arrangement.spacedBy(FollowUpMessageSpacing),
    ) {
        for (message in messages) {
            FollowUpConversationBubble(
                message = message,
                readerIsRequester = readerIsRequester,
                zone = zone,
                locale = locale,
                tag = tag,
            )
        }
    }
}

@Composable
private fun FollowUpConversationBubble(
    message: FollowUpVisitRequestMessage,
    readerIsRequester: Boolean,
    zone: ZoneId,
    locale: Locale,
    tag: String,
) {
    val belongsToReader = conversationMessageBelongsToReader(message.authorKind, readerIsRequester)
    val speaker = stringResource(
        conversationSpeakerLabel(
            conversationSpeaker(message.authorKind, readerIsRequester),
        ),
    )
    val maxBubbleWidth = LocalConfiguration.current.screenWidthDp.dp * FollowUpBubbleMaxWidthFraction
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (belongsToReader) Arrangement.End else Arrangement.Start,
    ) {
        Box(
            modifier = Modifier
                .widthIn(min = FollowUpBubbleMinWidth, max = maxBubbleWidth)
                .background(
                    color = if (belongsToReader) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                    shape = MaterialTheme.shapes.large,
                )
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(FollowUpMessageLineSpacing)) {
                Text(
                    text = conversationMoment(message, zone, locale)
                        ?.let { moment ->
                            stringResource(R.string.follow_up_conversation_speaker, speaker, moment)
                        }
                        // A moment this build cannot read is not invented: the message says who wrote it
                        // and its own text stands (`BR-042`).
                        ?: speaker,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (belongsToReader) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.testTag(requestConversationMessageTag(tag, message.id)),
                )
                // The message's own words are user-entered content: stated exactly as they were written,
                // never translated and never replaced by localized text (`Project.md` §10, `dev.md` §9).
                Text(
                    text = message.body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (belongsToReader) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

private val FollowUpMessageSpacing = 8.dp
private val FollowUpMessageLineSpacing = 2.dp
private val FollowUpExchangeIconSize = 18.dp
private val FollowUpBubbleMinWidth = 96.dp
private const val FollowUpBubbleMaxWidthFraction = 0.86f

