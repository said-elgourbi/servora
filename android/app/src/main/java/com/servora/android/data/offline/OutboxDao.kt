package com.servora.android.data.offline

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * Reads and writes the outbox rows.
 *
 * The state values are bound as parameters rather than written into the SQL, so the vocabulary has
 * one definition ([OutboxOperationState]) and a query cannot drift from it (`BR-041`).
 */
@Dao
internal interface OutboxDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(row: OutboxOperationEntity)

    /**
     * The earliest row still waiting to be applied, or `null` when nothing is queued (§4).
     *
     * Ordering by `recordedAt` is what keeps a create and a later change to the same entity from
     * being applied out of order: the engine always sees the oldest eligible row first.
     */
    @Query(
        "select * from outbox_operations where subjectId = :subjectId and state in (:states) " +
            "order by recordedAt asc limit 1",
    )
    suspend fun head(subjectId: String, states: List<String>): OutboxOperationEntity?

    @Query("update outbox_operations set state = :state where operationId = :operationId")
    suspend fun setState(operationId: String, state: String)

    @Query("delete from outbox_operations where operationId = :operationId")
    suspend fun delete(operationId: String)

    /** Records a failed attempt: the row is retained, and this is the retry bookkeeping (§6). */
    @Query(
        "update outbox_operations set state = :state, attemptCount = attemptCount + 1, " +
            "lastAttemptAt = :at, lastFailure = :reason where operationId = :operationId",
    )
    suspend fun markAttemptFailed(
        operationId: String,
        state: String,
        at: Long,
        reason: String,
    )

    /**
     * Returns a replay that process death interrupted to [OutboxOperationState.PENDING].
     *
     * Cancellation and process death must not mark an operation as failed (§6): the row was never
     * answered, so it is simply waiting again.
     */
    @Query(
        "update outbox_operations set state = :pending where state = :inFlight",
    )
    suspend fun recoverInFlight(inFlight: String, pending: String)

    /** How many operations are still waiting for the backend, refused ones excluded (§7). */
    @Query(
        "select count(*) from outbox_operations where subjectId = :subjectId and state != :rejected",
    )
    suspend fun awaitingCount(subjectId: String, rejected: String): Int

    /** The operations the backend refused, oldest first, so the user can be shown them (§6). */
    @Query(
        "select * from outbox_operations where subjectId = :subjectId and state = :rejected " +
            "order by recordedAt asc",
    )
    suspend fun rejected(subjectId: String, rejected: String): List<OutboxOperationEntity>

    /** The queued operations of one entity, oldest first, for the pending overlay (§7). */
    @Query(
        "select * from outbox_operations where subjectId = :subjectId and targetId = :targetId " +
            "order by recordedAt asc",
    )
    suspend fun forTarget(subjectId: String, targetId: String): List<OutboxOperationEntity>
}
