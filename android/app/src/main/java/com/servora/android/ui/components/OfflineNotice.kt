package com.servora.android.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp

/**
 * A notice about how current what is on screen is, or about work the backend has not answered yet
 * (`docs/architecture/offline-first-architecture.md` §2, §7).
 *
 * Waiting uses the quiet secondary container so it never competes with a refusal; a refusal uses the
 * error container the screens use for their other refusals. It is never a dialog: nothing here
 * blocks field work (`BR-012`, `BR-015`).
 */
@Composable
fun OfflineNotice(
    message: String,
    tag: String,
    isRefusal: Boolean = false,
    glyphRes: Int? = null,
    modifier: Modifier = Modifier,
) {
    val container = if (isRefusal) {
        MaterialTheme.colorScheme.errorContainer
    } else {
        MaterialTheme.colorScheme.secondaryContainer
    }
    val content = if (isRefusal) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.onSecondaryContainer
    }
    Surface(
        modifier = modifier.fillMaxWidth().testTag(tag),
        shape = MaterialTheme.shapes.large,
        color = container,
        contentColor = content,
        border = BorderStroke(1.dp, content.copy(alpha = 0.25f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (glyphRes != null) {
                Icon(
                    painter = painterResource(glyphRes),
                    contentDescription = null,
                    tint = content,
                    modifier = Modifier.size(16.dp),
                )
            }
            Text(text = message, style = MaterialTheme.typography.bodySmall)
        }
    }
}
