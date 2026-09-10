package com.servora.android

import android.app.Application
import com.servora.android.data.preferences.AppearancePreferences
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * Application entry point.
 *
 * `@HiltAndroidApp` creates the application-level dependency container; composition
 * stays in the DI modules (`dev.md` §1).
 */
@HiltAndroidApp
class ServoraApplication : Application() {

    @Inject
    lateinit var appearancePreferences: AppearancePreferences

    override fun onCreate() {
        super.onCreate()
        // Applied before any activity exists so the first frame already uses the appearance the
        // user chose on a previous run (`docs/decisions/007-android-appearance-controls.md`).
        appearancePreferences.applyStoredTheme()
    }
}
