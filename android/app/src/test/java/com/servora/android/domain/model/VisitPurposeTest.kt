package com.servora.android.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The purpose-aware outcome vocabulary (`BR-071`, `BR-078`).
 *
 * A Work Visit and an Assessment Visit record different conclusions, so the completion sheet draws its
 * choices from the purpose rather than from the whole catalogue; these tests pin that split and the
 * purpose/outcome wire-code readers beside it.
 */
class VisitPurposeTest {

    @Test
    fun `a Work Visit offers exactly the four work outcomes`() {
        assertEquals(
            listOf(
                VisitOutcome.RESOLVED,
                VisitOutcome.NEEDS_FOLLOW_UP,
                VisitOutcome.NEEDS_PARTS,
                VisitOutcome.UNABLE_TO_COMPLETE,
            ),
            visitOutcomesForPurpose(VisitPurpose.WORK),
        )
    }

    @Test
    fun `an Assessment Visit offers exactly the four assessment outcomes`() {
        assertEquals(
            listOf(
                VisitOutcome.NO_WORK_REQUIRED,
                VisitOutcome.WORK_RECOMMENDED,
                VisitOutcome.QUOTE_REQUIRED,
                VisitOutcome.UNABLE_TO_ASSESS,
            ),
            visitOutcomesForPurpose(VisitPurpose.ASSESSMENT),
        )
    }

    @Test
    fun `the two purpose halves never overlap`() {
        assertEquals(
            emptyList<VisitOutcome>(),
            visitOutcomesForPurpose(VisitPurpose.WORK).filter {
                it in visitOutcomesForPurpose(VisitPurpose.ASSESSMENT)
            },
        )
    }

    @Test
    fun `reads the purpose wire code or reports none for an unknown one`() {
        assertEquals(VisitPurpose.WORK, visitPurposeOrNull("WORK"))
        assertEquals(VisitPurpose.ASSESSMENT, visitPurposeOrNull("ASSESSMENT"))
        assertNull(visitPurposeOrNull(null))
        assertNull(visitPurposeOrNull("MAINTENANCE"))
    }

    @Test
    fun `reads an assessment outcome wire code`() {
        assertEquals(VisitOutcome.NO_WORK_REQUIRED, visitOutcomeOrNull("NO_WORK_REQUIRED"))
        assertEquals(VisitOutcome.UNABLE_TO_ASSESS, visitOutcomeOrNull("UNABLE_TO_ASSESS"))
        assertNull(visitOutcomeOrNull("ASSESSMENT_COMPLETE"))
    }
}
