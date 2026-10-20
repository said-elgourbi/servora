package com.servora.android

import android.app.Application
import coil3.ImageLoader
import coil3.SingletonImageLoader
import com.servora.android.data.preferences.AppearancePreferences
import com.servora.android.data.push.PushRegistrationCoordinator
import com.servora.android.data.session.SessionManager
import com.servora.android.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
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

    @Inject
    lateinit var imageLoader: ImageLoader

    @Inject
    lateinit var pushRegistration: PushRegistrationCoordinator

    @Inject lateinit var assignmentNotifications: com.servora.android.data.push.AssignmentNotifications

    @Inject lateinit var sessionManager: SessionManager
    @Inject @ApplicationScope lateinit var applicationScope: CoroutineScope

    override fun onCreate() {
        super.onCreate()
        // The app's own image stack is the one the composables draw with, so a photo is read by the
        // feature's reader and cached once rather than by a second stack with caches of its own
        // (`D4b`, `docs/decisions/016-android-image-stack-and-viewer-zoom.md`).
        SingletonImageLoader.setSafe { imageLoader }
        // Applied before any activity exists so the first frame already uses the appearance the
        // user chose on a previous run (`docs/decisions/007-android-appearance-controls.md`).
        appearancePreferences.applyStoredTheme()
        pushRegistration.start()
        assignmentNotifications.start()
        // Token rotation can launch a service without an activity. Restore through the same
        // idempotent owner the UI uses, so background reconciliation can authenticate too.
        applicationScope.launch { sessionManager.restore() }
    }
}
