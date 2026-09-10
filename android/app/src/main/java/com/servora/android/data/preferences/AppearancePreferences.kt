package com.servora.android.data.preferences

import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.edit
import androidx.core.os.LocaleListCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stores the two appearance choices of the app chrome — the app language and the light/dark
 * theme — and applies them through [AppCompatDelegate].
 *
 * Both are device-local presentation preferences rather than business data: they never leave the
 * device and are not part of the API contract (`BR-001`). The theme is a single enum read once
 * per process start, so app-private [SharedPreferences] is enough; DataStore would add writing
 * machinery for one value (`dev.md` §4).
 */
@Singleton
class AppearancePreferences @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /**
     * Applies the stored theme.
     *
     * Called from `ServoraApplication.onCreate`, before any activity is created, so the very
     * first frame already uses the chosen appearance.
     */
    fun applyStoredTheme() {
        AppCompatDelegate.setDefaultNightMode(storedTheme().nightMode)
    }

    /** Remembers [theme] and applies it to the running app. */
    fun storeTheme(theme: AppTheme) {
        preferences().edit { putString(KEY_THEME, theme.name) }
        AppCompatDelegate.setDefaultNightMode(theme.nightMode)
    }

    /**
     * Remembers [language] by applying it as the application locale.
     *
     * AppCompat persists this choice itself — including across process death — because
     * `AppLocalesMetadataHolderService` is declared in the manifest, so the app keeps no second
     * copy of it.
     */
    fun storeLanguage(language: AppLanguage) {
        val locales = LocaleListCompat.forLanguageTags(language.languageTag)
        AppCompatDelegate.setApplicationLocales(locales)
    }

    private fun preferences(): SharedPreferences =
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    private fun storedTheme(): AppTheme =
        AppTheme.fromStoredValue(preferences().getString(KEY_THEME, null))

    private companion object {
        const val PREFERENCES_NAME = "servora.appearance"
        const val KEY_THEME = "theme"
    }
}
