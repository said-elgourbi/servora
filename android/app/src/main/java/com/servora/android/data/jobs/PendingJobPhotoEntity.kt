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
 * Storage form of one pending photo.
 *
 * It holds the **path** of the captured bytes, never the bytes themselves: the image is a file in
 * app-private storage and this row is what makes it findable (`offline-first-architecture.md` §9).
 * The row is what survives process death, so a photo captured in the field is not lost because the
 * upload had not happened yet (`BR-014`).
 *
 * The column names are this store's own; the domain type is `PendingJobPhoto`, and the mapping lives
 * in `PendingJobPhotoStore` so no Room type leaks upward (`dev.md` §1, §6).
 *
 * A phase whose stored value this build cannot read is kept as it is stored, and the mapping exposes
 * it as "not recorded" rather than guessing a phase or dropping the photo (`BR-042`, `BR-014`).
 */
@Entity(
    tableName = "pending_job_photos",
    indices = [
        // The tray reads one Job's pending photos for one signed-in subject.
        Index(value = ["subjectId", "jobId", "recordedAt"]),
        // The upload handler marks a batch of photos as queued.
        Index(value = ["submitted"]),
    ],
)
internal data class PendingJobPhotoEntity(
    /** The photo's id, which is also the idempotency key of its upload (`BR-031`). */
    @PrimaryKey val photoId: String,
    val subjectId: String,
    val jobId: String,
    val localPath: String,
    val phase: String?,
    val note: String?,
    val capturedAt: String,
    val mimeType: String,
    val recordedAt: Long,
    val submitted: Boolean,
)

/** Reads and writes the pending photos. */
@Dao
internal interface PendingJobPhotoDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(row: PendingJobPhotoEntity)

    /** The pending photos of one Job, oldest first, as the tray shows them. */
    @Query(
        "select * from pending_job_photos where subjectId = :subjectId and jobId = :jobId " +
            "order by recordedAt asc",
    )
    fun pending(subjectId: String, jobId: String): Flow<List<PendingJobPhotoEntity>>

    @Query("select * from pending_job_photos where photoId = :photoId")
    suspend fun find(photoId: String): PendingJobPhotoEntity?

    /** Records what the technician chose while reviewing a photo that has not been submitted. */
    @Query(
        "update pending_job_photos set phase = :phase, note = :note " +
            "where photoId = :photoId and submitted = 0",
    )
    suspend fun updateReview(photoId: String, phase: String?, note: String?)

    /** Marks the photos whose upload is now queued, so the tray stops offering to remove them. */
    @Query("update pending_job_photos set submitted = 1 where photoId in (:photoIds)")
    suspend fun markSubmitted(photoIds: List<String>)

    @Query("delete from pending_job_photos where photoId = :photoId")
    suspend fun delete(photoId: String)
}
