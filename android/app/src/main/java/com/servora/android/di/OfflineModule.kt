package com.servora.android.di

import android.content.Context
import androidx.room.Room
import com.servora.android.data.jobs.PendingJobPhotoDao
import com.servora.android.data.jobs.PendingJobPhotoStore
import com.servora.android.data.jobs.RoomPendingJobPhotoStore
import com.servora.android.data.offline.OfflineDatabase
import com.servora.android.data.offline.OfflineMigrations
import com.servora.android.data.offline.OutboxDao
import com.servora.android.data.offline.OutboxStore
import com.servora.android.data.offline.RoomOutboxStore
import com.servora.android.data.offline.RoomWorkingSetStore
import com.servora.android.data.offline.WorkingSetDao
import com.servora.android.data.offline.WorkingSetStore
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * The device-local store: the working set, the outbox and the pending photo records
 * (`docs/architecture/offline-first-architecture.md` §2–§4, §9).
 *
 * The database is built without a destructive fallback on purpose: a local schema change has to
 * migrate pending work rather than drop it, so every version bump ships a migration (§3, `BR-014`).
 */
@Module
@InstallIn(SingletonComponent::class)
internal object OfflineModule {

    @Provides
    @Singleton
    fun provideOfflineDatabase(@ApplicationContext context: Context): OfflineDatabase =
        Room.databaseBuilder(context, OfflineDatabase::class.java, OfflineDatabase.NAME)
            .addMigrations(*OfflineMigrations.ALL)
            .build()

    @Provides
    fun provideOutboxDao(database: OfflineDatabase): OutboxDao = database.outboxDao()

    @Provides
    fun provideWorkingSetDao(database: OfflineDatabase): WorkingSetDao = database.workingSetDao()

    @Provides
    fun providePendingJobPhotoDao(database: OfflineDatabase): PendingJobPhotoDao =
        database.pendingJobPhotoDao()
}

/** Binds the local stores to their Room-backed implementations. */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class OfflineStoreModule {

    @Binds
    @Singleton
    abstract fun bindOutboxStore(implementation: RoomOutboxStore): OutboxStore

    @Binds
    @Singleton
    abstract fun bindWorkingSetStore(implementation: RoomWorkingSetStore): WorkingSetStore

    /** The photos captured on this device the backend has not accepted (`BR-015`, §9). */
    @Binds
    @Singleton
    abstract fun bindPendingJobPhotoStore(
        implementation: RoomPendingJobPhotoStore,
    ): PendingJobPhotoStore
}
