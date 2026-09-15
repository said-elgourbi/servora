package com.servora.android.data.offline

import androidx.room.Entity

/**
 * Storage form of a [WorkingSetEntry].
 *
 * One row is one projection the backend last reported for one entity, so a read can be answered
 * without connectivity and a successful read replaces the row rather than merging into it (§2, §10).
 */
@Entity(
    tableName = "working_set_entries",
    primaryKeys = ["subjectId", "entityType", "entityId"],
)
internal data class WorkingSetEntryEntity(
    val subjectId: String,
    val entityType: String,
    val entityId: String,
    val scopeId: String?,
    val version: Int?,
    val payload: String,
    val reportedAt: Long,
)
