package com.servora.android.data.offline

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Durable storage for the last state the backend reported for a projection
 * (`docs/architecture/offline-first-architecture.md` §2).
 *
 * Rows are read and written by the feature that owns the projection: the payload is that feature's
 * wire contract, so this store never interprets it (`Project.md` §12). It holds no authority —
 * a successful read always replaces a row, and a row is only ever written from an API answer.
 */
interface WorkingSetStore {
    /** Stores the answer the backend just reported, replacing any earlier one. */
    suspend fun put(entry: WorkingSetEntry)

    /** The reported answer for one entity, or `null` when nothing has been read yet. */
    suspend fun get(
        subjectId: String,
        entityType: String,
        entityId: String,
    ): WorkingSetEntry?

    /** Drops one row, used when a dependency it described has changed. */
    suspend fun evict(
        subjectId: String,
        entityType: String,
        entityId: String,
    )

    /** Drops every row of one projection type for a subject. */
    suspend fun evictType(subjectId: String, entityType: String)

    /**
     * Drops every row of a subject.
     *
     * Called when a session ends: the working set belongs to the session that read it rather than
     * staying readable to whoever signs in next (§10, `BR-001`).
     */
    suspend fun clear(subjectId: String)
}

/** Default [WorkingSetStore], backed by Room. The implementation is module-internal. */
@Singleton
internal class RoomWorkingSetStore @Inject constructor(
    private val dao: WorkingSetDao,
) : WorkingSetStore {

    override suspend fun put(entry: WorkingSetEntry) {
        dao.put(entry.toEntity())
    }

    override suspend fun get(
        subjectId: String,
        entityType: String,
        entityId: String,
    ): WorkingSetEntry? = dao.get(subjectId, entityType, entityId)?.toEntry()

    override suspend fun evict(
        subjectId: String,
        entityType: String,
        entityId: String,
    ) {
        dao.evict(subjectId, entityType, entityId)
    }

    override suspend fun evictType(subjectId: String, entityType: String) {
        dao.evictType(subjectId, entityType)
    }

    override suspend fun clear(subjectId: String) {
        dao.clearSubject(subjectId)
    }
}

/** Maps a stored row onto the domain entry. */
internal fun WorkingSetEntryEntity.toEntry(): WorkingSetEntry =
    WorkingSetEntry(
        subjectId = subjectId,
        entityType = entityType,
        entityId = entityId,
        scopeId = scopeId,
        version = version,
        payload = payload,
        reportedAt = reportedAt,
    )

/** Maps a domain entry onto its stored row. */
internal fun WorkingSetEntry.toEntity(): WorkingSetEntryEntity =
    WorkingSetEntryEntity(
        subjectId = subjectId,
        entityType = entityType,
        entityId = entityId,
        scopeId = scopeId,
        version = version,
        payload = payload,
        reportedAt = reportedAt,
    )
