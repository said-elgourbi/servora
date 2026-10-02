package com.servora.android.ui.jobs

import com.servora.android.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The field language a Visit-status event is stated with (`BR-080`, `BR-012`).
 *
 * The timeline states what happened on the work — `Work started`, `Arrived on site` — rather than which
 * code was written, and this is where that mapping lives, so the rule is verifiable without a device: the
 * screen only asks for the phrase and draws it.
 *
 * The codes are the API's own `toStatus` values (`BR-041`), so they are stated literally here: a code this
 * build does not know must have **no** phrase rather than another status's wording, which is what keeps an
 * unreadable event reported generically instead of wrongly (`BR-042`).
 */
class JobActivityTitleTest {

    @Test
    fun `states every destination the field route offers in the language of the field`() {
        assertEquals(
            R.string.job_activity_visit_status_scheduled,
            activityVisitStatusTitle("SCHEDULED"),
        )
        assertEquals(R.string.job_activity_visit_status_en_route, activityVisitStatusTitle("EN_ROUTE"))
        assertEquals(R.string.job_activity_visit_status_on_site, activityVisitStatusTitle("ON_SITE"))
        assertEquals(
            R.string.job_activity_visit_status_in_progress,
            activityVisitStatusTitle("IN_PROGRESS"),
        )
        assertEquals(
            R.string.job_activity_visit_status_completed,
            activityVisitStatusTitle("COMPLETED"),
        )
        assertEquals(
            R.string.job_activity_visit_status_canceled,
            activityVisitStatusTitle("CANCELED"),
        )
    }

    @Test
    fun `names no destination the entry did not report`() {
        assertNull(activityVisitStatusTitle(null))
        assertNull(activityVisitStatusTitle(""))
        assertNull(activityVisitStatusTitle("DRAFT"))
        assertNull(activityVisitStatusTitle("NO_SHOW"))
        assertNull(activityVisitStatusTitle("IN_PROGRESS_ISH"))
    }
}
