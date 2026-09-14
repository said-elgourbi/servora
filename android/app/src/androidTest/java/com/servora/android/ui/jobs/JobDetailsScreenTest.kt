package com.servora.android.ui.jobs

import android.content.Context
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.servora.android.R
import com.servora.android.data.customers.CustomersFailureReason
import com.servora.android.data.jobs.JobActionFailure
import com.servora.android.domain.model.AssignableTechnician
import com.servora.android.domain.model.ScheduleConflict
import com.servora.android.domain.model.TechnicianAssignment
import com.servora.android.domain.model.AssignmentRole
import com.servora.android.domain.model.CustomerJobAddress
import com.servora.android.domain.model.JobActivityEvent
import com.servora.android.domain.model.JobActivityKind
import com.servora.android.domain.model.JobDetails
import com.servora.android.domain.model.JobDetailsTechnician
import com.servora.android.domain.model.JobDetailsVisit
import com.servora.android.domain.model.JobStatus
import com.servora.android.domain.model.VisitStatus
import com.servora.android.ui.theme.ServoraTheme
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What the Job Details screen presents for a Manager (`BR-021`, `BR-068`, `BR-081`).
 *
 * The requirement this screen exists for is the assignment model: technicians belong to the Visit
 * that represents the Job, a Visit may carry several of them, exactly one is the Lead, and a Visit
 * nobody is assigned to is presented as unassigned — which is the absence of an assignment and not a
 * status (`BR-042`, `BR-068`).
 */
@RunWith(AndroidJUnit4::class)
class JobDetailsScreenTest {

    @get:Rule val composeTestRule = createComposeRule()

    @Test
    fun showsEveryTechnicianAssignedToTheRepresentedVisitAndMarksOnlyTheLead() {
        render(details = job())

        composeTestRule.onNodeWithTag(jobDetailsTechnicianTag("member-1")).assertIsDisplayed()
        composeTestRule.onNodeWithTag(jobDetailsTechnicianTag("member-2")).assertIsDisplayed()
        composeTestRule.onNodeWithTag(jobDetailsTechnicianTag("member-3")).assertIsDisplayed()
        composeTestRule.onNodeWithText("Mike Lead").assertIsDisplayed()
        composeTestRule.onNodeWithText("Sarah Moreau").assertIsDisplayed()
        composeTestRule.onNodeWithText("John Tremblay").assertIsDisplayed()

        // Exactly one Lead, and the badge belongs to that technician's row (`BR-068`).
        composeTestRule.onNodeWithTag(jobDetailsLeadBadgeTag("member-1")).assertIsDisplayed()
        composeTestRule.onNodeWithTag(jobDetailsLeadBadgeTag("member-2")).assertDoesNotExist()
        composeTestRule.onNodeWithTag(jobDetailsLeadBadgeTag("member-3")).assertDoesNotExist()
    }

    @Test
    fun showsUnassignedWhenTheRepresentedVisitHasNoTechnicians() {
        render(details = job().copy(technicians = emptyList()))

        composeTestRule.onNodeWithTag(JobDetailsUnassignedTag).assertIsDisplayed()
        composeTestRule
            .onNodeWithText(string(R.string.customers_job_unassigned))
            .assertIsDisplayed()
        composeTestRule.onNodeWithTag(jobDetailsTechnicianTag("member-1")).assertDoesNotExist()
    }

    @Test
    fun presentsTheJobsStatusAndTheVisitsStatusSeparately() {
        render(details = job())

        // Two state machines, so both are presented rather than one standing in for the other
        // (`BR-058`, `BR-059`, `BR-074`).
        composeTestRule
            .onNodeWithText(string(R.string.customers_job_status_scheduled))
            .assertIsDisplayed()
        composeTestRule
            .onNodeWithText(string(R.string.visit_status_en_route))
            .assertIsDisplayed()
    }

    @Test
    fun presentsTheRepresentedVisitsScheduleAndTheJobsAddressAndCustomer() {
        render(details = job())

        composeTestRule.onNodeWithTag(JobDetailsScheduleTag).assertIsDisplayed()
        composeTestRule.onNodeWithTag(JobDetailsAddressTag).assertIsDisplayed()
        // The Job's preserved snapshot, as one line (`BR-056`).
        composeTestRule
            .onNodeWithText("987 Cedar Lane, Ottawa, ON K1A 0B1")
            .assertIsDisplayed()
        composeTestRule.onNodeWithTag(JobDetailsCustomerTag).assertIsDisplayed()
        composeTestRule.onNodeWithText("Martha Reynolds").assertIsDisplayed()
        composeTestRule.onNodeWithText("Furnace repair").assertIsDisplayed()
    }

    @Test
    fun saysAJobWithNoVisitIsNotScheduled() {
        render(details = job().copy(selectedVisit = null, technicians = emptyList()))

        composeTestRule
            .onNodeWithText(string(R.string.job_details_not_scheduled))
            .assertIsDisplayed()
        composeTestRule.onNodeWithTag(JobDetailsUnassignedTag).assertIsDisplayed()
    }

    @Test
    fun showsTheFirstReadBeforeTheJobArrives() {
        render(state = JobDetailsUiState(jobId = JOB_ID, isLoading = true))

        composeTestRule.onNodeWithTag(JobDetailsLoadingTag).assertIsDisplayed()
        composeTestRule.onNodeWithTag(JobDetailsContentTag).assertDoesNotExist()
    }

    @Test
    fun reportsAFailedReadAndRetriesIt() {
        var retried = false
        render(
            state = JobDetailsUiState(
                jobId = JOB_ID,
                failureReason = CustomersFailureReason.NETWORK,
            ),
            onRetry = { retried = true },
        )

        composeTestRule.onNodeWithTag(JobDetailsFailureTag).assertIsDisplayed()
        composeTestRule.onNodeWithTag(JobDetailsRetryTag).performClick()

        assertTrue("The failure's retry action must ask for the Job again", retried)
    }

    private fun render(
        details: JobDetails? = null,
        state: JobDetailsUiState? = null,
        canUpdateJob: Boolean = false,
        canViewTechnicians: Boolean = false,
        onRetry: () -> Unit = {},
        onRetryActivity: () -> Unit = {},
        onOpenCustomer: (String) -> Unit = {},
        onOpenInMaps: (CustomerJobAddress) -> Unit = {},
        onLoadAssignableTechnicians: () -> Unit = {},
        onChangeJobStatus: (JobStatus) -> Unit = {},
        onAddActivityText: (String) -> Unit = {},
        onRescheduleVisit: (Instant, Instant) -> Unit = { _, _ -> },
        onAssignTechnicians: (List<TechnicianAssignment>) -> Unit = {},
        onConfirmPendingAction: () -> Unit = {},
        onDismissPendingAction: () -> Unit = {},
        onDismissActionMessage: () -> Unit = {},
    ) {
        val screenState = state ?: JobDetailsUiState(jobId = JOB_ID, details = details)
        composeTestRule.setContent {
            ServoraTheme {
                JobDetailsScreen(
                    state = screenState,
                    canUpdateJob = canUpdateJob,
                    canViewTechnicians = canViewTechnicians,
                    onRetry = onRetry,
                    onRetryActivity = onRetryActivity,
                    onOpenCustomer = onOpenCustomer,
                    onOpenInMaps = onOpenInMaps,
                    onLoadAssignableTechnicians = onLoadAssignableTechnicians,
                    onChangeJobStatus = onChangeJobStatus,
                    onAddActivityText = onAddActivityText,
                    onRescheduleVisit = onRescheduleVisit,
                    onAssignTechnicians = onAssignTechnicians,
                    onConfirmPendingAction = onConfirmPendingAction,
                    onDismissPendingAction = onDismissPendingAction,
                    onDismissActionMessage = onDismissActionMessage,
                )
            }
        }
    }

    @Test
    fun offersNoManagementActionToACallerWithoutTheCapability() {
        render(details = job(), canUpdateJob = false, canViewTechnicians = true)

        // A hidden button is not authorization, but an action nobody may perform is not one to offer
        // either (`BR-006`, `BR-007`). The Job's status is still presented — it is a read, not an
        // action — it simply does not act.
        composeTestRule.onNodeWithTag(JobDetailsStatusActionTag).assertDoesNotExist()
        composeTestRule
            .onNodeWithText(string(R.string.customers_job_status_scheduled))
            .assertIsDisplayed()
        composeTestRule.onNodeWithTag(JobDetailsRescheduleActionTag).assertDoesNotExist()
        composeTestRule.onNodeWithTag(JobDetailsManageTechniciansActionTag).assertDoesNotExist()
    }

    @Test
    fun placesEachActionWithTheDataItAffects() {
        render(details = job(), canUpdateJob = true, canViewTechnicians = true)

        // The Job's status action sits with the Job's status, the reschedule with the represented
        // Visit's schedule, and the crew's management with the technicians (`BR-059`, `BR-066`).
        composeTestRule.onNodeWithTag(JobDetailsStatusActionTag).assertIsDisplayed()
        composeTestRule.onNodeWithTag(JobDetailsVisitActionsTag).assertIsDisplayed()
        composeTestRule.onNodeWithTag(JobDetailsRescheduleActionTag).assertIsDisplayed()
        composeTestRule.onNodeWithTag(JobDetailsTechniciansTag).assertIsDisplayed()
        composeTestRule.onNodeWithTag(JobDetailsManageTechniciansActionTag).assertIsDisplayed()
    }

    @Test
    fun doesNotOfferVisitActionsForAJobWithNoVisit() {
        render(
            details = job().copy(selectedVisit = null),
            canUpdateJob = true,
            canViewTechnicians = true,
        )

        // A reschedule edits the represented Visit and a crew is stated on one, so a Job with no Visit
        // is offered neither (`BR-051`, `BR-068`).
        composeTestRule.onNodeWithTag(JobDetailsRescheduleActionTag).assertDoesNotExist()
        composeTestRule.onNodeWithTag(JobDetailsManageTechniciansActionTag).assertDoesNotExist()
    }

    @Test
    fun statesTheJobNumberOnceAndLeavesTheTitleItsOwn() {
        render(details = job())

        // `BR-052` gives the Job a number and a title, and the header states each of them once: the
        // number is not repeated inside the title.
        composeTestRule
            .onAllNodesWithText(string(R.string.job_details_job_number_format, 1042))
            .assertCountEquals(1)
        composeTestRule.onAllNodesWithText("Furnace repair").assertCountEquals(1)
    }

    @Test
    fun offersTheJobsOwnStatusChipAsTheControlThatChangesIt() {
        var selected: JobStatus? = null
        render(
            details = job().copy(
                status = JobStatus.PENDING_REVIEW,
                allowedStatusTransitions = listOf(JobStatus.COMPLETED),
            ),
            canUpdateJob = true,
            onChangeJobStatus = { status -> selected = status },
        )

        // The status the user would change *is* the control: the chip the header presents wears the
        // Job's current status, and it is the thing that is tapped.
        composeTestRule.onNodeWithTag(JobDetailsStatusActionTag).assertIsDisplayed()
        composeTestRule
            .onNodeWithText(string(R.string.customers_job_status_pending_review))
            .assertIsDisplayed()

        composeTestRule.onNodeWithTag(JobDetailsStatusActionTag).performClick()
        // The menu offers what `BR-058` permits and nothing else: the client holds no second copy of
        // the lifecycle, so no arbitrary status is offered, and cancellation is absent while its reason
        // catalogue is an open question (`BR-041`, `BR-064`).
        composeTestRule.onNodeWithTag(jobDetailsStatusOptionTag("CANCELED")).assertDoesNotExist()
        composeTestRule.onNodeWithTag(jobDetailsStatusOptionTag("IN_PROGRESS")).assertDoesNotExist()
        composeTestRule.onNodeWithTag(jobDetailsStatusOptionTag("COMPLETED")).performClick()

        assertEquals(JobStatus.COMPLETED, selected)
    }

    @Test
    fun labelsTheJobsStatusSoItIsNotReadAsTheVisitsStatus() {
        render(details = job(), canUpdateJob = true)

        // The Job's own status is labelled with what it belongs to, so the header's chip and the Visit
        // card's badge are never read as the same state (`BR-028`, `BR-059`).
        composeTestRule
            .onNodeWithText(string(R.string.job_details_job_status_label))
            .assertIsDisplayed()
        composeTestRule.onNodeWithTag(JobDetailsStatusActionTag).assertIsDisplayed()
    }

    @Test
    fun statesTheStatusTheJobIsInWhenTheControlOpens() {
        render(
            details = job().copy(
                status = JobStatus.PENDING_REVIEW,
                allowedStatusTransitions = listOf(JobStatus.COMPLETED),
            ),
            canUpdateJob = true,
        )

        composeTestRule.onNodeWithTag(JobDetailsStatusActionTag).performClick()

        // The menu opens on the status the Job is in and then offers what `BR-058` permits for it: the
        // status the Job already holds is stated rather than offered, and no other status is a choice
        // the client invents (`BR-041`).
        composeTestRule.onNodeWithTag(JobDetailsStatusCurrentTag).assertIsDisplayed()
        composeTestRule.onNodeWithTag(jobDetailsStatusOptionTag("COMPLETED")).assertIsDisplayed()
        composeTestRule.onNodeWithTag(jobDetailsStatusOptionTag("SCHEDULED")).assertDoesNotExist()
    }

    @Test
    fun labelsTheVisitsDateAndKeepsTheVisitsStatusOnTheCardsOwnBadge() {
        // The Visit is `SCHEDULED` while the Job is `PENDING_REVIEW`: the card states the Visit's date
        // under a date label, so the Visit's status is stated once — by the card's own badge (`BR-074`).
        render(
            details = job().copy(
                status = JobStatus.PENDING_REVIEW,
                allowedStatusTransitions = emptyList(),
                selectedVisit = visit().copy(status = VisitStatus.SCHEDULED),
            ),
        )

        composeTestRule
            .onNodeWithText(string(R.string.job_details_visit_date_label))
            .assertIsDisplayed()
        composeTestRule
            .onAllNodesWithText(string(R.string.visit_status_scheduled))
            .assertCountEquals(1)
        // The Job's status is stated separately, under its own label (`BR-058`, `BR-059`).
        composeTestRule
            .onAllNodesWithText(string(R.string.customers_job_status_pending_review))
            .assertCountEquals(1)
    }

    @Test
    fun disablesReschedulingWhenTheBackendSaysTheVisitCannotBeRescheduled() {
        render(
            details = job().copy(selectedVisit = visit().copy(reschedulable = false)),
            canUpdateJob = true,
        )

        // `BR-073` permits a reschedule only while the Visit is `SCHEDULED`, and that answer comes
        // from the API rather than from a second copy of the rule.
        composeTestRule.onNodeWithTag(JobDetailsRescheduleActionTag).assertIsNotEnabled()
    }

    @Test
    fun opensTheCustomerTheJobBelongsTo() {
        var opened: String? = null
        render(details = job(), onOpenCustomer = { customerId -> opened = customerId })

        composeTestRule.onNodeWithTag(JobDetailsCustomerTag).performClick()

        assertEquals("customer-1", opened)
    }

    @Test
    fun offersTheAddressToTheDeviceAndMarksTheRowAsOpeningAMap() {
        var opened: CustomerJobAddress? = null
        render(details = job(), onOpenInMaps = { address -> opened = address })

        // The design ends the address row with the navigation glyph, so the row says what the tap will
        // do before it is tapped (`Figma/src/screens/JobDetails.tsx`).
        composeTestRule.onNodeWithTag(JobDetailsAddressMapTag).assertIsDisplayed()

        composeTestRule.onNodeWithTag(JobDetailsAddressTag).performClick()

        assertEquals("987 Cedar Lane", opened?.addressLine1)
    }

    @Test
    fun doesNotMakeAnAddressRowAControlWhenTheJobHasNoAddress() {
        var opened: CustomerJobAddress? = null
        // A Job with no address has nothing to navigate to, so the row is not a control (`BR-056`).
        render(details = job().copy(address = null), onOpenInMaps = { address -> opened = address })

        composeTestRule.onNodeWithText(string(R.string.job_details_no_address)).assertIsDisplayed()
        composeTestRule.onNodeWithTag(JobDetailsAddressMapTag).assertDoesNotExist()
        composeTestRule.onNodeWithTag(JobDetailsAddressTag).performClick()
        assertNull(opened)
    }

    @Test
    fun asksForTheTechniciansWhenTheAssignActionIsUsed() {
        var requested = false
        render(
            details = job(),
            canUpdateJob = true,
            canViewTechnicians = true,
            onLoadAssignableTechnicians = { requested = true },
        )

        composeTestRule.onNodeWithTag(JobDetailsManageTechniciansActionTag).performClick()

        assertTrue("Choosing a crew needs the technicians the API offers", requested)
        composeTestRule.onNodeWithTag(JobDetailsAssignmentSheetTag).assertIsDisplayed()
    }

    @Test
    fun statesTheWholeCrewWithTheLeadTheUserChose() {
        var confirmed: List<TechnicianAssignment>? = null
        render(
            details = job(),
            canUpdateJob = true,
            canViewTechnicians = true,
            state = JobDetailsUiState(
                jobId = JOB_ID,
                details = job(),
                assignableTechnicians = listOf(
                    AssignableTechnician("member-1", "Mike Lead"),
                    AssignableTechnician("member-2", "Sarah Moreau"),
                    AssignableTechnician("member-3", "John Tremblay"),
                ),
            ),
            onAssignTechnicians = { assignments -> confirmed = assignments },
        )

        composeTestRule.onNodeWithTag(JobDetailsManageTechniciansActionTag).performClick()
        // The current Lead leaves the crew and another technician takes over: one explicit action
        // that states the whole crew (`BR-069`).
        composeTestRule.onNodeWithTag(jobDetailsAssignmentCandidateTag("member-1")).performClick()
        composeTestRule.onNodeWithTag(jobDetailsAssignmentCandidateTag("member-3")).performClick()
        composeTestRule.onNodeWithTag(jobDetailsAssignmentLeadTag("member-3")).performClick()
        composeTestRule.onNodeWithTag(JobDetailsAssignmentConfirmTag).performClick()

        assertEquals(
            listOf("member-2:TECHNICIAN", "member-3:LEAD"),
            confirmed?.map { "${it.membershipId}:${it.role}" }?.sorted(),
        )
    }

    @Test
    fun putsTheConflictsTheApiReportedToTheUserBeforeApplyingThem() {
        var confirmed = false
        val conflicts = listOf(
            ScheduleConflict(
                visitId = "visit-9",
                jobNumber = 1043,
                technicianName = "Mike Lead",
                scheduledStart = "2026-09-15T13:00:00.000Z",
                scheduledEnd = "2026-09-15T15:00:00.000Z",
            ),
        )
        render(
            details = job(),
            canUpdateJob = true,
            state = JobDetailsUiState(
                jobId = JOB_ID,
                details = job(),
                pendingConfirmation = PendingJobAction.Reschedule(
                    scheduledStart = Instant.parse("2026-09-15T13:00:00Z"),
                    scheduledEnd = Instant.parse("2026-09-15T15:00:00Z"),
                    conflicts = conflicts,
                ),
            ),
            onConfirmPendingAction = { confirmed = true },
        )

        // `BR-070` requires the conflicting visit, the technician and the time to be shown before the
        // user confirms.
        composeTestRule.onNodeWithTag(JobDetailsConflictDialogTag).assertIsDisplayed()
        composeTestRule.onNodeWithText("#1043", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithTag(JobDetailsConflictConfirmTag).performClick()

        assertTrue("Confirming the conflict must apply the change", confirmed)
    }

    @Test
    fun reportsARefusedActionWithWhatTheApiSaid() {
        var released = false
        // The report is a Snackbar, so the test drives the clock instead of letting it run: a report
        // that dismissed itself between the action and the assertion would make the assertion depend
        // on timing (`qa.md` §6).
        composeTestRule.mainClock.autoAdvance = false
        render(
            details = job(),
            canUpdateJob = true,
            state = JobDetailsUiState(
                jobId = JOB_ID,
                details = job(),
                actionFailure = JobActionFailure.JOB_REVIEW_CONDITION_NOT_MET,
            ),
            onDismissActionMessage = { released = true },
        )
        composeTestRule.mainClock.advanceTimeBy(1_000)

        // A refusal says what the API said, and it waits for the user rather than disappearing: the
        // Job on screen shows no change, because the change did not happen (`BR-001`).
        composeTestRule.onNodeWithTag(JobDetailsActionMessageTag).assertIsDisplayed()
        composeTestRule
            .onNodeWithText(string(R.string.job_action_error_review_condition))
            .assertIsDisplayed()

        composeTestRule.onNodeWithText(string(R.string.job_action_dismiss)).performClick()
        composeTestRule.mainClock.advanceTimeBy(1_000)

        assertTrue("Dismissing the refusal must release the report", released)
    }

    @Test
    fun reportsACompletedActionInATransientReport() {
        // The Job the API answered with already presents the change, so what the action did is
        // reported by a Snackbar rather than left standing in the layout (`BR-001`).
        composeTestRule.mainClock.autoAdvance = false
        render(
            details = job(),
            canUpdateJob = true,
            state = JobDetailsUiState(
                jobId = JOB_ID,
                details = job(),
                completedAction = JobActionKind.RESCHEDULE,
            ),
        )
        composeTestRule.mainClock.advanceTimeBy(1_000)

        composeTestRule.onNodeWithTag(JobDetailsActionMessageTag).assertIsDisplayed()
        composeTestRule
            .onNodeWithText(string(R.string.job_action_done_reschedule))
            .assertIsDisplayed()
    }

    private fun string(resId: Int, vararg args: Any): String =
        ApplicationProvider.getApplicationContext<Context>().getString(resId, *args)

    @Test
    fun rendersTheJobActivityAsATimelineWithVisitContextAndMetadata() {
        render(
            details = job(),
            state = JobDetailsUiState(
                jobId = JOB_ID,
                details = job(),
                activity = listOf(
                    activityEvent(
                        id = "event-1",
                        kind = JobActivityKind.VISIT_NOTE_ADDED,
                        visitSequence = 2,
                        actorName = "John Smith",
                        body = "Found a damaged capacitor.",
                    ),
                ),
            ),
        )

        composeTestRule.onNodeWithTag(JobActivityTag).assertIsDisplayed()
        composeTestRule.onNodeWithTag(jobActivityEventTag("event-1")).assertIsDisplayed()
        // The note's own text is the primary entry, and the Visit context is stated beside the member
        // (`BR-080`).
        composeTestRule.onNodeWithText("Found a damaged capacitor.").assertIsDisplayed()
        composeTestRule.onNodeWithText("Visit 2", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("John Smith", substring = true).assertIsDisplayed()
    }

    @Test
    fun doesNotShowAVisitLabelForJobLevelActivity() {
        render(
            details = job(),
            state = JobDetailsUiState(
                jobId = JOB_ID,
                details = job(),
                activity = listOf(
                    activityEvent(
                        id = "event-1",
                        kind = JobActivityKind.JOB_STATUS_CHANGED,
                        visitSequence = null,
                        toStatus = "SCHEDULED",
                        body = null,
                    ),
                ),
            ),
        )

        // A Job-level event states no Visit: within the entry, no text carries a Visit label.
        composeTestRule.onNodeWithTag(jobActivityEventTag("event-1")).assertIsDisplayed()
        composeTestRule
            .onNodeWithText(
                string(
                    R.string.job_activity_job_status_changed,
                    string(R.string.customers_job_status_scheduled),
                ),
            )
            .assertIsDisplayed()
        composeTestRule
            .onAllNodes(
                hasText("Visit", substring = true) and
                    hasAnyAncestor(hasTestTag(jobActivityEventTag("event-1"))),
            )
            .assertCountEquals(0)
    }

    @Test
    fun showsTheEmptyStateWhenTheJobHasNoActivity() {
        render(
            details = job(),
            state = JobDetailsUiState(jobId = JOB_ID, details = job(), activity = emptyList()),
        )

        composeTestRule.onNodeWithTag(JobActivityEmptyTag).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.job_activity_empty_title)).assertIsDisplayed()
    }

    @Test
    fun floatingActivityActionAddsATextOnlyUpdate() {
        var update: String? = null
        render(
            details = job(),
            state = JobDetailsUiState(jobId = JOB_ID, details = job(), activity = emptyList()),
            canUpdateJob = true,
            onAddActivityText = { update = it },
        )

        composeTestRule.onNodeWithTag(JobDetailsAddActivityTag).assertIsDisplayed()
        composeTestRule.onNodeWithTag(JobDetailsAddActivityTag).performClick()

        composeTestRule.onNodeWithTag(JobDetailsActivityTextTag).assertIsDisplayed()
        composeTestRule.onAllNodesWithText("Photo", substring = true).assertCountEquals(0)
        composeTestRule.onAllNodesWithText("Audio", substring = true).assertCountEquals(0)

        composeTestRule.onNodeWithTag(JobDetailsActivityTextTag)
            .performTextInput("  Replaced the air filter.  ")
        composeTestRule.onNodeWithText(string(R.string.job_activity_save_update)).performClick()

        assertEquals("Replaced the air filter.", update)
    }

    private companion object {
        const val JOB_ID = "job-1"
    }
}

/**
 * The Job these tests present: one represented Visit carrying three technicians, one of whom is the
 * Lead, which is the shape the assignment model allows (`BR-068`).
 */
private fun job() = JobDetails(
    id = "job-1",
    jobNumber = 1042,
    title = "Furnace repair",
    description = "Customer reports the furnace is not producing heat.",
    status = JobStatus.SCHEDULED,
    allowedStatusTransitions = listOf(JobStatus.IN_PROGRESS),
    version = 7,
    customerId = "customer-1",
    customerName = "Martha Reynolds",
    address = CustomerJobAddress(
        propertyName = "Cedar Lane Building",
        addressLine1 = "987 Cedar Lane",
        addressLine2 = null,
        city = "Ottawa",
        province = "ON",
        postalCode = "K1A 0B1",
        country = "Canada",
    ),
    selectedVisit = visit(),
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
        JobDetailsTechnician(
            membershipId = "member-3",
            name = "John Tremblay",
            role = AssignmentRole.TECHNICIAN,
        ),
    ),
)

/** The represented Visit of the fixture: `EN_ROUTE`, which `BR-073` does not allow rescheduling. */
private fun visit() = JobDetailsVisit(
    id = "visit-1",
    status = VisitStatus.EN_ROUTE,
    scheduledStart = "2026-09-14T13:00:00.000Z",
    scheduledEnd = "2026-09-14T15:00:00.000Z",
    version = 2,
    reschedulable = false,
)

private fun activityEvent(
    id: String = "event-1",
    kind: JobActivityKind = JobActivityKind.VISIT_NOTE_ADDED,
    recordedAt: String = "2026-09-14T14:14:00.000Z",
    actorName: String? = "John Smith",
    visitSequence: Int? = 2,
    toStatus: String? = null,
    body: String? = "Found a damaged capacitor.",
) = JobActivityEvent(
    id = id,
    kind = kind,
    recordedAt = recordedAt,
    actorName = actorName,
    visitSequence = visitSequence,
    fromStatus = null,
    toStatus = toStatus,
    technicianName = null,
    roleCode = null,
    previousRoleCode = null,
    outcomeCode = null,
    outcomeSummary = null,
    body = body,
)
