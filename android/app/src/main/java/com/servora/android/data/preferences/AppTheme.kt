package com.servora.android.data.preferences

import androidx.appcompat.app.AppCompatDelegate

/**
 * The appearance a user can choose for the app itself.
 *
 * The approved design offers light and dark only, so there is deliberately no `SYSTEM` value:
 * the app never follows the device setting, and the stored choice is applied before the first
 * frame. Nothing beyond the two designed options is offered (`BR-042`).
 */
enum class AppTheme {
    LIGHT,
    DARK;

    /** The `AppCompatDelegate` night mode this choice applies. */
    val nightMode: Int
        get() = when (this) {
            LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            DARK -> AppCompatDelegate.MODE_NIGHT_YES
        }

    companion object {
        /**
         * Resolves a stored choice.
         *
         * A first run, or a value written by an older build, resolves to the light appearance,
         * which is what the window theme in `values/themes.xml` draws.
         */
        fun fromStoredValue(stored: String?): AppTheme =
            entries.firstOrNull { it.name == stored } ?: LIGHT
    }
}
