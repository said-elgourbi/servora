package com.servora.android.data.offline

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.servora.android.data.jobs.JOB_AUDIO_MIME_TYPE
import com.servora.android.data.jobs.JobAudioOperations
import com.servora.android.data.jobs.JobAudioPayloads
import com.servora.android.data.jobs.JobPhotoOperations
import com.servora.android.data.jobs.JobPhotoPayloads
import com.servora.android.data.jobs.RoomPendingJobAudioNoteStore
import com.servora.android.data.jobs.RoomPendingJobPhotoStore
import com.servora.android.data.session.AuthenticatedSubject
import com.servora.android.domain.model.EvidencePhase
import com.servora.android.domain.model.PendingJobAudioNote
import com.servora.android.domain.model.PendingJobPhoto
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The local store on a real device.
 *
 * Room's queries and constraints are only exercised against SQLite, which is why this cannot be a JVM
 * unit test (`qa.md` §9). It covers what the engine relies on: the queue is read oldest first, a
 * refusal is terminal, an interrupted replay is returned to waiting, and the working set holds one
 * reported answer per subject and entity.
 */
@RunWith(AndroidJUnit4::class)
class OfflineDatabaseTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var database: OfflineDatabase
    private lateinit var outbox: RoomOutboxStore
    private lateinit var workingSet: RoomWorkingSetStore

    @Before
    fun openDatabase() {
        database = Room.inMemoryDatabaseBuilder(context, OfflineDatabase::class.java).build()
        outbox = RoomOutboxStore(database.outboxDao())
        workingSet = RoomWorkingSetStore(database.workingSetDao())
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun readsTheQueueOldestFirst() = runTest {
        outbox.record(operation(id = "second", recordedAt = 2_000L))
        outbox.record(operation(id = "first", recordedAt = 1_000L))

        assertEquals("first", outbox.head(SUBJECT)?.operationId)
    }

    @Test
    fun returnsNothingWhenTheQueueIsEmpty() = runTest {
        assertNull(outbox.head(SUBJECT))
    }

    @Test
    fun doesNotOfferAnOperationTheBackendRefused() = runTest {
        outbox.record(operation(id = "refused", recordedAt = 1_000L))
        outbox.markRejected("refused", OutboxFailureReason.NOT_AUTHORIZED, at = 2_000L)

        assertNull(outbox.head(SUBJECT))
        assertEquals(0, outbox.awaitingCount(SUBJECT))
        assertEquals(listOf("refused"), outbox.rejected(SUBJECT).map { it.operationId })
    }

    @Test
    fun keepsAnOperationThatFailedRetryablyAndRecordsTheAttempt() = runTest {
        outbox.record(operation(id = "flaky", recordedAt = 1_000L))
        outbox.markRetryable("flaky", OutboxFailureReason.NETWORK, at = 2_000L)

        val queued = outbox.head(SUBJECT)
        assertNotNull(queued)
        assertEquals(OutboxOperationState.FAILED, queued?.state)
        assertEquals(1, queued?.attemptCount)
        assertEquals(2_000L, queued?.lastAttemptAt)
        assertEquals(OutboxFailureReason.NETWORK, queued?.lastFailure)
        assertEquals(1, outbox.awaitingCount(SUBJECT))
    }

    @Test
    fun returnsAnInterruptedReplayToWaiting() = runTest {
        outbox.record(operation(id = "interrupted", recordedAt = 1_000L))
        outbox.markInFlight("interrupted")

        // An operation being applied is not offered for a second replay at the same time.
        assertNull(outbox.head(SUBJECT))

        outbox.recoverInFlight()

        val recovered = outbox.head(SUBJECT)
        assertEquals("interrupted", recovered?.operationId)
        assertEquals(OutboxOperationState.PENDING, recovered?.state)
    }

    @Test
    fun removesAnOperationTheBackendAccepted() = runTest {
        outbox.record(operation(id = "accepted", recordedAt = 1_000L))
        outbox.markApplied("accepted")

        assertNull(outbox.head(SUBJECT))
        assertEquals(0, outbox.awaitingCount(SUBJECT))
    }

    @Test
    fun keepsSubjectsApart() = runTest {
        outbox.record(operation(id = "mine", recordedAt = 1_000L))
        outbox.record(operation(id = "theirs", recordedAt = 1_000L, subject = "user-2"))

        assertEquals(listOf("mine"), queueIds(SUBJECT))
        assertEquals(listOf("theirs"), queueIds("user-2"))
    }

    @Test
    fun reportsTheOperationsQueuedForOneEntity() = runTest {
        outbox.record(operation(id = "archive", recordedAt = 1_000L))
        outbox.record(operation(id = "restore", recordedAt = 2_000L))
        outbox.record(
            operation(id = "elsewhere", recordedAt = 3_000L).copy(targetId = "property-2"),
        )

        assertEquals(
            listOf("archive", "restore"),
            outbox.queuedFor(SUBJECT, "property-1").map { it.operationId },
        )
    }

    @Test
    fun storesOneReportedAnswerPerEntity() = runTest {
        workingSet.put(entry(payload = "{\"version\":1}"))
        workingSet.put(entry(payload = "{\"version\":2}"))

        assertEquals("{\"version\":2}", workingSet.get(SUBJECT, TYPE, "property-1")?.payload)
    }

    @Test
    fun evictsOnlyTheEntryItIsAsked() = runTest {
        workingSet.put(entry(entityId = "property-1"))
        workingSet.put(entry(entityId = "property-2"))

        workingSet.evict(SUBJECT, TYPE, "property-1")

        assertNull(workingSet.get(SUBJECT, TYPE, "property-1"))
        assertNotNull(workingSet.get(SUBJECT, TYPE, "property-2"))
    }

    @Test
    fun keepsOneReportedAnswerPerFilterOfTheSameProjection() = runTest {
        // The customer list is the projection that holds more than one row per entity type: the
        // backend's answer depends on the filter, so the filter is part of the row's key and a row is
        // only ever served for the filter it was read under.
        workingSet.put(listEntry(filterKey = "ACTIVE:ALL", payload = "{\"count\":1}"))
        workingSet.put(listEntry(filterKey = "ALL:ALL", payload = "{\"count\":9}"))

        assertEquals(
            "{\"count\":1}",
            workingSet.get(SUBJECT, WorkingSetEntityTypes.CUSTOMER_LIST, "ACTIVE:ALL")?.payload,
        )
        assertEquals(
            "{\"count\":9}",
            workingSet.get(SUBJECT, WorkingSetEntityTypes.CUSTOMER_LIST, "ALL:ALL")?.payload,
        )
        assertNull(workingSet.get(SUBJECT, WorkingSetEntityTypes.CUSTOMER_LIST, "INACTIVE:ALL"))
    }

    @Test
    fun clearsEveryEntryOfOneSubjectOnly() = runTest {
        workingSet.put(entry(entityId = "property-1"))
        workingSet.put(entry(entityId = "property-2"))
        workingSet.put(entry(entityId = "property-1", subject = "user-2"))

        workingSet.clear(SUBJECT)

        assertNull(workingSet.get(SUBJECT, TYPE, "property-1"))
        assertNull(workingSet.get(SUBJECT, TYPE, "property-2"))
        assertNotNull(workingSet.get("user-2", TYPE, "property-1"))
    }

    /**
     * The pending photo records on SQLite (`BR-015`, `offline-first-architecture.md` §9).
     *
     * What the feature depends on is that a captured photo is durable, scoped to the subject and the
     * Job, editable only while it is unsaved, and removed only by an explicit decision — including the
     * queueing of its upload, which is the same write the technician's save performs (`§5`).
     */
    @Test
    fun keepsPendingPhotosPerSubjectAndJobAndQueuesTheUploadOnce() = runTest {
        val photos = pendingPhotos()
        photos.record(photo("photo-1", jobId = "job-1"))
        photos.record(photo("photo-2", jobId = "job-2"))

        assertEquals(
            listOf("photo-1"),
            photos.pending(SUBJECT, "job-1").first().map { it.photoId },
        )
        assertEquals(
            listOf("photo-2"),
            photos.pending(SUBJECT, "job-2").first().map { it.photoId },
        )
        // Another subject's pending evidence is never visible to this one (`§10`).
        assertEquals(emptyList<String>(), photos.pending("user-2", "job-1").first().map { it.photoId })
    }

    @Test
    fun recordsTheChoiceMadeWhileReviewingAndTheQueueingWhenItIsSaved() = runTest {
        val photos = pendingPhotos()
        photos.record(photo("photo-1"))

        photos.updateReview("photo-1", EvidencePhase.AFTER_WORK, "Panel closed")
        assertEquals("Panel closed", photos.find("photo-1")?.note)
        assertEquals(EvidencePhase.AFTER_WORK, photos.find("photo-1")?.phase)

        assertEquals(true, photos.submit(photo("photo-1")))
        assertEquals(true, photos.find("photo-1")?.submitted)
        // The upload is queued with the photo's own id as its idempotency key (`BR-031`).
        assertEquals("photo-1", outbox.head(SUBJECT)?.operationId)
        assertEquals(JobPhotoOperations.ADD_PHOTO, outbox.head(SUBJECT)?.operationType)
    }

    @Test
    fun refusesToReviewAPhotoWhoseUploadIsAlreadyQueued() = runTest {
        val photos = pendingPhotos()
        photos.record(photo("photo-1"))
        photos.submit(photo("photo-1"))

        photos.updateReview("photo-1", EvidencePhase.BEFORE_WORK, "too late")

        assertEquals(EvidencePhase.DURING_WORK, photos.find("photo-1")?.phase)
        assertNull(photos.find("photo-1")?.note)
    }

    /**
     * The pending audio notes on a real device (`BR-091`, `ADR-018`).
     *
     * Audio is evidence of its own kind, so its rows have their own table, their own subject scoping and
     * their own queueing of the upload — the same rules the photo records follow, asserted against the
     * same SQLite the device runs (`qa.md` §9).
     */
    @Test
    fun keepsPendingAudioNotesPerSubjectAndJobAndQueuesTheUploadOnce() = runTest {
        val audio = pendingAudioNotes()
        audio.record(audioNote("audio-1", jobId = "job-1"))
        audio.record(audioNote("audio-2", jobId = "job-2"))

        assertEquals(
            listOf("audio-1"),
            audio.pending(SUBJECT, "job-1").first().map { it.audioNoteId },
        )
        assertEquals(
            listOf("audio-2"),
            audio.pending(SUBJECT, "job-2").first().map { it.audioNoteId },
        )
        // Another subject's pending evidence is never visible to this one (`§10`).
        assertEquals(
            emptyList<String>(),
            audio.pending("user-2", "job-1").first().map { it.audioNoteId },
        )
    }

    @Test
    fun recordsAnAudioReviewAndTheQueueingWhenItIsAttached() = runTest {
        val audio = pendingAudioNotes()
        audio.record(audioNote("audio-1"))

        audio.updateReview("audio-1", EvidencePhase.AFTER_WORK, "Compressor is noisy")
        assertEquals("Compressor is noisy", audio.find("audio-1")?.note)
        assertEquals(EvidencePhase.AFTER_WORK, audio.find("audio-1")?.phase)

        assertEquals(true, audio.submit(requireNotNull(audio.find("audio-1"))))
        assertEquals(true, audio.find("audio-1")?.submitted)
        // The upload is queued with the recording's own id as its idempotency key (`BR-031`).
        assertEquals("audio-1", outbox.head(SUBJECT)?.operationId)
        assertEquals(JobAudioOperations.ADD_AUDIO, outbox.head(SUBJECT)?.operationType)
    }

    @Test
    fun refusesToReviewAnAudioNoteWhoseUploadIsAlreadyQueued() = runTest {
        val audio = pendingAudioNotes()
        audio.record(audioNote("audio-1"))
        audio.submit(audioNote("audio-1"))

        audio.updateReview("audio-1", EvidencePhase.BEFORE_WORK, "too late")

        assertEquals(EvidencePhase.DURING_WORK, audio.find("audio-1")?.phase)
        assertNull(audio.find("audio-1")?.note)
    }

    /** The audio records, over the same database the photo ones are covered on. */
    private fun pendingAudioNotes(): RoomPendingJobAudioNoteStore = RoomPendingJobAudioNoteStore(
        dao = database.pendingJobAudioNoteDao(),
        outbox = outbox,
        payloads = JobAudioPayloads(Json),
        subject = object : AuthenticatedSubject {
            override fun current(): String? = SUBJECT
        },
        clock = Clock.fixed(Instant.parse("2026-09-15T13:05:00Z"), ZoneOffset.UTC),
    )

    private fun audioNote(
        audioNoteId: String,
        jobId: String = "job-1",
        phase: EvidencePhase? = EvidencePhase.DURING_WORK,
    ): PendingJobAudioNote = PendingJobAudioNote(
        audioNoteId = audioNoteId,
        jobId = jobId,
        localPath = "app-private/job-audio/$SUBJECT/$audioNoteId.m4a",
        phase = phase,
        note = null,
        capturedAt = "2026-09-15T13:04:05Z",
        durationSeconds = 18,
        mimeType = JOB_AUDIO_MIME_TYPE,
        recordedAt = 1_000L,
        submitted = false,
    )

    private fun pendingPhotos(): RoomPendingJobPhotoStore = RoomPendingJobPhotoStore(
        dao = database.pendingJobPhotoDao(),
        outbox = outbox,
        payloads = JobPhotoPayloads(Json),
        subject = object : AuthenticatedSubject {
            override fun current(): String? = SUBJECT
        },
        clock = Clock.fixed(Instant.parse("2026-09-15T13:05:00Z"), ZoneOffset.UTC),
    )

    private fun photo(
        photoId: String,
        jobId: String = "job-1",
        phase: EvidencePhase? = EvidencePhase.DURING_WORK,
    ): PendingJobPhoto = PendingJobPhoto(
        photoId = photoId,
        jobId = jobId,
        localPath = "app-private/job-photos/$SUBJECT/$photoId.jpg",
        phase = phase,
        note = null,
        capturedAt = "2026-09-15T13:04:05Z",
        mimeType = "image/jpeg",
        recordedAt = 1_000L,
        submitted = false,
    )

    /** The ids one subject's queue holds, oldest first; the queue is consumed as it is read. */
    private suspend fun queueIds(subjectId: String): List<String> {
        val ids = mutableListOf<String>()
        while (true) {
            val head = outbox.head(subjectId) ?: break
            ids += head.operationId
            outbox.markApplied(head.operationId)
        }
        return ids
    }

    private fun operation(
        id: String,
        recordedAt: Long,
        subject: String = SUBJECT,
    ): OutboxOperation = OutboxOperation(
        operationId = id,
        operationType = "property.archive",
        targetId = "property-1",
        subjectId = subject,
        payload = "{\"customerId\":\"customer-1\"}",
        capturedAt = "2026-09-14T12:00:00Z",
        recordedAt = recordedAt,
        expectedVersion = 1,
        attemptCount = 0,
        lastAttemptAt = null,
        lastFailure = null,
        state = OutboxOperationState.PENDING,
    )

    private fun entry(
        entityId: String = "property-1",
        payload: String = "{}",
        subject: String = SUBJECT,
    ): WorkingSetEntry = WorkingSetEntry(
        subjectId = subject,
        entityType = TYPE,
        entityId = entityId,
        scopeId = "customer-1",
        version = 1,
        payload = payload,
        reportedAt = 1_000L,
    )

    /** A working-set row of the customer-list projection, keyed by the filter it was read under. */
    private fun listEntry(filterKey: String, payload: String): WorkingSetEntry = WorkingSetEntry(
        subjectId = SUBJECT,
        entityType = WorkingSetEntityTypes.CUSTOMER_LIST,
        entityId = filterKey,
        scopeId = null,
        version = null,
        payload = payload,
        reportedAt = 1_000L,
    )

    private companion object {
        const val SUBJECT = "user-1"
        const val TYPE = WorkingSetEntityTypes.PROPERTY_DETAIL
    }
}
