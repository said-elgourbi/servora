package com.servora.android.ui.jobs

import com.servora.android.data.customers.CustomersFailureReason
import com.servora.android.data.jobs.AssignableTechniciansResult
import com.servora.android.data.jobs.FakeJobPhotoFiles
import com.servora.android.data.jobs.FakeJobPhotoPickedItems
import com.servora.android.data.jobs.JobActionFailure
import com.servora.android.data.jobs.JobActionResult
import com.servora.android.data.jobs.JobActivityResult
import com.servora.android.data.jobs.JobDetailsRepository
import com.servora.android.data.jobs.JobDetailsResult
import com.servora.android.data.jobs.JobPhotoImages
import com.servora.android.data.jobs.JobPhotoOperations
import com.servora.android.data.jobs.JobPhotoPickedItems
import com.servora.android.data.jobs.JobPhotoSession
import com.servora.android.data.jobs.MAX_JOB_PHOTO_BYTES
import com.servora.android.data.jobs.PhotoCollaborators
import com.servora.android.data.jobs.TEST_CLOCK
import com.servora.android.data.jobs.VisitNoteResult
import com.servora.android.domain.model.AssignableTechnician
import com.servora.android.domain.model.CustomerJobAddress
import com.servora.android.domain.model.JobActivityEvent
import com.servora.android.domain.model.JobDetails
import com.servora.android.domain.model.JobPhotoPhase
import com.servora.android.domain.model.JobStatus
import com.servora.android.domain.model.TechnicianAssignment
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The technician's photo work on the Job Details screen, as the screen drives it
 * (`BR-012`, `BR-015`, `BR-027`, `BR-031`).
 *
 * What is asserted is the whole point of the slice: a photo becomes durable before it is confirmed,
 * the phase the technician chose is remembered for the next photo, a note is optional, an unsaved
 * photo can be removed, and saving queues the upload through the existing outbox rather than claiming
 * the backend accepted anything (`BR-001`, `BR-014`). Both sources are covered — the camera, and the
 * device's own photo picker, whose items are taken one at a time so a photo that cannot be taken never
 * stops the others (`D3`).
 */
class JobPhotoCaptureViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun installMainDispatcher() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun restoreMainDispatcher() {
        Dispatchers.resetMain()
    }

    @Test
    fun `records a captured photo in the tray before it is submitted`() = runTest(dispatcher) {
        val photos = PhotoCollaborators()
        val viewModel = startedViewModel(photos)

        val capture = requireNotNull(viewModel.beginPhotoCapture())
        photos.files.writeCapture(capture.localPath)
        viewModel.photoCaptured(capture)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(capture.photoId, state.capturedPhoto?.photoId)
        assertEquals(listOf(capture.photoId), state.pendingPhotos.map { it.photoId })
        // Nothing is queued yet: the technician has not saved the tray.
        assertTrue(photos.outbox.stored.isEmpty())
        assertEquals(0, photos.sync.requests)
    }

    @Test
    fun `remembers the phase chosen and starts the next capture in it`() = runTest(dispatcher) {
        val photos = PhotoCollaborators()
        val viewModel = startedViewModel(photos)

        // The first capture starts in the working default (`BR-012`).
        assertEquals(JobPhotoPhase.DURING_WORK, viewModel.uiState.value.photoPhase)

        val first = requireNotNull(viewModel.beginPhotoCapture())
        photos.files.writeCapture(first.localPath)
        viewModel.photoCaptured(first)
        advanceUntilIdle()
        viewModel.confirmCapturedPhoto(JobPhotoPhase.AFTER_WORK, null)
        advanceUntilIdle()

        assertEquals(JobPhotoPhase.AFTER_WORK, viewModel.uiState.value.photoPhase)
        assertEquals(JobPhotoPhase.AFTER_WORK, photos.store.stored.first().phase)
        // A note is optional: confirming without one records no note.
        assertNull(photos.store.stored.first().note)

        // The next capture starts where the last one ended.
        val second = requireNotNull(viewModel.beginPhotoCapture())
        photos.files.writeCapture(second.localPath)
        viewModel.photoCaptured(second)
        advanceUntilIdle()

        assertEquals(JobPhotoPhase.AFTER_WORK, photos.store.stored.last().phase)
    }

    @Test
    fun `removes a photo the technician has not submitted`() = runTest(dispatcher) {
        val photos = PhotoCollaborators()
        val viewModel = startedViewModel(photos)

        val capture = requireNotNull(viewModel.beginPhotoCapture())
        photos.files.writeCapture(capture.localPath)
        viewModel.photoCaptured(capture)
        advanceUntilIdle()

        viewModel.removePendingPhoto(capture.photoId)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.pendingPhotos.isEmpty())
        assertTrue(photos.files.storedPaths.isEmpty())
        assertNull(photos.store.find(capture.photoId))
    }

    @Test
    fun `queues the tray through the outbox when the technician saves it`() = runTest(dispatcher) {
        val photos = PhotoCollaborators()
        val viewModel = startedViewModel(photos)

        val capture = requireNotNull(viewModel.beginPhotoCapture())
        photos.files.writeCapture(capture.localPath)
        viewModel.photoCaptured(capture)
        advanceUntilIdle()
        viewModel.confirmCapturedPhoto(JobPhotoPhase.BEFORE_WORK, "Panel before the repair")
        viewModel.submitPendingPhotos()
        advanceUntilIdle()

        val queued = photos.outbox.stored
        assertEquals(1, queued.size)
        assertEquals(JobPhotoOperations.ADD_PHOTO, queued.first().operationType)
        // The photo's own id is the idempotency key every replay reuses (`BR-031`).
        assertEquals(capture.photoId, queued.first().operationId)
        assertEquals(JOB_ID, queued.first().targetId)
        // The bytes stay on the device: the backend has not accepted them yet.
        assertEquals(setOf(capture.localPath), photos.files.storedPaths)
        assertEquals(1, photos.sync.requests)
        assertEquals(JobPhotoMessage.QUEUED, viewModel.uiState.value.photoMessage)
        // A queued photo is no longer the technician's to remove locally (§9).
        assertEquals(false, viewModel.uiState.value.pendingPhotos.first().isRemovable())
    }

    @Test
    fun `reports a photo the camera did not write and records nothing`() = runTest(dispatcher) {
        val photos = PhotoCollaborators()
        val viewModel = startedViewModel(photos)

        val capture = requireNotNull(viewModel.beginPhotoCapture())
        viewModel.photoCaptured(capture)
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.capturedPhoto)
        assertTrue(viewModel.uiState.value.pendingPhotos.isEmpty())
        assertEquals(JobPhotoFailure.CAPTURE_FAILED, viewModel.uiState.value.photoFailure)
    }

    @Test
    fun `reports a capture this device cannot convert and records nothing`() = runTest(dispatcher) {
        val photos = PhotoCollaborators()
        val viewModel = startedViewModel(photos)

        val capture = requireNotNull(viewModel.beginPhotoCapture())
        photos.files.writeCapture(capture.localPath, FakeJobPhotoFiles.UNACCEPTED_BYTES)
        viewModel.photoCaptured(capture)
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.capturedPhoto)
        assertTrue(viewModel.uiState.value.pendingPhotos.isEmpty())
        assertEquals(JobPhotoFailure.PHOTO_TYPE_NOT_ACCEPTED, viewModel.uiState.value.photoFailure)
    }

    @Test
    fun `reports a capture over the upload limit and records nothing`() = runTest(dispatcher) {
        val photos = PhotoCollaborators()
        val viewModel = startedViewModel(photos)

        val capture = requireNotNull(viewModel.beginPhotoCapture())
        photos.files.writeCapture(
            capture.localPath,
            FakeJobPhotoFiles.JPEG_BYTES + ByteArray(MAX_JOB_PHOTO_BYTES),
        )
        viewModel.photoCaptured(capture)
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.capturedPhoto)
        assertTrue(viewModel.uiState.value.pendingPhotos.isEmpty())
        assertEquals(JobPhotoFailure.PHOTO_TOO_LARGE, viewModel.uiState.value.photoFailure)
    }

    @Test
    fun `reports a capture the device cannot store and records nothing`() = runTest(dispatcher) {
        val photos = PhotoCollaborators()
        photos.files.writeSucceeds = false
        // The device cannot store the converted bytes: a capture that needs no conversion is never
        // rewritten, so this is the path on which a failed write can refuse a capture.
        photos.processing.conversion = FakeJobPhotoFiles.JPEG_BYTES
        val viewModel = startedViewModel(photos)

        val capture = requireNotNull(viewModel.beginPhotoCapture())
        photos.files.writeCapture(capture.localPath, FakeJobPhotoFiles.UNACCEPTED_BYTES)
        viewModel.photoCaptured(capture)
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.capturedPhoto)
        assertTrue(viewModel.uiState.value.pendingPhotos.isEmpty())
        assertEquals(JobPhotoFailure.PHOTO_NOT_SAVED, viewModel.uiState.value.photoFailure)
    }

    @Test
    fun `does not open the camera when no session can own the photo`() = runTest(dispatcher) {
        val photos = PhotoCollaborators()
        photos.subject.setSubject(null)
        val viewModel = startedViewModel(photos)

        assertNull(viewModel.beginPhotoCapture())
        assertEquals(JobPhotoFailure.NOT_SIGNED_IN, viewModel.uiState.value.photoFailure)
    }

    @Test
    fun `follows the tray when the backend accepts a queued photo`() = runTest(dispatcher) {
        val photos = PhotoCollaborators()
        val viewModel = startedViewModel(photos)

        val capture = requireNotNull(viewModel.beginPhotoCapture())
        photos.files.writeCapture(capture.localPath)
        viewModel.photoCaptured(capture)
        advanceUntilIdle()
        viewModel.confirmCapturedPhoto(JobPhotoPhase.DURING_WORK, null)
        viewModel.submitPendingPhotos()
        advanceUntilIdle()
        assertEquals(1, viewModel.uiState.value.pendingPhotos.size)

        // What the upload handler does once the API accepts the photo (`JobPhotoUploadHandler`).
        photos.store.remove(capture.photoId)
        photos.files.delete(capture.localPath)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.pendingPhotos.isEmpty())
    }

    @Test
    fun `keeps the photo when the review panel is closed without a decision`() =
        runTest(dispatcher) {
            val photos = PhotoCollaborators()
            val viewModel = startedViewModel(photos)

            val capture = requireNotNull(viewModel.beginPhotoCapture())
            photos.files.writeCapture(capture.localPath)
            viewModel.photoCaptured(capture)
            advanceUntilIdle()

            viewModel.keepCapturedPhoto()
            advanceUntilIdle()

            assertNull(viewModel.uiState.value.capturedPhoto)
            assertEquals(listOf(capture.photoId), viewModel.uiState.value.pendingPhotos.map { it.photoId })
            assertEquals(setOf(capture.localPath), photos.files.storedPaths)
        }

    @Test
    fun `discards the photo the technician reviewed and kept nothing else`() = runTest(dispatcher) {
        val photos = PhotoCollaborators()
        val viewModel = startedViewModel(photos)

        val capture = requireNotNull(viewModel.beginPhotoCapture())
        photos.files.writeCapture(capture.localPath)
        viewModel.photoCaptured(capture)
        advanceUntilIdle()

        viewModel.discardCapturedPhoto()
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.capturedPhoto)
        assertTrue(viewModel.uiState.value.pendingPhotos.isEmpty())
        assertTrue(photos.files.storedPaths.isEmpty())
        assertNull(photos.store.find(capture.photoId))
    }

    @Test
    fun `records every photo of a pick and reviews them one at a time`() = runTest(dispatcher) {
        val photos = PhotoCollaborators()
        val picked = FakeJobPhotoPickedItems.ofJpegs("uri-1", "uri-2")
        val viewModel = startedViewModel(photos, picked)

        viewModel.photosPicked(listOf("uri-1", "uri-2"))
        advanceUntilIdle()

        // The pick's first photo is recorded and open for review; the second is not taken until the
        // technician is done with this one (`D3`).
        val first = requireNotNull(viewModel.uiState.value.capturedPhoto)
        assertEquals(listOf("uri-1"), picked.readUris)
        assertEquals(listOf(first.photoId), viewModel.uiState.value.pendingPhotos.map { it.photoId })

        viewModel.confirmCapturedPhoto(JobPhotoPhase.BEFORE_WORK, "Worn filter")
        advanceUntilIdle()

        val second = requireNotNull(viewModel.uiState.value.capturedPhoto)
        assertEquals(listOf("uri-1", "uri-2"), picked.readUris)
        assertTrue(first.photoId != second.photoId)

        viewModel.confirmCapturedPhoto(JobPhotoPhase.BEFORE_WORK, null)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertNull(state.capturedPhoto)
        // Two photos from one action, each with its own draft, its own file and its own place in the
        // tray (`D3`).
        assertEquals(2, state.pendingPhotos.size)
        assertEquals(2, state.pendingPhotos.map { it.localPath }.toSet().size)
        assertEquals(2, photos.store.stored.size)
        assertEquals(JobPhotoPhase.BEFORE_WORK, photos.store.stored.first().phase)
        assertEquals("Worn filter", photos.store.stored.first().note)
    }

    @Test
    fun `records a picked photo in the phase the technician used last`() = runTest(dispatcher) {
        val photos = PhotoCollaborators()
        val viewModel = startedViewModel(photos, FakeJobPhotoPickedItems.ofJpegs("uri-1"))

        // The technician captured a photo after the work and confirmed that phase.
        val capture = requireNotNull(viewModel.beginPhotoCapture())
        photos.files.writeCapture(capture.localPath)
        viewModel.photoCaptured(capture)
        advanceUntilIdle()
        viewModel.confirmCapturedPhoto(JobPhotoPhase.AFTER_WORK, null)
        advanceUntilIdle()

        viewModel.photosPicked(listOf("uri-1"))
        advanceUntilIdle()

        val picked = requireNotNull(viewModel.uiState.value.capturedPhoto)
        // The draft carries the phase in effect, exactly as a capture's does, so a photo the technician
        // never reviews is still one the API can accept (`BR-027`). The review that opened is where
        // they change it.
        assertEquals(JobPhotoPhase.AFTER_WORK, picked.phase)
        assertEquals(JobPhotoPhase.AFTER_WORK, photos.store.stored.last().phase)
    }

    @Test
    fun `reports a photo of a pick it could not read and takes the rest`() = runTest(dispatcher) {
        val photos = PhotoCollaborators()
        val picked = FakeJobPhotoPickedItems(
            mapOf(
                "uri-1" to FakeJobPhotoFiles.JPEG_BYTES,
                "uri-2" to null,
                "uri-3" to FakeJobPhotoFiles.JPEG_BYTES,
            ),
        )
        val viewModel = startedViewModel(photos, picked)

        viewModel.photosPicked(listOf("uri-1", "uri-2", "uri-3"))
        advanceUntilIdle()
        viewModel.confirmCapturedPhoto(JobPhotoPhase.DURING_WORK, null)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        // The photo that could not be read is named by its place in the pick, and the pass went on to
        // the photo after it rather than stopping (`D3`, `BR-014`).
        assertEquals(JobPhotoFailure.PHOTO_NOT_READ, state.photoFailure)
        assertEquals(PhotoItemPosition(2, 3), state.photoFailureItem)
        assertEquals(listOf("uri-1", "uri-2", "uri-3"), picked.readUris)
        requireNotNull(state.capturedPhoto)
        // Two photos were recorded, each with its own bytes, and nothing was recorded for the one that
        // could not be read.
        assertEquals(2, state.pendingPhotos.size)
        assertEquals(2, photos.store.stored.size)
        assertEquals(2, photos.files.storedPaths.size)
    }

    @Test
    fun `reports a photo of a pick it could not prepare and records the rest`() = runTest(dispatcher) {
        val photos = PhotoCollaborators()
        // Bytes the API does not accept, and a device whose conversion cannot deliver: the item is
        // refused before any draft is written (`D3b`, `BR-014`).
        photos.processing.conversion = null
        val picked = FakeJobPhotoPickedItems(
            mapOf(
                "uri-1" to FakeJobPhotoFiles.UNACCEPTED_BYTES,
                "uri-2" to FakeJobPhotoFiles.JPEG_BYTES,
            ),
        )
        val viewModel = startedViewModel(photos, picked)

        viewModel.photosPicked(listOf("uri-1", "uri-2"))
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(JobPhotoFailure.PHOTO_TYPE_NOT_ACCEPTED, state.photoFailure)
        assertEquals(PhotoItemPosition(1, 2), state.photoFailureItem)
        requireNotNull(state.capturedPhoto)
        assertEquals(1, photos.store.stored.size)
        assertEquals(1, photos.files.storedPaths.size)
    }

    @Test
    fun `reports every photo of a pick it could not read, one report at a time`() = runTest(dispatcher) {
        val photos = PhotoCollaborators()
        val picked = FakeJobPhotoPickedItems(mapOf("uri-1" to null, "uri-2" to null))
        val viewModel = startedViewModel(photos, picked)

        viewModel.photosPicked(listOf("uri-1", "uri-2"))
        advanceUntilIdle()

        // Nothing was recorded and nothing is under review, and the first skipped photo is named.
        assertTrue(viewModel.uiState.value.pendingPhotos.isEmpty())
        assertNull(viewModel.uiState.value.capturedPhoto)
        assertEquals(JobPhotoFailure.PHOTO_NOT_READ, viewModel.uiState.value.photoFailure)
        assertEquals(PhotoItemPosition(1, 2), viewModel.uiState.value.photoFailureItem)

        // The screen dismisses the report it showed and the next skipped photo follows, because a pick
        // that skipped several photos has to name every one of them (`D3`).
        viewModel.dismissPhotoMessage()
        assertEquals(JobPhotoFailure.PHOTO_NOT_READ, viewModel.uiState.value.photoFailure)
        assertEquals(PhotoItemPosition(2, 2), viewModel.uiState.value.photoFailureItem)

        viewModel.dismissPhotoMessage()
        assertNull(viewModel.uiState.value.photoFailure)
        assertNull(viewModel.uiState.value.photoFailureItem)
    }

    @Test
    fun `records nothing when the picker handed nothing over`() = runTest(dispatcher) {
        val photos = PhotoCollaborators()
        val picked = FakeJobPhotoPickedItems.ofJpegs("uri-1")
        val viewModel = startedViewModel(photos, picked)

        viewModel.photosPicked(emptyList())
        advanceUntilIdle()

        // A dismissed picker chose nothing, so nothing is read, recorded or reported (`BR-042`).
        assertTrue(picked.readUris.isEmpty())
        assertTrue(photos.store.stored.isEmpty())
        assertNull(viewModel.uiState.value.capturedPhoto)
        assertNull(viewModel.uiState.value.photoFailure)
    }

    @Test
    fun `records nothing for a pick when no session can own it`() = runTest(dispatcher) {
        val photos = PhotoCollaborators(subjectId = null)
        val viewModel = startedViewModel(photos, FakeJobPhotoPickedItems.ofJpegs("uri-1"))

        viewModel.photosPicked(listOf("uri-1"))
        advanceUntilIdle()

        // The draft is the session's, so a session that ended mid-pick records nothing and the item is
        // reported instead (`§10`, `BR-014`).
        assertEquals(JobPhotoFailure.NOT_SIGNED_IN, viewModel.uiState.value.photoFailure)
        assertEquals(PhotoItemPosition(1, 1), viewModel.uiState.value.photoFailureItem)
        assertTrue(photos.store.stored.isEmpty())
        assertTrue(photos.files.storedPaths.isEmpty())
    }

    @Test
    fun `reports a device whose photo picker cannot be opened`() = runTest(dispatcher) {
        val photos = PhotoCollaborators()
        val viewModel = startedViewModel(photos)

        viewModel.photoPickerUnavailable()
        advanceUntilIdle()

        // Nothing was chosen and nothing is claimed, but the technician is told why the action did
        // nothing rather than being left with a tap that appears to do nothing (`BR-042`).
        assertEquals(JobPhotoFailure.PICKER_UNAVAILABLE, viewModel.uiState.value.photoFailure)
        assertNull(viewModel.uiState.value.photoFailureItem)
    }
}



/** The ViewModel under test, over the photo slice's in-memory collaborators. */
private fun viewModel(
    photos: PhotoCollaborators,
    pickedItems: JobPhotoPickedItems = FakeJobPhotoPickedItems(emptyMap()),
): JobDetailsViewModel =
    JobDetailsViewModel(
        repository = PhotoJobRepository(),
        photos = photos.session,
        jobPhotoImages = JobPhotoImages.None,
        pickedItems = pickedItems,
        clock = TEST_CLOCK,
    )

/**
 * The ViewModel with the Job read and settled, because a photo may only be captured for a Job the
 * screen is actually showing (`BR-001`).
 */
private suspend fun TestScope.startedViewModel(
    photos: PhotoCollaborators,
    pickedItems: JobPhotoPickedItems = FakeJobPhotoPickedItems(emptyMap()),
): JobDetailsViewModel {
    val viewModel = viewModel(photos, pickedItems)
    viewModel.start(JOB_ID)
    advanceUntilIdle()
    return viewModel
}

/** The Job every test in this file works on. */
private const val JOB_ID = "job-1"

/**
 * A [JobDetailsRepository] that answers with one Job.
 *
 * These tests are about the technician's photo work, not about reading a Job, so nothing else the
 * repository offers is exercised; a call to it would mean the test is asserting the wrong thing
 * (`qa.md` §6.1).
 */
private class PhotoJobRepository : JobDetailsRepository {

    override suspend fun loadJobDetails(jobId: String): JobDetailsResult =
        JobDetailsResult.Success(
            JobDetails(
                id = JOB_ID,
                jobNumber = 1042,
                title = "Furnace repair",
                description = null,
                status = JobStatus.SCHEDULED,
                allowedStatusTransitions = listOf(JobStatus.IN_PROGRESS),
                version = 7,
                customerId = "customer-1",
                customerName = "Martha Reynolds",
                address = null,
                selectedVisit = null,
                technicians = emptyList(),
            ),
        )

    override suspend fun loadJobActivity(jobId: String): JobActivityResult =
        JobActivityResult.Success(emptyList())

    override suspend fun addVisitNote(
        jobId: String,
        visitId: String,
        body: String,
    ): VisitNoteResult = unsupported()

    override suspend fun changeJobStatus(
        jobId: String,
        status: JobStatus,
        note: String?,
        expectedVersion: Int,
    ): JobActionResult = unsupported()

    override suspend fun rescheduleVisit(
        jobId: String,
        visitId: String,
        scheduledStart: Instant,
        scheduledEnd: Instant,
        reason: String?,
        confirmConflicts: Boolean,
        expectedVersion: Int,
    ): JobActionResult = unsupported()

    override suspend fun assignVisitTechnicians(
        jobId: String,
        visitId: String,
        assignments: List<TechnicianAssignment>,
        confirmConflicts: Boolean,
        expectedVersion: Int,
    ): JobActionResult = unsupported()

    override suspend fun loadAssignableTechnicians(): AssignableTechniciansResult =
        unsupported()

    private fun unsupported(): Nothing =
        throw AssertionError("these tests only capture photos")
}
