package com.servora.android.ui.jobs

import com.servora.android.data.customers.CustomersFailureReason
import com.servora.android.data.jobs.JobActionFailure
import com.servora.android.data.jobs.JobActionResult
import com.servora.android.data.jobs.JobActivityResult
import com.servora.android.data.jobs.AssignableTechniciansResult
import com.servora.android.data.jobs.AudioCollaborators
import com.servora.android.data.jobs.FakeJobAudioEvidenceCache
import com.servora.android.data.jobs.FakeJobAudioPlayer
import com.servora.android.data.jobs.FakeJobPhotoExporter
import com.servora.android.data.jobs.JobAudioEvidenceCache
import com.servora.android.data.jobs.JobAudioEvidenceRead
import com.servora.android.data.jobs.JobAudioOperations
import com.servora.android.data.jobs.JobAudioPlayback
import com.servora.android.data.jobs.JobAudioPlayer
import com.servora.android.data.jobs.JobAudioSession
import com.servora.android.data.jobs.JOB_AUDIO_MIME_TYPE
import com.servora.android.data.jobs.JobDetailsRepository
import com.servora.android.data.jobs.JobDetailsResult
import com.servora.android.data.jobs.JobPhotoExporter
import com.servora.android.data.jobs.JobPhotoImages
import com.servora.android.data.jobs.JobPhotoSession
import com.servora.android.data.jobs.PhotoCollaborators
import com.servora.android.data.jobs.TEST_CLOCK
import com.servora.android.data.jobs.FakeJobPhotoFiles
import com.servora.android.data.jobs.FakeJobPhotoPickedItems
import com.servora.android.data.jobs.FakeOfflineSync
import com.servora.android.data.jobs.InMemoryPendingJobPhotoStore
import com.servora.android.data.offline.AdjustableClock
import com.servora.android.data.offline.InMemoryOutboxStore
import com.servora.android.data.offline.OutboxFailureReason
import com.servora.android.data.offline.ReadSource
import com.servora.android.data.session.FakeAuthenticatedSubject
import com.servora.android.data.jobs.ActivityWriteResult
import com.servora.android.domain.model.AssignableTechnician
import com.servora.android.domain.model.AssignmentRole
import com.servora.android.domain.model.CustomerJobAddress
import com.servora.android.domain.model.JobActivityEvent
import com.servora.android.domain.model.JobActivityKind
import com.servora.android.domain.model.JobDetails
import com.servora.android.domain.model.JobDetailsTechnician
import com.servora.android.domain.model.JobDetailsVisit
import com.servora.android.domain.model.EvidencePhase
import com.servora.android.domain.model.JobStatus
import com.servora.android.domain.model.ScheduleConflict
import com.servora.android.domain.model.TechnicianAssignment
import com.servora.android.domain.model.VisitStatus
import java.time.Clock
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * What the Job Details screen is told, and what it asks [JobDetailsRepository] for.
 *
 * Which Visit represents the Job and who is assigned to it is the backend's answer (`BR-001`,
 * `BR-068`, `BR-081`), so these tests assert that the answer is reported faithfully, that a repeated
 * allocation of the same Job does not read it twice, and that a failed read never leaves a different
 * Job on screen.
 */
class JobDetailsViewModelTest {

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
    fun `reads the Job it is asked for and reports the backend's values`() = runTest(dispatcher) {
        val repository = RecordingJobDetailsRepository(JobDetailsResult.Success(job()))
        val viewModel = viewModel(repository)

        viewModel.start(JOB_ID)
        assertTrue(viewModel.uiState.value.showsInitialLoading)

        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(listOf(JOB_ID), repository.requestedJobIds)
        assertFalse(state.isLoading)
        assertNull(state.failureReason)
        assertEquals(JOB_ID, state.details?.id)
        assertEquals(JobStatus.SCHEDULED, state.details?.status)
        assertEquals(VisitStatus.EN_ROUTE, state.details?.selectedVisit?.status)
        assertEquals(
            listOf("Mike Lead", "Sarah Moreau"),
            state.details?.technicians?.map { it.name },
        )
        assertEquals(
            listOf(AssignmentRole.LEAD, AssignmentRole.TECHNICIAN),
            state.details?.technicians?.map { it.role },
        )
        assertTrue(state.details?.technicians?.first()?.isLead == true)
        assertFalse(state.showsFailure)
    }

    @Test
    fun `reports a Job the device answered as the last reported one`() = runTest(dispatcher) {
        val repository = RecordingJobDetailsRepository(
            JobDetailsResult.Success(job(), ReadSource.WORKING_SET),
        )
        val viewModel = viewModel(repository)

        viewModel.start(JOB_ID)
        advanceUntilIdle()

        // Offline, the Job is the last one the backend reported, and the screen is told so rather than
        // being left to present a local copy as current (`offline-first-architecture.md` §2, §7, `D5`).
        assertEquals(ReadSource.WORKING_SET, viewModel.uiState.value.detailsSource)
    }

    @Test
    fun `reports a Job the backend answered as current`() = runTest(dispatcher) {
        val repository = RecordingJobDetailsRepository(JobDetailsResult.Success(job()))
        val viewModel = viewModel(repository)

        viewModel.start(JOB_ID)
        advanceUntilIdle()

        assertEquals(ReadSource.BACKEND, viewModel.uiState.value.detailsSource)
    }

    @Test
    fun `reports an activity the device answered as the last reported one`() = runTest(dispatcher) {
        // A photo entry, because the projection is what makes the Job's evidence readable offline
        // (`D5`, `BR-080`).
        val event = activityEvent(
            kind = JobActivityKind.JOB_PHOTO_ADDED,
            photoId = "photo-1",
            photoPhase = "DURING_WORK",
            body = null,
        )
        val repository = RecordingJobDetailsRepository(
            result = JobDetailsResult.Success(job()),
            activityResults = listOf(
                JobActivityResult.Success(listOf(event), ReadSource.WORKING_SET),
            ),
        )
        val viewModel = viewModel(repository)

        viewModel.start(JOB_ID)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(listOf(event), state.activity)
        assertEquals(ReadSource.WORKING_SET, state.activitySource)
    }

    @Test
    fun `reports a failed read as having no answer from the device at all`() = runTest(dispatcher) {
        val repository = RecordingJobDetailsRepository(
            JobDetailsResult.Failure(CustomersFailureReason.NETWORK),
        )
        val viewModel = viewModel(repository)

        viewModel.start(JOB_ID)
        advanceUntilIdle()

        // A failure leaves nothing to describe, so there is no "last reported" answer either (`BR-042`).
        val state = viewModel.uiState.value
        assertEquals(ReadSource.BACKEND, state.detailsSource)
        assertEquals(ReadSource.BACKEND, state.activitySource)
        assertNull(state.details)
        assertNull(state.activity)
    }

    @Test
    fun `does not read the same Job twice while it is already held`() = runTest(dispatcher) {
        val repository = RecordingJobDetailsRepository(JobDetailsResult.Success(job()))
        val viewModel = viewModel(repository)

        viewModel.start(JOB_ID)
        advanceUntilIdle()
        // A recomposition of the same destination must not start a second read (`BR-001`).
        viewModel.start(JOB_ID)
        advanceUntilIdle()

        assertEquals(listOf(JOB_ID), repository.requestedJobIds)
    }

    @Test
    fun `reports a failed read and offers the read again`() = runTest(dispatcher) {
        val repository = RecordingJobDetailsRepository(
            JobDetailsResult.Failure(CustomersFailureReason.NETWORK),
        )
        val viewModel = viewModel(repository)

        viewModel.start(JOB_ID)
        advanceUntilIdle()

        val failed = viewModel.uiState.value
        assertTrue(failed.showsFailure)
        assertNull(failed.details)

        viewModel.retry()
        advanceUntilIdle()

        assertEquals(listOf(JOB_ID, JOB_ID), repository.requestedJobIds)
    }

    @Test
    fun `a failed read never leaves the previous Job on screen`() = runTest(dispatcher) {
        val repository = ScriptedJobDetailsRepository(
            listOf(
                JobDetailsResult.Success(job()),
                JobDetailsResult.Failure(CustomersFailureReason.NOT_FOUND),
            ),
        )
        val viewModel = viewModel(repository)

        viewModel.start(JOB_ID)
        advanceUntilIdle()
        assertEquals(JOB_ID, viewModel.uiState.value.details?.id)

        // The Job is no longer readable: keeping it on screen would describe a record the backend no
        // longer reports (`BR-001`).
        viewModel.retry()
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.details)
        assertTrue(viewModel.uiState.value.showsFailure)
    }

    @Test
    fun `retry without a Job to read does nothing`() = runTest(dispatcher) {
        val repository = RecordingJobDetailsRepository(JobDetailsResult.Success(job()))
        val viewModel = viewModel(repository)

        viewModel.retry()
        advanceUntilIdle()

        assertTrue(repository.requestedJobIds.isEmpty())
    }

    @Test
    fun `reset releases the Job and allows it to be read again`() = runTest(dispatcher) {
        val repository = RecordingJobDetailsRepository(JobDetailsResult.Success(job()))
        val viewModel = viewModel(repository)

        viewModel.start(JOB_ID)
        advanceUntilIdle()
        // Ending the session releases the Job, so the next user does not see the previous one's
        // record (`BR-001`).
        viewModel.reset()

        assertNull(viewModel.uiState.value.details)

        viewModel.start(JOB_ID)
        advanceUntilIdle()

        assertEquals(listOf(JOB_ID, JOB_ID), repository.requestedJobIds)
    }

    @Test
    fun `asks the backend to move the Job with the version the screen was shown`() = runTest(dispatcher) {
        val repository = RecordingJobDetailsRepository(JobDetailsResult.Success(job()))
        val viewModel = viewModel(repository)
        viewModel.start(JOB_ID)
        advanceUntilIdle()

        // The version the read reported is what keeps a change from being applied to newer state
        // (`BR-086`).
        viewModel.changeJobStatus(JobStatus.IN_PROGRESS)
        advanceUntilIdle()

        assertEquals(listOf("status:IN_PROGRESS:7"), repository.actions)
        assertTrue(viewModel.uiState.value.isSubmitting.not())
        assertEquals(JobActionKind.STATUS_CHANGE, viewModel.uiState.value.completedAction)
        assertNull(viewModel.uiState.value.actionFailure)
    }

    @Test
    fun `holds a refused action until its conflicts are accepted, then resends it confirmed`() =
        runTest(dispatcher) {
            // `BR-070` makes a conflict a question rather than a failure, so the action waits for the
            // user's answer and is resent with the conflicts confirmed.
            val conflicts = listOf(
                ScheduleConflict(
                    visitId = "visit-9",
                    jobNumber = 1043,
                    technicianName = "Mike Lead",
                    scheduledStart = "2026-09-15T13:00:00.000Z",
                    scheduledEnd = "2026-09-15T15:00:00.000Z",
                ),
            )
            val repository = RecordingJobDetailsRepository(
                result = JobDetailsResult.Success(job()),
                actionResults = ArrayDeque(listOf(JobActionResult.Conflicts(conflicts), JobActionResult.Success(job()))),
            )
            val viewModel = viewModel(repository)
            viewModel.start(JOB_ID)
            advanceUntilIdle()

            viewModel.rescheduleVisit(
                scheduledStart = Instant.parse("2026-09-15T13:00:00Z"),
                scheduledEnd = Instant.parse("2026-09-15T15:00:00Z"),
            )
            advanceUntilIdle()

            val pending = viewModel.uiState.value.pendingConfirmation
            assertTrue(pending is PendingJobAction.Reschedule)
            assertEquals(conflicts, pending?.conflicts)
            assertEquals(listOf("reschedule:visit-1:false:2"), repository.actions)
            assertNull(viewModel.uiState.value.actionFailure)

            viewModel.confirmPendingAction()
            advanceUntilIdle()

            assertEquals(
                listOf("reschedule:visit-1:false:2", "reschedule:visit-1:true:2"),
                repository.actions,
            )
            assertNull(viewModel.uiState.value.pendingConfirmation)
            assertEquals(JobActionKind.RESCHEDULE, viewModel.uiState.value.completedAction)
        }

    @Test
    fun `leaves the conflicts unaccepted without sending anything`() = runTest(dispatcher) {
        val repository = RecordingJobDetailsRepository(
            result = JobDetailsResult.Success(job()),
            actionResults = ArrayDeque(listOf(JobActionResult.Conflicts(emptyList()))),
        )
        val viewModel = viewModel(repository)
        viewModel.start(JOB_ID)
        advanceUntilIdle()

        viewModel.assignVisitTechnicians(listOf(TechnicianAssignment("member-1", AssignmentRole.LEAD)))
        advanceUntilIdle()
        viewModel.dismissPendingAction()
        advanceUntilIdle()

        // Nothing was applied, so nothing more is sent (`BR-070`).
        assertEquals(listOf("assign:[member-1:LEAD]:false:2"), repository.actions)
        assertNull(viewModel.uiState.value.pendingConfirmation)
        assertNull(viewModel.uiState.value.completedAction)
    }

    @Test
    fun `reports a refused action instead of changing the Job on screen`() = runTest(dispatcher) {
        val repository = RecordingJobDetailsRepository(
            result = JobDetailsResult.Success(job()),
            actionResults = ArrayDeque(
                listOf(JobActionResult.Failure(JobActionFailure.JOB_REVIEW_CONDITION_NOT_MET)),
            ),
        )
        val viewModel = viewModel(repository)
        viewModel.start(JOB_ID)
        advanceUntilIdle()

        viewModel.changeJobStatus(JobStatus.PENDING_REVIEW)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(JobActionFailure.JOB_REVIEW_CONDITION_NOT_MET, state.actionFailure)
        assertNull(state.completedAction)
        // The Job on screen is still the one the backend last reported (`BR-001`).
        assertEquals(JobStatus.SCHEDULED, state.details?.status)
    }

    @Test
    fun `presents the Job the action answered with`() = runTest(dispatcher) {
        val repository = RecordingJobDetailsRepository(
            result = JobDetailsResult.Success(job()),
            actionResults = ArrayDeque(
                listOf(JobActionResult.Success(job().copy(status = JobStatus.IN_PROGRESS, version = 8))),
            ),
        )
        val viewModel = viewModel(repository)
        viewModel.start(JOB_ID)
        advanceUntilIdle()

        viewModel.changeJobStatus(JobStatus.IN_PROGRESS)
        advanceUntilIdle()

        assertEquals(JobStatus.IN_PROGRESS, viewModel.uiState.value.details?.status)
        assertEquals(8, viewModel.uiState.value.details?.version)
    }

    @Test
    fun `reads the technicians the assign sheet offers and reports a refused read`() =
        runTest(dispatcher) {
            val offered = listOf(AssignableTechnician("member-1", "Mike Lead"))
            val repository = RecordingJobDetailsRepository(
                result = JobDetailsResult.Success(job()),
                assignable = offered,
            )
            val viewModel = viewModel(repository)
            viewModel.start(JOB_ID)
            advanceUntilIdle()

            viewModel.loadAssignableTechnicians()
            advanceUntilIdle()

            assertEquals(offered, viewModel.uiState.value.assignableTechnicians)
            assertNull(viewModel.uiState.value.assignableFailure)
        }

    @Test
    fun `does not start a second action while one is in flight`() = runTest(dispatcher) {
        val repository = RecordingJobDetailsRepository(JobDetailsResult.Success(job()))
        val viewModel = viewModel(repository)
        viewModel.start(JOB_ID)
        advanceUntilIdle()

        viewModel.changeJobStatus(JobStatus.IN_PROGRESS)
        viewModel.changeJobStatus(JobStatus.IN_PROGRESS)
        advanceUntilIdle()

        assertEquals(listOf("status:IN_PROGRESS:7"), repository.actions)
    }

    @Test
    fun `reads the activity once the Job was read and reports the backend's events`() = runTest(dispatcher) {
        val repository = RecordingJobDetailsRepository(
            JobDetailsResult.Success(job()),
            activityResults = listOf(
                JobActivityResult.Success(
                    listOf(
                        activityEvent(id = "event-1", visitSequence = 2, body = "Fixed."),
                        activityEvent(
                            id = "event-2",
                            kind = JobActivityKind.JOB_STATUS_CHANGED,
                            visitSequence = null,
                            toStatus = "SCHEDULED",
                            body = null,
                        ),
                    ),
                ),
            ),
        )
        val viewModel = viewModel(repository)
        viewModel.start(JOB_ID)
        advanceUntilIdle()

        assertEquals(listOf("event-1", "event-2"), viewModel.uiState.value.activity?.map { it.id })
        assertEquals(2, viewModel.uiState.value.activity?.firstOrNull()?.visitSequence)
        assertNull(viewModel.uiState.value.activity?.firstOrNull { it.id == "event-2" }?.visitSequence)
        assertNull(viewModel.uiState.value.activityFailure)
    }

    @Test
    fun `reports a failed activity read without leaving a previous activity on screen`() = runTest(dispatcher) {
        val repository = RecordingJobDetailsRepository(
            JobDetailsResult.Success(job()),
            activityResults = listOf(
                JobActivityResult.Failure(CustomersFailureReason.NETWORK),
            ),
        )
        val viewModel = viewModel(repository)
        viewModel.start(JOB_ID)
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.activity)
        assertEquals(CustomersFailureReason.NETWORK, viewModel.uiState.value.activityFailure)
    }

    @Test
    fun `adds a text activity update to the represented Visit`() = runTest(dispatcher) {
        val note = activityEvent(id = "note-1", body = "Replaced the filter.")
        val repository = RecordingJobDetailsRepository(
            result = JobDetailsResult.Success(job()),
            noteResult = ActivityWriteResult.Success(listOf(note)),
        )
        val viewModel = viewModel(repository)
        viewModel.start(JOB_ID)
        advanceUntilIdle()

        viewModel.addActivityText("  Replaced the filter.  ")
        advanceUntilIdle()

        assertEquals(listOf("note:visit-1:Replaced the filter."), repository.actions)
        assertEquals(listOf("note-1"), viewModel.uiState.value.activity?.map { it.id })
        assertEquals(JobActionKind.ACTIVITY_TEXT, viewModel.uiState.value.completedAction)
        assertFalse(viewModel.uiState.value.isSubmitting)
    }

    @Test
    fun `reads the Job Activity again after a crew change so the timeline shows it`() = runTest(dispatcher) {
        val note = activityEvent(id = "note-1", body = "Replaced the filter.")
        val assigned = activityEvent(
            id = "assigned-1",
            kind = JobActivityKind.VISIT_TECHNICIAN_ASSIGNED,
            technicianName = "Mike Lead",
            roleCode = "LEAD",
            body = null,
        )
        val repository = RecordingJobDetailsRepository(
            result = JobDetailsResult.Success(job()),
            actionResults = ArrayDeque(
                listOf(JobActionResult.Success(job().copy(version = 8))),
            ),
            activityResults = listOf(
                JobActivityResult.Success(listOf(note)),
                JobActivityResult.Success(listOf(assigned, note)),
            ),
        )
        val viewModel = viewModel(repository)
        viewModel.start(JOB_ID)
        advanceUntilIdle()
        assertEquals(listOf("note-1"), viewModel.uiState.value.activity?.map { it.id })

        viewModel.assignVisitTechnicians(
            listOf(TechnicianAssignment("member-1", AssignmentRole.LEAD)),
        )
        advanceUntilIdle()

        // The action answered with the Job, not with the timeline, so the timeline is read again
        // rather than left showing what the Job looked like before the assignment (`BR-001`,
        // `BR-080`).
        assertEquals(listOf(JOB_ID, JOB_ID), repository.requestedActivityJobIds)
        assertEquals(
            listOf("assigned-1", "note-1"),
            viewModel.uiState.value.activity?.map { it.id },
        )
        assertNull(viewModel.uiState.value.activityFailure)
    }

    @Test
    fun `reads the Job Activity again after a schedule change so the timeline shows it`() =
        runTest(dispatcher) {
            val rescheduled = activityEvent(
                id = "rescheduled-1",
                kind = JobActivityKind.VISIT_RESCHEDULED,
                body = null,
            )
            val repository = RecordingJobDetailsRepository(
                result = JobDetailsResult.Success(job()),
                actionResults = ArrayDeque(
                    listOf(JobActionResult.Success(job().copy(version = 8))),
                ),
                activityResults = listOf(
                    JobActivityResult.Success(emptyList()),
                    JobActivityResult.Success(listOf(rescheduled)),
                ),
            )
            val viewModel = viewModel(repository)
            viewModel.start(JOB_ID)
            advanceUntilIdle()

            viewModel.rescheduleVisit(
                Instant.parse("2026-09-15T13:00:00Z"),
                Instant.parse("2026-09-15T15:00:00Z"),
            )
            advanceUntilIdle()

            assertEquals(listOf(JOB_ID, JOB_ID), repository.requestedActivityJobIds)
            assertEquals(
                listOf("rescheduled-1"),
                viewModel.uiState.value.activity?.map { it.id },
            )
        }

    @Test
    fun `does not read the Job Activity again when the action was refused`() = runTest(dispatcher) {
        val repository = RecordingJobDetailsRepository(
            result = JobDetailsResult.Success(job()),
            actionResults = ArrayDeque(
                listOf(JobActionResult.Failure(JobActionFailure.VERSION_CONFLICT)),
            ),
        )
        val viewModel = viewModel(repository)
        viewModel.start(JOB_ID)
        advanceUntilIdle()

        viewModel.changeJobStatus(JobStatus.IN_PROGRESS)
        advanceUntilIdle()

        // Nothing changed, so there is nothing new to project (`BR-067`): the timeline is not asked
        // for again.
        assertEquals(listOf(JOB_ID), repository.requestedActivityJobIds)
    }

    @Test
    fun `records a take as a durable draft when the recorder stops`() = runTest(dispatcher) {
        val repository = RecordingJobDetailsRepository(
            result = JobDetailsResult.Success(job()),
            activityResults = listOf(JobActivityResult.Success(emptyList())),
        )
        val audio = AudioCollaborators()
        val viewModel = viewModel(repository, audio = audio.session)
        viewModel.start(JOB_ID)
        advanceUntilIdle()

        viewModel.startAudioRecording()
        viewModel.stopAudioRecording()
        advanceUntilIdle()

        // The take is on this device, findable after a process death, and not evidence yet (`BR-014`,
        // `BR-091`): nothing is queued, nothing is claimed, and the Activity is not read for it.
        val draft = requireNotNull(viewModel.uiState.value.audioDraft)
        assertEquals(JOB_AUDIO_MIME_TYPE, draft.mimeType)
        assertEquals(EvidencePhase.DURING_WORK, draft.phase)
        assertEquals(JOB_ID, draft.jobId)
        assertTrue(audio.files.exists(draft.localPath))
        assertTrue(audio.outbox.stored.isEmpty())
        assertEquals(listOf(JOB_ID), repository.requestedActivityJobIds)
    }

    @Test
    fun `measures the take from the device's clock when the recorder stops`() = runTest(dispatcher) {
        val repository = RecordingJobDetailsRepository(
            result = JobDetailsResult.Success(job()),
            activityResults = listOf(JobActivityResult.Success(emptyList())),
        )
        val clock = AdjustableClock(Instant.parse("2026-09-15T13:05:00Z"))
        val audio = AudioCollaborators(clock = clock)
        val viewModel = viewModel(repository, clock = clock, audio = audio.session)
        viewModel.start(JOB_ID)
        advanceUntilIdle()

        viewModel.startAudioRecording()
        clock.advance(Duration.ofSeconds(18))
        viewModel.stopAudioRecording()
        advanceUntilIdle()

        // The length is this device's own measurement of the take, for the review to show; the API reads
        // the authoritative one from the container and that is what it stores (`ADR-018` A3).
        assertEquals(18, viewModel.uiState.value.audioDraft?.durationSeconds)
    }

    @Test
    fun `reports a device whose recorder could not start and records nothing`() = runTest(dispatcher) {
        val repository = RecordingJobDetailsRepository(
            result = JobDetailsResult.Success(job()),
            activityResults = listOf(JobActivityResult.Success(emptyList())),
        )
        val audio = AudioCollaborators()
        audio.recorder.startSucceeds = false
        val viewModel = viewModel(repository, audio = audio.session)
        viewModel.start(JOB_ID)
        advanceUntilIdle()

        viewModel.startAudioRecording()
        advanceUntilIdle()

        assertEquals(JobAudioFailure.RECORDER_UNAVAILABLE, viewModel.uiState.value.audioFailure)
        assertNull(viewModel.uiState.value.audioRecording)
        assertTrue(viewModel.uiState.value.pendingAudioNotes.isEmpty())
        // The recorder is released and whatever it may have created goes with it (`§9`).
        assertEquals(1, audio.recorder.releases)
        assertTrue(audio.files.storedPaths.isEmpty())
    }

    @Test
    fun `records nothing for a take the recorder could not finish`() = runTest(dispatcher) {
        val repository = RecordingJobDetailsRepository(
            result = JobDetailsResult.Success(job()),
            activityResults = listOf(JobActivityResult.Success(emptyList())),
        )
        val audio = AudioCollaborators()
        audio.recorder.stopSucceeds = false
        val viewModel = viewModel(repository, audio = audio.session)
        viewModel.start(JOB_ID)
        advanceUntilIdle()

        viewModel.startAudioRecording()
        viewModel.stopAudioRecording()
        advanceUntilIdle()

        assertEquals(JobAudioFailure.RECORDING_FAILED, viewModel.uiState.value.audioFailure)
        assertTrue(viewModel.uiState.value.pendingAudioNotes.isEmpty())
        assertTrue(audio.files.storedPaths.isEmpty())
    }

    @Test
    fun `drops a take the device could not have kept rather than recording an empty one`() =
        runTest(dispatcher) {
            val repository = RecordingJobDetailsRepository(
                result = JobDetailsResult.Success(job()),
                activityResults = listOf(JobActivityResult.Success(emptyList())),
            )
            val audio = AudioCollaborators()
            // The recorder "finished", but the file it left has no bytes in it: nothing is uploadable, so
            // nothing is recorded (`BR-014`, `BR-042`).
            audio.recorder.recordingBytes = ByteArray(0)
            val viewModel = viewModel(repository, audio = audio.session)
            viewModel.start(JOB_ID)
            advanceUntilIdle()

            viewModel.startAudioRecording()
            viewModel.stopAudioRecording()
            advanceUntilIdle()

            assertEquals(JobAudioFailure.RECORDING_EMPTY, viewModel.uiState.value.audioFailure)
            assertTrue(viewModel.uiState.value.pendingAudioNotes.isEmpty())
            assertTrue(audio.files.storedPaths.isEmpty())
        }

    @Test
    fun `leaves a recording that is still running with nothing recorded`() = runTest(dispatcher) {
        val repository = RecordingJobDetailsRepository(
            result = JobDetailsResult.Success(job()),
            activityResults = listOf(JobActivityResult.Success(emptyList())),
        )
        val audio = AudioCollaborators()
        val viewModel = viewModel(repository, audio = audio.session)
        viewModel.start(JOB_ID)
        advanceUntilIdle()

        viewModel.startAudioRecording()
        viewModel.cancelAudioRecording()
        advanceUntilIdle()

        // Nothing had been recorded, so nothing is lost, and the microphone is not left open (`BR-091`).
        assertNull(viewModel.uiState.value.audioRecording)
        assertTrue(viewModel.uiState.value.pendingAudioNotes.isEmpty())
        assertEquals(1, audio.recorder.releases)
        assertTrue(audio.files.storedPaths.isEmpty())
    }

    @Test
    fun `attaches the take with the phase and note the technician chose`() = runTest(dispatcher) {
        val repository = RecordingJobDetailsRepository(
            result = JobDetailsResult.Success(job()),
            activityResults = listOf(JobActivityResult.Success(emptyList())),
        )
        val audio = AudioCollaborators()
        val viewModel = viewModel(repository, audio = audio.session)
        viewModel.start(JOB_ID)
        advanceUntilIdle()

        viewModel.startAudioRecording()
        viewModel.stopAudioRecording()
        advanceUntilIdle()
        val draft = requireNotNull(viewModel.uiState.value.audioDraft)
        viewModel.selectAudioPhase(EvidencePhase.AFTER_WORK)
        viewModel.attachAudioNote("Compressor is noisy")
        advanceUntilIdle()

        // Attaching queues the upload through the outbox and asks the existing trigger to replay it
        // (`BR-031`, §5, §6); the take is no longer the technician's to remove.
        assertEquals(JobAudioMessage.QUEUED, viewModel.uiState.value.audioMessage)
        assertEquals(1, audio.sync.requests)
        val queued = audio.outbox.stored.single()
        assertEquals(draft.audioNoteId, queued.operationId)
        assertEquals(JobAudioOperations.ADD_AUDIO, queued.operationType)
        assertTrue(audio.store.find(draft.audioNoteId)?.submitted == true)
        assertTrue(queued.payload.contains("AFTER_WORK"))
        assertTrue(queued.payload.contains("Compressor is noisy"))
        // Queueing is not evidence: the Activity is not read until the backend has accepted it.
        assertEquals(listOf(JOB_ID), repository.requestedActivityJobIds)
    }

    @Test
    fun `reports a take that could not be queued and keeps it on the device`() = runTest(dispatcher) {
        val repository = RecordingJobDetailsRepository(
            result = JobDetailsResult.Success(job()),
            activityResults = listOf(JobActivityResult.Success(emptyList())),
        )
        val audio = AudioCollaborators()
        audio.store.submitsToQueue = false
        val viewModel = viewModel(repository, audio = audio.session)
        viewModel.start(JOB_ID)
        advanceUntilIdle()

        viewModel.startAudioRecording()
        viewModel.stopAudioRecording()
        advanceUntilIdle()
        viewModel.attachAudioNote(null)
        advanceUntilIdle()

        assertEquals(JobAudioFailure.NOT_QUEUED, viewModel.uiState.value.audioFailure)
        assertEquals(1, viewModel.uiState.value.pendingAudioNotes.size)
        assertFalse(viewModel.uiState.value.pendingAudioNotes.single().submitted)
    }

    @Test
    fun `removes a take the backend permanently refused`() = runTest(dispatcher) {
        val repository = RecordingJobDetailsRepository(
            result = JobDetailsResult.Success(job()),
            activityResults = listOf(JobActivityResult.Success(emptyList())),
        )
        val audio = AudioCollaborators()
        val viewModel = viewModel(repository, audio = audio.session)
        viewModel.start(JOB_ID)
        advanceUntilIdle()

        viewModel.startAudioRecording()
        viewModel.stopAudioRecording()
        advanceUntilIdle()
        val draft = requireNotNull(viewModel.uiState.value.audioDraft)
        viewModel.attachAudioNote(null)
        advanceUntilIdle()
        // What the replay engine does when the API refuses the take for good (§6).
        audio.outbox.markRejected(draft.audioNoteId, OutboxFailureReason.INVALID, at = 2_000L)
        viewModel.removePendingAudioNote(draft.audioNoteId)
        advanceUntilIdle()

        // The refusal is terminal, so the discard clears the file, the draft and the queued refusal
        // together (`BR-014`, `BR-031`).
        assertNull(viewModel.uiState.value.audioFailure)
        assertTrue(viewModel.uiState.value.pendingAudioNotes.isEmpty())
        assertTrue(audio.files.storedPaths.isEmpty())
        assertTrue(audio.outbox.rejected("user-1").isEmpty())
    }

    @Test
    fun `does not remove a take whose upload may still be accepted`() = runTest(dispatcher) {
        val repository = RecordingJobDetailsRepository(
            result = JobDetailsResult.Success(job()),
            activityResults = listOf(JobActivityResult.Success(emptyList())),
        )
        val audio = AudioCollaborators()
        val viewModel = viewModel(repository, audio = audio.session)
        viewModel.start(JOB_ID)
        advanceUntilIdle()

        viewModel.startAudioRecording()
        viewModel.stopAudioRecording()
        advanceUntilIdle()
        val draft = requireNotNull(viewModel.uiState.value.audioDraft)
        viewModel.attachAudioNote(null)
        advanceUntilIdle()
        viewModel.removePendingAudioNote(draft.audioNoteId)
        advanceUntilIdle()

        // The backend may still accept a queued upload, so removing it would be a local decision about
        // evidence that may already exist (`BR-014`, §9).
        assertEquals(JobAudioFailure.ALREADY_SUBMITTED, viewModel.uiState.value.audioFailure)
        assertEquals(1, viewModel.uiState.value.pendingAudioNotes.size)
        assertTrue(audio.files.exists(draft.localPath))
    }

    @Test
    fun `reads the Job Activity again once a recording is accepted`() = runTest(dispatcher) {
        // The event the second read answers with is not an audio one: the timeline entry for a recording
        // is tracker 035's Phase 9c, and what this test pins is the trigger — an upload the API accepted
        // reads the timeline again (`BR-001`, `BR-080`).
        val recorded = activityEvent(id = "audio-1")
        val repository = RecordingJobDetailsRepository(
            result = JobDetailsResult.Success(job()),
            activityResults = listOf(
                JobActivityResult.Success(emptyList()),
                JobActivityResult.Success(listOf(recorded)),
            ),
        )
        val audio = AudioCollaborators()
        val viewModel = viewModel(repository, audio = audio.session)
        viewModel.start(JOB_ID)
        advanceUntilIdle()

        viewModel.startAudioRecording()
        viewModel.stopAudioRecording()
        advanceUntilIdle()
        val draft = requireNotNull(viewModel.uiState.value.audioDraft)
        viewModel.attachAudioNote(null)
        advanceUntilIdle()
        assertEquals(listOf(JOB_ID), repository.requestedActivityJobIds)

        // What the upload handler does once the API accepts the take (`JobAudioUploadHandler`).
        audio.store.remove(draft.audioNoteId)
        advanceUntilIdle()

        // The recording is evidence the Activity projects, so the timeline is read again rather than left
        // showing a Job without the take the technician just recorded.
        assertEquals(listOf(JOB_ID, JOB_ID), repository.requestedActivityJobIds)
        assertEquals(listOf("audio-1"), viewModel.uiState.value.activity?.map { it.id })
    }

    @Test
    fun `reads the Job Activity again once an upload is accepted so the timeline shows the photo`() =
        runTest(dispatcher) {
            val photo = activityEvent(
                id = "photo-1",
                kind = JobActivityKind.JOB_PHOTO_ADDED,
                visitSequence = null,
                body = "Panel before the repair",
                photoId = "photo-1",
                photoPhase = "BEFORE_WORK",
            )
            val repository = RecordingJobDetailsRepository(
                result = JobDetailsResult.Success(job()),
                activityResults = listOf(
                    JobActivityResult.Success(emptyList()),
                    JobActivityResult.Success(listOf(photo)),
                ),
            )
            val photos = PhotoCollaborators()
            val viewModel = viewModel(repository, photos.session)
            viewModel.start(JOB_ID)
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.activity.isNullOrEmpty())

            val capture = requireNotNull(viewModel.beginPhotoCapture())
            photos.files.writeCapture(capture.localPath)
            viewModel.photoCaptured(capture)
            advanceUntilIdle()
            viewModel.confirmCapturedPhoto(EvidencePhase.BEFORE_WORK, "Panel before the repair")
            viewModel.submitPendingPhotos()
            advanceUntilIdle()

            // Saving queues the upload. The Activity is not read for that, because the backend has not
            // recorded anything yet (`BR-031`).
            assertEquals(listOf(JOB_ID), repository.requestedActivityJobIds)

            // What the upload handler does once the API accepts the photo (`JobPhotoUploadHandler`).
            photos.store.remove(capture.photoId)
            advanceUntilIdle()

            // The photo is evidence the Activity projects, so the timeline is read again rather than
            // left showing a Job without the photo the technician just recorded (`BR-001`, `BR-080`).
            assertEquals(listOf(JOB_ID, JOB_ID), repository.requestedActivityJobIds)
            assertEquals(listOf("photo-1"), viewModel.uiState.value.activity?.map { it.id })
        }

    @Test
    fun `does not read the Job Activity again when an unsaved photo is removed`() =
        runTest(dispatcher) {
            val repository = RecordingJobDetailsRepository(
                result = JobDetailsResult.Success(job()),
                activityResults = listOf(JobActivityResult.Success(emptyList())),
            )
            val photos = PhotoCollaborators()
            val viewModel = viewModel(repository, photos.session)
            viewModel.start(JOB_ID)
            advanceUntilIdle()

            val capture = requireNotNull(viewModel.beginPhotoCapture())
            photos.files.writeCapture(capture.localPath)
            viewModel.photoCaptured(capture)
            advanceUntilIdle()
            viewModel.removePendingPhoto(capture.photoId)
            advanceUntilIdle()

            // The photo was never sent, so nothing was recorded and there is nothing new to read
            // (`BR-014`).
            assertEquals(listOf(JOB_ID), repository.requestedActivityJobIds)
        }

    @Test
    fun `removes accepted evidence through the API and takes the timeline from its answer`() =
        runTest(dispatcher) {
            val photo = activityEvent(
                id = "photo-1",
                kind = JobActivityKind.JOB_PHOTO_ADDED,
                visitSequence = null,
                body = null,
                photoId = "photo-1",
                photoPhase = "DURING_WORK",
            )
            val removal = activityEvent(
                id = "removal-1",
                kind = JobActivityKind.JOB_PHOTO_REMOVED,
                visitSequence = null,
                body = null,
                photoId = "photo-1",
                photoRemovalReason = "Photographed the wrong property",
            )
            val repository = RecordingJobDetailsRepository(
                result = JobDetailsResult.Success(job()),
                activityResults = listOf(JobActivityResult.Success(listOf(photo))),
                noteResult = ActivityWriteResult.Success(listOf(removal)),
            )
            val viewModel = viewModel(repository)
            viewModel.start(JOB_ID)
            advanceUntilIdle()

            viewModel.removeEvidencePhoto("photo-1", "  Photographed the wrong property  ")
            advanceUntilIdle()

            // The reason is stated, trimmed, and the photo is named by the id it is already known by
            // (`BR-089`).
            assertEquals(
                listOf("removal:photo-1:Photographed the wrong property"),
                repository.actions,
            )
            val state = viewModel.uiState.value
            // The answer to the write is the backend's, so the evidence the Job holds is current again
            // (`BR-001`, `BR-080`, `§7`).
            assertEquals(listOf("removal-1"), state.activity?.map { it.id })
            assertEquals(ReadSource.BACKEND, state.activitySource)
            assertEquals(JobPhotoMessage.EVIDENCE_REMOVED, state.photoMessage)
            assertNull(state.photoFailure)
            assertNull(state.photoRemoval)
        }

    @Test
    fun `reports a refused removal as the reason it names, and changes nothing`() =
        runTest(dispatcher) {
            val photo = activityEvent(
                id = "photo-1",
                kind = JobActivityKind.JOB_PHOTO_ADDED,
                visitSequence = null,
                body = null,
                photoId = "photo-1",
                photoPhase = "DURING_WORK",
            )
            val repository = RecordingJobDetailsRepository(
                result = JobDetailsResult.Success(job()),
                activityResults = listOf(JobActivityResult.Success(listOf(photo))),
                noteResult = ActivityWriteResult.Failure(JobActionFailure.FORBIDDEN),
            )
            val viewModel = viewModel(repository)
            viewModel.start(JOB_ID)
            advanceUntilIdle()

            viewModel.removeEvidencePhoto("photo-1", "Wrong property")
            advanceUntilIdle()

            val state = viewModel.uiState.value
            // A refusal is reported as what happened, and the timeline is left as the backend last
            // reported it rather than being patched locally (`BR-001`, `BR-042`, `BR-067`).
            assertEquals(JobPhotoFailure.REMOVAL_NOT_PERMITTED, state.photoFailure)
            assertEquals(listOf("photo-1"), state.activity?.map { it.id })
            assertNull(state.photoRemoval)
            assertNull(state.photoMessage)
        }

    @Test
    fun `sends no removal without a reason`() = runTest(dispatcher) {
        val photo = activityEvent(
            id = "photo-1",
            kind = JobActivityKind.JOB_PHOTO_ADDED,
            visitSequence = null,
            body = null,
            photoId = "photo-1",
            photoPhase = "DURING_WORK",
        )
        val repository = RecordingJobDetailsRepository(
            result = JobDetailsResult.Success(job()),
            activityResults = listOf(JobActivityResult.Success(listOf(photo))),
        )
        val viewModel = viewModel(repository)
        viewModel.start(JOB_ID)
        advanceUntilIdle()

        viewModel.removeEvidencePhoto("photo-1", "   ")
        advanceUntilIdle()

        // `BR-089` records the reason, so the screen requires one before it asks the API to apply
        // anything: nothing is sent without it.
        assertEquals(emptyList<String>(), repository.actions)
    }

    @Test
    fun `reports a removal of a photo this screen does not hold as evidence`() = runTest(dispatcher) {
        val repository = RecordingJobDetailsRepository(
            result = JobDetailsResult.Success(job()),
            activityResults = listOf(JobActivityResult.Success(emptyList())),
        )
        val viewModel = viewModel(repository)
        viewModel.start(JOB_ID)
        advanceUntilIdle()

        viewModel.removeEvidencePhoto("photo-1", "Wrong property")
        advanceUntilIdle()

        // A photo neither the device nor the Activity reports is not evidence this screen can act on, so
        // nothing is sent and the manager is told rather than left with a tap that did nothing
        // (`BR-042`, `BR-088`).
        assertEquals(emptyList<String>(), repository.actions)
        assertEquals(
            JobPhotoFailure.EXPORT_UNREADABLE,
            viewModel.uiState.value.photoFailure,
        )
    }

    @Test
    fun `plays a take this device holds from its own bytes, without asking the API for any`() =
        runTest(dispatcher) {
            val repository = RecordingJobDetailsRepository(
                result = JobDetailsResult.Success(job()),
                activityResults = listOf(JobActivityResult.Success(emptyList())),
            )
            val audio = AudioCollaborators()
            val player = FakeJobAudioPlayer()
            val evidence = FakeJobAudioEvidenceCache()
            val viewModel = viewModel(
                repository,
                audio = audio.session,
                audioPlayer = player,
                audioEvidence = evidence,
            )
            viewModel.start(JOB_ID)
            advanceUntilIdle()
            viewModel.startAudioRecording()
            viewModel.stopAudioRecording()
            advanceUntilIdle()
            val draft = requireNotNull(viewModel.uiState.value.audioDraft)

            viewModel.toggleAudioPlayback(draft.audioNoteId)
            advanceUntilIdle()

            // The take is on this device and the backend has accepted nothing, so the recording plays from
            // its own file and nothing is read through the API (`BR-013`, `BR-088`).
            assertEquals(listOf("${draft.audioNoteId}:${draft.localPath}"), player.played)
            assertEquals(emptyList<String>(), evidence.requested)
            assertEquals(draft.audioNoteId, viewModel.uiState.value.audioPlayback?.audioNoteId)
        }

    @Test
    fun `pauses the recording it holds where it got to, and continues from there when tapped again`() =
        runTest(dispatcher) {
            val repository = RecordingJobDetailsRepository(
                result = JobDetailsResult.Success(job()),
                activityResults = listOf(JobActivityResult.Success(emptyList())),
            )
            val player = FakeJobAudioPlayer()
            val viewModel = viewModel(repository, audioPlayer = player)
            viewModel.start(JOB_ID)
            advanceUntilIdle()
            viewModel.startAudioRecording()
            viewModel.stopAudioRecording()
            advanceUntilIdle()
            val draft = requireNotNull(viewModel.uiState.value.audioDraft)

            viewModel.toggleAudioPlayback(draft.audioNoteId)
            advanceUntilIdle()
            viewModel.toggleAudioPlayback(draft.audioNoteId)
            advanceUntilIdle()

            // One control is both the play and the pause, and a pause **holds** the recording where it got
            // to: the playhead states a position the technician can move, so the same control must not be
            // the thing that throws that position away (`BR-042`, `ADR-018` A11). The state it draws is the
            // player's own answer rather than this screen's guess.
            assertEquals(listOf("${draft.audioNoteId}:${draft.localPath}"), player.played)
            assertEquals(1, player.pauses)
            assertEquals(0, player.resumes)
            assertEquals(0, player.stops)
            assertEquals(
                JobAudioPlayback(draft.audioNoteId, isPlaying = false),
                viewModel.uiState.value.audioPlayback,
            )

            viewModel.toggleAudioPlayback(draft.audioNoteId)
            advanceUntilIdle()

            // Tapping it again continues the recording the player still holds rather than starting it over
            // from the beginning (`A11`).
            assertEquals(1, player.resumes)
            assertEquals(listOf("${draft.audioNoteId}:${draft.localPath}"), player.played)
            assertEquals(
                JobAudioPlayback(draft.audioNoteId, isPlaying = true),
                viewModel.uiState.value.audioPlayback,
            )
        }

    @Test
    fun `moves the recording the player holds to the position the technician chose`() = runTest(dispatcher) {
        val recording = activityEvent(
            id = "audio-1",
            kind = JobActivityKind.JOB_AUDIO_ADDED,
            visitSequence = null,
            body = null,
            audioNoteId = "audio-1",
            audioDurationSeconds = 18,
        )
        val repository = RecordingJobDetailsRepository(
            result = JobDetailsResult.Success(job()),
            activityResults = listOf(JobActivityResult.Success(listOf(recording))),
        )
        val player = FakeJobAudioPlayer()
        val viewModel = viewModel(repository, audioPlayer = player)
        viewModel.start(JOB_ID)
        advanceUntilIdle()
        viewModel.toggleAudioPlayback("audio-1")
        advanceUntilIdle()

        viewModel.seekAudioPlayback("audio-1", 4_000)
        advanceUntilIdle()

        // Where a recording went is the device player's own answer, and the position the playhead and the
        // elapsed seconds draw is read from it rather than remembered here (`ADR-018` A11, `BR-001`).
        assertEquals(listOf(4_000), player.seeks)
        assertEquals(4_000, viewModel.audioPlaybackProgress.value?.positionMillis)
        assertEquals("audio-1", viewModel.audioPlaybackProgress.value?.audioNoteId)
    }

    @Test
    fun `does not move a recording the player does not hold`() = runTest(dispatcher) {
        val repository = RecordingJobDetailsRepository(
            result = JobDetailsResult.Success(job()),
            activityResults = listOf(JobActivityResult.Success(emptyList())),
        )
        val player = FakeJobAudioPlayer()
        val viewModel = viewModel(repository, audioPlayer = player)
        viewModel.start(JOB_ID)
        advanceUntilIdle()

        viewModel.seekAudioPlayback("audio-1", 4_000)
        advanceUntilIdle()

        // A recording this screen never loaded has no timebase to be moved through, and its track is drawn
        // as one that cannot be used rather than as one that would work if it were touched (`BR-042`).
        assertEquals(emptyList<Int>(), player.seeks)
        assertNull(viewModel.audioPlaybackProgress.value)
    }

    @Test
    fun `moves the playhead without rewriting the state the screen is composed from`() =
        runTest(dispatcher) {
            val recording = activityEvent(
                id = "audio-1",
                kind = JobActivityKind.JOB_AUDIO_ADDED,
                visitSequence = null,
                body = null,
                audioNoteId = "audio-1",
                audioDurationSeconds = 18,
            )
            val repository = RecordingJobDetailsRepository(
                result = JobDetailsResult.Success(job()),
                activityResults = listOf(JobActivityResult.Success(listOf(recording))),
            )
            val player = FakeJobAudioPlayer()
            val viewModel = viewModel(repository, audioPlayer = player)
            viewModel.start(JOB_ID)
            advanceUntilIdle()
            viewModel.toggleAudioPlayback("audio-1")
            advanceUntilIdle()
            val screenState = viewModel.uiState.value

            player.reportProgress(4_000)
            advanceUntilIdle()

            // The position is an answer of its own (`ADR-018` A11): it is read where the playhead and the
            // elapsed seconds are drawn, and the state Job Details, the timeline and the entry around the
            // recording are composed from is not rewritten for it (`BR-012`).
            assertEquals(4_000, viewModel.audioPlaybackProgress.value?.positionMillis)
            assertEquals(screenState, viewModel.uiState.value)
        }

    @Test
    fun `reads accepted evidence through the API once, then plays the file it cached`() =
        runTest(dispatcher) {
            val recording = activityEvent(
                id = "audio-1",
                kind = JobActivityKind.JOB_AUDIO_ADDED,
                visitSequence = null,
                body = "Furnace noise",
                audioNoteId = "audio-1",
                audioPhase = "DURING_WORK",
                audioDurationSeconds = 18,
            )
            val repository = RecordingJobDetailsRepository(
                result = JobDetailsResult.Success(job()),
                activityResults = listOf(JobActivityResult.Success(listOf(recording))),
            )
            val player = FakeJobAudioPlayer()
            val evidence = FakeJobAudioEvidenceCache()
            val viewModel = viewModel(
                repository,
                audioPlayer = player,
                audioEvidence = evidence,
            )
            viewModel.start(JOB_ID)
            advanceUntilIdle()

            viewModel.toggleAudioPlayback("audio-1")
            advanceUntilIdle()

            // The bytes are the backend's, so they are read through the API and played from the local copy
            // the cache answers with. The length the timeline states is the API's own reading of the
            // container, so nothing here asks for it again (`ADR-018` A3/A9).
            assertEquals(listOf("job-1:audio-1"), evidence.requested)
            assertEquals(listOf("audio-1:/cache/audio-1.m4a"), player.played)
            assertEquals("audio-1", viewModel.uiState.value.audioPlayback?.audioNoteId)
            assertNull(viewModel.uiState.value.audioPlaybackLoading)
        }

    @Test
    fun `reports a recording whose bytes could not be read as needing the server`() =
        runTest(dispatcher) {
            val recording = activityEvent(
                id = "audio-1",
                kind = JobActivityKind.JOB_AUDIO_ADDED,
                visitSequence = null,
                body = null,
                audioNoteId = "audio-1",
                audioDurationSeconds = 18,
            )
            val repository = RecordingJobDetailsRepository(
                result = JobDetailsResult.Success(job()),
                activityResults = listOf(JobActivityResult.Success(listOf(recording))),
            )
            val player = FakeJobAudioPlayer()
            val viewModel = viewModel(
                repository,
                audioPlayer = player,
                audioEvidence = FakeJobAudioEvidenceCache(JobAudioEvidenceRead.Unreachable),
            )
            viewModel.start(JOB_ID)
            advanceUntilIdle()

            viewModel.toggleAudioPlayback("audio-1")
            advanceUntilIdle()

            // Nothing was played and the technician is told which state it is: the bytes are only on the
            // server and this device could not reach it (`BR-013`, `BR-042`).
            assertEquals(emptyList<String>(), player.played)
            assertEquals(JobAudioFailure.PLAYBACK_UNREACHABLE, viewModel.uiState.value.audioFailure)
            assertNull(viewModel.uiState.value.audioPlaybackLoading)
        }

    @Test
    fun `reports a device that could not play the recording it was given`() = runTest(dispatcher) {
        val recording = activityEvent(
            id = "audio-1",
            kind = JobActivityKind.JOB_AUDIO_ADDED,
            visitSequence = null,
            body = null,
            audioNoteId = "audio-1",
            audioDurationSeconds = 18,
        )
        val repository = RecordingJobDetailsRepository(
            result = JobDetailsResult.Success(job()),
            activityResults = listOf(JobActivityResult.Success(listOf(recording))),
        )
        val player = FakeJobAudioPlayer().apply { playSucceeds = false }
        val viewModel = viewModel(repository, audioPlayer = player)
        viewModel.start(JOB_ID)
        advanceUntilIdle()

        viewModel.toggleAudioPlayback("audio-1")
        advanceUntilIdle()

        // A device that cannot play the recording reports that rather than leaving a control that looks as
        // though it did something (`BR-042`).
        assertEquals(JobAudioFailure.PLAYBACK_FAILED, viewModel.uiState.value.audioFailure)
        assertNull(viewModel.uiState.value.audioPlayback)
    }

    @Test
    fun `reports a read that failed in a way this build did not foresee instead of crashing`() =
        runTest(dispatcher) {
            val recording = activityEvent(
                id = "audio-1",
                kind = JobActivityKind.JOB_AUDIO_ADDED,
                visitSequence = null,
                body = null,
                audioNoteId = "audio-1",
                audioDurationSeconds = 18,
            )
            val repository = RecordingJobDetailsRepository(
                result = JobDetailsResult.Success(job()),
                activityResults = listOf(JobActivityResult.Success(listOf(recording))),
            )
            val player = FakeJobAudioPlayer()
            val evidence = FakeJobAudioEvidenceCache().apply {
                failure = IllegalStateException("a failure this build cannot name")
            }
            val viewModel = viewModel(
                repository,
                audioPlayer = player,
                audioEvidence = evidence,
            )
            viewModel.start(JOB_ID)
            advanceUntilIdle()

            viewModel.toggleAudioPlayback("audio-1")
            advanceUntilIdle()

            // A tap that starts playback is a user action on a phone in the field (`BR-012`): a read that
            // fails in a way this build cannot name is reported as a recording that could not be read
            // rather than taking the screen down with it (`BR-042`).
            assertEquals(listOf("job-1:audio-1"), evidence.requested)
            assertEquals(emptyList<String>(), player.played)
            assertEquals(JobAudioFailure.PLAYBACK_UNAVAILABLE, viewModel.uiState.value.audioFailure)
            assertNull(viewModel.uiState.value.audioPlaybackLoading)
        }

    @Test
    fun `removes an accepted recording through the API and takes the timeline from its answer`() =
        runTest(dispatcher) {
            val recording = activityEvent(
                id = "audio-1",
                kind = JobActivityKind.JOB_AUDIO_ADDED,
                visitSequence = null,
                body = null,
                audioNoteId = "audio-1",
                audioDurationSeconds = 18,
            )
            val removal = activityEvent(
                id = "removal-1",
                kind = JobActivityKind.JOB_AUDIO_REMOVED,
                visitSequence = null,
                body = null,
                audioNoteId = "audio-1",
                audioRemovalReason = "Recorded the wrong job",
            )
            val repository = RecordingJobDetailsRepository(
                result = JobDetailsResult.Success(job()),
                activityResults = listOf(JobActivityResult.Success(listOf(recording))),
                noteResult = ActivityWriteResult.Success(listOf(removal)),
            )
            val viewModel = viewModel(repository)
            viewModel.start(JOB_ID)
            advanceUntilIdle()

            viewModel.removeEvidenceAudioNote("audio-1", "  Recorded the wrong job  ")
            advanceUntilIdle()

            // The reason is stated, trimmed, and the recording is named by the id it is already known by
            // (`BR-089`, `ADR-018` A7).
            assertEquals(
                listOf("audio-removal:audio-1:Recorded the wrong job"),
                repository.actions,
            )
            val state = viewModel.uiState.value
            // The write's own answer is the backend's, so the evidence the Job holds is current again
            // (`BR-001`, `BR-080`, `§7`).
            assertEquals(listOf("removal-1"), state.activity?.map { it.id })
            assertEquals(ReadSource.BACKEND, state.activitySource)
            assertEquals(JobAudioMessage.EVIDENCE_REMOVED, state.audioMessage)
            assertNull(state.audioFailure)
            assertNull(state.audioRemoval)
        }

    @Test
    fun `stops playing a recording that is taken out of ordinary use`() = runTest(dispatcher) {
        val recording = activityEvent(
            id = "audio-1",
            kind = JobActivityKind.JOB_AUDIO_ADDED,
            visitSequence = null,
            body = null,
            audioNoteId = "audio-1",
            audioDurationSeconds = 18,
        )
        val repository = RecordingJobDetailsRepository(
            result = JobDetailsResult.Success(job()),
            activityResults = listOf(JobActivityResult.Success(listOf(recording))),
            noteResult = ActivityWriteResult.Success(emptyList()),
        )
        val player = FakeJobAudioPlayer()
        val viewModel = viewModel(repository, audioPlayer = player)
        viewModel.start(JOB_ID)
        advanceUntilIdle()
        viewModel.toggleAudioPlayback("audio-1")
        advanceUntilIdle()
        assertEquals("audio-1", viewModel.uiState.value.audioPlayback?.audioNoteId)

        viewModel.removeEvidenceAudioNote("audio-1", "Recorded the wrong job")
        advanceUntilIdle()

        // Evidence that leaves ordinary use is not left playing (`BR-089`, `BR-067`).
        assertNull(viewModel.uiState.value.audioPlayback)
    }

    @Test
    fun `reports a refused audio removal as the reason it names, and changes nothing`() =
        runTest(dispatcher) {
            val recording = activityEvent(
                id = "audio-1",
                kind = JobActivityKind.JOB_AUDIO_ADDED,
                visitSequence = null,
                body = null,
                audioNoteId = "audio-1",
                audioDurationSeconds = 18,
            )
            val repository = RecordingJobDetailsRepository(
                result = JobDetailsResult.Success(job()),
                activityResults = listOf(JobActivityResult.Success(listOf(recording))),
                noteResult =
                    ActivityWriteResult.Failure(JobActionFailure.AUDIO_NOTE_ALREADY_REMOVED),
            )
            val viewModel = viewModel(repository)
            viewModel.start(JOB_ID)
            advanceUntilIdle()

            viewModel.removeEvidenceAudioNote("audio-1", "Recorded the wrong job")
            advanceUntilIdle()

            val state = viewModel.uiState.value
            // The API's own conflict is reported as what happened, and the timeline is left as the backend
            // last reported it rather than being patched locally (`BR-001`, `BR-042`, `BR-067`).
            assertEquals(JobAudioFailure.REMOVAL_NO_LONGER_AVAILABLE, state.audioFailure)
            assertEquals(listOf("audio-1"), state.activity?.map { it.id })
            assertNull(state.audioRemoval)
            assertNull(state.audioMessage)
        }

    @Test
    fun `sends no audio removal without a reason`() = runTest(dispatcher) {
        val recording = activityEvent(
            id = "audio-1",
            kind = JobActivityKind.JOB_AUDIO_ADDED,
            visitSequence = null,
            body = null,
            audioNoteId = "audio-1",
            audioDurationSeconds = 18,
        )
        val repository = RecordingJobDetailsRepository(
            result = JobDetailsResult.Success(job()),
            activityResults = listOf(JobActivityResult.Success(listOf(recording))),
        )
        val viewModel = viewModel(repository)
        viewModel.start(JOB_ID)
        advanceUntilIdle()

        viewModel.removeEvidenceAudioNote("audio-1", "   ")
        advanceUntilIdle()

        // `BR-089` records the reason, so the screen requires one before it asks the API to apply
        // anything: nothing is sent without it.
        assertEquals(emptyList<String>(), repository.actions)
        assertNull(viewModel.uiState.value.audioFailure)
    }

    @Test
    fun `reports a removal of a recording this screen does not hold as evidence`() =
        runTest(dispatcher) {
            val repository = RecordingJobDetailsRepository(
                result = JobDetailsResult.Success(job()),
                activityResults = listOf(JobActivityResult.Success(emptyList())),
            )
            val viewModel = viewModel(repository)
            viewModel.start(JOB_ID)
            advanceUntilIdle()

            viewModel.removeEvidenceAudioNote("audio-1", "Recorded the wrong job")
            advanceUntilIdle()

            // A recording the Activity does not report is not evidence this screen can remove — a take the
            // technician has not attached is theirs to discard — so nothing is sent (`BR-042`, `BR-089`).
            assertEquals(emptyList<String>(), repository.actions)
            assertEquals(
                JobAudioFailure.REMOVAL_NO_LONGER_AVAILABLE,
                viewModel.uiState.value.audioFailure,
            )
        }
}

private const val JOB_ID = "job-1"

/** The Job these tests read: one represented Visit with a Lead and one other technician. */
private fun job() = JobDetails(
    id = JOB_ID,
    jobNumber = 1042,
    title = "Furnace repair",
    description = null,
    status = JobStatus.SCHEDULED,
    allowedStatusTransitions = listOf(JobStatus.IN_PROGRESS),
    version = 7,
    customerId = "customer-1",
    customerName = "Martha Reynolds",
    address = CustomerJobAddress(
        propertyName = null,
        addressLine1 = "987 Cedar Lane",
        addressLine2 = null,
        city = "Ottawa",
        province = "ON",
        postalCode = "K1A 0B1",
        country = "Canada",
    ),
    selectedVisit = JobDetailsVisit(
        id = "visit-1",
        status = VisitStatus.EN_ROUTE,
        scheduledStart = "2026-09-14T13:00:00.000Z",
        scheduledEnd = "2026-09-14T15:00:00.000Z",
        version = 2,
        reschedulable = true,
    ),
    technicians = listOf(
        JobDetailsTechnician(
            membershipId = "member-1",
            name = "Mike Lead",
            role = AssignmentRole.LEAD,
        ),
        JobDetailsTechnician(
            membershipId = "member-2",
            name = "Sarah Moreau",
            role = AssignmentRole.TECHNICIAN,
        ),
    ),
)

private fun activityEvent(
    id: String = "event-1",
    kind: JobActivityKind = JobActivityKind.VISIT_NOTE_ADDED,
    recordedAt: String = "2026-09-14T14:14:00.000Z",
    actorName: String? = "John Smith",
    visitSequence: Int? = 2,
    technicianName: String? = null,
    roleCode: String? = null,
    toStatus: String? = null,
    body: String? = "Found a damaged capacitor.",
    photoId: String? = null,
    photoPhase: String? = null,
    photoRemovalReason: String? = null,
    audioNoteId: String? = null,
    audioPhase: String? = null,
    audioDurationSeconds: Int? = null,
    audioRemovalReason: String? = null,
) = JobActivityEvent(
    id = id,
    kind = kind,
    recordedAt = recordedAt,
    actorName = actorName,
    visitSequence = visitSequence,
    fromStatus = null,
    toStatus = toStatus,
    technicianName = technicianName,
    roleCode = roleCode,
    previousRoleCode = null,
    outcomeCode = null,
    outcomeSummary = null,
    body = body,
    photoId = photoId,
    photoPhase = photoPhase,
    photoRemovalReason = photoRemovalReason,
    audioNoteId = audioNoteId,
    audioPhase = audioPhase,
    audioDurationSeconds = audioDurationSeconds,
    audioRemovalReason = audioRemovalReason,
)

/** Records every Job it is asked for, and every action it is asked to send. */
private class RecordingJobDetailsRepository(
    private val result: JobDetailsResult,
    actionResults: ArrayDeque<JobActionResult> = ArrayDeque(),
    private val assignable: List<AssignableTechnician>? = null,
    activityResults: List<JobActivityResult> = listOf(JobActivityResult.Success(emptyList())),
    private val noteResult: ActivityWriteResult = ActivityWriteResult.Success(emptyList()),
) : JobDetailsRepository {

    val requestedJobIds = mutableListOf<String>()

    /** Every Job whose Activity was asked for, in the order it was asked for. */
    val requestedActivityJobIds = mutableListOf<String>()

    /** Every action this repository was asked to send, as a comparable description. */
    val actions = mutableListOf<String>()

    private val queuedActions = actionResults
    private val queuedActivityResults = ArrayDeque(activityResults)

    override suspend fun loadJobDetails(jobId: String): JobDetailsResult {
        requestedJobIds += jobId
        return result
    }

    override suspend fun loadJobActivity(jobId: String): JobActivityResult {
        requestedActivityJobIds += jobId
        // The last scripted answer repeats, so a test that scripts one answer answers every read, and
        // a test that scripts a later one can report something different after an action.
        return if (queuedActivityResults.size > 1) {
            queuedActivityResults.removeFirst()
        } else {
            queuedActivityResults.first()
        }
    }

    override suspend fun addVisitNote(
        jobId: String,
        visitId: String,
        body: String,
    ): ActivityWriteResult {
        actions += "note:$visitId:$body"
        return noteResult
    }

    /**
     * Records a removal and answers with the scripted write result.
     *
     * The removal is a write like a note: it answers with the refreshed timeline, so a test scripts its
     * answer the same way and asserts that the screen asked for the removal it was told to
     * (`BR-089`).
     */
    override suspend fun removeJobPhoto(
        jobId: String,
        photoId: String,
        reason: String,
    ): ActivityWriteResult {
        actions += "removal:$photoId:$reason"
        return noteResult
    }

    /**
     * Records an audio recording's removal and answers with the scripted write result.
     *
     * It is a write like a note — it answers with the refreshed timeline — so a test scripts its answer
     * the same way and asserts the recording it was told to remove (`BR-089`, `ADR-018` A7).
     */
    override suspend fun removeJobAudioNote(
        jobId: String,
        audioNoteId: String,
        reason: String,
    ): ActivityWriteResult {
        actions += "audio-removal:$audioNoteId:$reason"
        return noteResult
    }

    override suspend fun changeJobStatus(
        jobId: String,
        status: JobStatus,
        note: String?,
        expectedVersion: Int,
    ): JobActionResult {
        actions += "status:$status:$expectedVersion"
        return nextAction()
    }

    override suspend fun rescheduleVisit(
        jobId: String,
        visitId: String,
        scheduledStart: Instant,
        scheduledEnd: Instant,
        reason: String?,
        confirmConflicts: Boolean,
        expectedVersion: Int,
    ): JobActionResult {
        actions += "reschedule:$visitId:$confirmConflicts:$expectedVersion"
        return nextAction()
    }

    override suspend fun assignVisitTechnicians(
        jobId: String,
        visitId: String,
        assignments: List<TechnicianAssignment>,
        confirmConflicts: Boolean,
        expectedVersion: Int,
    ): JobActionResult {
        val crew = assignments.joinToString(",") { "${it.membershipId}:${it.role}" }
        actions += "assign:[$crew]:$confirmConflicts:$expectedVersion"
        return nextAction()
    }

    override suspend fun loadAssignableTechnicians(): AssignableTechniciansResult =
        AssignableTechniciansResult.Success(assignable ?: emptyList())

    private fun nextAction(): JobActionResult =
        if (queuedActions.isEmpty()) {
            JobActionResult.Success(resultDetails())
        } else {
            queuedActions.removeFirst()
        }

    private fun resultDetails() = when (result) {
        is JobDetailsResult.Success -> result.details
        is JobDetailsResult.Failure ->
            throw AssertionError("these tests answer an action with a Job")
    }
}

/** Answers a scripted sequence of reads, so a later read can report something different. */
private class ScriptedJobDetailsRepository(
    private val results: List<JobDetailsResult>,
) : JobDetailsRepository {

    private var index = 0

    override suspend fun loadJobDetails(jobId: String): JobDetailsResult {
        val result = results[index.coerceAtMost(results.lastIndex)]
        index += 1
        return result
    }

    override suspend fun loadJobActivity(jobId: String): JobActivityResult =
        JobActivityResult.Success(emptyList())

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

    override suspend fun removeJobAudioNote(
        jobId: String,
        audioNoteId: String,
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
        throw AssertionError("these tests only read a Job")
}

/**
 * The ViewModel under test, with the photo slice's collaborators a test controls.
 *
 * The real [JobPhotoSession] is used rather than a fake: how the tray, the outbox queue and the local
 * file cleanup behave is what the photo slice's own test (`JobPhotoCaptureViewModelTest`) covers, and
 * only the collaborators that need a device or a network are replaced here (`qa.md` §6.1).
 */
private fun viewModel(
    repository: JobDetailsRepository,
    session: JobPhotoSession = PhotoCollaborators().session,
    exporter: JobPhotoExporter = FakeJobPhotoExporter(),
    audio: JobAudioSession = AudioCollaborators().session,
    audioPlayer: JobAudioPlayer = FakeJobAudioPlayer(),
    audioEvidence: JobAudioEvidenceCache = FakeJobAudioEvidenceCache(),
    clock: Clock = TEST_CLOCK,
) = JobDetailsViewModel(
    repository = repository,
    photos = session,
    audio = audio,
    jobPhotoImages = JobPhotoImages.None,
    // These tests are about the Job's own actions, so no photo source hands over anything.
    pickedItems = FakeJobPhotoPickedItems(emptyMap()),
    exporter = exporter,
    audioEvidence = audioEvidence,
    audioPlayer = audioPlayer,
    clock = clock,
)
