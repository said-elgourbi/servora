package com.servora.android.data.offline

/** [WorkingSetStore] in memory, for tests that are about behaviour rather than about SQLite. */
class InMemoryWorkingSetStore : WorkingSetStore {

    private val rows = mutableMapOf<Triple<String, String, String>, WorkingSetEntry>()

    /** Every row still stored. */
    val stored: List<WorkingSetEntry>
        get() = rows.values.toList()

    override suspend fun put(entry: WorkingSetEntry) {
        rows[key(entry.subjectId, entry.entityType, entry.entityId)] = entry
    }

    override suspend fun get(
        subjectId: String,
        entityType: String,
        entityId: String,
    ): WorkingSetEntry? = rows[key(subjectId, entityType, entityId)]

    override suspend fun evict(
        subjectId: String,
        entityType: String,
        entityId: String,
    ) {
        rows.remove(key(subjectId, entityType, entityId))
    }

    override suspend fun evictType(subjectId: String, entityType: String) {
        rows.keys.removeAll { it.first == subjectId && it.second == entityType }
    }

    override suspend fun clear(subjectId: String) {
        rows.keys.removeAll { it.first == subjectId }
    }

    private fun key(subjectId: String, entityType: String, entityId: String) =
        Triple(subjectId, entityType, entityId)
}
