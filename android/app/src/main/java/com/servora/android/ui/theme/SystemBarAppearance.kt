package com.servora.android.ui.theme

import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Paints the system bar content — the status bar's clock, connectivity and battery icons, and the
 * navigation bar's handle — to contrast with the appearance the app is actually drawing.
 *
 * The content must follow the app's *effective* appearance rather than the device setting: a user
 * who chooses the light appearance on a phone running dark mode still sees a light screen, so the
 * system bar content has to be dark. `AppCompatDelegate` turns that choice into the real
 * configuration value, so the [darkTheme] this receives is already the resolved app appearance,
 * including a system-following one should the product offer it later
 * (`docs/decisions/007-android-appearance-controls.md`).
 *
 * Only the content appearance is changed. The window keeps its edge-to-edge layout and the insets
 * the screens apply are untouched, so this cannot shift any content.
 */
@Composable
internal fun SystemBarAppearance(darkTheme: Boolean) {
    // A preview, or any composition with no activity behind it, has no window to paint.
    val activity = LocalActivity.current ?: return
    SideEffect {
        val window = activity.window
        WindowCompat.getInsetsController(window, window.decorView).apply {
            // Dark content on the light appearance, light content on the dark appearance.
            isAppearanceLightStatusBars = !darkTheme
            isAppearanceLightNavigationBars = !darkTheme
        }
    }
}
