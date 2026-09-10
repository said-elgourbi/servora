package com.servora.android.ui.appearance

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.servora.android.R
import com.servora.android.data.preferences.AppLanguage
import com.servora.android.data.preferences.AppTheme

/** Track and segment metrics taken from the design's pills (`Figma/src/screens/Login.tsx`). */
private val TrackPadding = 4.dp
private val TrackGap = 2.dp
private val SegmentHeight = 40.dp
private val LanguageSegmentMinWidth = 52.dp
private val ThemeSegmentWidth = 44.dp
private val SegmentIconSize = 18.dp
private val SelectedSegmentElevation = 1.dp

/**
 * The appearance controls the sign-in page shows at the top of the screen: the app language on
 * the left and the light/dark theme on the right.
 *
 * Each control is a segmented pill, as designed: a quiet track
 * ([MaterialTheme.colorScheme.secondary]) holding the option that is currently applied, raised on
 * the surface colour. Both choices apply to the whole app and are remembered on the device
 * (`docs/decisions/007-android-appearance-controls.md`).
 */
@Composable
internal fun AppearanceControls(
    modifier: Modifier = Modifier,
    appearance: AppAppearance = LocalAppAppearance.current,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LanguageControl(
            selected = appearance.language,
            onSelect = appearance.onLanguageChange,
        )
        ThemeControl(
            selected = appearance.theme,
            onSelect = appearance.onThemeChange,
        )
    }
}

/** One segment per language the app ships. */
@Composable
private fun LanguageControl(
    selected: AppLanguage,
    onSelect: (AppLanguage) -> Unit,
    modifier: Modifier = Modifier,
) {
    SegmentedTrack(modifier = modifier) {
        AppLanguage.entries.forEach { language ->
            Segment(
                selected = language == selected,
                onClick = { onSelect(language) },
                // The segment is announced by the language's own name, not by its code.
                label = stringResource(languageNameRes(language)),
                modifier = Modifier.widthIn(min = LanguageSegmentMinWidth),
            ) {
                Text(
                    text = stringResource(languageCodeRes(language)),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    // The spoken label replaces the abbreviation rather than adding to it.
                    modifier = Modifier.clearAndSetSemantics {},
                )
            }
        }
    }
}

/** One segment per appearance the product offers: light and dark, never the system setting. */
@Composable
private fun ThemeControl(
    selected: AppTheme,
    onSelect: (AppTheme) -> Unit,
    modifier: Modifier = Modifier,
) {
    SegmentedTrack(modifier = modifier) {
        AppTheme.entries.forEach { theme ->
            Segment(
                selected = theme == selected,
                onClick = { onSelect(theme) },
                label = stringResource(themeNameRes(theme)),
                modifier = Modifier.width(ThemeSegmentWidth),
            ) {
                Icon(
                    painter = painterResource(themeIconRes(theme)),
                    contentDescription = null,
                    modifier = Modifier.size(SegmentIconSize),
                )
            }
        }
    }
}

/** The quiet track both pills sit in. */
@Composable
private fun SegmentedTrack(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.secondary,
    ) {
        Row(
            modifier = Modifier
                .padding(TrackPadding)
                .selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(TrackGap),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}

/**
 * One option of a pill: flat while it is not applied, raised on the surface colour while it is.
 *
 * [label] is what a screen reader announces, which is why the visible content is not reused for
 * it — the design's abbreviations and icons do not say what they do.
 */
@Composable
private fun Segment(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier
            .height(SegmentHeight)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = label },
        shape = MaterialTheme.shapes.extraSmall,
        color = if (selected) MaterialTheme.colorScheme.surface else Color.Transparent,
        contentColor = if (selected) {
            MaterialTheme.colorScheme.onSurface
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        shadowElevation = if (selected) SelectedSegmentElevation else 0.dp,
    ) {
        Box(
            modifier = Modifier.padding(horizontal = TrackPadding * 2),
            contentAlignment = Alignment.Center,
            content = { content() },
        )
    }
}

@StringRes
private fun languageCodeRes(language: AppLanguage): Int = when (language) {
    AppLanguage.ENGLISH -> R.string.appearance_language_english_code
    AppLanguage.FRENCH -> R.string.appearance_language_french_code
}

@StringRes
private fun languageNameRes(language: AppLanguage): Int = when (language) {
    AppLanguage.ENGLISH -> R.string.appearance_language_english_name
    AppLanguage.FRENCH -> R.string.appearance_language_french_name
}

@StringRes
private fun themeNameRes(theme: AppTheme): Int = when (theme) {
    AppTheme.LIGHT -> R.string.appearance_theme_light
    AppTheme.DARK -> R.string.appearance_theme_dark
}

@DrawableRes
private fun themeIconRes(theme: AppTheme): Int = when (theme) {
    AppTheme.LIGHT -> R.drawable.ic_appearance_light
    AppTheme.DARK -> R.drawable.ic_appearance_dark
}
