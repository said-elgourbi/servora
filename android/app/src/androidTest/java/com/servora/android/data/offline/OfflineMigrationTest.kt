package com.servora.android.data.offline

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The local schema's migration, on a real device.
 *
 * The database has no destructive fallback (`docs/architecture/offline-first-architecture.md` §3), so a
 * version bump that is not migrated would fail rather than drop a technician's pending work
 * (`BR-014`). This test creates a version 1 database, applies `MIGRATION_1_2`, and lets Room validate
 * the result against the committed `2.json` — which is what proves the migration SQL and the entity
 * agree.
 *
 * It cannot run on the JVM: it needs SQLite and the exported schemas as assets (`qa.md` §7.3, §9).
 */
@RunWith(AndroidJUnit4::class)
class OfflineMigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        OfflineDatabase::class.java,
    )

    @Test
    fun migratesAVersion1DatabaseAndKeepsItsQueuedWork() {
        helper.createDatabase(DATABASE_NAME, 1).apply {
            // A queued operation, as a version 1 install would hold one.
            insertQueuedOperation(this)
            close()
        }

        val migrated = helper.runMigrationsAndValidate(
            DATABASE_NAME,
            2,
            true,
            OfflineMigrations.MIGRATION_1_2,
        )

        // The work the technician had not yet had applied is still queued (`BR-014`).
        migrated.query("select count(*) from outbox_operations").use { cursor ->
            cursor.moveToFirst()
            assertEquals(1, cursor.getInt(0))
        }
        // The new table exists and starts empty.
        migrated.query("select count(*) from pending_job_photos").use { cursor ->
            cursor.moveToFirst()
            assertEquals(0, cursor.getInt(0))
        }
        migrated.close()
    }

    @Test
    fun addsThePendingPhotoTableWithItsColumns() {
        helper.createDatabase(DATABASE_NAME, 1).close()

        val migrated = helper.runMigrationsAndValidate(
            DATABASE_NAME,
            2,
            true,
            OfflineMigrations.MIGRATION_1_2,
        )

        migrated.execSQL(
            "insert into pending_job_photos (photoId, subjectId, jobId, localPath, phase, note, " +
                "capturedAt, mimeType, recordedAt, submitted) values " +
                "('photo-1', 'user-1', 'job-1', '/tmp/photo-1.jpg', 'DURING_WORK', null, " +
                "'2026-09-15T13:04:05Z', 'image/jpeg', 1000, 0)",
        )
        migrated.query(
            "select phase, submitted from pending_job_photos where photoId = 'photo-1'",
        ).use { cursor ->
            cursor.moveToFirst()
            assertEquals("DURING_WORK", cursor.getString(0))
            assertEquals(0, cursor.getInt(1))
        }
        migrated.close()
    }

    private fun insertQueuedOperation(db: SupportSQLiteDatabase) {
        db.execSQL(
            "insert into outbox_operations (operationId, operationType, targetId, subjectId, " +
                "payload, capturedAt, recordedAt, expectedVersion, attemptCount, lastAttemptAt, " +
                "lastFailure, state) values ('operation-1', 'property.archive', 'property-1', " +
                "'user-1', '{}', '2026-09-15T13:04:05Z', 1000, 1, 0, null, null, 'PENDING')",
        )
    }

    private companion object {
        const val DATABASE_NAME = "offline-migration-test.db"
    }
}
