package com.servora.android.data.push

import android.Manifest
import android.os.Build
import android.content.pm.PackageManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationCompat
import com.servora.android.MainActivity
import com.servora.android.R
import com.servora.android.data.preferences.NotificationPreferences
import com.servora.android.data.session.SessionStore
import com.servora.android.di.ApplicationScope
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

@Singleton
class AssignmentNotifications @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val sessions: SessionStore,
    private val installation: PushInstallationStorage,
    private val preferences: NotificationPreferences,
    private val environment: NotificationEnvironment,
    private val receipts: PushReceiptLedger,
    @param:ApplicationScope private val scope: CoroutineScope,
) {
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val _pending = MutableStateFlow<AssignmentPushMessage?>(null)
    val pending = _pending.asStateFlow()
    private var started = false

    @Synchronized
    fun start() {
        if (started) return
        started = true
        scope.launch {
            try { sessions.restore() } catch (_: IOException) {
                Log.w("AssignmentPush", "Session storage unavailable; clearing alerts")
                clear()
                return@launch
            }
            var previousSession: String? = null
            combine(sessions.changes, preferences.enabled, environment.state) { session, enabled, device ->
                Triple(session?.sessionId, enabled, device)
            }.collect { (sessionId, enabled, device) ->
                if (previousSession != null && previousSession != sessionId) clear()
                previousSession = sessionId
                if (!enabled || device.notificationPermission != "GRANTED" || !device.channelEnabled) {
                    clearDisplayed()
                }
                clearObsolete()
            }
        }
    }

    /** Called on the FCM service thread after local session storage has been restored. No network I/O. */
    fun receive(payload: Map<String, String>) {
        val message = AssignmentPushMessage.parse(payload, Instant.now()) ?: return
        try {
            synchronized(sessions) {
                environment.refresh()
                if (!canPresent(message) || !receipts.reserve(message, Instant.now())) return
                if (!canPresent(message)) return
                val intent = Intent(context, MainActivity::class.java).apply {
                    action = ACTION
                    this.data = Uri.parse("servora-assignment://${message.bindingId}/${message.eventId}")
                    flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    payload.forEach { (key, value) -> putExtra(key, value) }
                }
                val tap = PendingIntent.getActivity(context, 0, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                // Reads the persisted app locale even on a service-only cold start (API 26–32 too).
                val localized = ContextCompat.getContextForLanguage(context)
                if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                        context, Manifest.permission.POST_NOTIFICATIONS,
                    ) != PackageManager.PERMISSION_GRANTED) return
                manager.notify(message.notificationTag, NOTIFICATION_ID,
                    NotificationCompat.Builder(context, NotificationEnvironment.ASSIGNMENTS_CHANNEL)
                        .setSmallIcon(R.drawable.ic_notification_assignment)
                        .setContentTitle(localized.getString(R.string.notification_assignment_title))
                        .setContentText(localized.getString(R.string.notification_assignment_body))
                        .setContentIntent(tap).setAutoCancel(true).setOnlyAlertOnce(true)
                        .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                        .setTimeoutAfter((message.expiresAt.toEpochMilli() - System.currentTimeMillis()).coerceAtLeast(1))
                        .build())
            }
        } catch (_: IOException) {
            Log.w("AssignmentPush", "Receipt storage unavailable; alert suppressed")
        } catch (_: SecurityException) {
            Log.w("AssignmentPush", "Notification permission unavailable; alert suppressed")
        }
    }

    fun acceptTap(intent: Intent?) {
        if (intent?.action != ACTION) return
        val keys = listOf("version", "eventType", "eventId", "bindingId", "organizationId", "jobId", "visitId", "expiresAt")
        val data = keys.associateWith { intent.getStringExtra(it).orEmpty() }
        _pending.value = AssignmentPushMessage.parse(data, Instant.now())
        // Recreation must not replay a consumed navigation request.
        intent.action = null
        keys.forEach(intent::removeExtra)
    }

    /** Only the authenticated NavHost consumes a tap, through its ordinary Job/Visit route. */
    fun consumeTap(message: AssignmentPushMessage): AssignmentPushMessage? = synchronized(sessions) {
        if (_pending.value != message) return null
        _pending.value = null
        try { if (matches(message)) message else null } catch (_: IOException) {
            Log.w("AssignmentPush", "Installation storage unavailable; tap discarded")
            null
        }
    }

    fun clearObsolete() {
        synchronized(sessions) {
            val current = try { installation.read() } catch (_: IOException) {
                Log.w("AssignmentPush", "Installation storage unavailable; clearing alerts")
                clearDisplayed()
                return
            }
            val sessionId = sessions.current()?.sessionId
            manager.activeNotifications.filter {
                it.tag?.startsWith("assignment:") == true &&
                    (sessionId == null || current?.sessionId != sessionId ||
                        it.tag?.startsWith("assignment:${current.bindingId}:") != true)
            }.forEach { manager.cancel(it.tag, it.id) }
        }
    }

    fun clear() {
        _pending.value = null
        clearDisplayed()
    }

    private fun clearDisplayed() = synchronized(sessions) {
        manager.activeNotifications.filter { it.tag?.startsWith("assignment:") == true }
            .forEach { manager.cancel(it.tag, it.id) }
    }

    private fun matches(message: AssignmentPushMessage): Boolean = assignmentBindingMatches(
        message, installation.read(), sessions.current()?.sessionId, Instant.now(),
    )

    private fun canPresent(message: AssignmentPushMessage): Boolean {
        val device = environment.state.value
        return preferences.enabled.value && device.notificationPermission == "GRANTED" &&
            device.channelEnabled && manager.areNotificationsEnabled() && matches(message)
    }

    companion object {
        const val ACTION = "com.servora.android.OPEN_ASSIGNMENT"
        private const val NOTIFICATION_ID = 1
    }
}
