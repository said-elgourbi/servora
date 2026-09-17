package com.servora.android.data.jobs

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Storage form of one pending audio note.
 *
 * It holds the **path** of the recording, never the bytes themselves: the audio is a file in
 * app-private storage and this row is what makes it findable (`offline-first-architecture.md` §9).
 * The row is what survives process death, so a recording made in the field is not lost because the
 * upload had not happened yet (`BR-014`).
 *
 * The column names are this store's own; the domain type is `PendingJobAudioNote`, and the mapping
 * lives in `PendingJobAudioNoteStore` so no Room type leaks upward (`dev.md` §1, §6).
 *
 * A phase whose stored value this build cannot read is kept as it is stored, and the mapping exposes
 * it as "not recorded" rather than guessing a phase or dropping the recording (`BR-042`, `BR-014`).
 */
@Entity(
    tableName = "pending_job_audio_notes",
    indices = [
        // The notice reads one Job's pending recordings for one signed-in subject.
        Index(value = ["subjectId", "jobId", "recordedAt"]),
        // The upload handler marks a recording as queued.
        Index(value = ["submitted"]),
    ],
)
internal data class PendingJobAudioNoteEntity(
    /** The recording's id, which is also the idempotency key of its upload (`BR-031`). */
    @PrimaryKey val audioNoteId: String,
    val subjectId: String,
    val jobId: String,
    val localPath: String,
    val phase: String?,
    val note: String?,
    val capturedAt: String,
    val durationSeconds: Int,
    val mimeType: String,
    val recordedAt: Long,
    val submitted: Boolean,
)

/** Reads and writes the pending audio notes. */
@Dao
internal interface PendingJobAudioNoteDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(row: PendingJobAudioNoteEntity)

    /** The pending recordings of one Job, oldest first, as the notice shows them. */
    @Query(
        "select * from pending_job_audio_notes where subjectId = :subjectId and jobId = :jobId " +
            "order by recordedAt asc",
    )
    fun pending(subjectId: String, jobId: String): Flow<List<PendingJobAudioNoteEntity>>

    @Query("select * from pending_job_audio_notes where audioNoteId = :audioNoteId")
    suspend fun find(audioNoteId: String): PendingJobAudioNoteEntity?

    /** Records what the technician chose while reviewing a recording that has not been submitted. */
    @Query(
        "update pending_job_audio_notes set phase = :phase, note = :note " +
            "where audioNoteId = :audioNoteId and submitted = 0",
    )
    suspend fun updateReview(audioNoteId: String, phase: String?, note: String?)

    /** Marks the recording whose upload is now queued, so the notice stops offering to remove it. */
    @Query("update pending_job_audio_notes set submitted = 1 where audioNoteId = :audioNoteId")
    suspend fun markSubmitted(audioNoteId: String)

    @Query("delete from pending_job_audio_notes where audioNoteId = :audioNoteId")
    suspend fun delete(audioNoteId: String)
}
