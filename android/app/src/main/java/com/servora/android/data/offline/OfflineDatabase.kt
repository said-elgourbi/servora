package com.servora.android.data.offline

import androidx.room.Database
import androidx.room.RoomDatabase
import com.servora.android.data.jobs.PendingJobPhotoDao
import com.servora.android.data.jobs.PendingJobPhotoEntity

/**
 * The device-local store: the working set (last state the backend reported), the outbox (pending
 * mutations) and the pending photo records, the stores
 * `docs/architecture/offline-first-architecture.md` §2, §9 defines.
 *
 * Business data on the device is never authoritative (`BR-001`, `BR-031`). The schema is migrated
 * with Room's own mechanism and **never** through a destructive fallback: a local schema change must
 * carry pending work forward rather than drop it (§3, `BR-014`). `1.json` under `android/app/schemas`
 * is the baseline `MIGRATION_1_2` is written against, and `2.json` is the current export.
 */
@Database(
    entities = [
        OutboxOperationEntity::class,
        WorkingSetEntryEntity::class,
        PendingJobPhotoEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
internal abstract class OfflineDatabase : RoomDatabase() {
    abstract fun outboxDao(): OutboxDao
    abstract fun workingSetDao(): WorkingSetDao

    /** The pending photo records: evidence captured on this device the backend has not accepted. */
    abstract fun pendingJobPhotoDao(): PendingJobPhotoDao

    internal companion object {
        /** The database file name; one local store serves every signed-in subject. */
        const val NAME = "servora-offline.db"
    }
}
