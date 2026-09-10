package com.servora.android.data.preferences

import androidx.appcompat.app.AppCompatDelegate
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The stored theme is applied to the platform as an `AppCompatDelegate` night mode, so that
 * mapping is behaviour worth pinning: a wrong one would show the theme the user did not choose
 * (`docs/decisions/007-android-appearance-controls.md`).
 */
class AppThemeTest {

    @Test
    fun `applies light as the day mode`() {
        assertEquals(AppCompatDelegate.MODE_NIGHT_NO, AppTheme.LIGHT.nightMode)
    }

    @Test
    fun `applies dark as the night mode`() {
        assertEquals(AppCompatDelegate.MODE_NIGHT_YES, AppTheme.DARK.nightMode)
    }

    @Test
    fun `offers exactly the two designed appearances`() {
        assertEquals(listOf(AppTheme.LIGHT, AppTheme.DARK), AppTheme.entries)
    }

    @Test
    fun `resolves a stored choice`() {
        assertEquals(AppTheme.DARK, AppTheme.fromStoredValue("DARK"))
    }

    @Test
    fun `falls back to light when no choice was stored`() {
        assertEquals(AppTheme.LIGHT, AppTheme.fromStoredValue(null))
    }

    @Test
    fun `falls back to light for a value the app does not ship`() {
        // Also covers a build that stored a system-following appearance: the design offers light
        // and dark only, so an unreadable value must resolve to something the app can draw.
        assertEquals(AppTheme.LIGHT, AppTheme.fromStoredValue("SYSTEM"))
    }
}
