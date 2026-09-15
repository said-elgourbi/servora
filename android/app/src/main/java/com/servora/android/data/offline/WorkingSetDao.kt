package com.servora.android.data.offline

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * Reads and writes the working-set rows.
 *
 * A write always replaces the row: the working set holds the last state the backend reported, so
 * there is nothing to merge (`docs/architecture/offline-first-architecture.md` §2, §10).
 */
@Dao
internal interface WorkingSetDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(row: WorkingSetEntryEntity)

    @Query(
        "select * from working_set_entries where subjectId = :subjectId " +
            "and entityType = :entityType and entityId = :entityId",
    )
    suspend fun get(
        subjectId: String,
        entityType: String,
        entityId: String,
    ): WorkingSetEntryEntity?

    @Query(
        "delete from working_set_entries where subjectId = :subjectId " +
            "and entityType = :entityType and entityId = :entityId",
    )
    suspend fun evict(subjectId: String, entityType: String, entityId: String)

    @Query("delete from working_set_entries where subjectId = :subjectId")
    suspend fun clearSubject(subjectId: String)
}
