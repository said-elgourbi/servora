package com.servora.android.domain.model

/**
 * The outcome a Visit's completion records (`BR-078`).
 *
 * The outcome describes what resulted from the field attempt; the Visit's status describes whether
 * the attempt is over (`BR-074`). They are separate concepts and are never merged (`BR-077`), so this
 * vocabulary is drawn from the API's own codes rather than from anything the client decides.
 *
 * Which outcomes are valid is decided by the Visit's purpose (`BR-071`, `BR-078`): a Work Visit
 * records one of [workOptions], and an Assessment Visit records one of [assessmentOptions]. The
 * backend enforces that rule; a client may filter its choices but is never authoritative (`BR-007`).
 * The codes are stable and machine-readable and the labels are localized (`BR-028`, `BR-041`). The
 * follow-up expectation is derived from the Work codes by the backend (`BR-078`); no
 * `follow_up_required` flag is modelled here, because `BR-078` confirms there is none. The catalogue
 * is closed: a code the API does not have is refused rather than presented (`BR-042`).
 */
enum class VisitOutcome {
    // Work Visit outcomes (`BR-078`).
    /** The work was completed successfully; no follow-up is expected. */
    RESOLVED,

    /** The work requires another field attempt. */
    NEEDS_FOLLOW_UP,

    /** The work requires parts. */
    NEEDS_PARTS,

    /** The work could not be completed; the technician states why. */
    UNABLE_TO_COMPLETE,

    // Assessment Visit outcomes (`BR-078`).
    /** Nothing needs to be done; the assessment found no work. */
    NO_WORK_REQUIRED,

    /** Work is recommended; the office decides what happens next. */
    WORK_RECOMMENDED,

    /** A quote is needed before work can be planned. */
    QUOTE_REQUIRED,

    /** The assessment could not be done; the technician states why. */
    UNABLE_TO_ASSESS,
    ;

    companion object {
        /** The outcomes a Work Visit may record (`BR-078`). */
        val workOptions: List<VisitOutcome> =
            listOf(RESOLVED, NEEDS_FOLLOW_UP, NEEDS_PARTS, UNABLE_TO_COMPLETE)

        /** The outcomes an Assessment Visit may record (`BR-078`). */
        val assessmentOptions: List<VisitOutcome> =
            listOf(NO_WORK_REQUIRED, WORK_RECOMMENDED, QUOTE_REQUIRED, UNABLE_TO_ASSESS)
    }
}

/**
 * The outcomes a Visit of [purpose] may record (`BR-071`, `BR-078`).
 *
 * The completion sheet draws its choices from this rather than from the whole catalogue, because a
 * Work Visit cannot record `QUOTE_REQUIRED` and an Assessment Visit cannot record `RESOLVED`; the
 * backend refuses either pair, so the picker never offers it (`BR-007`, `BR-041`).
 */
fun visitOutcomesForPurpose(purpose: VisitPurpose): List<VisitOutcome> =
    when (purpose) {
        VisitPurpose.WORK -> VisitOutcome.workOptions
        VisitPurpose.ASSESSMENT -> VisitOutcome.assessmentOptions
    }

/**
 * Reads the wire code of an outcome, or `null` when this build does not know it.
 *
 * A code Servora does not have cannot be presented, so it is refused rather than shown as something
 * it is not (`BR-042`).
 */
fun visitOutcomeOrNull(code: String?): VisitOutcome? =
    VisitOutcome.entries.firstOrNull { outcome -> outcome.name == code }
