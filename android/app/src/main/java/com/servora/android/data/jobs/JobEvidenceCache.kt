package com.servora.android.data.jobs

import com.servora.android.data.offline.WorkingSetEntityTypes
import com.servora.android.data.offline.WorkingSetEntry
import com.servora.android.data.offline.WorkingSetStore
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * The Job read's use of the working set (`docs/architecture/offline-first-architecture.md` §2, §12).
 *
 * Two answers are kept, exactly as the screen reads them: the Job itself and its chronological
 * activity. Both are stored as the backend's own **wire response**, so a read served offline runs the
 * same mapper the online read runs and the local copy cannot describe the Job differently (`BR-041`).
 *
 * This is what the decision `D5` requires of the metadata: a technician without connectivity still
 * sees which photos the Job holds, with each one's phase, note and time, and the Activity around them
 * (`BR-013`, `BR-080`). The **bytes** are a separate question and are never stored here — they are the
 * image stack's evictable cache (`§9`, `JobPhotoImages`).
 */
@Singleton
class JobEvidenceCache @Inject constructor(
    private val workingSet: WorkingSetStore,
    private val json: Json,
    private val clock: Clock,
) {

    /**
     * Records the Job the backend just reported, replacing whatever was held before (§2).
     *
     * Internal because the payload is the read's own wire shape: only the repository that performs the
     * read writes it.
     */
    internal suspend fun rememberJob(subjectId: String, jobId: String, job: JobDetailsDto) {
        workingSet.put(
            WorkingSetEntry(
                subjectId = subjectId,
                entityType = WorkingSetEntityTypes.JOB_DETAILS,
                entityId = jobId,
                scopeId = null,
                version = job.version,
                payload = json.encodeToString(JobDetailsDto.serializer(), job),
                reportedAt = clock.millis(),
            ),
        )
    }

    /**
     * The last Job the backend reported, or `null` when none is held.
     *
     * An answer this build cannot map is treated as no answer: the read then reports the honest failure
     * it would report without a local copy (`BR-042`).
     */
    suspend fun reportedJob(subjectId: String, jobId: String): JobDetailsDto? =
        reported(subjectId, WorkingSetEntityTypes.JOB_DETAILS, jobId) { payload ->
            json.decodeFromString(JobDetailsDto.serializer(), payload)
        }

    /** Records the activity the backend just reported, replacing whatever was held before (§2). */
    internal suspend fun rememberActivity(
        subjectId: String,
        jobId: String,
        activity: JobActivityDto,
    ) {
        workingSet.put(
            WorkingSetEntry(
                subjectId = subjectId,
                entityType = WorkingSetEntityTypes.JOB_ACTIVITY,
                entityId = jobId,
                scopeId = null,
                version = null,
                payload = json.encodeToString(JobActivityDto.serializer(), activity),
                reportedAt = clock.millis(),
            ),
        )
    }

    /** The last activity the backend reported, or `null` when none is held (`BR-080`). */
    suspend fun reportedActivity(subjectId: String, jobId: String): JobActivityDto? =
        reported(subjectId, WorkingSetEntityTypes.JOB_ACTIVITY, jobId) { payload ->
            json.decodeFromString(JobActivityDto.serializer(), payload)
        }

    /** The row for one projection, or `null` when nothing is held or it cannot be mapped. */
    private suspend fun <T> reported(
        subjectId: String,
        entityType: String,
        entityId: String,
        decode: (String) -> T,
    ): T? {
        val entry = workingSet.get(
            subjectId = subjectId,
            entityType = entityType,
            entityId = entityId,
        ) ?: return null
        return try {
            decode(entry.payload)
        } catch (failure: SerializationException) {
            null
        }
    }
}
