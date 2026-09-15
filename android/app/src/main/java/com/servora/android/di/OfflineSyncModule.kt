package com.servora.android.di

import com.servora.android.data.offline.AndroidConnectivityObserver
import com.servora.android.data.offline.ConnectivityObserver
import com.servora.android.data.offline.OfflineOperationHandler
import com.servora.android.data.offline.OfflineSessionLifecycle
import com.servora.android.data.offline.OfflineSync
import com.servora.android.data.offline.OfflineSyncCoordinator
import com.servora.android.data.offline.SessionScopedOfflineState
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.Multibinds
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * The replay machinery: when the queue is replayed, what observes connectivity, and what a session's
 * life does to local state (`docs/architecture/offline-first-architecture.md` §6, §10).
 */
@Module
@InstallIn(SingletonComponent::class)
internal object OfflineSyncModule {

    /** The scope offline replay runs on for the process's lifetime (`ApplicationScope`). */
    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)
}

/** Binds the replay machinery to its implementations. */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class OfflineSyncBindingsModule {

    @Binds
    @Singleton
    abstract fun bindConnectivityObserver(
        implementation: AndroidConnectivityObserver,
    ): ConnectivityObserver

    @Binds
    @Singleton
    abstract fun bindOfflineSync(implementation: OfflineSyncCoordinator): OfflineSync

    @Binds
    @Singleton
    abstract fun bindOfflineSessionLifecycle(
        implementation: SessionScopedOfflineState,
    ): OfflineSessionLifecycle

    /**
     * The handlers one replay can use.
     *
     * Declared as a multibinding so the engine can be built with none: a build that has no
     * offline-capable operation yet still constructs, and an operation queued by a build that had one
     * is left untouched rather than refused (`BR-014`).
     */
    @Multibinds
    abstract fun offlineOperationHandlers(): Set<OfflineOperationHandler>
}
