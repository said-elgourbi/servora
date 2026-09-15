package com.servora.android.data.offline

import com.servora.android.di.ApplicationScope
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

/**
 * Asks for the pending queue to be replayed
 * (`docs/architecture/offline-first-architecture.md` §6).
 *
 * Callers do not wait: a replay may need several round trips, and none of the trigger points — a
 * screen opening, a session being restored, a network coming back — should block on it. Runs are
 * serialized by [OutboxReplayEngine], so overlapping triggers cost one replay rather than several.
 */
interface OfflineSync {
    /** Asks for a replay; returns immediately. */
    fun requestSync()
}

/** Default [OfflineSync], replaying on an application-scoped coroutine scope. */
@Singleton
class OfflineSyncCoordinator @Inject constructor(
    private val engine: OutboxReplayEngine,
    private val connectivity: ConnectivityObserver,
    @ApplicationScope private val scope: CoroutineScope,
) : OfflineSync {

    init {
        // Connectivity is watched for the whole process: it is one of the three triggers the standard
        // names, and the only one the app cannot notice on its own.
        scope.launch {
            connectivity.availability().filter { it }.collect { requestSync() }
        }
    }

    override fun requestSync() {
        scope.launch { engine.replay() }
    }
}
