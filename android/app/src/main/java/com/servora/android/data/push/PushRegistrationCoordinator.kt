package com.servora.android.data.push

import android.util.Log
import com.servora.android.BuildConfig
import com.servora.android.data.offline.ConnectivityObserver
import com.servora.android.data.preferences.NotificationPreferences
import com.servora.android.data.session.SessionStore
import com.servora.android.di.ApplicationScope
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Latest-state reconciliation only, with capped backoff. Nothing enters the business outbox. */
@Singleton
class PushRegistrationCoordinator @Inject constructor(
    private val reconciler: PushRegistrationReconciler,
    private val notifications: AssignmentNotifications,
    private val sessions: SessionStore,
    private val preferences: NotificationPreferences,
    private val environment: NotificationEnvironment,
    private val connectivity: ConnectivityObserver,
    @param:ApplicationScope private val scope: CoroutineScope,
) {
    private val triggers = Channel<Unit>(Channel.CONFLATED)
    private var started = false

    @Synchronized
    fun start() {
        if (started) return
        started = true
        environment.refresh()
        scope.launch {
            connectivity.availability().collect { if (it) requestReconciliation() }
        }
        scope.launch {
            while (true) {
                delay(24 * 60 * 60 * 1000L)
                environment.refresh()
                requestReconciliation()
            }
        }
        scope.launch {
            var previous = sessions.current()
            sessions.changes.collect { current ->
                val ended = previous
                previous = current
                if (ended != null && ended.sessionId != current?.sessionId) {
                    // Cleanup uses the captured credential; it cannot unregister a new session.
                    launch {
                        withTimeoutOrNull(10_000) {
                            reconciler.unregister(ended.sessionId, ended.tokens.accessToken)
                        }
                    }
                }
            }
        }
        scope.launch {
            reconcileLatestPushState(
                sessions.changes.map { it?.sessionId }.distinctUntilChanged(),
                preferences.enabled,
                environment.state,
                triggers.receiveAsFlow().onStart { emit(Unit) },
            ) { sessionId, enabled, device ->
                reconciler.reconcile(sessionId, BuildConfig.APPLICATION_ID, enabled, device).also {
                    if (it == PushReconciliationResult.REGISTERED) notifications.clearObsolete()
                    if (it == PushReconciliationResult.STOPPED) {
                        // Deliberately omit HTTP bodies, provider exceptions, proof and identifiers.
                        Log.w("PushRegistration", "Registration paused until the next lifecycle trigger")
                    }
                }
            }
        }
    }

    fun requestReconciliation() { triggers.trySend(Unit) }
}

internal suspend fun reconcileLatestPushState(
    sessions: Flow<String?>,
    enabled: Flow<Boolean>,
    device: Flow<PushDeviceState>,
    triggers: Flow<Unit>,
    reconcile: suspend (String, Boolean, PushDeviceState) -> PushReconciliationResult,
) {
    combine(sessions, enabled, device, triggers) { sessionId, preference, observed, _ ->
        Triple(sessionId, preference, observed)
    }.collectLatest { (sessionId, preference, observed) ->
        if (sessionId == null) return@collectLatest
        var backoff = 1_000L
        while (true) {
            val result = withTimeoutOrNull(30_000) {
                reconcile(sessionId, preference, observed)
            } ?: PushReconciliationResult.RETRY
            if (result != PushReconciliationResult.RETRY) break
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(15 * 60 * 1000L)
        }
    }
}
