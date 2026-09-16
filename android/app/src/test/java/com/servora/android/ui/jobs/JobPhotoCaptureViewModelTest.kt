package com.servora.android.ui.jobs

import com.servora.android.data.customers.CustomersFailureReason
import com.servora.android.data.jobs.AssignableTechniciansResult
import com.servora.android.data.jobs.FakeJobPhotoExporter
import com.servora.android.data.jobs.FakeJobPhotoFiles
import com.servora.android.data.jobs.FakeJobPhotoPickedItems
import com.servora.android.data.jobs.JobActionFailure
import com.servora.android.data.jobs.JobActionResult
import com.servora.android.data.jobs.JobActivityResult
import com.servora.android.data.jobs.JobDetailsRepository
import com.servora.android.data.jobs.JobDetailsResult
import com.servora.android.data.jobs.JobPhotoExportOutcome
import com.servora.android.data.jobs.JobPhotoExportSource
import com.servora.android.data.jobs.JobPhotoExporter
import com.servora.android.data.jobs.JobPhotoImages
import com.servora.android.data.jobs.JobPhotoOperations
import com.servora.android.data.jobs.JobPhotoPickedItems
import com.servora.android.data.jobs.JobPhotoSession
import com.servora.android.data.jobs.MAX_JOB_PHOTO_BYTES
import com.servora.android.data.jobs.PhotoCollaborators
import com.servora.android.data.jobs.TEST_CLOCK
import com.servora.android.data.jobs.ActivityWriteResult
import com.servora.android.data.offline.OutboxFailureReason
import com.servora.android.domain.model.AssignableTechnician
import com.servora.android.domain.model.CustomerJobAddress
import com.servora.android.domain.model.JobActivityEvent
import com.servora.android.domain.model.JobActivityKind
import com.servora.android.domain.model.JobDetails
import com.servora.android.domain.model.JobPhotoPhase
import com.servora.android.domain.model.JobPhotoSyncState
import com.servora.android.domain.model.JobStatus
import com.servora.android.domain.model.TechnicianAssignment
import com.servora.android.domain.model.isRemovable
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
 * photo can be removed, a photo whose upload the backend permanently refused can be discarded while a
 * queued one cannot, and saving queues the upload through the existing outbox rather than claiming the
 * backend accepted anything (`BR-001`, `BR-014`, `D6c`). Both sources are covered — the camera, and the
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
        val repository = PhotoJobRepository()
        val viewModel = startedViewModel(photos, repository = repository)

        val capture = requireNotNull(viewModel.beginPhotoCapture())
        photos.files.writeCapture(capture.localPath)
        viewModel.photoCaptured(capture)
        advanceUntilIdle()
        viewModel.confirmCapturedPhoto(JobPhotoPhase.DURING_WORK, null)
        viewModel.submitPendingPhotos()
        advanceUntilIdle()
        assertEquals(1, viewModel.uiState.value.pendingPhotos.size)
        val readsBeforeAcceptance = repository.activityReads

        // What the upload handler does once the API accepts the photo (`JobPhotoUploadHandler`).
        photos.store.remove(capture.photoId)
        photos.files.delete(capture.localPath)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.pendingPhotos.isEmpty())
        // The API holds evidence it did not hold before, so the Activity that projects it is read
        // again (`BR-001`, `BR-080`).
        assertEquals(readsBeforeAcceptance + 1, repository.activityReads)
    }

    @Test
    fun `discards a refused photo the tray reports, without re-reading the timeline`() =
        runTest(dispatcher) {
            val photos = PhotoCollaborators()
            val first = startedViewModel(photos)
            val capture = requireNotNull(first.beginPhotoCapture())
            photos.files.writeCapture(capture.localPath)
            first.photoCaptured(capture)
            advanceUntilIdle()
            first.submitPendingPhotos()
            advanceUntilIdle()
            // The backend permanently refused the upload (`403`): the operation is finished and is
            // never replayed (`§6`, `D6c`).
            photos.outbox.markRejected(capture.photoId, OutboxFailureReason.NOT_AUTHORIZED, 0L)

            // Opening the Job is where a refusal becomes visible: the tray reports what the queue
            // holds (`§7`).
            val repository = PhotoJobRepository()
            val viewModel = startedViewModel(photos, repository = repository)
            assertEquals(
                JobPhotoSyncState.REFUSED,
                viewModel.uiState.value.photoUploads[capture.photoId],
            )
            val readsWhenOpened = repository.activityReads

            viewModel.removePendingPhoto(capture.photoId)
            advanceUntilIdle()

            assertTrue(viewModel.uiState.value.pendingPhotos.isEmpty())
            assertNull(viewModel.uiState.value.photoFailure)
            assertTrue(photos.files.storedPaths.isEmpty())
            assertTrue(photos.outbox.stored.isEmpty())
            assertNull(photos.store.find(capture.photoId))
            // What went was the device's own refused draft, not evidence the API accepted, so the
            // timeline is not read for it (`BR-001`, §9).
            assertEquals(readsWhenOpened, repository.activityReads)
        }

    @Test
    fun `keeps a queued photo the screen was asked to remove, and reports why`() =
        runTest(dispatcher) {
            val photos = PhotoCollaborators()
            val viewModel = startedViewModel(photos)

            val capture = requireNotNull(viewModel.beginPhotoCapture())
            photos.files.writeCapture(capture.localPath)
            viewModel.photoCaptured(capture)
            advanceUntilIdle()
            viewModel.submitPendingPhotos()
            advanceUntilIdle()

            viewModel.removePendingPhoto(capture.photoId)
            advanceUntilIdle()

            // The API may still accept it, so nothing is removed and the refusal is reported rather
            // than leaving the technician to think the photo had gone (`BR-014`, `BR-042`).
            assertEquals(
                listOf(capture.photoId),
                viewModel.uiState.value.pendingPhotos.map { it.photoId },
            )
            assertEquals(JobPhotoFailure.ALREADY_SUBMITTED, viewModel.uiState.value.photoFailure)
            assertEquals(setOf(capture.localPath), photos.files.storedPaths)
            assertEquals(1, photos.outbox.stored.size)
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

    @Test
    fun `saves the photo from the bytes this device still holds`() = runTest(dispatcher) {
        val photos = PhotoCollaborators()
        val exporter = FakeJobPhotoExporter()
        val viewModel = startedViewModel(photos, exporter = exporter)
        val capture = requireNotNull(viewModel.beginPhotoCapture())
        photos.files.writeCapture(capture.localPath)
        viewModel.photoCaptured(capture)
        advanceUntilIdle()

        viewModel.savePhotoToDevice(capture.photoId)
        advanceUntilIdle()

        assertEquals(
            listOf(JobPhotoExportSource.Local(capture.photoId, capture.localPath)),
            exporter.saved,
        )
        assertEquals(JobPhotoMessage.SAVED_TO_DEVICE, viewModel.uiState.value.photoMessage)
        assertNull(viewModel.uiState.value.photoFailure)
    }

    @Test
    fun `saves evidence the backend holds from the API`() = runTest(dispatcher) {
        val photos = PhotoCollaborators()
        val exporter = FakeJobPhotoExporter()
        val viewModel = startedViewModel(
            photos,
            exporter = exporter,
            repository = PhotoJobRepository(activity = listOf(photoActivityEvent("photo-9"))),
        )

        viewModel.savePhotoToDevice("photo-9")
        advanceUntilIdle()

        assertEquals(
            listOf(JobPhotoExportSource.Evidence(photoId = "photo-9", jobId = JOB_ID)),
            exporter.saved,
        )
        assertEquals(JobPhotoMessage.SAVED_TO_DEVICE, viewModel.uiState.value.photoMessage)
    }

    @Test
    fun `asks for the storage permission a save needs and retries it once it is granted`() =
        runTest(dispatcher) {
            val photos = PhotoCollaborators()
            val exporter = FakeJobPhotoExporter(
                saveOutcome = JobPhotoExportOutcome.PERMISSION_REQUIRED,
            )
            val viewModel = startedViewModel(photos, exporter = exporter)
            val capture = requireNotNull(viewModel.beginPhotoCapture())
            photos.files.writeCapture(capture.localPath)
            viewModel.photoCaptured(capture)
            advanceUntilIdle()

            viewModel.savePhotoToDevice(capture.photoId)
            advanceUntilIdle()

            assertEquals(capture.photoId, viewModel.uiState.value.photoSaveAwaitingPermission)
            assertNull(
                "a save waiting on a permission is not a failure yet",
                viewModel.uiState.value.photoFailure,
            )

            exporter.saveOutcome = JobPhotoExportOutcome.SAVED
            viewModel.onSavePermissionResult(granted = true)
            advanceUntilIdle()

            assertNull(viewModel.uiState.value.photoSaveAwaitingPermission)
            assertEquals("the same save is retried", 2, exporter.saved.size)
            assertEquals(JobPhotoMessage.SAVED_TO_DEVICE, viewModel.uiState.value.photoMessage)
        }

    @Test
    fun `reports a declined storage permission rather than leaving the save looking done`() =
        runTest(dispatcher) {
            val photos = PhotoCollaborators()
            val exporter = FakeJobPhotoExporter(
                saveOutcome = JobPhotoExportOutcome.PERMISSION_REQUIRED,
            )
            val viewModel = startedViewModel(photos, exporter = exporter)
            val capture = requireNotNull(viewModel.beginPhotoCapture())
            photos.files.writeCapture(capture.localPath)
            viewModel.photoCaptured(capture)
            advanceUntilIdle()

            viewModel.savePhotoToDevice(capture.photoId)
            advanceUntilIdle()
            viewModel.onSavePermissionResult(granted = false)
            advanceUntilIdle()

            assertEquals(JobPhotoFailure.SAVE_PERMISSION_DENIED, viewModel.uiState.value.photoFailure)
            assertNull(viewModel.uiState.value.photoSaveAwaitingPermission)
            assertEquals("a declined permission saves nothing", 1, exporter.saved.size)
        }

    @Test
    fun `shares the photo with the application the technician chooses`() = runTest(dispatcher) {
        val photos = PhotoCollaborators()
        val exporter = FakeJobPhotoExporter()
        val viewModel = startedViewModel(photos, exporter = exporter)
        val capture = requireNotNull(viewModel.beginPhotoCapture())
        photos.files.writeCapture(capture.localPath)
        viewModel.photoCaptured(capture)
        advanceUntilIdle()

        viewModel.sharePhoto(capture.photoId, chooserTitle = "Share photo")
        advanceUntilIdle()

        assertEquals(
            listOf(JobPhotoExportSource.Local(capture.photoId, capture.localPath)),
            exporter.shared,
        )
        assertEquals(
            "the chooser's title comes from the screen, not from here (`BR-028`)",
            "Share photo",
            exporter.lastChooserTitle,
        )
        assertEquals(JobPhotoMessage.SHARED, viewModel.uiState.value.photoMessage)
        assertNull(viewModel.uiState.value.photoFailure)
    }

    @Test
    fun `reports a photo that cannot be read rather than writing an empty file`() =
        runTest(dispatcher) {
            val photos = PhotoCollaborators()
            val exporter = FakeJobPhotoExporter(saveOutcome = JobPhotoExportOutcome.UNREADABLE)
            val viewModel = startedViewModel(photos, exporter = exporter)
            val capture = requireNotNull(viewModel.beginPhotoCapture())
            photos.files.writeCapture(capture.localPath)
            viewModel.photoCaptured(capture)
            advanceUntilIdle()

            viewModel.savePhotoToDevice(capture.photoId)
            advanceUntilIdle()

            assertEquals(JobPhotoFailure.EXPORT_UNREADABLE, viewModel.uiState.value.photoFailure)
            assertNull(viewModel.uiState.value.photoMessage)
        }

    @Test
    fun `reports a device with nothing to share the photo with`() = runTest(dispatcher) {
        val photos = PhotoCollaborators()
        val exporter = FakeJobPhotoExporter(shareOutcome = JobPhotoExportOutcome.NO_SHARE_TARGET)
        val viewModel = startedViewModel(photos, exporter = exporter)
        val capture = requireNotNull(viewModel.beginPhotoCapture())
        photos.files.writeCapture(capture.localPath)
        viewModel.photoCaptured(capture)
        advanceUntilIdle()

        viewModel.sharePhoto(capture.photoId, chooserTitle = "Share photo")
        advanceUntilIdle()

        assertEquals(JobPhotoFailure.SHARE_UNAVAILABLE, viewModel.uiState.value.photoFailure)
    }

    @Test
    fun `does not export a photo the Job no longer holds`() = runTest(dispatcher) {
        val photos = PhotoCollaborators()
        val exporter = FakeJobPhotoExporter()
        val viewModel = startedViewModel(photos, exporter = exporter)

        viewModel.savePhotoToDevice("photo-9")
        advanceUntilIdle()

        assertTrue("nothing is written for a photo no record holds", exporter.saved.isEmpty())
        assertEquals(
            "a tap that can write nothing says so rather than doing nothing",
            JobPhotoFailure.EXPORT_UNREADABLE,
            viewModel.uiState.value.photoFailure,
        )
    }

    @Test
    fun `reports the evidence action that is running until it lands`() = runTest(dispatcher) {
        val photos = PhotoCollaborators()
        val exporter = FakeJobPhotoExporter()
        val viewModel = startedViewModel(photos, exporter = exporter)
        val capture = requireNotNull(viewModel.beginPhotoCapture())
        photos.files.writeCapture(capture.localPath)
        viewModel.photoCaptured(capture)
        advanceUntilIdle()

        viewModel.savePhotoToDevice(capture.photoId)

        // The save is reported as running the moment it is asked for, because the viewer draws that:
        // reading a photo's bytes — here a file, on the API a download — is not instant, and a control
        // that shows nothing until it lands reads as a tap that did nothing (`BR-042`).
        assertEquals(JobPhotoExportAction.SAVE, viewModel.uiState.value.photoExport)
        assertNull("nothing is claimed before it lands", viewModel.uiState.value.photoMessage)

        advanceUntilIdle()

        assertNull("the action is over", viewModel.uiState.value.photoExport)
        assertEquals(JobPhotoMessage.SAVED_TO_DEVICE, viewModel.uiState.value.photoMessage)
    }

    @Test
    fun `does not start a second evidence action while one is running`() = runTest(dispatcher) {
        val photos = PhotoCollaborators()
        val exporter = FakeJobPhotoExporter()
        val viewModel = startedViewModel(photos, exporter = exporter)
        val capture = requireNotNull(viewModel.beginPhotoCapture())
        photos.files.writeCapture(capture.localPath)
        viewModel.photoCaptured(capture)
        advanceUntilIdle()

        viewModel.savePhotoToDevice(capture.photoId)
        viewModel.sharePhoto(capture.photoId, chooserTitle = "Share photo")
        advanceUntilIdle()

        // One evidence action at a time: the second is not started, so two reports cannot race and the
        // copy the technician asked for is the copy that is written (`D12`, `D13`).
        assertEquals(1, exporter.saved.size)
        assertTrue("a share started while a save runs is not begun", exporter.shared.isEmpty())
        assertEquals(JobPhotoMessage.SAVED_TO_DEVICE, viewModel.uiState.value.photoMessage)
    }
}



/** The ViewModel under test, over the photo slice's in-memory collaborators. */
private fun viewModel(
    photos: PhotoCollaborators,
    pickedItems: JobPhotoPickedItems = FakeJobPhotoPickedItems(emptyMap()),
    exporter: JobPhotoExporter = FakeJobPhotoExporter(),
    repository: JobDetailsRepository = PhotoJobRepository(),
): JobDetailsViewModel =
    JobDetailsViewModel(
        repository = repository,
        photos = photos.session,
        jobPhotoImages = JobPhotoImages.None,
        pickedItems = pickedItems,
        exporter = exporter,
        clock = TEST_CLOCK,
    )

/**
 * The ViewModel with the Job read and settled, because a photo may only be captured for a Job the
 * screen is actually showing (`BR-001`).
 */
private suspend fun TestScope.startedViewModel(
    photos: PhotoCollaborators,
    pickedItems: JobPhotoPickedItems = FakeJobPhotoPickedItems(emptyMap()),
    exporter: JobPhotoExporter = FakeJobPhotoExporter(),
    repository: JobDetailsRepository = PhotoJobRepository(),
): JobDetailsViewModel {
    val viewModel = viewModel(photos, pickedItems, exporter, repository)
    viewModel.start(JOB_ID)
    advanceUntilIdle()
    return viewModel
}

/** One photo entry of the Job's Activity, as the API reports it (`BR-080`). */
private fun photoActivityEvent(photoId: String) = JobActivityEvent(
    id = photoId,
    kind = JobActivityKind.JOB_PHOTO_ADDED,
    recordedAt = "2026-09-15T13:05:00.000Z",
    actorName = "Mike Lead",
    visitSequence = null,
    fromStatus = null,
    toStatus = null,
    technicianName = null,
    roleCode = null,
    previousRoleCode = null,
    outcomeCode = null,
    outcomeSummary = null,
    body = null,
    photoId = photoId,
    photoPhase = "DURING_WORK",
)

/** The Job every test in this file works on. */
private const val JOB_ID = "job-1"

/**
 * A [JobDetailsRepository] that answers with one Job.
 *
 * These tests are about the technician's photo work, not about reading a Job, so nothing else the
 * repository offers is exercised; a call to it would mean the test is asserting the wrong thing
 * (`qa.md` §6.1).
 */
private class PhotoJobRepository(
    private val activity: List<JobActivityEvent> = emptyList(),
) : JobDetailsRepository {

    /**
     * How many times the timeline was read, so a test can tell the re-read an accepted upload earns
     * from the one a refusal the technician discarded must not cause (`BR-001`, `BR-080`, `D6c`).
     */
    var activityReads = 0
        private set

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

    override suspend fun loadJobActivity(jobId: String): JobActivityResult {
        activityReads += 1
        return JobActivityResult.Success(activity)
    }

    override suspend fun addVisitNote(
        jobId: String,
        visitId: String,
        body: String,
    ): ActivityWriteResult = unsupported()

    override suspend fun removeJobPhoto(
        jobId: String,
        photoId: String,
        reason: String,
    ): ActivityWriteResult = unsupported()

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
