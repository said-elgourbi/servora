package com.servora.android.data.preferences

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Stores notification presentation consent for this device.
 *
 * The registration coordinator mirrors this installation's preference to the backend. It stays
 * distinct from Android permission/channel state; the default is never treated as OS consent.
 */
@Singleton
class NotificationPreferences @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val preferences: SharedPreferences =
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    private val _enabled = MutableStateFlow(preferences.getBoolean(KEY_ENABLED, true))

    val enabled: StateFlow<Boolean> = _enabled

    fun storeEnabled(enabled: Boolean) {
        preferences.edit { putBoolean(KEY_ENABLED, enabled) }
        _enabled.value = enabled
    }

    private companion object {
        const val PREFERENCES_NAME = "servora.notifications"
        const val KEY_ENABLED = "enabled"
    }
}
