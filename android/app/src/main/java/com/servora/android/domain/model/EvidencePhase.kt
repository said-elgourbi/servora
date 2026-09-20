package com.servora.android.domain.model

/**
 * The field-work phase evidence was captured in (`BR-091`, `BR-027`).
 *
 * Every kind of evidence carries it — a photo and an audio recording alike — and it is **one**
 * vocabulary rather than a classification per kind: the phase says what an update is about, not what
 * kind of file it is, so a second vocabulary would be two codes for one idea (`BR-041`, `ADR-018` A4).
 *
 * The codes are the stable, machine-readable values the API exchanges and stores (`BR-028`); the
 * screen resolves a localized label for one rather than showing the code, and no translated text is
 * ever sent as the value.
 */
enum class EvidencePhase {
    /** The state of the work before the technician started. */
    BEFORE_WORK,

    /** The work in progress. */
    DURING_WORK,

    /** The result once the work was done. */
    AFTER_WORK,
}

/**
 * The stable code the local stores and the queued uploads hold for a phase, or `null` when no phase
 * is recorded.
 *
 * One writer and one reader share this, so what a stored phase is cannot drift between the pending
 * row and the operation that uploads it (`BR-041`).
 */
fun evidencePhaseNameOrNull(phase: EvidencePhase?): String? = phase?.name

/**
 * The phase a stored or reported code names, or `null` when this build cannot read it (`BR-042`).
 *
 * A code this build does not have is never guessed: the caller reports what it can and the API
 * remains the authority for what the value means (`BR-001`).
 */
fun evidencePhaseOrNull(code: String?): EvidencePhase? =
    code?.let { value -> EvidencePhase.entries.firstOrNull { it.name == value } }
