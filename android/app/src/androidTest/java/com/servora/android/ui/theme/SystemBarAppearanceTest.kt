package com.servora.android.ui.theme

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The status/navigation bar content must contrast with the appearance the app is drawing — not
 * with the device setting — so a light app appearance on a dark phone still gets dark system bar
 * icons (`docs/decisions/007-android-appearance-controls.md`).
 *
 * These tests assert the window appearance flag the platform reads, because pixels an
 * instrumentation test cannot sample reliably are what the on-device check confirms.
 */
@RunWith(AndroidJUnit4::class)
class SystemBarAppearanceTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun lightAppearanceUsesDarkSystemBarContent() {
        composeTestRule.setContent { ServoraTheme(darkTheme = false) {} }
        composeTestRule.waitForIdle()

        assertTrue(controller().isAppearanceLightStatusBars)
        assertTrue(controller().isAppearanceLightNavigationBars)
    }

    @Test
    fun darkAppearanceUsesLightSystemBarContent() {
        composeTestRule.setContent { ServoraTheme(darkTheme = true) {} }
        composeTestRule.waitForIdle()

        assertFalse(controller().isAppearanceLightStatusBars)
        assertFalse(controller().isAppearanceLightNavigationBars)
    }

    @Test
    fun changingTheAppearanceRepaintsTheSystemBarContent() {
        val darkTheme = mutableStateOf(false)
        composeTestRule.setContent { ServoraTheme(darkTheme = darkTheme.value) {} }
        composeTestRule.waitForIdle()
        assertTrue(controller().isAppearanceLightStatusBars)

        darkTheme.value = true
        composeTestRule.waitForIdle()

        assertFalse(controller().isAppearanceLightStatusBars)
        assertFalse(controller().isAppearanceLightNavigationBars)
    }

    private fun controller(): WindowInsetsControllerCompat {
        val window = composeTestRule.activity.window
        return WindowCompat.getInsetsController(window, window.decorView)
    }
}
