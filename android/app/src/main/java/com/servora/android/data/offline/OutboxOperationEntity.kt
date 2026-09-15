package com.servora.android.data.offline

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Storage form of an [OutboxOperation].
 *
 * The column names are this store's own; the domain type is what the rest of the app uses, and the
 * mapping lives in [RoomOutboxStore] so no Room type leaks upward (`dev.md` §1, §6).
 */
@Entity(
    tableName = "outbox_operations",
    indices = [
        // Replay reads the earliest row waiting to be applied, scoped to the subject (§4).
        Index(value = ["subjectId", "state", "recordedAt"]),
        // The pending overlay asks for the queued operations of one entity (§7).
        Index(value = ["subjectId", "targetId"]),
    ],
)
internal data class OutboxOperationEntity(
    @PrimaryKey val operationId: String,
    val operationType: String,
    val targetId: String,
    val subjectId: String,
    val payload: String,
    val capturedAt: String?,
    val recordedAt: Long,
    val expectedVersion: Int?,
    val attemptCount: Int,
    val lastAttemptAt: Long?,
    val lastFailure: String?,
    val state: String,
)
