package com.servora.android.ui.jobs

import com.servora.android.domain.model.JobStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which Job status destinations a user is asked to confirm before the change is sent (`BR-058`).
 *
 * The decision is a rule rather than a drawing, so it is asserted without a device: closing a Job ends
 * its field work and makes its Visit outcomes final (`BR-062`), and reopening a closed Job returns it to
 * the scheduling workflow (`BR-063`). Every other destination is the ordinary correction the status
 * control exists for and is sent as it is chosen, because a confirmation on each one would make the menu
 * slower to use for no decision anybody needed to make.
 *
 * The confirmation decides an **affordance**, never authorization or eligibility: whether a change is
 * allowed is the API's answer (`BR-007`, `BR-061`, `BR-062`), and the dialog cannot override it.
 */
class JobStatusConfirmationTest {

    @Test
    fun asksBeforeClosingAJobFromAnyOpenStatus() {
        for (from in openStatuses) {
            assertTrue(
                "closing a Job in $from must be confirmed",
                requiresJobStatusConfirmation(from, JobStatus.COMPLETED),
            )
        }
    }

    @Test
    fun asksBeforeReopeningAClosedJob() {
        // A terminal Job's only destination is its reopen, and undoing a close is as consequential as
        // making it (`BR-063`).
        assertTrue(requiresJobStatusConfirmation(JobStatus.COMPLETED, JobStatus.NEW))
        assertTrue(requiresJobStatusConfirmation(JobStatus.CANCELED, JobStatus.NEW))
    }

    @Test
    fun sendsAnOrdinaryDestinationWithoutAsking() {
        // Moving a Job between open statuses, forwards or backwards, is the everyday correction: it is
        // sent as it is chosen (`BR-058`).
        assertFalse(requiresJobStatusConfirmation(JobStatus.NEW, JobStatus.SCHEDULED))
        assertFalse(requiresJobStatusConfirmation(JobStatus.SCHEDULED, JobStatus.IN_PROGRESS))
        assertFalse(
            requiresJobStatusConfirmation(JobStatus.IN_PROGRESS, JobStatus.SCHEDULED),
        )
        assertFalse(
            requiresJobStatusConfirmation(JobStatus.SCHEDULED, JobStatus.PENDING_REVIEW),
        )
        assertFalse(
            requiresJobStatusConfirmation(JobStatus.PENDING_REVIEW, JobStatus.IN_PROGRESS),
        )
    }

    private val openStatuses = listOf(
        JobStatus.NEW,
        JobStatus.SCHEDULED,
        JobStatus.IN_PROGRESS,
        JobStatus.PENDING_REVIEW,
    )
}
