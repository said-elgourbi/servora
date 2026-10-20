package com.servora.android.ui.notifications

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.res.stringResource
import com.servora.android.R
import com.servora.android.data.push.PushDeviceState

/** Activity owns the OS permission flow; screens only render its observed state. */
data class NotificationSettings(
    val device: PushDeviceState,
    val requestPermission: () -> Unit,
)

val LocalNotificationSettings = staticCompositionLocalOf<NotificationSettings?> { null }

@Composable
fun NotificationPermissionSettings() {
    val settings = LocalNotificationSettings.current ?: return
    val available = settings.device.notificationPermission == "GRANTED" && settings.device.channelEnabled
    Text(stringResource(if (available) R.string.notification_permission_available else R.string.notification_permission_unavailable))
    if (!available) {
        TextButton(onClick = settings.requestPermission) {
            Text(stringResource(R.string.notification_permission_action))
        }
    }
}
