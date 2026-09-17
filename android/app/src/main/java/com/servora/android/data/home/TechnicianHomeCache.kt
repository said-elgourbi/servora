package com.servora.android.data.home

import com.servora.android.data.offline.WorkingSetEntityTypes
import com.servora.android.data.offline.WorkingSetEntry
import com.servora.android.data.offline.WorkingSetStore
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * The technician home read's use of the working set
 * (`docs/architecture/offline-first-architecture.md` §2, §12).
 *
 * `BR-013` names "viewing assigned work" as field work that has to survive a loss of connectivity, so
 * the answer the backend last reported is kept and served when the API cannot be reached. The payload
 * is the read's own **wire response**, so a read served offline runs the same mapper the online read
 * runs and the local copy cannot describe the day differently (`BR-041`).
 *
 * The row is keyed by the authenticated subject (`§10`), so one technician's day is never served to
 * whoever signs in next (`BR-001`).
 */
@Singleton
class TechnicianHomeCache @Inject constructor(
    private val workingSet: WorkingSetStore,
    private val json: Json,
    private val clock: Clock,
) {

    /**
     * Records the day the backend just reported, replacing the earlier one (§2).
     *
     * Internal because the payload is the read's own wire shape: only the repository that performs
     * the read writes it.
     */
    internal suspend fun remember(subjectId: String, home: TechnicianHomeDto) {
        workingSet.put(
            WorkingSetEntry(
                subjectId = subjectId,
                entityType = WorkingSetEntityTypes.TECHNICIAN_HOME,
                entityId = TECHNICIAN_HOME_ROW,
                scopeId = null,
                version = null,
                payload = json.encodeToString(TechnicianHomeDto.serializer(), home),
                reportedAt = clock.millis(),
            ),
        )
    }

    /**
     * The last day the backend reported, or `null` when none is held.
     *
     * An answer this build cannot map is treated as no answer: the read then reports the honest
     * failure it would report without a local copy (`BR-042`).
     */
    internal suspend fun reported(subjectId: String): TechnicianHomeDto? {
        val entry = workingSet.get(
            subjectId = subjectId,
            entityType = WorkingSetEntityTypes.TECHNICIAN_HOME,
            entityId = TECHNICIAN_HOME_ROW,
        ) ?: return null
        return try {
            json.decodeFromString(TechnicianHomeDto.serializer(), entry.payload)
        } catch (failure: SerializationException) {
            null
        }
    }
}

/**
 * The row's local identifier.
 *
 * One row is held **per subject**: the day is the caller's own work, so the answer is not scoped by
 * anything else the way the customer list is scoped by its filter (`§10`).
 */
private const val TECHNICIAN_HOME_ROW = "day"
