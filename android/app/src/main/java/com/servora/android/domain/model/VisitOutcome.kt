package com.servora.android.domain.model

/**
 * The outcome a Visit's completion records (`BR-078`).
 *
 * The outcome describes what resulted from the field attempt; the Visit's status describes whether
 * the attempt is over (`BR-074`). They are separate concepts and are never merged (`BR-077`), so this
 * vocabulary is drawn from the API's own codes rather than from anything the client decides.
 *
 * The codes are stable and machine-readable and the labels are localized (`BR-028`, `BR-041`). The
 * follow-up expectation is derived from the code by the backend (`BR-078`); no `follow_up_required`
 * flag is modelled here, because `BR-078` confirms there is none.
 */
enum class VisitOutcome {
    /** The work was completed successfully; no follow-up is expected. */
    RESOLVED,

    /** The work requires parts. */
    NEEDS_PARTS,

    /** The work requires another field attempt. */
    NEEDS_FOLLOWUP,

    /** The work requires a quote or approval, which is a business follow-up. */
    NEEDS_QUOTE_APPROVAL,

    /** The work could not be completed. */
    UNABLE_TO_COMPLETE,
}

/**
 * Reads the wire code of an outcome, or `null` when this build does not know it.
 *
 * A code Servora does not have cannot be presented, so it is refused rather than shown as something
 * it is not (`BR-042`).
 */
fun visitOutcomeOrNull(code: String?): VisitOutcome? =
    VisitOutcome.entries.firstOrNull { outcome -> outcome.name == code }
