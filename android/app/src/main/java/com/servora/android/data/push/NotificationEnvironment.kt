package com.servora.android.data.push

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.servora.android.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

@Singleton
class NotificationEnvironment @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val preferences = context.getSharedPreferences("servora.push.permission", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(PushDeviceState())
    val state = _state.asStateFlow()

    fun markPermissionRequested() {
        // Write before launching the system prompt, so a process restart preserves request history.
        preferences.edit(commit = true) { putBoolean("requested", true) }
        refresh()
    }

    fun refresh() {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(
            ASSIGNMENTS_CHANNEL, context.getString(R.string.notification_channel_assignments),
            NotificationManager.IMPORTANCE_DEFAULT,
        ))
        val granted = if (Build.VERSION.SDK_INT < 33) manager.areNotificationsEnabled()
        else ContextCompat.checkSelfPermission(
            context, Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        val requested = Build.VERSION.SDK_INT < 33 || preferences.getBoolean("requested", false)
        // A grant restored by Android also establishes permission history for later revocation.
        if (Build.VERSION.SDK_INT >= 33 && granted && !requested) {
            preferences.edit { putBoolean("requested", true) }
        }
        val locale = AppCompatDelegate.getApplicationLocales()[0]
            ?: ContextCompat.getContextForLanguage(context).resources.configuration.locales[0]
        val channel = manager.getNotificationChannel(ASSIGNMENTS_CHANNEL)
        _state.value = PushDeviceState(
            locale = if (locale?.language == "fr") "fr-CA" else "en-CA",
            notificationPermission = when {
                granted -> "GRANTED"
                requested -> "DENIED"
                else -> "NOT_REQUESTED"
            },
            channelEnabled = channel != null && channel.importance != NotificationManager.IMPORTANCE_NONE &&
                (Build.VERSION.SDK_INT < 28 || channel.group?.let {
                    manager.getNotificationChannelGroup(it)?.isBlocked
                } != true),
        )
    }

    companion object { const val ASSIGNMENTS_CHANNEL = "visit_assignments" }
}
