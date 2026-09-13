package com.servora.android.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.servora.android.R

/** Identifies the one contextual top bar, so a test can assert a screen has exactly one. */
const val ServoraTopBarTag = "servora-top-bar"

/** Identifies the top bar's back control, so navigation can be driven from a test. */
const val ServoraTopBarBackTag = "servora-top-bar-back"

/** Identifies the top bar's title, so a test can assert the destination's own title. */
const val ServoraTopBarTitleTag = "servora-top-bar-title"

/** Identifies the top bar's optional context line, so a test can assert it. */
const val ServoraTopBarSubtitleTag = "servora-top-bar-subtitle"

private val BackIconSize = 22.dp

/**
 * Everything the one contextual top bar needs for the destination that is currently on top.
 *
 * The destination supplies its own localized [title] and says whether it is a [isRoot] destination:
 * a root destination reached directly from the bottom navigation has nothing to go back to, so it
 * shows no back control. Every other destination passes [onBack], which pops the same back-stack
 * entry Android's system Back pops (`docs/decisions/011-android-contextual-top-bar.md`).
 *
 * [subtitle] is the destination's optional context line: the Add Property form names the customer
 * the new Property belongs to, so the screen is never ambiguous about what it is creating. A
 * destination without context leaves it `null` and draws the title alone.
 *
 * [actions] is the destination's own trailing content. A screen with no contextual action leaves it
 * empty, as the product direction requires.
 */
data class ServoraTopBarState(
    val title: String,
    val isRoot: Boolean,
    val onBack: (() -> Unit)? = null,
    val subtitle: String? = null,
    val actions: @Composable RowScope.() -> Unit = {},
)

/**
 * The single contextual header of the signed-in application.
 *
 * It is drawn once, by the app shell's `Scaffold`, so a screen never stacks a second header above
 * its own content. The back control's meaning is carried by a localized content description rather
 * than visible text (`BR-028`), and it is only present when the destination can actually go back.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServoraTopBar(
    state: ServoraTopBarState,
    modifier: Modifier = Modifier,
) {
    TopAppBar(
        modifier = modifier.testTag(ServoraTopBarTag),
        title = {
            Column {
                Text(
                    text = state.title,
                    modifier = Modifier.testTag(ServoraTopBarTitleTag),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                state.subtitle?.takeIf { it.isNotBlank() }?.let { subtitle ->
                    Text(
                        text = subtitle,
                        modifier = Modifier.testTag(ServoraTopBarSubtitleTag),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
        navigationIcon = {
            val onBack = state.onBack
            if (!state.isRoot && onBack != null) {
                IconButton(
                    modifier = Modifier.testTag(ServoraTopBarBackTag),
                    onClick = onBack,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_chevron_left),
                        contentDescription = stringResource(R.string.nav_back),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(BackIconSize),
                    )
                }
            }
        },
        actions = state.actions,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
            titleContentColor = MaterialTheme.colorScheme.onBackground,
            navigationIconContentColor = MaterialTheme.colorScheme.primary,
            actionIconContentColor = MaterialTheme.colorScheme.primary,
        ),
    )
}
