package com.servora.android.data.device

import android.content.Context
import android.os.Build
import com.servora.android.BuildConfig
import com.servora.android.domain.model.AuthSessionPlatform
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Identity this client presents when it asks the backend to issue a session
 * (`device` block of `POST /auth/sign-in`).
 */
data class DeviceIdentity(
    val platform: String,
    val deviceId: String,
    val deviceName: String?,
    val appVersion: String?,
)

/**
 * Supplies this installation's [DeviceIdentity].
 *
 * [DeviceIdentity.deviceId] is a random identifier generated once per installation
 * and kept in the app's private storage. It is deliberately not a hardware
 * identifier: the backend can tell installations apart without the client
 * reporting anything that identifies the physical device.
 */
@Singleton
class DeviceIdentityProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun current(): DeviceIdentity = DeviceIdentity(
        platform = AuthSessionPlatform.ANDROID.name,
        deviceId = installationId(),
        deviceName = Build.MODEL.takeIf { it.isNotBlank() },
        appVersion = BuildConfig.VERSION_NAME,
    )

    private fun installationId(): String {
        val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        val existing = preferences.getString(KEY_DEVICE_ID, null)
        if (existing != null) {
            return existing
        }
        val generated = UUID.randomUUID().toString()
        // Written synchronously: losing the identity to device death right after the
        // first request would register a second installation with the backend.
        preferences.edit().putString(KEY_DEVICE_ID, generated).commit()
        return generated
    }

    private companion object {
        const val PREFERENCES_NAME = "servora.device"
        const val KEY_DEVICE_ID = "deviceId"
    }
}
