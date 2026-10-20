package com.servora.android.data.push

import android.content.Context
import com.google.android.gms.tasks.Task
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/** Token compatibility mode matches the existing FCM_TOKEN API/Admin adapter; never returns a FID. */
@Singleton
class FirebasePushTokenProvider @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : PushTokenProvider {
    override val configured: Boolean get() = FirebaseApp.getApps(context).isNotEmpty()

    @Suppress("DEPRECATION")
    override suspend fun token(): String = FirebaseMessaging.getInstance().token.awaitRedacted()

    @Suppress("DEPRECATION")
    override suspend fun deleteToken() {
        FirebaseMessaging.getInstance().deleteToken().awaitRedacted()
    }
}

private suspend fun <T> Task<T>.awaitRedacted(): T = suspendCancellableCoroutine { continuation ->
    addOnCompleteListener(java.util.concurrent.Executor { it.run() }) { task ->
        if (continuation.isActive) {
            if (task.isSuccessful) continuation.resume(task.result)
            else continuation.resumeWithException(IOException("Firebase registration unavailable"))
        }
    }
}

@AndroidEntryPoint
class ServoraMessagingService : FirebaseMessagingService() {
    @Inject lateinit var registration: PushRegistrationCoordinator

    @Deprecated("FCM_TOKEN compatibility with the backend registration contract")
    override fun onNewToken(token: String) {
        // Fetch current SDK state in reconciliation rather than replaying this callback's value.
        registration.requestReconciliation()
    }
    @Inject lateinit var notifications: AssignmentNotifications
    @Inject lateinit var sessions: com.servora.android.data.session.SessionStore

    override fun onMessageReceived(message: com.google.firebase.messaging.RemoteMessage) {
        // Data-only callbacks run on the service worker thread. Restore local identity only;
        // presentation never waits for an API request or starts unowned asynchronous work.
        kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
            kotlinx.coroutines.withTimeoutOrNull(7_000) {
                sessions.restore()
                notifications.receive(message.data)
            }
        }
    }

}
