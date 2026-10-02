package com.servora.android.ui.components

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/*
 * The tappable contact line and the `tel:`/`mailto:` actions behind it.
 *
 * They live here rather than in one feature package because more than one feature reaches a person from
 * what it is showing: the customers list and Customer Detail draw them for a Customer's own values
 * (`ADR-022` D5, D6), and the Job Details Contacts section draws them for the effective primary contact
 * and for every other way of reaching the Customer (`BR-092`, `BR-095`). A second copy of the affordance
 * would let the app dial one value and compose another (`BR-041`, `dev.md` §1).
 */

/** The size of the glyph that leads a contact line. */
private val ContactLineIconSize = 12.dp

/** The tag a missing dialer or email client is reported under. */
private const val ContactIntentLogTag = "ServoraContact"

/**
 * One contact line: the leading glyph and the value.
 *
 * The line is a link only when [onClick] is supplied — Customer Details and Job Details pass the
 * `tel:`/`mailto:` action for the phone and the email, while the customers list passes none so that a tap on
 * a contact value opens the customer rather than dialling or composing
 * (`docs/tracker/007-android-customers-list.md`).
 */
@Composable
internal fun CustomerContactLine(
    text: String,
    glyph: Int,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val shape = MaterialTheme.shapes.small
    val lineModifier = if (onClick == null) {
        modifier.clip(shape)
    } else {
        modifier.clip(shape).clickable(onClick = onClick)
    }
    Row(
        modifier = lineModifier.padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            painter = painterResource(glyph),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(ContactLineIconSize),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Opens the dialer with [phone]. `ACTION_DIAL` needs no permission and never places the call. */
internal fun dialIntent(phone: String): Intent =
    Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", phone, null))

/** Opens the user's email client addressed to [email]. */
internal fun mailIntent(email: String): Intent =
    Intent(Intent.ACTION_SENDTO, Uri.fromParts("mailto", email, null))

/**
 * Starts an outbound contact intent.
 *
 * A device with no dialer or email client keeps the value visible instead of crashing the screen. The
 * failure is logged rather than rethrown because nothing in the app can recover from a missing platform
 * app; the contact value itself is never logged.
 */
internal fun Context.startContactIntent(intent: Intent) {
    try {
        startActivity(intent)
    } catch (missing: ActivityNotFoundException) {
        Log.d(ContactIntentLogTag, "No activity handled an outbound contact intent.", missing)
    }
}
