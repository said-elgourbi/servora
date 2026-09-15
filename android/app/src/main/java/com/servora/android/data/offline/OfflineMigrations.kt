package com.servora.android.data.offline

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * The local schema's migrations.
 *
 * A migration is the only way this database changes shape: the builder has no destructive fallback,
 * so a schema change that is not migrated would fail rather than drop the technician's pending work
 * (`docs/architecture/offline-first-architecture.md` §3, `BR-014`).
 */
internal object OfflineMigrations {

    /**
     * 1 → 2 adds the pending photo records (`JobPhotoFiles`, `PendingJobPhotoStore`).
     *
     * The statements are the ones Room derives from `PendingJobPhotoEntity`; they are written out here
     * because the table has to be created on a device that already holds queued work, which is
     * exactly when a destructive fallback would be unacceptable.
     */
    val MIGRATION_1_2: Migration = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "create table if not exists `pending_job_photos` (" +
                    "`photoId` text not null, " +
                    "`subjectId` text not null, " +
                    "`jobId` text not null, " +
                    "`localPath` text not null, " +
                    "`phase` text, " +
                    "`note` text, " +
                    "`capturedAt` text not null, " +
                    "`mimeType` text not null, " +
                    "`recordedAt` integer not null, " +
                    "`submitted` integer not null, " +
                    "primary key(`photoId`))",
            )
            db.execSQL(
                "create index if not exists `index_pending_job_photos_subjectId_jobId_recordedAt` " +
                    "on `pending_job_photos` (`subjectId`, `jobId`, `recordedAt`)",
            )
            db.execSQL(
                "create index if not exists `index_pending_job_photos_submitted` " +
                    "on `pending_job_photos` (`submitted`)",
            )
        }
    }

    /** Every migration the database needs, in the order Room applies them. */
    val ALL: Array<Migration> = arrayOf(MIGRATION_1_2)
}
