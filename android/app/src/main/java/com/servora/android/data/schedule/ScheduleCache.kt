package com.servora.android.data.schedule

import com.servora.android.data.offline.WorkingSetEntityTypes
import com.servora.android.data.offline.WorkingSetEntry
import com.servora.android.data.offline.WorkingSetStore
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * The schedule read's use of the working set
 * (`docs/architecture/offline-first-architecture.md` §2, §12, §13).
 *
 * `BR-013` names "viewing assigned work" as field work that has to survive a loss of connectivity, so
 * the day the backend last reported for the caller's **own** work is kept and served when the API
 * cannot be reached. The payload is the read's own **wire response**, so a read served offline runs
 * the same mapper the online read runs and the local copy cannot describe the day differently
 * (`BR-041`).
 *
 * The row is keyed by the authenticated subject **and the local date** (§10, §13.4): one
 * technician's day is never served to whoever signs in next, and a day read for another date is
 * never served for this one. An **organization-scoped** answer is deliberately not kept: the office
 * board is a dispatcher's read and stays online-only (`ADR-020` D6).
 */
@Singleton
class ScheduleCache @Inject constructor(
    private val workingSet: WorkingSetStore,
    private val json: Json,
    private val clock: Clock,
) {

    /**
     * Records the day the backend just reported for [localDate], replacing the earlier one (§2).
     *
     * Internal because the payload is the read's own wire shape: only the repository that performs
     * the read writes it.
     */
    internal suspend fun rememberDay(
        subjectId: String,
        localDate: String,
        schedule: ScheduleDto,
    ) {
        workingSet.put(
            WorkingSetEntry(
                subjectId = subjectId,
                entityType = WorkingSetEntityTypes.TECHNICIAN_SCHEDULE,
                entityId = localDate,
                scopeId = null,
                version = null,
                payload = json.encodeToString(ScheduleDto.serializer(), schedule),
                reportedAt = clock.millis(),
            ),
        )
    }

    /**
     * The last day the backend reported for [localDate], or `null` when none is held.
     *
     * An answer this build cannot map is treated as no answer: the read then reports the honest
     * failure it would report without a local copy (`BR-042`).
     */
    internal suspend fun reportedDay(
        subjectId: String,
        localDate: String,
    ): ScheduleDto? {
        val entry = workingSet.get(
            subjectId = subjectId,
            entityType = WorkingSetEntityTypes.TECHNICIAN_SCHEDULE,
            entityId = localDate,
        ) ?: return null
        return try {
            json.decodeFromString(ScheduleDto.serializer(), entry.payload)
        } catch (failure: SerializationException) {
            null
        }
    }
}
