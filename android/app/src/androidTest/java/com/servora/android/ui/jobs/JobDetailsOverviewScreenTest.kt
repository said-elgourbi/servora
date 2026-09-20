package com.servora.android.ui.jobs

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.servora.android.R
import com.servora.android.data.customers.CustomersFailureReason
import com.servora.android.data.jobs.JobAudioPlaybackProgress
import com.servora.android.data.jobs.JobPhotoImages
import com.servora.android.data.jobs.QueuedVisitFieldAction
import com.servora.android.data.jobs.QueuedVisitNote
import com.servora.android.domain.model.AssignmentRole
import com.servora.android.domain.model.CustomerJobAddress
import com.servora.android.domain.model.EvidencePhase
import com.servora.android.domain.model.JobActivityEvent
import com.servora.android.domain.model.JobActivityKind
import com.servora.android.domain.model.JobContactPerson
import com.servora.android.domain.model.JobCustomerContact
import com.servora.android.domain.model.JobDetails
import com.servora.android.domain.model.JobDetailsTechnician
import com.servora.android.domain.model.JobDetailsVisit
import com.servora.android.domain.model.JobDetailsVisitSummary
import com.servora.android.domain.model.JobStatus
import com.servora.android.domain.model.TechnicianAssignment
import com.servora.android.domain.model.VisitOutcome
import com.servora.android.domain.model.VisitStatus
import com.servora.android.ui.theme.ServoraTheme
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What the redesigned Job Details screen presents, and how it separates the Job from its Visits
 * (`BR-047`, `BR-059`, `BR-080`, `BR-081`).
 *
 * The screen exists because the account of a Job's every Visit could not be told apart from the Visit on
 * screen. These tests check exactly that separation: the Job's own information is stated as the Job's, the
 * Visit that represents it has its own section — labelled for when that field attempt is for rather than
 * for its place on the page (`BR-072`, `BR-081`) — and the Job Activity section groups each Visit's
 * updates under that Visit: one foldable group per Visit, headed `Visit N · date`, whose revealed content
 * is headed by a card stating that Visit, with the Job's own events in a group of their own.
 *
 * They are Compose device tests, so the product owner runs them (`qa.md` §7.3): the agent compiles them
 * (`assembleDebugAndroidTest`) and leaves the run to the device.
 */
@RunWith(AndroidJUnit4::class)
class JobDetailsOverviewScreenTest {

    @get:Rule val composeTestRule = createComposeRule()

    @Test
    fun statesTheJobsOwnInformationAsTheJobs() {
        render(details = overviewJob())

        composeTestRule.onNodeWithTag(JobOverviewSectionTag).assertIsDisplayed()
        composeTestRule
            .onNodeWithText(string(R.string.job_details_overview_label))
            .assertIsDisplayed()
        composeTestRule
            .onNodeWithText(string(R.string.job_details_job_number_format, 1042))
            .assertIsDisplayed()
        composeTestRule.onNodeWithText("Furnace repair").assertIsDisplayed()
        composeTestRule.onNodeWithText("Blower motor is noisy.").assertIsDisplayed()
        composeTestRule.onNodeWithTag(JobDetailsCustomerTag).assertIsDisplayed()
        composeTestRule.onNodeWithText("Martha Reynolds").assertIsDisplayed()
        composeTestRule.onNodeWithTag(JobDetailsAddressTag).assertIsDisplayed()
        composeTestRule
            .onNodeWithText("987 Cedar Lane, Ottawa, ON K1A 0B1")
            .assertIsDisplayed()
        // The Job's status is the Job's, and the represented Visit's is the Visit's: two state machines
        // presented apart (`BR-058`, `BR-059`, `BR-074`). The Visit's own status is asked for by where it
        // sits, because the Visit's activity group states it as well and the two must not be confused.
        composeTestRule
            .onNodeWithText(string(R.string.customers_job_status_scheduled))
            .assertIsDisplayed()
        composeTestRule
            .onNode(
                hasText(string(R.string.visit_status_en_route)) and
                    hasAnyAncestor(hasTestTag(JobCurrentVisitSectionTag)),
            )
            .assertIsDisplayed()
    }

    @Test
    fun leadsWithThePrimaryContactPhoneAndKeepsTheOtherNumbersFolded() {
        // `BR-095`: the Job card presents **one** phone — the effective primary contact's — and keeps every
        // other way of reaching the customer behind a disclosure that starts closed, so the number a
        // technician came for is the one on screen (`BR-012`).
        render(
            details = overviewJob().copy(
                customerContactDetails = JobCustomerContact(
                    phone = "+15145550142",
                    email = null,
                    notes = null,
                    contacts = listOf(
                        JobContactPerson(
                            firstName = "John",
                            lastName = "Smith",
                            phone = "+15551234567",
                            email = "john@example.com",
                            isPrimary = true,
                        ),
                        JobContactPerson(
                            firstName = "Marie",
                            lastName = "Tremblay",
                            phone = "+15559876543",
                            email = null,
                        ),
                    ),
                ),
            ),
        )

        composeTestRule.onNodeWithText("+15551234567").assertIsDisplayed()
        composeTestRule.onNodeWithTag(JobDetailsCustomerPrimaryBadgeTag).assertIsDisplayed()
        composeTestRule.onNodeWithTag(JobDetailsCustomerOtherContactsToggleTag).assertHasClickAction()
        // The customer's own general line and the other contact person are folded away: nothing about
        // them is on screen until the disclosure is opened.
        composeTestRule.onNodeWithText("+15145550142").assertDoesNotExist()
        composeTestRule.onNodeWithText("Marie Tremblay").assertDoesNotExist()

        composeTestRule.onNodeWithTag(JobDetailsCustomerOtherContactsToggleTag).performClick()

        composeTestRule.onNodeWithText("+15145550142").assertExists()
        composeTestRule.onNodeWithText("Marie Tremblay").assertExists()
        composeTestRule.onNodeWithText("+15559876543").assertExists()
    }

    @Test
    fun marksTheCustomersOwnPhoneWhenNoContactPersonIsFlagged() {
        // Zero primary contacts is a legal state (`BR-095`): the customer is then its own primary, so its
        // own number is the one the card leads with — with the badge — and every contact person is folded
        // away behind it.
        render(
            details = overviewJob().copy(
                customerContactDetails = JobCustomerContact(
                    phone = "+15145550142",
                    email = null,
                    notes = null,
                    contacts = listOf(
                        JobContactPerson(
                            firstName = "John",
                            lastName = "Smith",
                            phone = "+15551234567",
                            email = null,
                        ),
                    ),
                ),
            ),
        )

        composeTestRule.onNodeWithText("+15145550142").assertIsDisplayed()
        composeTestRule.onNodeWithTag(JobDetailsCustomerPrimaryBadgeTag).assertIsDisplayed()
        composeTestRule.onNodeWithText("John Smith").assertDoesNotExist()

        composeTestRule.onNodeWithTag(JobDetailsCustomerOtherContactsToggleTag).performClick()

        composeTestRule.onNodeWithText("John Smith").assertExists()
        composeTestRule.onNodeWithText("+15551234567").assertExists()
    }

    @Test
    fun presentsNoDisclosureWhenThereIsNoOtherWayOfReachingTheCustomer() {
        // No contact person and no second number: there is nothing to fold, so the card states the
        // customer's own number as its primary one and draws no disclosure (`BR-095`, `BR-012`).
        render(
            details = overviewJob().copy(
                customerContactDetails = JobCustomerContact(
                    phone = "+15145550142",
                    email = null,
                    notes = null,
                ),
            ),
        )

        composeTestRule.onNodeWithText("+15145550142").assertIsDisplayed()
        composeTestRule.onNodeWithTag(JobDetailsCustomerPrimaryBadgeTag).assertIsDisplayed()
        composeTestRule.onNodeWithTag(JobDetailsCustomerOtherContactsTag).assertDoesNotExist()
    }

    @Test
    fun givesTheVisitThatRepresentsTheJobItsOwnSectionAndOpensItsActivityGroupByDefault() {
        render(details = overviewJob())

        composeTestRule.onNodeWithTag(JobCurrentVisitSectionTag).assertIsDisplayed()
        // The section is labelled for what the Visit it represents **is**: this fixture's Visit is under
        // way, so it is the current one, and a Visit the clock has not reached yet is labelled for its own
        // day instead (`BR-072`, `BR-074`).
        composeTestRule
            .onNodeWithText(string(R.string.job_details_current_visit_label))
            .assertIsDisplayed()
        composeTestRule.onNodeWithTag(JobDetailsScheduleTag).assertIsDisplayed()
        // The represented Visit's crew, and only its crew: the earlier Visit's technician is not shown
        // here (`BR-068`, `BR-081`). The crew is asked for inside the section, because the Visit's own
        // activity group states the same crew below it.
        composeTestRule
            .onNode(
                hasTestTag(jobDetailsTechnicianTag("member-1")) and
                    hasAnyAncestor(hasTestTag(JobCurrentVisitSectionTag)),
            )
            .assertIsDisplayed()
        composeTestRule
            .onNode(hasText("Mike Lead") and hasAnyAncestor(hasTestTag(JobCurrentVisitSectionTag)))
            .assertIsDisplayed()
        composeTestRule.onNodeWithTag(jobDetailsTechnicianTag("member-9")).assertDoesNotExist()

        // The represented Visit's own group in the Job Activity section is open without a tap, and it
        // holds that Visit's own event; the earlier Visit's group stays folded (`BR-012`, `BR-080`).
        composeTestRule.onNodeWithTag(JobActivitySectionTag).assertIsDisplayed()
        composeTestRule.onNodeWithTag(jobActivityVisitBodyTag(VISIT_TWO_ID)).assertIsDisplayed()
        composeTestRule.onNodeWithTag(jobActivityVisitBodyTag(VISIT_ONE_ID)).assertDoesNotExist()
        composeTestRule.onNodeWithTag(jobActivityEventTag("visit-2-note")).assertIsDisplayed()
    }

    @Test
    fun headsEachVisitsGroupWithItsNumberAndItsDate() {
        render(details = overviewJob())

        // One group per Visit, both present: the Job's earlier field attempt is stated as its own group
        // rather than hidden (`BR-047`, `BR-071`).
        composeTestRule.onNodeWithTag(JobActivitySectionTag).assertIsDisplayed()
        composeTestRule
            .onNodeWithText(
                string(
                    R.string.section_count_format,
                    string(R.string.job_activity_label),
                    overviewActivity().size,
                ),
            )
            .assertIsDisplayed()
        composeTestRule.onNodeWithTag(jobActivityVisitGroupTag(VISIT_ONE_ID)).assertIsDisplayed()
        composeTestRule.onNodeWithTag(jobActivityVisitGroupTag(VISIT_TWO_ID)).assertIsDisplayed()

        // The heading is `Visit N · date`: the number says which field attempt it is, which is what tells
        // two Visits of similar dates apart, and the date is what the group was for (`BR-052`, `BR-072`).
        composeTestRule
            .onNodeWithText(
                string(
                    R.string.job_activity_visit_title_format,
                    1,
                    scheduledDate("2026-09-07T13:00:00.000Z"),
                ),
            )
            .assertIsDisplayed()
        composeTestRule
            .onNodeWithText(
                string(
                    R.string.job_activity_visit_title_format,
                    2,
                    scheduledDate("2026-09-14T13:00:00.000Z"),
                ),
            )
            .assertIsDisplayed()

        // The folded heading also states what the Visit's status is and who is on its crew (`BR-068`).
        composeTestRule
            .onNode(
                hasText("Dave Past") and hasAnyAncestor(hasTestTag(jobActivityVisitToggleTag(VISIT_ONE_ID))),
                useUnmergedTree = true,
            )
            .assertIsDisplayed()
        composeTestRule
            .onNode(
                hasText(string(R.string.visit_status_completed)) and
                    hasAnyAncestor(hasTestTag(jobActivityVisitToggleTag(VISIT_ONE_ID))),
                useUnmergedTree = true,
            )
            .assertIsDisplayed()
        // Folded by default, so nothing the group holds is drawn until it is opened (`BR-012`).
        composeTestRule.onNodeWithTag(jobActivityVisitBodyTag(VISIT_ONE_ID)).assertDoesNotExist()
    }

    @Test
    fun statesWhenTheRepresentedVisitIsForRatherThanCallingEveryVisitCurrent() {
        // The section presents the Visit the read selected (`BR-081`) and states when that field attempt
        // is for, as of the device's own clock: work scheduled for tomorrow is not happening now
        // (`BR-072`, `BR-074`).
        val today = LocalDate.now(ZoneId.systemDefault())
        render(details = overviewJobWithRepresentedVisitOn(today.plusDays(1)))

        composeTestRule.onNodeWithTag(JobCurrentVisitSectionTag).assertIsDisplayed()
        composeTestRule
            .onNodeWithText(string(R.string.job_details_tomorrow_visit_label))
            .assertIsDisplayed()
        composeTestRule
            .onNodeWithText(string(R.string.job_details_current_visit_label))
            .assertDoesNotExist()
    }

    @Test
    fun statesARepresentedVisitThatHasPassedAsThePreviousOne() {
        // The read represents a Visit that is behind the clock when the Job has nothing scheduled ahead of
        // it (`BR-081`), and the section says so rather than calling it current.
        val today = LocalDate.now(ZoneId.systemDefault())
        render(details = overviewJobWithRepresentedVisitOn(today.minusDays(1)))

        composeTestRule.onNodeWithTag(JobCurrentVisitSectionTag).assertIsDisplayed()
        composeTestRule
            .onNodeWithText(string(R.string.job_details_previous_visit_label))
            .assertIsDisplayed()
        composeTestRule
            .onNodeWithText(string(R.string.job_details_current_visit_label))
            .assertDoesNotExist()
    }

    @Test
    fun headsAVisitsOwnActivityWithACardStatingThatVisit() {
        render(details = overviewJob())

        composeTestRule.onNodeWithTag(jobActivityVisitToggleTag(VISIT_ONE_ID)).performClick()

        // What the group reveals is headed by one card stating the Visit's own facts — the day it was for,
        // the window it was scheduled for, what it resulted in and the crew assigned to it — and that
        // Visit's entries follow it (`BR-047`, `BR-068`, `BR-072`, `BR-077`, `BR-080`).
        val card = jobActivityVisitCardTag(VISIT_ONE_ID)
        composeTestRule.onNodeWithTag(card).assertIsDisplayed()
        composeTestRule
            .onNode(hasTestTag(jobActivityVisitDateTag(VISIT_ONE_ID)) and hasAnyAncestor(hasTestTag(card)))
            .assertIsDisplayed()
        composeTestRule
            .onNode(hasTestTag(jobActivityVisitTimeTag(VISIT_ONE_ID)) and hasAnyAncestor(hasTestTag(card)))
            .assertIsDisplayed()
        // The outcome the Visit holds, named beside the schedule it resulted from: the completed earlier
        // field attempt says what it resulted in, and the label is the API's own code localized
        // (`BR-078`, `BR-028`).
        composeTestRule
            .onNode(
                hasTestTag(jobActivityVisitOutcomeTag(VISIT_ONE_ID)) and hasAnyAncestor(hasTestTag(card)),
            )
            .assertIsDisplayed()
        composeTestRule
            .onNode(
                hasText(string(R.string.job_activity_outcome_resolved)) and
                    hasAnyAncestor(hasTestTag(jobActivityVisitOutcomeTag(VISIT_ONE_ID))),
                useUnmergedTree = true,
            )
            .assertIsDisplayed()
        composeTestRule
            .onNode(
                hasTestTag(jobDetailsTechnicianTag("member-9")) and hasAnyAncestor(hasTestTag(card)),
            )
            .assertIsDisplayed()
        composeTestRule
            .onNode(hasText("Dave Past") and hasAnyAncestor(hasTestTag(card)))
            .assertIsDisplayed()
    }

    @Test
    fun statesNoOutcomeForAVisitThatHoldsNone() {
        // The outcome is the Visit's **own current** outcome and not the history its activity holds
        // (`BR-079`): a Visit that holds none states none, rather than being given the outcome of another
        // Visit or one it no longer has (`BR-042`).
        val job = overviewJob()
        render(
            details = job.copy(
                visits = job.visits.map { visit ->
                    if (visit.id == VISIT_ONE_ID) visit.copy(outcome = null) else visit
                },
            ),
        )

        composeTestRule.onNodeWithTag(jobActivityVisitToggleTag(VISIT_ONE_ID)).performClick()

        composeTestRule.onNodeWithTag(jobActivityVisitBodyTag(VISIT_ONE_ID)).assertIsDisplayed()
        composeTestRule.onNodeWithTag(jobActivityVisitDateTag(VISIT_ONE_ID)).assertIsDisplayed()
        composeTestRule.onNodeWithTag(jobActivityVisitOutcomeTag(VISIT_ONE_ID)).assertDoesNotExist()
    }

    @Test
    fun saysInsideAVisitsOwnGroupWhenNothingHasBeenRecordedOnIt() {
        // Every Visit keeps its group, and a Visit no one has written anything on says so instead of
        // reading as a Visit the page left out (`BR-042`, `BR-080`).
        render(
            state = JobDetailsUiState(
                jobId = OVERVIEW_JOB_ID,
                details = overviewJob(),
                activity = overviewJobWideEvents(),
            ),
        )

        composeTestRule.onNodeWithTag(jobActivityVisitEmptyTag(VISIT_TWO_ID)).assertIsDisplayed()
        composeTestRule
            .onNodeWithText(string(R.string.job_details_visit_activity_empty))
            .assertIsDisplayed()
    }

    @Test
    fun expandingAVisitRevealsItsSummaryAndItsOwnActivityAndCollapsingHidesThemAgain() {
        render(details = overviewJob())

        val toggle = jobActivityVisitToggleTag(VISIT_ONE_ID)
        composeTestRule.onNodeWithTag(toggle).performClick()

        composeTestRule.onNodeWithTag(jobActivityVisitBodyTag(VISIT_ONE_ID)).assertIsDisplayed()
        // The summary: the Visit's full date, the window it is scheduled for, and the crew assigned to it
        // (`BR-068`, `BR-072`).
        composeTestRule.onNodeWithTag(jobActivityVisitDateTag(VISIT_ONE_ID)).assertIsDisplayed()
        composeTestRule.onNodeWithTag(jobActivityVisitTimeTag(VISIT_ONE_ID)).assertIsDisplayed()
        composeTestRule
            .onNode(
                hasText(
                    scheduledTimeRange("2026-09-07T13:00:00.000Z", "2026-09-07T15:00:00.000Z"),
                ) and hasAnyAncestor(hasTestTag(jobActivityVisitBodyTag(VISIT_ONE_ID))),
                useUnmergedTree = true,
            )
            .assertIsDisplayed()
        composeTestRule
            .onNodeWithText(string(R.string.job_details_assigned_technicians_label))
            .assertIsDisplayed()
        composeTestRule
            .onNode(
                hasText("Dave Past") and hasAnyAncestor(hasTestTag(jobActivityVisitBodyTag(VISIT_ONE_ID))),
                useUnmergedTree = true,
            )
            .assertIsDisplayed()
        // That Visit's own activity, and only that Visit's: the represented Visit's event is in its own
        // group and is never drawn twice (`BR-080`).
        composeTestRule.onNodeWithTag(jobActivityEventTag("visit-1-note")).assertIsDisplayed()
        composeTestRule
            .onAllNodesWithTag(jobActivityEventTag("visit-1-note"))
            .assertCountEquals(1)

        composeTestRule.onNodeWithTag(toggle).performClick()
        composeTestRule.onNodeWithTag(jobActivityVisitBodyTag(VISIT_ONE_ID)).assertDoesNotExist()
        composeTestRule.onNodeWithTag(jobActivityEventTag("visit-1-note")).assertDoesNotExist()
    }

    @Test
    fun opensAndClosesEachVisitIndependentlyAndKeepsTheHeadingTheSameSize() {
        render(details = overviewJob())

        val firstGroup = jobActivityVisitToggleTag(VISIT_ONE_ID)
        val secondGroup = jobActivityVisitToggleTag(VISIT_TWO_ID)
        val before = composeTestRule.onNodeWithTag(firstGroup).getBoundsInRoot()

        // The represented Visit's group is open without a tap, and the earlier Visit's is not
        // (`BR-012`, `BR-081`).
        composeTestRule.onNodeWithTag(jobActivityVisitBodyTag(VISIT_TWO_ID)).assertIsDisplayed()
        composeTestRule.onNodeWithTag(jobActivityVisitBodyTag(VISIT_ONE_ID)).assertDoesNotExist()

        composeTestRule.onNodeWithTag(firstGroup).performClick()

        // Opening a Visit does not reflow the heading it was opened from, and leaves the other group
        // exactly as it was (`BR-012`).
        composeTestRule.onNodeWithTag(jobActivityVisitBodyTag(VISIT_ONE_ID)).assertIsDisplayed()
        composeTestRule.onNodeWithTag(secondGroup).assertIsDisplayed()
        composeTestRule.onNodeWithTag(jobActivityVisitBodyTag(VISIT_TWO_ID)).assertIsDisplayed()
        assertEquals(before, composeTestRule.onNodeWithTag(firstGroup).getBoundsInRoot())
    }

    @Test
    fun namesTheExpandActionForEachVisitAndStatesWhetherItIsOpen() {
        render(details = overviewJob())

        // The control is a control, and the action it performs belongs to the Visit it acts on: a screen
        // reader announcing "expand" alone would not say which of two Visits it would open (`BR-028`).
        val firstGroup = composeTestRule.onNodeWithTag(jobActivityVisitToggleTag(VISIT_ONE_ID))
        firstGroup.assertHasClickAction()
        composeTestRule
            .onNodeWithContentDescription(string(R.string.job_activity_visit_expand_label, 1))
            .assertIsDisplayed()
        composeTestRule
            .onNodeWithContentDescription(string(R.string.job_activity_visit_collapse_label, 2))
            .assertIsDisplayed()

        // The heading states whether its Visit is open as well as what opening it does, so the state is
        // announced rather than left to the chevron's direction (`BR-011`, `BR-028`).
        firstGroup.assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.StateDescription,
                string(R.string.job_activity_group_collapsed),
            ),
        )
        composeTestRule
            .onNodeWithTag(jobActivityVisitToggleTag(VISIT_TWO_ID))
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    string(R.string.job_activity_group_expanded),
                ),
            )

        firstGroup.performClick()

        // The same control now states the opposite action, and only for the Visit it opened.
        composeTestRule
            .onNodeWithContentDescription(string(R.string.job_activity_visit_collapse_label, 1))
            .assertIsDisplayed()
        composeTestRule
            .onNodeWithContentDescription(string(R.string.job_activity_visit_collapse_label, 2))
            .assertIsDisplayed()
        firstGroup.assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.StateDescription,
                string(R.string.job_activity_group_expanded),
            ),
        )
    }

    @Test
    fun keepsEachVisitsActivityWithItsVisitAndTheJobsOwnEventsInTheirOwnGroup() {
        render(details = overviewJob())

        // The events that belong to no Visit are the general group's, and no Visit's event is drawn
        // there (`BR-080`).
        composeTestRule.onNodeWithTag(JobActivityGeneralGroupTag).assertIsDisplayed()
        composeTestRule
            .onNodeWithText(string(R.string.job_details_job_updates_label))
            .assertIsDisplayed()
        composeTestRule.onNodeWithTag(jobActivityEventTag("job-1")).assertIsDisplayed()
        composeTestRule.onNodeWithTag(jobActivityEventTag("photo-1")).assertIsDisplayed()
        composeTestRule
            .onNode(
                hasTestTag(jobActivityEventTag("visit-1-note")) and
                    hasAnyAncestor(hasTestTag(JobActivityGeneralBodyTag)),
            )
            .assertDoesNotExist()
        composeTestRule
            .onNode(
                hasTestTag(jobActivityEventTag("visit-2-note")) and
                    hasAnyAncestor(hasTestTag(JobActivityGeneralBodyTag)),
            )
            .assertDoesNotExist()

        // The represented Visit's event is in its own group, and the earlier Visit's is in its own —
        // which is folded until it is asked for (`BR-012`, `BR-080`).
        composeTestRule.onNodeWithTag(jobActivityEventTag("visit-2-note")).assertIsDisplayed()
        composeTestRule.onNodeWithTag(jobActivityEventTag("visit-1-note")).assertDoesNotExist()
        composeTestRule.onNodeWithTag(jobActivityVisitToggleTag(VISIT_ONE_ID)).performClick()
        composeTestRule.onNodeWithTag(jobActivityEventTag("visit-1-note")).assertIsDisplayed()
    }

    @Test
    fun saysWhenTheJobHasNoJobWideEventYetWithoutHidingAVisitsOwn() {
        render(
            state = JobDetailsUiState(
                jobId = OVERVIEW_JOB_ID,
                details = overviewJob(),
                // Every event belongs to a Visit, which is what a Job whose office has recorded nothing
                // of its own yet reports (`BR-080`).
                activity = listOf(overviewVisitNote("visit-2-note", visitSequence = 2)),
            ),
        )

        composeTestRule.onNodeWithTag(JobActivityGeneralEmptyTag).assertIsDisplayed()
        composeTestRule
            .onNodeWithText(string(R.string.job_details_job_updates_empty))
            .assertIsDisplayed()
        // The Visit's own account is still drawn: an empty general group is not an empty page.
        composeTestRule.onNodeWithTag(jobActivityEventTag("visit-2-note")).assertIsDisplayed()
    }

    @Test
    fun drawsEveryUpdateExactlyOnceAcrossTheGroups() {
        render(details = overviewJob())

        // Open every Visit so the whole account is on screen, then check that the one timeline the
        // backend reported is drawn once rather than once per group (`BR-080`, `BR-001`).
        composeTestRule.onNodeWithTag(jobActivityVisitToggleTag(VISIT_ONE_ID)).performClick()

        overviewActivity().forEach { event ->
            composeTestRule.onAllNodesWithTag(jobActivityEventTag(event.id)).assertCountEquals(1)
        }
    }

    @Test
    fun keepsTheHeadingControlsWhenAVisitCarriesLongTechnicianNames() {
        val longCrew = listOf(
            JobDetailsTechnician(
                membershipId = "member-1",
                name = "Alexandra Maximiliana Beauchamp-Stevenson",
                role = AssignmentRole.LEAD,
            ),
            JobDetailsTechnician(
                membershipId = "member-2",
                name = "Bartholomew Fitzgerald-Wellington the Third",
                role = AssignmentRole.TECHNICIAN,
            ),
            JobDetailsTechnician(
                membershipId = "member-3",
                name = "Christopher Constantine Van Der Meer",
                role = AssignmentRole.TECHNICIAN,
            ),
        )
        render(
            details = overviewJob().copy(
                visits = listOf(
                    overviewVisitOne(),
                    overviewVisitTwoSummary().copy(technicians = longCrew),
                ),
            ),
        )

        // The heading wraps its title and its crew, so the status and the disclosure stay on the screen
        // and reachable beside them (`BR-028`, `BR-012`).
        val toggle = composeTestRule.onNodeWithTag(jobActivityVisitToggleTag(VISIT_TWO_ID))
        toggle.assertIsDisplayed()
        toggle.assertHasClickAction()
        composeTestRule
            .onNodeWithContentDescription(string(R.string.job_activity_visit_collapse_label, 2))
            .assertIsDisplayed()
        composeTestRule
            .onNode(
                hasText(string(R.string.visit_status_en_route)) and
                    hasAnyAncestor(hasTestTag(jobActivityVisitToggleTag(VISIT_TWO_ID))),
                useUnmergedTree = true,
            )
            .assertIsDisplayed()
        // The group's summary lists the whole crew, so nothing is lost to the heading's shortening
        // (`BR-068`).
        composeTestRule
            .onNode(
                hasText("Alexandra Maximiliana Beauchamp-Stevenson"),
                useUnmergedTree = true,
            )
            .assertIsDisplayed()
        composeTestRule
            .onNode(
                hasText("Christopher Constantine Van Der Meer"),
                useUnmergedTree = true,
            )
            .assertIsDisplayed()
    }

    @Test
    fun showsTheFirstReadBeforeTheJobArrives() {
        render(state = JobDetailsUiState(jobId = OVERVIEW_JOB_ID, isLoading = true))

        composeTestRule.onNodeWithTag(JobDetailsLoadingTag).assertIsDisplayed()
        composeTestRule.onNodeWithTag(JobDetailsContentTag).assertDoesNotExist()
    }

    @Test
    fun reportsAJobItCouldNotReadAndOffersTheReadAgain() {
        render(
            state = JobDetailsUiState(
                jobId = OVERVIEW_JOB_ID,
                failureReason = CustomersFailureReason.SERVER,
            ),
        )

        composeTestRule.onNodeWithTag(JobDetailsFailureTag).assertIsDisplayed()
        composeTestRule.onNodeWithTag(JobDetailsRetryTag).assertIsDisplayed()
        composeTestRule.onNodeWithTag(JobOverviewSectionTag).assertDoesNotExist()
    }

    @Test
    fun readsTheJobWhileItsActivityIsStillBeingRead() {
        // The Job is on screen as soon as its read answers, and the sections that need the timeline wait
        // for it rather than claiming there is none (`BR-042`).
        render(state = JobDetailsUiState(jobId = OVERVIEW_JOB_ID, details = overviewJob(), activity = null))

        composeTestRule.onNodeWithTag(JobDetailsContentTag).assertIsDisplayed()
        composeTestRule.onNodeWithTag(JobOverviewSectionTag).assertIsDisplayed()
        composeTestRule.onNodeWithTag(JobCurrentVisitSectionTag).assertIsDisplayed()
        composeTestRule.onNodeWithTag(JobActivityGeneralEmptyTag).assertDoesNotExist()
    }

    /**
     * Renders the screen with a Manager's capabilities and the Job a test states.
     *
     * Every callback is defaulted so a test states only what it is about, and no test performs an action
     * here: what the actions do is the ViewModel's, which its own tests cover.
     */
    private fun render(
        details: JobDetails = overviewJob(),
        state: JobDetailsUiState? = null,
        canUpdateJob: Boolean = true,
        canUpdateAssignedVisit: Boolean = true,
        canRecordVisitOutcome: Boolean = true,
        canAddVisitNote: Boolean = true,
        canAddEvidencePhoto: Boolean = true,
        canViewTechnicians: Boolean = true,
        canOpenCustomer: Boolean = true,
    ) {
        val screenState = state ?: JobDetailsUiState(
            jobId = OVERVIEW_JOB_ID,
            details = details,
            // The Job's activity as one timeline, so the sections that split it have something to split
            // (`BR-080`). A test that is about the read's own states passes its own `state`.
            activity = overviewActivity(),
        )
        // Built outside the composition: a test holds the player's answer as a state the screen reads, so
        // no state object is created while the screen composes.
        val audioProgress = mutableStateOf<JobAudioPlaybackProgress?>(null)
        composeTestRule.setContent {
            ServoraTheme {
                JobDetailsOverviewScreen(
                    state = screenState,
                    canUpdateJob = canUpdateJob,
                    canUpdateAssignedVisit = canUpdateAssignedVisit,
                    canRecordVisitOutcome = canRecordVisitOutcome,
                    canAddVisitNote = canAddVisitNote,
                    canAddEvidencePhoto = canAddEvidencePhoto,
                    canViewTechnicians = canViewTechnicians,
                    canOpenCustomer = canOpenCustomer,
                    onRetry = {},
                    onRetryActivity = {},
                    onOpenCustomer = {},
                    onOpenInMaps = {},
                    onLoadAssignableTechnicians = {},
                    onChangeJobStatus = {},
                    onChangeVisitStatus = { _, _, _ -> },
                    onDiscardQueuedVisitAction = {},
                    onDiscardQueuedVisitNote = {},
                    onAddActivityText = {},
                    onRescheduleVisit = { _, _ -> },
                    onAssignTechnicians = {},
                    onConfirmPendingAction = {},
                    onDismissPendingAction = {},
                    onDismissActionMessage = {},
                    onCapturePhoto = {},
                    onChoosePhotos = {},
                    onConfirmCapturedPhoto = { _, _ -> },
                    onDiscardCapturedPhoto = {},
                    onKeepCapturedPhoto = {},
                    onRemovePendingPhoto = {},
                    onSubmitPendingPhotos = {},
                    onDismissPhotoMessage = {},
                    onSavePhoto = {},
                    onSharePhoto = { _, _ -> },
                    onRemoveEvidencePhoto = { _, _ -> },
                    onSavePermissionResult = {},
                    canViewEvidence = false,
                    canRemoveEvidence = false,
                    canAddAudio = false,
                    canRemoveAudioEvidence = false,
                    onSelectAudioPhase = {},
                    onStartAudioRecording = {},
                    onStopAudioRecording = {},
                    onCancelAudioRecording = {},
                    onToggleAudioPlayback = {},
                    onSeekAudioPlayback = { _, _ -> },
                    onAttachAudioNote = {},
                    onRemovePendingAudioNote = {},
                    onRemoveEvidenceAudioNote = { _, _ -> },
                    onMicrophoneDenied = {},
                    onDismissAudioMessage = {},
                    audioProgress = audioProgress,
                    photoImages = JobPhotoImages.None,
                )
            }
        }
    }

    private fun string(resId: Int, vararg args: Any): String =
        ApplicationProvider.getApplicationContext<Context>().getString(resId, *args)

    /**
     * The date a Visit's group heads itself with, in the device's language and time zone — the way the
     * screen formats a Visit's date (`BR-028`, `BR-072`).
     *
     * It is computed here from the instant the read reported, so the test states that Visit's own date
     * rather than assuming one language's spelling of it.
     */
    private fun scheduledDate(scheduledStart: String): String =
        Instant.parse(scheduledStart)
            .atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(deviceLocale()))

    /** The window a Visit's summary states: its scheduled start and end, in the device's language. */
    private fun scheduledTimeRange(scheduledStart: String, scheduledEnd: String): String {
        val formatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(deviceLocale())
        val zone = ZoneId.systemDefault()
        return string(
            R.string.job_details_visit_time_range,
            Instant.parse(scheduledStart).atZone(zone).format(formatter),
            Instant.parse(scheduledEnd).atZone(zone).format(formatter),
        )
    }

    /** The language the device is set to, which is the one the screen formats a Visit with (`BR-028`). */
    private fun deviceLocale(): Locale {
        val configuration =
            ApplicationProvider.getApplicationContext<Context>().resources.configuration
        return configuration.locales.takeIf { locales -> !locales.isEmpty }?.get(0) ?: Locale.ROOT
    }
}

private const val OVERVIEW_JOB_ID = "job-1"
private const val VISIT_ONE_ID = "visit-1"
private const val VISIT_TWO_ID = "visit-2"

/**
 * The Job these tests read: two Visits, the second of which the read represents, and activity on both
 * the Job and each Visit (`BR-047`, `BR-080`, `BR-081`).
 */
private fun overviewJob() = JobDetails(
    id = OVERVIEW_JOB_ID,
    jobNumber = 1042,
    title = "Furnace repair",
    description = "Blower motor is noisy.",
    status = JobStatus.SCHEDULED,
    allowedStatusTransitions = listOf(JobStatus.IN_PROGRESS),
    version = 7,
    customerId = "customer-1",
    customerName = "Martha Reynolds",
    customerContactDetails = JobCustomerContact(
        phone = "+15145550142",
        email = null,
        notes = null,
    ),
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
        id = VISIT_TWO_ID,
        status = VisitStatus.EN_ROUTE,
        scheduledStart = "2026-09-14T13:00:00.000Z",
        scheduledEnd = "2026-09-14T15:00:00.000Z",
        version = 2,
        reschedulable = true,
        allowedStatusTransitions = listOf(VisitStatus.ON_SITE, VisitStatus.SCHEDULED),
        fieldActionable = true,
    ),
    technicians = listOf(
        JobDetailsTechnician(
            membershipId = "member-1",
            name = "Mike Lead",
            role = AssignmentRole.LEAD,
        ),
    ),
    visits = listOf(
        JobDetailsVisitSummary(
            id = VISIT_ONE_ID,
            sequence = 1,
            status = VisitStatus.COMPLETED,
            // The earlier field attempt was completed and holds the outcome it recorded, which is what
            // lets its group state what resulted from it (`BR-077`, `BR-078`).
            outcome = VisitOutcome.RESOLVED,
            scheduledStart = "2026-09-07T13:00:00.000Z",
            scheduledEnd = "2026-09-07T15:00:00.000Z",
            version = 4,
            technicians = listOf(
                JobDetailsTechnician(
                    membershipId = "member-9",
                    name = "Dave Past",
                    role = AssignmentRole.LEAD,
                ),
            ),
        ),
        JobDetailsVisitSummary(
            id = VISIT_TWO_ID,
            sequence = 2,
            status = VisitStatus.EN_ROUTE,
            // Still under way, so it holds no outcome at all (`BR-077`, `BR-079`).
            outcome = null,
            scheduledStart = "2026-09-14T13:00:00.000Z",
            scheduledEnd = "2026-09-14T15:00:00.000Z",
            version = 2,
            technicians = listOf(
                JobDetailsTechnician(
                    membershipId = "member-1",
                    name = "Mike Lead",
                    role = AssignmentRole.LEAD,
                ),
            ),
        ),
    ),
)

/** The Visit the read represents, as its own row (`BR-081`). */
private fun overviewVisitTwoSummary() = overviewJob().visits.single { visit -> visit.id == VISIT_TWO_ID }

/** The Job's earlier Visit, as its own row (`BR-047`). */
private fun overviewVisitOne() = overviewJob().visits.single { visit -> visit.id == VISIT_ONE_ID }

/**
 * The Job these tests read with its represented Visit moved to [day], scheduled and not under way
 * (`BR-072`, `BR-081`).
 *
 * The Visit the read represents and the Visit the read lists are the same field attempt, so both are
 * moved together and neither can be presented as the other (`BR-047`).
 */
private fun overviewJobWithRepresentedVisitOn(day: LocalDate): JobDetails {
    val zone = ZoneId.systemDefault()
    val start = day.atTime(13, 0).atZone(zone).toInstant().toString()
    val end = day.atTime(15, 0).atZone(zone).toInstant().toString()
    val base = overviewJob()
    // The fixture always carries the Visit the read represents, so the move is stated as one.
    val represented = requireNotNull(base.selectedVisit)
    return base.copy(
        selectedVisit = represented.copy(
            status = VisitStatus.SCHEDULED,
            scheduledStart = start,
            scheduledEnd = end,
        ),
        visits = base.visits.map { visit ->
            if (visit.id == VISIT_TWO_ID) {
                visit.copy(status = VisitStatus.SCHEDULED, scheduledStart = start, scheduledEnd = end)
            } else {
                visit
            }
        },
    )
}

/** One Visit-level Activity event (`BR-080`). */
private fun overviewVisitNote(id: String, visitSequence: Int) = JobActivityEvent(
    id = id,
    kind = JobActivityKind.VISIT_NOTE_ADDED,
    recordedAt = "2026-09-14T14:14:00.000Z",
    actorName = "John Smith",
    visitSequence = visitSequence,
    fromStatus = null,
    toStatus = null,
    technicianName = null,
    roleCode = null,
    previousRoleCode = null,
    outcomeCode = null,
    outcomeSummary = null,
    body = "Found a damaged capacitor.",
)

/**
 * The Job's activity as its read reports it: its own events and each Visit's, newest first (`BR-080`).
 *
 * The order is the API's, so a group the screen draws is a slice of one timeline rather than a list the
 * client re-sorted.
 */
private fun overviewActivity() =
    listOf(overviewVisitNote("visit-2-note", visitSequence = 2)) +
        overviewJobWideEvents() +
        listOf(overviewVisitNote("visit-1-note", visitSequence = 1))

/** The Job's own events, which belong to no Visit (`BR-080`). */
private fun overviewJobWideEvents() = listOf(
    JobActivityEvent(
        id = "photo-1",
        kind = JobActivityKind.JOB_PHOTO_ADDED,
        recordedAt = "2026-09-14T13:40:00.000Z",
        actorName = "John Smith",
        visitSequence = null,
        fromStatus = null,
        toStatus = null,
        technicianName = null,
        roleCode = null,
        previousRoleCode = null,
        outcomeCode = null,
        outcomeSummary = null,
        body = null,
        photoId = "photo-1",
        photoPhase = "DURING_WORK",
    ),
    JobActivityEvent(
        id = "job-1",
        kind = JobActivityKind.JOB_STATUS_CHANGED,
        recordedAt = "2026-09-14T13:00:00.000Z",
        actorName = "Martha Manager",
        visitSequence = null,
        fromStatus = "NEW",
        toStatus = "SCHEDULED",
        technicianName = null,
        roleCode = null,
        previousRoleCode = null,
        outcomeCode = null,
        outcomeSummary = null,
        body = null,
    ),
)
