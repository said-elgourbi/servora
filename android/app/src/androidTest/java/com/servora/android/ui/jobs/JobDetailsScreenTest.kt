package com.servora.android.ui.jobs

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import coil3.request.ImageRequest
import com.servora.android.R
import com.servora.android.data.customers.CustomersFailureReason
import com.servora.android.data.jobs.JobActionFailure
import com.servora.android.data.jobs.JobPhotoImages
import com.servora.android.data.offline.ReadSource
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
import com.servora.android.domain.model.EvidencePhase
import com.servora.android.domain.model.JobPhotoSyncState
import com.servora.android.domain.model.JobStatus
import com.servora.android.domain.model.PendingJobPhoto
import com.servora.android.domain.model.VisitStatus
import com.servora.android.ui.theme.ServoraTheme
import java.io.File
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

    @Test
    fun showsAPendingPhotoWithItsPhaseNoteAndARemoveAction() {
        render(
            details = job(),
            state = JobDetailsUiState(
                jobId = JOB_ID,
                details = job(),
                pendingPhotos = listOf(pendingPhoto()),
            ),
        )

        composeTestRule.onNodeWithTag(JobPhotoTrayTag).assertIsDisplayed()
        composeTestRule.onNodeWithTag(jobPhotoPendingTileTag("photo-1")).assertIsDisplayed()
        // The phase the photo was taken in, and the note the technician typed.
        composeTestRule.onNodeWithText("During work").assertIsDisplayed()
        composeTestRule.onNodeWithText("Sawdust on the belt").assertIsDisplayed()
        // The X exists while the photo is unsaved (`BR-027`, §9).
        composeTestRule.onNodeWithTag(jobPhotoPendingRemoveTag("photo-1")).assertIsDisplayed()
        composeTestRule.onNodeWithTag(JobPhotoTraySubmitTag).assertIsDisplayed()
    }

    @Test
    fun reportsTheUploadStateOfAQueuedPhotoInsteadOfOfferingRemoval() {
        render(
            details = job(),
            state = JobDetailsUiState(
                jobId = JOB_ID,
                details = job(),
                pendingPhotos = listOf(pendingPhoto(submitted = true)),
                photoUploads = mapOf("photo-1" to JobPhotoSyncState.QUEUED),
            ),
        )

        composeTestRule.onNodeWithTag(jobPhotoPendingStateTag("photo-1")).assertIsDisplayed()
        composeTestRule.onNodeWithText("Waiting to upload").assertIsDisplayed()
        // The backend may already hold it, so the device no longer offers to delete it (`BR-014`).
        composeTestRule.onNodeWithTag(jobPhotoPendingRemoveTag("photo-1")).assertDoesNotExist()
        // The API may still accept it, so it is not the technician's to discard either (`D6c`).
        composeTestRule.onNodeWithTag(jobPhotoRefusedDiscardTag("photo-1")).assertDoesNotExist()
    }

    @Test
    fun offersTheDiscardAPhotoTheBackendRefusedIsLeftWith() {
        var discarded: String? = null
        render(
            details = job(),
            state = JobDetailsUiState(
                jobId = JOB_ID,
                details = job(),
                pendingPhotos = listOf(pendingPhoto(submitted = true)),
                photoUploads = mapOf("photo-1" to JobPhotoSyncState.REFUSED),
            ),
            onRemovePendingPhoto = { photoId -> discarded = photoId },
        )

        // The refusal is what the action is offered beside, and what says why it exists (`D6c`).
        composeTestRule.onNodeWithText("Refused by the server").assertIsDisplayed()
        composeTestRule.onNodeWithTag(jobPhotoRefusedDiscardTag("photo-1")).performClick()

        assertEquals("photo-1", discarded)
        // The refusal is terminal, so the X a draft carries is not drawn as well: one action clears it.
        composeTestRule.onNodeWithTag(jobPhotoPendingRemoveTag("photo-1")).assertDoesNotExist()
    }

    @Test
    fun showsTheReviewPanelWithTheThreePhasesAsLargeOptions() {
        render(
            details = job(),
            state = JobDetailsUiState(
                jobId = JOB_ID,
                details = job(),
                capturedPhoto = pendingPhoto(),
                photoPhase = EvidencePhase.DURING_WORK,
            ),
        )

        composeTestRule.onNodeWithTag(JobPhotoReviewSheetTag).assertIsDisplayed()
        // The preview, the optional note and the two decisions the technician can take.
        composeTestRule.onNodeWithTag(JobPhotoReviewPreviewTag).assertIsDisplayed()
        composeTestRule.onNodeWithTag(JobPhotoReviewNoteTag).assertIsDisplayed()
        composeTestRule.onNodeWithTag(JobPhotoReviewConfirmTag).assertIsDisplayed()
        composeTestRule.onNodeWithTag(JobPhotoReviewDiscardTag).assertIsDisplayed()

        // Three large phase buttons, never radio buttons (`BR-012`).
        composeTestRule.onNodeWithTag(EvidencePhaseSelectorTag).assertIsDisplayed()
        composeTestRule.onNodeWithTag(evidencePhaseOptionTag(EvidencePhase.BEFORE_WORK))
            .assertIsDisplayed()
        composeTestRule.onNodeWithTag(evidencePhaseOptionTag(EvidencePhase.DURING_WORK))
            .assertIsDisplayed()
        composeTestRule.onNodeWithTag(evidencePhaseOptionTag(EvidencePhase.AFTER_WORK))
            .assertIsDisplayed()
        composeTestRule.onNodeWithText("Before work").assertIsDisplayed()
        composeTestRule.onNodeWithText("After work").assertIsDisplayed()
    }

    @Test
    fun statesHowManyPhotosTheJobHasAndStartsTheGalleryFolded() {
        render(
            details = job(),
            state = JobDetailsUiState(
                jobId = JOB_ID,
                details = job(),
                activity = listOf(
                    activityEvent(
                        id = "photo-1",
                        kind = JobActivityKind.JOB_PHOTO_ADDED,
                        visitSequence = null,
                        body = "Panel before the repair",
                        photoId = "photo-1",
                        photoPhase = "BEFORE_WORK",
                    ),
                    activityEvent(id = "event-2"),
                ),
            ),
        )

        // The heading states how many photos the Job has, and the gallery starts folded so the account
        // of what happened reads first (`BR-012`, `BR-080`).
        composeTestRule.onNodeWithTag(JobPhotoGalleryTag).assertIsDisplayed()
        composeTestRule
            .onNodeWithText(
                string(R.string.section_count_format, string(R.string.job_photo_gallery_label), 1),
            )
            .assertIsDisplayed()
        composeTestRule.onNodeWithTag(jobPhotoGalleryTileTag("photo-1")).assertDoesNotExist()
        // Folded, it still previews the photos it counts, so evidence is visible without opening it.
        composeTestRule
            .onNodeWithTag(JobPhotoGalleryPreviewTag, useUnmergedTree = true)
            .assertIsDisplayed()
    }

    @Test
    fun foldsTheGalleryAwayAndShowsItAgainFromItsHeading() {
        render(
            details = job(),
            state = JobDetailsUiState(
                jobId = JOB_ID,
                details = job(),
                activity = listOf(photoEvent()),
            ),
        )

        // The whole heading is the control, so the chevron and the label are one target (`BR-012`), and
        // the chevron's own description names the action rather than the state (`BR-028`).
        composeTestRule
            .onNodeWithContentDescription(string(R.string.job_photo_gallery_expand))
            .assertIsDisplayed()
        composeTestRule.onNodeWithTag(JobPhotoGalleryToggleTag).performClick()

        // The strip replaces the preview: it is the same collection, drawn in full.
        composeTestRule.onNodeWithTag(jobPhotoGalleryTileTag("photo-1")).assertIsDisplayed()
        composeTestRule
            .onNodeWithContentDescription(string(R.string.job_photo_gallery_collapse))
            .assertIsDisplayed()
        composeTestRule
            .onNodeWithTag(JobPhotoGalleryPreviewTag, useUnmergedTree = true)
            .assertDoesNotExist()

        composeTestRule.onNodeWithTag(JobPhotoGalleryToggleTag).performClick()
        composeTestRule.onNodeWithTag(jobPhotoGalleryTileTag("photo-1")).assertDoesNotExist()
    }

    @Test
    fun drawsAPhotoActivityAsThePhotoItRecordedWithItsBadgeAndItsNote() {
        val images = RecordingJobPhotoImages(ApplicationProvider.getApplicationContext())
        render(
            state = JobDetailsUiState(
                jobId = JOB_ID,
                details = job(),
                activity = listOf(
                    activityEvent(
                        id = "photo-1",
                        kind = JobActivityKind.JOB_PHOTO_ADDED,
                        visitSequence = null,
                        actorName = "Dev Manager",
                        body = "Panel before the repair",
                        photoId = "photo-1",
                        photoPhase = "BEFORE_WORK",
                    ),
                ),
            ),
            photoImages = images,
        )

        // The entry says what happened, the photo it recorded is drawn in the entry itself, and the
        // phase is the badge on that photo rather than a second statement in the title (`BR-012`,
        // `BR-015`, `BR-080`).
        composeTestRule
            .onNode(
                hasText(string(R.string.job_photo_activity_added)) and
                    hasAnyAncestor(hasTestTag(jobActivityEventTag("photo-1"))),
            )
            .assertIsDisplayed()
        composeTestRule.onNodeWithTag(jobPhotoActivityPhotoTag("photo-1")).assertIsDisplayed()
        composeTestRule
            .onNode(
                hasTestTag(jobPhotoActivityPhotoTag("photo-1")) and
                    hasText(string(R.string.evidence_phase_before)),
            )
            .assertIsDisplayed()
        // Actor and time are the metadata every other entry carries (`BR-080`).
        composeTestRule
            .onNode(
                hasText("Dev Manager", substring = true) and
                    hasAnyAncestor(hasTestTag(jobActivityEventTag("photo-1"))),
            )
            .assertIsDisplayed()
        // The note is read under the photo it belongs to, and drawn once.
        composeTestRule
            .onNode(
                hasTestTag(jobPhotoActivityNoteTag("photo-1")) and
                    hasText("Panel before the repair"),
            )
            .assertIsDisplayed()
    }

    @Test
    fun expandsALongActivityNoteOnDemandWithoutLosingItsPhoto() {
        // Long enough that the entry has to decide whether the note fits as it is read.
        val note = "Replaced the damaged connector and tested the unit. Everything is operating " +
            "normally. The panel was resealed afterwards and today's readings were written on the " +
            "sheet before we closed it up, so the next visit can compare them."
        render(
            state = JobDetailsUiState(
                jobId = JOB_ID,
                details = job(),
                activity = listOf(photoEvent(body = note)),
            ),
        )

        composeTestRule.onNodeWithTag(jobPhotoActivityNoteTag("photo-1")).assertIsDisplayed()
        composeTestRule.onNodeWithTag(jobPhotoActivityNoteActionTag("photo-1")).assertIsDisplayed()
        composeTestRule.onNodeWithTag(jobPhotoActivityNoteActionTag("photo-1")).performClick()

        // The rest of the note is readable now, the same action puts it back, and the photo the note
        // belongs to is still on screen (`BR-012`, `BR-027`).
        composeTestRule
            .onNode(
                hasText(string(R.string.job_photo_notes_less)) and
                    hasAnyAncestor(hasTestTag(jobActivityEventTag("photo-1"))),
            )
            .assertIsDisplayed()
        composeTestRule.onNodeWithTag(jobPhotoActivityPhotoTag("photo-1")).assertIsDisplayed()
    }

    @Test
    fun opensAnActivityPhotoInTheSameViewerOnThePageThePhotoIsOn() {
        val images = RecordingJobPhotoImages(ApplicationProvider.getApplicationContext())
        render(
            state = JobDetailsUiState(
                jobId = JOB_ID,
                details = job(),
                activity = listOf(
                    // The Activity is newest first, and so is the collection the viewer pages in: the
                    // second entry's photo is the Job's second photo, not a viewer of its own (`D11`).
                    activityEvent(
                        id = "photo-2",
                        kind = JobActivityKind.JOB_PHOTO_ADDED,
                        visitSequence = null,
                        body = null,
                        photoId = "photo-2",
                        photoPhase = "AFTER_WORK",
                    ),
                    photoEvent(),
                ),
                pendingPhotos = listOf(pendingPhoto(photoId = "photo-5")),
            ),
            canViewEvidence = true,
            photoImages = images,
        )

        composeTestRule.onNodeWithTag(jobPhotoActivityPhotoTag("photo-1")).performClick()

        composeTestRule.onNodeWithTag(JobPhotoViewerTag).assertIsDisplayed()
        awaitPhoto()
        composeTestRule.onNodeWithTag(JobPhotoViewerImageTag).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.job_photo_viewer_position, 2, 3))
            .assertIsDisplayed()
        assertTrue(
            "The entry's photo must be the photo the viewer reads, at full size",
            images.fullSizeReads.contains("photo-1"),
        )
        // The other entry's photo has no note, so its entry reserves no caption area for one
        // (`BR-012`).
        composeTestRule.onNodeWithTag(jobPhotoActivityNoteTag("photo-2")).assertDoesNotExist()
    }

    @Test
    fun opensATappedAcceptedPhotoFullSizeAndClosesItAgain() {
        val images = RecordingJobPhotoImages(ApplicationProvider.getApplicationContext())
        render(
            state = JobDetailsUiState(
                jobId = JOB_ID,
                details = job(),
                activity = listOf(
                    activityEvent(
                        id = "photo-1",
                        kind = JobActivityKind.JOB_PHOTO_ADDED,
                        visitSequence = null,
                        body = "Panel before the repair",
                        photoId = "photo-1",
                        photoPhase = "BEFORE_WORK",
                    ),
                ),
            ),
            photoImages = images,
        )

        tapGalleryPhoto("photo-1")

        // The photo itself rather than the tile's preview, with the phase the record states and the
        // whole note (`D4`).
        composeTestRule.onNodeWithTag(JobPhotoViewerTag).assertIsDisplayed()
        awaitPhoto()
        composeTestRule.onNodeWithTag(JobPhotoViewerImageTag).assertIsDisplayed()
        // Scoped to the viewer: the gallery tile and the timeline entry behind it draw the same photo,
        // its badge and its note.
        composeTestRule
            .onNode(hasTestTag(JobPhotoViewerNoteTag) and hasText("Panel before the repair"))
            .assertIsDisplayed()
        composeTestRule
            .onNode(
                hasText(string(R.string.evidence_phase_before)) and
                    hasAnyAncestor(hasTestTag(JobPhotoViewerTag)),
            )
            .assertIsDisplayed()
        assertTrue(
            "The viewer must read the photo the backend holds, not the tile's thumbnail",
            images.fullSizeReads.contains("photo-1"),
        )

        composeTestRule.onNodeWithTag(JobPhotoViewerCloseTag).performClick()
        composeTestRule.onNodeWithTag(JobPhotoViewerTag).assertDoesNotExist()
    }

    @Test
    fun showsWhichPhotoOfTheJobIsOnScreenAndTheTwoEvidenceActions() {
        var savedPhotoId: String? = null
        var sharedPhotoId: String? = null
        var sharedTitle: String? = null
        render(
            state = JobDetailsUiState(
                jobId = JOB_ID,
                details = job(),
                activity = listOf(
                    activityEvent(
                        id = "photo-1",
                        kind = JobActivityKind.JOB_PHOTO_ADDED,
                        visitSequence = null,
                        body = "Panel before the repair",
                        photoId = "photo-1",
                        photoPhase = "BEFORE_WORK",
                    ),
                ),
                pendingPhotos = listOf(pendingPhoto(photoId = "photo-5")),
            ),
            canViewEvidence = true,
            onSavePhoto = { photoId -> savedPhotoId = photoId },
            onSharePhoto = { photoId, title ->
                sharedPhotoId = photoId
                sharedTitle = title
            },
        )

        // The photo the backend holds is the sequence's first page, so the position says so and the two
        // actions act on the photo the technician is looking at (`D11`).
        tapGalleryPhoto("photo-1")
        composeTestRule.onNodeWithTag(JobPhotoViewerTag).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.job_photo_viewer_position, 1, 2))
            .assertIsDisplayed()

        composeTestRule.onNodeWithTag(JobPhotoViewerSaveTag).performClick()
        assertEquals("photo-1", savedPhotoId)

        composeTestRule.onNodeWithTag(JobPhotoViewerShareTag).performClick()
        assertEquals("photo-1", sharedPhotoId)
        assertEquals("Share photo", sharedTitle)

        composeTestRule.onNodeWithTag(JobPhotoViewerCloseTag).performClick()
        composeTestRule.onNodeWithTag(JobPhotoViewerTag).assertDoesNotExist()
    }

    @Test
    fun offersTheRemovalOnlyToASessionThatMayRemoveEvidenceAndConfirmsTheReason() {
        var removedPhotoId: String? = null
        var removedReason: String? = null
        render(
            state = JobDetailsUiState(
                jobId = JOB_ID,
                details = job(),
                activity = listOf(photoEvent()),
            ),
            canViewEvidence = true,
            canRemoveEvidence = true,
            onRemoveEvidencePhoto = { photoId, reason ->
                removedPhotoId = photoId
                removedReason = reason
            },
        )

        tapGalleryPhoto("photo-1")
        composeTestRule.onNodeWithTag(JobPhotoViewerRemoveTag).performClick()

        // Nothing is applied until the manager has stated why (`BR-067`, `BR-089`).
        assertNull(removedPhotoId)
        composeTestRule.onNodeWithTag(JobPhotoRemovalDialogTag).assertIsDisplayed()
        // The action is disabled until a reason is given, because the API refuses a removal without one.
        composeTestRule.onNodeWithTag(JobPhotoRemovalConfirmTag).assertIsNotEnabled()

        composeTestRule.onNodeWithTag(JobPhotoRemovalReasonTag)
            .performTextInput("Photographed the wrong property")
        composeTestRule.onNodeWithTag(JobPhotoRemovalConfirmTag).performClick()

        assertEquals("photo-1", removedPhotoId)
        assertEquals("Photographed the wrong property", removedReason)
    }

    @Test
    fun doesNotOfferTheRemovalToASessionWithoutTheCapability() {
        // The removal is a Manager-level capability of its own (`BR-089`), so a session that may read
        // the evidence is not offered it (`BR-007`, `BR-011`).
        render(
            state = JobDetailsUiState(
                jobId = JOB_ID,
                details = job(),
                activity = listOf(photoEvent()),
            ),
            canViewEvidence = true,
            canRemoveEvidence = false,
        )

        tapGalleryPhoto("photo-1")
        composeTestRule.onNodeWithTag(JobPhotoViewerTag).assertIsDisplayed()
        composeTestRule.onNodeWithTag(JobPhotoViewerRemoveTag).assertDoesNotExist()
    }

    @Test
    fun keepsTheNotesUnderThePhotoAndExpandsALongOneOnDemand() {
        val images = RecordingJobPhotoImages(ApplicationProvider.getApplicationContext())
        // Long enough that the viewer has to decide whether it fits above the action that expands it.
        val note = "Replaced the damaged connector and tested the unit. Everything is operating " +
            "normally. The panel was resealed afterwards and today's readings were written on the " +
            "sheet before we closed it up, so the next visit can compare them."
        render(
            state = JobDetailsUiState(
                jobId = JOB_ID,
                details = job(),
                activity = listOf(photoEvent(body = note)),
            ),
            photoImages = images,
        )

        tapGalleryPhoto("photo-1")

        // The note is a labelled section under the photo (`BR-027`), and what does not fit is reached by
        // an explicit action rather than by taking the photo's room or hiding the rest of the note. Each
        // assertion is scoped to the viewer: the screen behind it still draws its own tiles and timeline.
        composeTestRule.onNodeWithTag(JobPhotoViewerNoteLabelTag).assertIsDisplayed()
        composeTestRule
            .onNode(
                hasText(string(R.string.job_photo_viewer_notes_label)) and
                    hasAnyAncestor(hasTestTag(JobPhotoViewerTag)),
            )
            .assertIsDisplayed()
        composeTestRule.onNodeWithTag(JobPhotoViewerNoteTag).assertIsDisplayed()
        composeTestRule.onNodeWithTag(JobPhotoViewerNoteMoreTag).assertIsDisplayed()

        composeTestRule.onNodeWithTag(JobPhotoViewerNoteMoreTag).performClick()

        // The rest of the note is now readable, and the same action is what puts it back.
        composeTestRule
            .onNode(
                hasText(string(R.string.job_photo_notes_less)) and
                    hasAnyAncestor(hasTestTag(JobPhotoViewerTag)),
            )
            .assertIsDisplayed()
    }

    @Test
    fun drawsNoNoteActionForANoteThatAlreadyFits() {
        val images = RecordingJobPhotoImages(ApplicationProvider.getApplicationContext())
        render(
            state = JobDetailsUiState(
                jobId = JOB_ID,
                details = job(),
                activity = listOf(photoEvent(body = "Sawdust on the belt")),
            ),
            photoImages = images,
        )

        tapGalleryPhoto("photo-1")

        // A short note is read in full: no expansion action, and no room reserved for one (`BR-012`).
        composeTestRule.onNodeWithTag(JobPhotoViewerNoteTag).assertIsDisplayed()
        composeTestRule.onNodeWithTag(JobPhotoViewerNoteMoreTag).assertDoesNotExist()
    }

    @Test
    fun carriesThePhotoChromeInTheTopBarInsteadOfBottomButtons() {
        val images = RecordingJobPhotoImages(ApplicationProvider.getApplicationContext())
        render(
            state = JobDetailsUiState(
                jobId = JOB_ID,
                details = job(),
                activity = listOf(photoEvent(phase = "DURING_WORK")),
                pendingPhotos = listOf(pendingPhoto(photoId = "photo-5")),
            ),
            canViewEvidence = true,
            photoImages = images,
        )

        tapGalleryPhoto("photo-1")

        // One minimal bar: the close action, the position, then the evidence actions; the photo's phase
        // is a badge over the photo itself (`D11`, `D12`, `D13`).
        composeTestRule.onNodeWithTag(JobPhotoViewerCloseTag).assertIsDisplayed()
        composeTestRule.onNodeWithTag(JobPhotoViewerPositionTag).assertIsDisplayed()
        composeTestRule.onNodeWithTag(JobPhotoViewerSaveTag).assertIsDisplayed()
        composeTestRule.onNodeWithTag(JobPhotoViewerShareTag).assertIsDisplayed()
        // The phase is drawn by the viewer, over the photo: the tray and the gallery behind it draw the
        // same badge for their own tiles, so the badge this test is about is the viewer's own.
        composeTestRule
            .onNode(
                hasText(string(R.string.evidence_phase_during)) and
                    hasAnyAncestor(hasTestTag(JobPhotoViewerTag)),
            )
            .assertIsDisplayed()
        // Each action is an icon whose accessible name is the action's own localized label (`BR-028`).
        composeTestRule
            .onNodeWithContentDescription(string(R.string.job_photo_viewer_save))
            .assertIsDisplayed()
        composeTestRule
            .onNodeWithContentDescription(string(R.string.job_photo_viewer_share))
            .assertIsDisplayed()
    }

    @Test
    fun doesNotStartAnEvidenceActionThatIsAlreadyRunning() {
        val images = RecordingJobPhotoImages(ApplicationProvider.getApplicationContext())
        var savedPhotoId: String? = null
        render(
            state = JobDetailsUiState(
                jobId = JOB_ID,
                details = job(),
                activity = listOf(photoEvent()),
                photoExport = JobPhotoExportAction.SAVE,
            ),
            canViewEvidence = true,
            onSavePhoto = { photoId -> savedPhotoId = photoId },
            photoImages = images,
        )

        tapGalleryPhoto("photo-1")

        // The action that is running says so — its glyph gives way to the viewer's own progress — and it
        // cannot be asked for twice, so a slow read is one write rather than two (`D12`, `BR-042`).
        composeTestRule.onNodeWithTag(JobPhotoViewerSaveTag).assertIsNotEnabled()
        composeTestRule.onNodeWithTag(JobPhotoViewerSaveTag).performClick()
        assertNull("a second save is not started while one runs", savedPhotoId)

        // The other action is not offered either: the ViewModel refuses a second evidence action while
        // one is in flight (`D12`, `D13`), so offering it would be a tap that silently does nothing.
        composeTestRule.onNodeWithTag(JobPhotoViewerShareTag).assertIsNotEnabled()
    }

    @Test
    fun reportsASaveInsideTheViewerWhereTheTechnicianIsLooking() {
        val images = RecordingJobPhotoImages(ApplicationProvider.getApplicationContext())
        render(
            state = JobDetailsUiState(
                jobId = JOB_ID,
                details = job(),
                activity = listOf(photoEvent()),
                photoMessage = JobPhotoMessage.SAVED_TO_DEVICE,
            ),
            canViewEvidence = true,
            photoImages = images,
        )

        tapGalleryPhoto("photo-1")

        // The viewer is a full-screen window of its own, so a report drawn by the screen behind it would
        // be a save the technician never saw: the report is drawn inside the viewer (`BR-042`).
        composeTestRule.onNodeWithTag(JobPhotoViewerTag).assertIsDisplayed()
        composeTestRule
            .onNode(
                hasTestTag(JobDetailsActionMessageTag) and
                    hasAnyAncestor(hasTestTag(JobPhotoViewerTag)),
            )
            .assertIsDisplayed()
        composeTestRule
            .onNodeWithText(string(R.string.job_photo_saved_to_device_message))
            .assertIsDisplayed()
    }

    @Test
    fun opensATappedPhotoOnItsOwnPageOfTheJobsPhotos() {
        render(
            state = JobDetailsUiState(
                jobId = JOB_ID,
                details = job(),
                activity = listOf(
                    activityEvent(
                        id = "photo-1",
                        kind = JobActivityKind.JOB_PHOTO_ADDED,
                        visitSequence = null,
                        photoId = "photo-1",
                        photoPhase = "BEFORE_WORK",
                    ),
                ),
                pendingPhotos = listOf(pendingPhoto(photoId = "photo-5")),
            ),
            canViewEvidence = true,
        )

        composeTestRule.onNodeWithTag(jobPhotoPendingTileTag("photo-5")).performClick()

        // The tray's photo is the second page of that same sequence, so a swipe either way continues
        // through what the screen shows around the photo that was tapped (`D11`).
        composeTestRule.onNodeWithTag(JobPhotoViewerTag).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.job_photo_viewer_position, 2, 2))
            .assertIsDisplayed()
    }

    @Test
    fun drawsNoEvidenceActionsForASessionThatMayNotReadEvidence() {
        render(
            state = JobDetailsUiState(
                jobId = JOB_ID,
                details = job(),
                pendingPhotos = listOf(pendingPhoto()),
            ),
            canViewEvidence = false,
        )

        composeTestRule.onNodeWithTag(jobPhotoPendingTileTag("photo-1")).performClick()

        // The photo is still shown; only the two actions that read it back are withheld (`BR-011`).
        composeTestRule.onNodeWithTag(JobPhotoViewerTag).assertIsDisplayed()
        composeTestRule.onNodeWithTag(JobPhotoViewerSaveTag).assertDoesNotExist()
        composeTestRule.onNodeWithTag(JobPhotoViewerShareTag).assertDoesNotExist()
    }

    @Test
    fun opensATappedPendingPhotoFromTheBytesTheDeviceStillHolds() {
        val images = RecordingJobPhotoImages(ApplicationProvider.getApplicationContext())
        render(
            state = JobDetailsUiState(
                jobId = JOB_ID,
                details = job(),
                pendingPhotos = listOf(pendingPhoto()),
            ),
            photoImages = images,
        )

        composeTestRule.onNodeWithTag(jobPhotoPendingTileTag("photo-1")).performClick()

        composeTestRule.onNodeWithTag(JobPhotoViewerTag).assertIsDisplayed()
        awaitPhoto()
        composeTestRule.onNodeWithTag(JobPhotoViewerImageTag).assertIsDisplayed()
        composeTestRule.onNodeWithText("Sawdust on the belt").assertIsDisplayed()
        assertTrue(
            "A photo the backend has not accepted must be read from this device (`§9`)",
            images.localFullSizeReads.contains("app-private/job-photos/user-1/photo-1.jpg"),
        )
    }

    @Test
    fun reportsAPhotoItCannotShowRatherThanDrawingSomethingElse() {
        render(
            state = JobDetailsUiState(
                jobId = JOB_ID,
                details = job(),
                pendingPhotos = listOf(pendingPhoto()),
            ),
            // No previews can be read, which is what a device that cannot decode the bytes, or a
            // session the API refuses, produces (`BR-042`).
            photoImages = JobPhotoImages.None,
        )

        composeTestRule.onNodeWithTag(jobPhotoPendingTileTag("photo-1")).performClick()

        composeTestRule.onNodeWithTag(JobPhotoViewerUnavailableTag).assertIsDisplayed()
        composeTestRule.onNodeWithTag(JobPhotoViewerImageTag).assertDoesNotExist()
    }

    @Test
    fun reportsAPhotoTheStackCouldNotReadRatherThanDrawingSomethingElse() {
        render(
            state = JobDetailsUiState(
                jobId = JOB_ID,
                details = job(),
                pendingPhotos = listOf(pendingPhoto()),
            ),
            // A request exists, so the viewer is drawn and the read is attempted — and the read fails,
            // which is what an API refusal or bytes the device cannot decode produces (`BR-042`).
            photoImages = UnreadableJobPhotoImages(ApplicationProvider.getApplicationContext()),
        )

        composeTestRule.onNodeWithTag(jobPhotoPendingTileTag("photo-1")).performClick()

        composeTestRule.waitUntil(timeoutMillis = PHOTO_LOAD_TIMEOUT) {
            composeTestRule
                .onAllNodesWithTag(JobPhotoViewerUnavailableTag)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeTestRule.onNodeWithTag(JobPhotoViewerUnavailableTag).assertIsDisplayed()
        composeTestRule.onNodeWithTag(JobPhotoViewerImageTag).assertDoesNotExist()
    }

    @Test
    fun zoomsAndPansWithoutLosingThePhotoItsPhaseNoteOrItsCloseAction() {
        val images = RecordingJobPhotoImages(ApplicationProvider.getApplicationContext())
        render(
            state = JobDetailsUiState(
                jobId = JOB_ID,
                details = job(),
                activity = listOf(
                    activityEvent(
                        id = "photo-1",
                        kind = JobActivityKind.JOB_PHOTO_ADDED,
                        visitSequence = null,
                        body = "Panel before the repair",
                        photoId = "photo-1",
                        photoPhase = "BEFORE_WORK",
                    ),
                ),
            ),
            photoImages = images,
        )

        tapGalleryPhoto("photo-1")
        awaitPhoto()

        // The photo is drawn zoomable (`D9`): a double-tap zooms it, and a two-finger pinch zooms it
        // further. What the viewer holds around the photo — its phase, its note and the close action —
        // is outside the gesture surface, so it stays where it was.
        composeTestRule.onNodeWithTag(JobPhotoViewerImageTag).performTouchInput { doubleClick() }
        composeTestRule.onNodeWithTag(JobPhotoViewerImageTag).performTouchInput {
            down(pointerId = 0, position = center - Offset(24f, 0f))
            down(pointerId = 1, position = center + Offset(24f, 0f))
            moveTo(pointerId = 0, position = center - Offset(160f, 0f))
            moveTo(pointerId = 1, position = center + Offset(160f, 0f))
            up(pointerId = 1)
            up(pointerId = 0)
        }
        // Panning is the same gesture one finger makes, and it stays inside the photo's own bounds.
        composeTestRule.onNodeWithTag(JobPhotoViewerImageTag).performTouchInput {
            swipe(start = center, end = center + Offset(120f, 90f))
        }

        composeTestRule.onNodeWithTag(JobPhotoViewerImageTag).assertIsDisplayed()
        // Scoped to the viewer: the gallery tile and the timeline entry behind it draw the same photo,
        // its badge and its note.
        composeTestRule
            .onNode(hasTestTag(JobPhotoViewerNoteTag) and hasText("Panel before the repair"))
            .assertIsDisplayed()
        composeTestRule
            .onNode(
                hasText(string(R.string.evidence_phase_before)) and
                    hasAnyAncestor(hasTestTag(JobPhotoViewerTag)),
            )
            .assertIsDisplayed()
        composeTestRule.onNodeWithTag(JobPhotoViewerCloseTag).assertIsDisplayed()

        // Closing from a zoomed state is the closing it always was: the viewer holds no zoom after it,
        // because the photo and its phase are no more than what the record and the device hold
        // (`BR-001`).
        composeTestRule.onNodeWithTag(JobPhotoViewerCloseTag).performClick()
        composeTestRule.onNodeWithTag(JobPhotoViewerTag).assertDoesNotExist()
    }

    @Test
    fun offersTheOneAddUpdateActionToASessionThatMayUpdateTheJob() {
        render(details = job(), canUpdateJob = true)

        // One action adds anything to the Activity, and it is offered for a Job whether or not a
        // Visit represents it, because a photo is Job-level evidence (`BR-015`, `BR-051`).
        composeTestRule.onNodeWithTag(JobDetailsAddActivityTag).assertIsDisplayed()
        // The timeline itself carries no second way in: the former Add photo action is gone.
        composeTestRule.onAllNodesWithText(string(R.string.job_photo_add_action)).assertCountEquals(0)
    }

    @Test
    fun doesNotOfferTheAddUpdateActionWithoutTheUpdateCapability() {
        render(details = job(), canUpdateJob = false)

        // A hidden action is not authorization, but an action nobody may perform is not offered
        // either (`BR-006`, `BR-007`).
        composeTestRule.onNodeWithTag(JobDetailsAddActivityTag).assertDoesNotExist()
    }

    @Test
    fun saysTheJobAndItsTimelineAreTheLastReportedAnswerWhenTheDeviceAnsweredThem() {
        render(
            state = JobDetailsUiState(
                jobId = JOB_ID,
                details = job(),
                detailsSource = ReadSource.WORKING_SET,
                activity = listOf(activityEvent()),
                activitySource = ReadSource.WORKING_SET,
            ),
        )

        // Offline, both halves of the screen are the last answer the backend reported rather than current
        // ones, and the screen says so instead of presenting a local copy as up to date
        // (`offline-first-architecture.md` §2, §7, `D5`).
        composeTestRule.onNodeWithTag(JobDetailsLastReportedTag).assertIsDisplayed()
        composeTestRule.onNodeWithTag(JobActivityLastReportedTag).assertIsDisplayed()
    }

    @Test
    fun doesNotClaimALocalAnswerWhenTheBackendAnswered() {
        render(details = job())

        composeTestRule.onNodeWithTag(JobDetailsLastReportedTag).assertDoesNotExist()
        composeTestRule.onNodeWithTag(JobActivityLastReportedTag).assertDoesNotExist()
    }

    /**
     * Waits until the image stack has drawn the photo the viewer asked for.
     *
     * The stack reads and decodes asynchronously — that is what its cache and its sampling are
     * (`D4b`) — so the photo is on screen a moment after the tap that opened it, not in the same frame.
     */
    private fun awaitPhoto() {
        composeTestRule.waitUntil(timeoutMillis = PHOTO_LOAD_TIMEOUT) {
            composeTestRule.onAllNodesWithTag(JobPhotoViewerImageTag).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun render(
        details: JobDetails? = null,
        state: JobDetailsUiState? = null,
        canUpdateJob: Boolean = false,
        canAddEvidencePhoto: Boolean = false,
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
        onCapturePhoto: () -> Unit = {},
        onChoosePhotos: () -> Unit = {},
        onConfirmCapturedPhoto: (EvidencePhase, String?) -> Unit = { _, _ -> },
        onDiscardCapturedPhoto: () -> Unit = {},
        onKeepCapturedPhoto: () -> Unit = {},
        onRemovePendingPhoto: (String) -> Unit = {},
        onSubmitPendingPhotos: () -> Unit = {},
        onDismissPhotoMessage: () -> Unit = {},
        onSavePhoto: (String) -> Unit = {},
        onSharePhoto: (String, String) -> Unit = { _, _ -> },
        onRemoveEvidencePhoto: (String, String) -> Unit = { _, _ -> },
        onSavePermissionResult: (Boolean) -> Unit = {},
        canViewEvidence: Boolean = false,
        canRemoveEvidence: Boolean = false,
        canAddAudio: Boolean = false,
        onSelectAudioPhase: (EvidencePhase) -> Unit = {},
        onStartAudioRecording: () -> Unit = {},
        onStopAudioRecording: () -> Unit = {},
        onCancelAudioRecording: () -> Unit = {},
        onAttachAudioNote: (String?) -> Unit = {},
        onRemovePendingAudioNote: (String) -> Unit = {},
        onMicrophoneDenied: () -> Unit = {},
        onDismissAudioMessage: () -> Unit = {},
        photoImages: JobPhotoImages = JobPhotoImages.None,
    ) {
        val screenState = state ?: JobDetailsUiState(jobId = JOB_ID, details = details)
        composeTestRule.setContent {
            ServoraTheme {
                JobDetailsScreen(
                    state = screenState,
                    canUpdateJob = canUpdateJob,
                    canAddEvidencePhoto = canAddEvidencePhoto,
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
                    onCapturePhoto = onCapturePhoto,
                    onChoosePhotos = onChoosePhotos,
                    onConfirmCapturedPhoto = onConfirmCapturedPhoto,
                    onDiscardCapturedPhoto = onDiscardCapturedPhoto,
                    onKeepCapturedPhoto = onKeepCapturedPhoto,
                    onRemovePendingPhoto = onRemovePendingPhoto,
                    onSubmitPendingPhotos = onSubmitPendingPhotos,
                    onDismissPhotoMessage = onDismissPhotoMessage,
                    onSavePhoto = onSavePhoto,
                    onSharePhoto = onSharePhoto,
                    onRemoveEvidencePhoto = onRemoveEvidencePhoto,
                    onSavePermissionResult = onSavePermissionResult,
                    canAddAudio = canAddAudio,
                    onSelectAudioPhase = onSelectAudioPhase,
                    onStartAudioRecording = onStartAudioRecording,
                    onStopAudioRecording = onStopAudioRecording,
                    onCancelAudioRecording = onCancelAudioRecording,
                    onAttachAudioNote = onAttachAudioNote,
                    onRemovePendingAudioNote = onRemovePendingAudioNote,
                    onMicrophoneDenied = onMicrophoneDenied,
                    onDismissAudioMessage = onDismissAudioMessage,
                    canViewEvidence = canViewEvidence,
                    canRemoveEvidence = canRemoveEvidence,
                    photoImages = photoImages,
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
    fun offersEveryDestinationTheBackendReportedAndNothingElse() {
        render(details = pendingReviewJob(), canUpdateJob = true)

        // The status the user would change *is* the control: the chip the header presents wears the
        // Job's current status, and it is the thing that is tapped.
        composeTestRule.onNodeWithTag(JobDetailsStatusActionTag).assertIsDisplayed()
        composeTestRule
            .onNodeWithText(string(R.string.customers_job_status_pending_review))
            .assertIsDisplayed()

        composeTestRule.onNodeWithTag(JobDetailsStatusActionTag).performClick()
        // The menu offers exactly what the backend reported — a Job may be moved to any destination it
        // permits, forwards or backwards — and nothing else: the client holds no second copy of the
        // lifecycle, cancellation is absent while its reason catalogue is open, and `NEW` is reached
        // by reopening a terminal Job rather than from an open one (`BR-041`, `BR-058`, `BR-064`).
        composeTestRule.onNodeWithTag(jobDetailsStatusOptionTag("SCHEDULED")).assertIsDisplayed()
        composeTestRule.onNodeWithTag(jobDetailsStatusOptionTag("IN_PROGRESS")).assertIsDisplayed()
        composeTestRule.onNodeWithTag(jobDetailsStatusOptionTag("COMPLETED")).assertIsDisplayed()
        composeTestRule.onNodeWithTag(jobDetailsStatusOptionTag("CANCELED")).assertDoesNotExist()
        composeTestRule.onNodeWithTag(jobDetailsStatusOptionTag("NEW")).assertDoesNotExist()
    }

    @Test
    fun sendsAnOrdinaryDestinationInTheOneRequestItChose() {
        val selected = mutableListOf<JobStatus>()
        render(
            details = pendingReviewJob(),
            canUpdateJob = true,
            onChangeJobStatus = { status -> selected += status },
        )

        composeTestRule.onNodeWithTag(JobDetailsStatusActionTag).performClick()
        composeTestRule.onNodeWithTag(jobDetailsStatusOptionTag("IN_PROGRESS")).performClick()

        // Every destination is one business operation: the client sends the status the user chose and
        // never walks the lifecycle with a series of requests (`BR-058`, `BR-067`).
        assertEquals(listOf(JobStatus.IN_PROGRESS), selected)
        composeTestRule.onNodeWithTag(JobDetailsStatusConfirmDialogTag).assertDoesNotExist()
    }

    @Test
    fun confirmsClosingAJobBeforeTheOneRequestIsSent() {
        val selected = mutableListOf<JobStatus>()
        render(
            details = pendingReviewJob(),
            canUpdateJob = true,
            onChangeJobStatus = { status -> selected += status },
        )

        composeTestRule.onNodeWithTag(JobDetailsStatusActionTag).performClick()
        composeTestRule.onNodeWithTag(jobDetailsStatusOptionTag("COMPLETED")).performClick()

        // Closing a Job is the user's explicit decision, so it is asked about rather than sent from a
        // tap that closed a menu (`BR-062`), and nothing has been sent while they decide.
        composeTestRule.onNodeWithTag(JobDetailsStatusConfirmDialogTag).assertIsDisplayed()
        composeTestRule
            .onNodeWithText(string(R.string.job_status_confirm_close_title))
            .assertIsDisplayed()
        assertEquals(emptyList<JobStatus>(), selected)

        composeTestRule.onNodeWithTag(JobDetailsStatusConfirmButtonTag).performClick()

        assertEquals(listOf(JobStatus.COMPLETED), selected)
        composeTestRule.onNodeWithTag(JobDetailsStatusConfirmDialogTag).assertDoesNotExist()
    }

    @Test
    fun sendsNothingWhenClosingIsDismissed() {
        val selected = mutableListOf<JobStatus>()
        render(
            details = pendingReviewJob(),
            canUpdateJob = true,
            onChangeJobStatus = { status -> selected += status },
        )

        composeTestRule.onNodeWithTag(JobDetailsStatusActionTag).performClick()
        composeTestRule.onNodeWithTag(jobDetailsStatusOptionTag("COMPLETED")).performClick()
        composeTestRule.onNodeWithText(string(R.string.job_action_cancel)).performClick()

        // Dismissing leaves the Job exactly as it was: a confirmation nobody gave is not a change
        // (`BR-067`).
        assertEquals(emptyList<JobStatus>(), selected)
        composeTestRule.onNodeWithTag(JobDetailsStatusConfirmDialogTag).assertDoesNotExist()
    }

    @Test
    fun confirmsReopeningAClosedJobWithItsOwnCopy() {
        val selected = mutableListOf<JobStatus>()
        render(
            details = job().copy(
                status = JobStatus.COMPLETED,
                allowedStatusTransitions = listOf(JobStatus.NEW),
            ),
            canUpdateJob = true,
            onChangeJobStatus = { status -> selected += status },
        )

        composeTestRule.onNodeWithTag(JobDetailsStatusActionTag).performClick()
        // A terminal Job offers its reopen and nothing else (`BR-063`).
        composeTestRule.onNodeWithTag(jobDetailsStatusOptionTag("NEW")).performClick()

        composeTestRule.onNodeWithTag(JobDetailsStatusConfirmDialogTag).assertIsDisplayed()
        composeTestRule
            .onNodeWithText(string(R.string.job_status_confirm_reopen_title))
            .assertIsDisplayed()

        composeTestRule.onNodeWithTag(JobDetailsStatusConfirmButtonTag).performClick()

        assertEquals(listOf(JobStatus.NEW), selected)
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
        render(details = pendingReviewJob(), canUpdateJob = true)

        composeTestRule.onNodeWithTag(JobDetailsStatusActionTag).performClick()

        // The menu opens on the status the Job is in and then offers what the backend reported for it:
        // the status the Job already holds is stated rather than offered, because standing still is not
        // a transition a client invents (`BR-041`, `BR-058`).
        composeTestRule.onNodeWithTag(JobDetailsStatusCurrentTag).assertIsDisplayed()
        composeTestRule.onNodeWithTag(jobDetailsStatusOptionTag("COMPLETED")).assertIsDisplayed()
        composeTestRule
            .onNodeWithTag(jobDetailsStatusOptionTag("PENDING_REVIEW"))
            .assertDoesNotExist()
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
    fun reportsWhyTheApiRefusedToCloseTheJob() {
        // `BR-062` refuses completion while the Job has an open Visit. The destination is structurally
        // permitted, so the refusal is its own answer and the screen says what it was (`BR-007`,
        // `BR-041`): the status the Job is in has not changed, because the change did not happen.
        composeTestRule.mainClock.autoAdvance = false
        render(
            details = job(),
            canUpdateJob = true,
            state = JobDetailsUiState(
                jobId = JOB_ID,
                details = job(),
                actionFailure = JobActionFailure.JOB_COMPLETION_BLOCKED,
            ),
        )
        composeTestRule.mainClock.advanceTimeBy(1_000)

        composeTestRule.onNodeWithTag(JobDetailsActionMessageTag).assertIsDisplayed()
        composeTestRule
            .onNodeWithText(string(R.string.job_action_error_completion_blocked))
            .assertIsDisplayed()
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

    /**
     * Opens a photo from the Activity gallery, which starts folded (`BR-012`, `BR-080`).
     *
     * The heading is the control that unfolds it, so a test that wants a tile taps the heading first —
     * exactly as the technician does.
     */
    private fun tapGalleryPhoto(photoId: String) {
        composeTestRule.onNodeWithTag(JobPhotoGalleryToggleTag).performClick()
        composeTestRule.onNodeWithTag(jobPhotoGalleryTileTag(photoId)).performClick()
    }

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
    fun theAddUpdateActionOffersANoteAndAPhotoAndSavesTheNote() {
        var update: String? = null
        var captured = false
        render(
            details = job(),
            state = JobDetailsUiState(jobId = JOB_ID, details = job(), activity = emptyList()),
            canUpdateJob = true,
            canAddEvidencePhoto = true,
            onAddActivityText = { update = it },
            onCapturePhoto = { captured = true },
        )

        composeTestRule.onNodeWithTag(JobDetailsAddActivityTag).assertIsDisplayed()
        composeTestRule.onNodeWithTag(JobDetailsAddActivityTag).performClick()

        // One sheet states what kind of update it is, and the note is the kind in effect, so its
        // field is ready without a second choice (`BR-012`, `BR-027`).
        composeTestRule.onNodeWithTag(JobUpdateSheetTag).assertIsDisplayed()
        composeTestRule.onNodeWithTag(JobUpdateNoteKindTag).assertIsDisplayed()
        composeTestRule.onNodeWithTag(JobUpdatePhotoKindTag).assertIsDisplayed()
        composeTestRule.onNodeWithTag(JobUpdateNoteTag).assertIsDisplayed()
        // The kinds are one choice of peers, so the selector states the two this session may add.
        composeTestRule.onNodeWithTag(JobUpdateKindSelectorTag).assertIsDisplayed()
        // Audio is a first-class kind of the sheet's hierarchy and is still not offered: the API has no
        // capability that accepts a recording yet, and an action that cannot be performed is not
        // presented (`BR-042`, `docs/decisions/015-evidence-capabilities.md` D2).
        composeTestRule.onNodeWithTag(JobUpdateAudioKindTag).assertDoesNotExist()
        composeTestRule
            .onAllNodesWithText(string(R.string.job_activity_update_audio))
            .assertCountEquals(0)

        composeTestRule.onNodeWithTag(JobUpdateNoteTag)
            .performTextInput("  Replaced the air filter.  ")
        composeTestRule.onNodeWithTag(JobUpdateSaveTag).performClick()

        assertEquals("Replaced the air filter.", update)
        assertEquals(false, captured)
    }

    @Test
    fun theAddUpdateSheetHandsAPhotoOverToTheCaptureFlow() {
        var captured = false
        render(
            details = job(),
            state = JobDetailsUiState(jobId = JOB_ID, details = job(), activity = emptyList()),
            canUpdateJob = true,
            canAddEvidencePhoto = true,
            onCapturePhoto = { captured = true },
        )

        composeTestRule.onNodeWithTag(JobDetailsAddActivityTag).performClick()
        // The photo kind states where the photo comes from, because there are two sources (`D3`).
        composeTestRule.onNodeWithTag(JobUpdatePhotoKindTag).performClick()
        composeTestRule.onNodeWithTag(JobUpdateTakePhotoTag).performClick()

        // The photo keeps the flow the photo slice owns — camera, review, tray — so the sheet closes
        // and hands over rather than growing a second photo UI (`BR-015`).
        assertTrue(captured)
        composeTestRule.onNodeWithTag(JobUpdateSheetTag).assertDoesNotExist()
    }

    @Test
    fun theAddUpdateSheetHandsAPhotoOverToTheDevicePicker() {
        var picked = false
        render(
            details = job(),
            state = JobDetailsUiState(jobId = JOB_ID, details = job(), activity = emptyList()),
            canUpdateJob = true,
            canAddEvidencePhoto = true,
            onChoosePhotos = { picked = true },
        )

        composeTestRule.onNodeWithTag(JobDetailsAddActivityTag).performClick()
        composeTestRule.onNodeWithTag(JobUpdatePhotoKindTag).performClick()
        composeTestRule.onNodeWithTag(JobUpdateChoosePhotosTag).performClick()

        // The library source hands over to the picker the photo slice owns, which asks for no storage
        // or media permission because it reads only what the technician selects (`D3`).
        assertTrue(picked)
        composeTestRule.onNodeWithTag(JobUpdateSheetTag).assertDoesNotExist()
    }

    @Test
    fun offersTheEvidenceSourcesToASessionWithoutTheJobUpdateCapability() {
        var picked = false
        render(
            details = job(),
            state = JobDetailsUiState(jobId = JOB_ID, details = job(), activity = emptyList()),
            canAddEvidencePhoto = true,
            onChoosePhotos = { picked = true },
        )

        // A default Technician holds the evidence capability and no Job update capability, so the
        // action is offered for the work they do record (`BR-009`, `BR-011`).
        composeTestRule.onNodeWithTag(JobDetailsAddActivityTag).assertIsDisplayed()
        composeTestRule.onNodeWithTag(JobDetailsAddActivityTag).performClick()

        // A note is a Job update, which this session may not write, so only the sources are offered.
        composeTestRule.onNodeWithTag(JobUpdateNoteKindTag).assertDoesNotExist()
        composeTestRule.onNodeWithTag(JobUpdateTakePhotoTag).assertIsDisplayed()
        composeTestRule.onNodeWithTag(JobUpdateChoosePhotosTag).performClick()
        assertTrue(picked)
    }

    @Test
    fun theAddUpdateSheetOffersNoNoteForAJobWithNoRepresentedVisit() {
        val jobWithoutVisit = job().copy(selectedVisit = null)
        render(
            details = jobWithoutVisit,
            state = JobDetailsUiState(
                jobId = JOB_ID,
                details = jobWithoutVisit,
                activity = emptyList(),
            ),
            canUpdateJob = true,
            canAddEvidencePhoto = true,
        )

        composeTestRule.onNodeWithTag(JobDetailsAddActivityTag).performClick()

        // A note belongs to a Visit, so a Job with none can only be given the evidence that is
        // Job-level (`BR-015`, `BR-051`), and the photo sources are what it is offered.
        composeTestRule.onNodeWithTag(JobUpdatePhotoKindTag).assertIsDisplayed()
        composeTestRule.onNodeWithTag(JobUpdateNoteKindTag).assertDoesNotExist()
        composeTestRule.onNodeWithTag(JobUpdateNoteTag).assertDoesNotExist()
        composeTestRule.onNodeWithTag(JobUpdateTakePhotoTag).assertIsDisplayed()
        composeTestRule.onNodeWithTag(JobUpdateChoosePhotosTag).assertIsDisplayed()
    }

    @Test
    fun keepsTheKindsAndThePhotoSourcesInTheirOwnRanks() {
        render(
            details = job(),
            state = JobDetailsUiState(jobId = JOB_ID, details = job(), activity = emptyList()),
            canUpdateJob = true,
            canAddEvidencePhoto = true,
        )

        composeTestRule.onNodeWithTag(JobDetailsAddActivityTag).performClick()

        // The kinds are one choice of peers and a note is the kind in effect, so the note's field is the
        // only control drawn below them (`BR-012`, `BR-027`).
        composeTestRule.onNodeWithTag(JobUpdateKindSelectorTag).assertIsDisplayed()
        composeTestRule.onNodeWithTag(JobUpdateNoteTag).assertIsDisplayed()
        composeTestRule.onAllNodesWithTag(JobUpdateTakePhotoTag).assertCountEquals(0)
        composeTestRule.onAllNodesWithTag(JobUpdateChoosePhotosTag).assertCountEquals(0)

        // Choosing the photo kind replaces the note's controls with the photo's own, and its two sources
        // sit inside the sheet but **outside** the kind selector: they are how a photo is taken, not
        // further kinds of update (`BR-012`).
        composeTestRule.onNodeWithTag(JobUpdatePhotoKindTag).performClick()
        composeTestRule.onNodeWithTag(JobUpdatePhotoSourcesTag).assertIsDisplayed()
        composeTestRule.onNodeWithTag(JobUpdateTakePhotoTag).assertIsDisplayed()
        composeTestRule.onNodeWithTag(JobUpdateChoosePhotosTag).assertIsDisplayed()
        composeTestRule
            .onNode(
                hasTestTag(JobUpdateTakePhotoTag) and
                    hasAnyAncestor(hasTestTag(JobUpdateKindSelectorTag)),
            )
            .assertDoesNotExist()
        composeTestRule
            .onNode(
                hasTestTag(JobUpdateChoosePhotosTag) and
                    hasAnyAncestor(hasTestTag(JobUpdateKindSelectorTag)),
            )
            .assertDoesNotExist()
        composeTestRule.onNodeWithTag(JobUpdateNoteTag).assertDoesNotExist()

        // The kinds are modes rather than steps: coming back to the note is the note's controls again,
        // and what was typed before looking at the photos is still there (`BR-012`).
        composeTestRule.onNodeWithTag(JobUpdateNoteKindTag).performClick()
        composeTestRule.onNodeWithTag(JobUpdateNoteTag).performTextInput("Replaced the air filter.")
        composeTestRule.onNodeWithTag(JobUpdatePhotoKindTag).performClick()
        composeTestRule.onNodeWithTag(JobUpdateNoteTag).assertDoesNotExist()
        composeTestRule.onNodeWithTag(JobUpdateNoteKindTag).performClick()
        composeTestRule.onNodeWithText("Replaced the air filter.").assertIsDisplayed()
    }

    @Test
    fun drawsTheAudioKindsOwnControlsWhenASessionMayAddAudio() {
        // No session may add audio yet, so this is the one kind the screen does not offer: the kind and
        // its controls are the seam the recorder lands in (`BR-042`, `ADR-015` D2).
        renderSheet(canWriteNote = true, canAddPhoto = true, canAddAudio = true)

        composeTestRule.onNodeWithTag(JobUpdateAudioKindTag).assertIsDisplayed()
        composeTestRule.onNodeWithTag(JobUpdateAudioKindTag).performClick()

        // Audio is a mode of its own and draws its own controls, so neither the note's field nor the
        // photo's sources are drawn in its place.
        composeTestRule.onNodeWithTag(JobUpdateNoteTag).assertDoesNotExist()
        composeTestRule.onNodeWithTag(JobUpdateTakePhotoTag).assertDoesNotExist()
        composeTestRule.onNodeWithTag(JobUpdateChoosePhotosTag).assertDoesNotExist()
    }

    /**
     * Draws the Add update sheet on its own, for a kind no screen offers yet (`BR-042`).
     *
     * The Job Details screen states which kinds the session may add, so the audio kind — which waits for
     * the capability `ADR-015` D2 reserves and does not create — is only reachable by drawing the sheet
     * directly.
     */
    private fun renderSheet(
        canWriteNote: Boolean,
        canAddPhoto: Boolean,
        canAddAudio: Boolean,
    ) {
        composeTestRule.setContent {
            ServoraTheme {
                JobUpdateSheet(
                    canWriteNote = canWriteNote,
                    canAddPhoto = canAddPhoto,
                    canAddAudio = canAddAudio,
                    isSubmitting = false,
                    audioDraft = null,
                    isRecordingAudio = false,
                    isAttachingAudio = false,
                    audioPhase = EvidencePhase.DURING_WORK,
                    onConfirmNote = {},
                    onTakePhoto = {},
                    onChoosePhotos = {},
                    onSelectAudioPhase = {},
                    onStartAudioRecording = {},
                    onStopAudioRecording = {},
                    onDiscardAudioDraft = {},
                    onAttachAudio = {},
                    onMicrophoneDenied = {},
                    onDismiss = {},
                )
            }
        }
    }

    private companion object {
        const val JOB_ID = "job-1"

        /** How long the image stack is given to read and decode a photo in a test (`D4b`). */
        const val PHOTO_LOAD_TIMEOUT = 10_000L
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
    // The destinations the API reports for a scheduled Job (`BR-058`): any other open status, or the
    // Job may be closed directly. It is the server's answer, so the fixture states it rather than
    // deriving it (`BR-041`).
    allowedStatusTransitions = listOf(
        JobStatus.IN_PROGRESS,
        JobStatus.PENDING_REVIEW,
        JobStatus.COMPLETED,
    ),
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

/**
 * A Job awaiting review, with the destinations the API reports for that status (`BR-058`): every other
 * open status, and a direct close. `PENDING_REVIEW` is not one of them, because standing still is not a
 * transition, and `NEW` is not either, because `NEW` is reached by reopening a terminal Job.
 */
private fun pendingReviewJob() = job().copy(
    status = JobStatus.PENDING_REVIEW,
    allowedStatusTransitions = listOf(
        JobStatus.SCHEDULED,
        JobStatus.IN_PROGRESS,
        JobStatus.COMPLETED,
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

/** One `JOB_PHOTO_ADDED` entry: the Job's photo as the Activity reports it (`BR-080`, `BR-015`). */
private fun photoEvent(
    body: String? = "Panel before the repair",
    phase: String? = "BEFORE_WORK",
) = activityEvent(
    id = "photo-1",
    kind = JobActivityKind.JOB_PHOTO_ADDED,
    visitSequence = null,
    body = body,
    photoId = "photo-1",
    photoPhase = phase,
)

private fun activityEvent(
    id: String = "event-1",
    kind: JobActivityKind = JobActivityKind.VISIT_NOTE_ADDED,
    recordedAt: String = "2026-09-14T14:14:00.000Z",
    actorName: String? = "John Smith",
    visitSequence: Int? = 2,
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
    technicianName = null,
    roleCode = null,
    previousRoleCode = null,
    outcomeCode = null,
    outcomeSummary = null,
    body = body,
    photoId = photoId,
    photoPhase = photoPhase,
)

/** One photo the technician captured that the backend has not accepted yet (`§9`). */
private fun pendingPhoto(
    photoId: String = "photo-1",
    phase: EvidencePhase? = EvidencePhase.DURING_WORK,
    note: String? = "Sawdust on the belt",
    submitted: Boolean = false,
) = PendingJobPhoto(
    photoId = photoId,
    jobId = "job-1",
    localPath = "app-private/job-photos/user-1/$photoId.jpg",
    phase = phase,
    note = note,
    capturedAt = "2026-09-15T13:04:05Z",
    mimeType = "image/jpeg",
    recordedAt = 1_000L,
    submitted = submitted,
)

/**
 * The stack was asked for the photo, and could not read it.
 *
 * It is the other half of [JobPhotoImages.None]: a request exists, so the viewer draws and reads, and
 * the read ends in the stack's failure — what an API refusal, a file that is gone, or bytes the device
 * cannot decode produces. The viewer must report that rather than draw something else (`BR-042`).
 */
private class UnreadableJobPhotoImages(private val context: Context) : JobPhotoImages {

    override fun localThumbnail(path: String): ImageRequest = request()

    override fun jobPhotoThumbnail(jobId: String, photoId: String): ImageRequest = request()

    override fun localFullSize(path: String): ImageRequest = request()

    override fun jobPhotoFullSize(jobId: String, photoId: String): ImageRequest = request()

    /** A file that is not there, so the read cannot end in anything but the stack's own failure. */
    private fun request(): ImageRequest = ImageRequest.Builder(context)
        .data(File(context.cacheDir, "no-such-photo.jpg"))
        .build()
}

/**
 * The photo reads the screen asked for (`D4`, `D4b`).
 *
 * A tile and the viewer ask the image stack for the same photo at different sizes, so a test has to be
 * able to say which one was asked for: the tile asks for a preview, and the viewer must ask for the
 * photo itself — from the device while it holds it, from the backend otherwise (§9, `BR-015`). Each
 * answer is a request the stack can really load, so a test asserts what is drawn and not only what was
 * asked for.
 */
private class RecordingJobPhotoImages(private val context: Context) : JobPhotoImages {

    /** The photo ids read as the photo itself, from the evidence the backend holds (`BR-015`). */
    val fullSizeReads = mutableListOf<String>()

    /** The local paths read as the photo itself, which only a photo still on the device has. */
    val localFullSizeReads = mutableListOf<String>()

    /** A real, loadable photo, so the viewer draws an image rather than reporting one it lacks. */
    private val photo: Drawable =
        BitmapDrawable(context.resources, Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888))

    override fun localThumbnail(path: String): ImageRequest = request()

    override fun jobPhotoThumbnail(jobId: String, photoId: String): ImageRequest = request()

    override fun localFullSize(path: String): ImageRequest {
        localFullSizeReads += path
        return request()
    }

    override fun jobPhotoFullSize(jobId: String, photoId: String): ImageRequest {
        fullSizeReads += photoId
        return request()
    }

    private fun request(): ImageRequest = ImageRequest.Builder(context).data(photo).build()
}

