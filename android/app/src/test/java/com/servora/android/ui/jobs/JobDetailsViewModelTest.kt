package com.servora.android.ui.jobs

import com.servora.android.data.customers.CustomersFailureReason
import com.servora.android.data.jobs.JobActionFailure
import com.servora.android.data.jobs.JobActionResult
import com.servora.android.data.jobs.JobActivityResult
import com.servora.android.data.jobs.AssignableTechniciansResult
import com.servora.android.data.jobs.FakeJobPhotoExporter
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
import com.servora.android.data.offline.InMemoryOutboxStore
import com.servora.android.data.session.FakeAuthenticatedSubject
import com.servora.android.data.jobs.VisitNoteResult
import com.servora.android.domain.model.AssignableTechnician
import com.servora.android.domain.model.AssignmentRole
import com.servora.android.domain.model.CustomerJobAddress
import com.servora.android.domain.model.JobActivityEvent
import com.servora.android.domain.model.JobActivityKind
import com.servora.android.domain.model.JobDetails
import com.servora.android.domain.model.JobDetailsTechnician
import com.servora.android.domain.model.JobDetailsVisit
import com.servora.android.domain.model.JobPhotoPhase
import com.servora.android.domain.model.JobStatus
import com.servora.android.domain.model.ScheduleConflict
import com.servora.android.domain.model.TechnicianAssignment
import com.servora.android.domain.model.VisitStatus
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
            noteResult = VisitNoteResult.Success(listOf(note)),
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
            viewModel.confirmCapturedPhoto(JobPhotoPhase.BEFORE_WORK, "Panel before the repair")
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
)

/** Records every Job it is asked for, and every action it is asked to send. */
private class RecordingJobDetailsRepository(
    private val result: JobDetailsResult,
    actionResults: ArrayDeque<JobActionResult> = ArrayDeque(),
    private val assignable: List<AssignableTechnician>? = null,
    activityResults: List<JobActivityResult> = listOf(JobActivityResult.Success(emptyList())),
    private val noteResult: VisitNoteResult = VisitNoteResult.Success(emptyList()),
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
    ): VisitNoteResult {
        actions += "note:$visitId:$body"
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
) = JobDetailsViewModel(
    repository = repository,
    photos = session,
    jobPhotoImages = JobPhotoImages.None,
    // These tests are about the Job's own actions, so no photo source hands over anything.
    pickedItems = FakeJobPhotoPickedItems(emptyMap()),
    exporter = exporter,
    clock = TEST_CLOCK,
)
