package com.servora.android.data.offline

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * The device-local store: the working set (last state the backend reported) and the outbox (pending
 * mutations), the two stores `docs/architecture/offline-first-architecture.md` §2 defines.
 *
 * Business data on the device is never authoritative (`BR-001`, `BR-031`). The schema is migrated
 * with Room's own mechanism and **never** through a destructive fallback: a local schema change must
 * carry pending work forward rather than drop it (§3, `BR-014`). `1.json` under `android/app/schemas`
 * is the baseline a future migration is written against.
 */
@Database(
    entities = [OutboxOperationEntity::class, WorkingSetEntryEntity::class],
    version = 1,
    exportSchema = true,
)
internal abstract class OfflineDatabase : RoomDatabase() {
    abstract fun outboxDao(): OutboxDao
    abstract fun workingSetDao(): WorkingSetDao

    internal companion object {
        /** The database file name; one local store serves every signed-in subject. */
        const val NAME = "servora-offline.db"
    }
}
