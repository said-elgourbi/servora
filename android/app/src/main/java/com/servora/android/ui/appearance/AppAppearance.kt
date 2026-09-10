package com.servora.android.ui.appearance

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import com.servora.android.data.preferences.AppLanguage
import com.servora.android.data.preferences.AppTheme

/**
 * The appearance the chrome controls render, plus the changes they request.
 *
 * The values come from the platform state the activity is applying, and the callbacks reach the
 * code that can change it ([com.servora.android.data.preferences.AppearancePreferences]) — which
 * only the application itself can do. Screens read [LocalAppAppearance] instead of taking four
 * more parameters, so a screen that gains the controls does not change shape
 * (`docs/decisions/007-android-appearance-controls.md`).
 */
@Immutable
data class AppAppearance(
    val language: AppLanguage,
    val theme: AppTheme,
    val onLanguageChange: (AppLanguage) -> Unit,
    val onThemeChange: (AppTheme) -> Unit,
)

/**
 * The appearance used when no activity supplies one: previews and component tests.
 *
 * The defaults are the app's starting appearance and the changes are ignored, so a screen
 * rendered outside the app draws working controls that do nothing.
 */
val LocalAppAppearance = staticCompositionLocalOf {
    AppAppearance(
        language = AppLanguage.ENGLISH,
        theme = AppTheme.LIGHT,
        onLanguageChange = {},
        onThemeChange = {},
    )
}
