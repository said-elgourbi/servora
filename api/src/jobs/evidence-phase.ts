/*
 * The field-work phase an update belongs to (`BR-027`, `BR-091`).
 *
 * The phase says what an update is about — before the work, during it, or after it — rather than what
 * kind of file carries it, so **every** evidence kind uses this one vocabulary (`BR-041`). It is defined
 * once here and imported by each kind's DTO, rather than repeated per kind: a photo and an audio note
 * that mean the same phase must send the same stable code, and a second list is how two codes for one
 * idea appear.
 *
 * The codes are stable and machine-readable; the labels a member reads are resolved by the client
 * (`BR-028`). A code this API does not know is refused rather than stored, because a client must not be
 * able to invent a phase Servora does not have (`BR-042`).
 */

/** The phases an update may belong to. */
export const EVIDENCE_PHASES = ['BEFORE_WORK', 'DURING_WORK', 'AFTER_WORK'] as const;

export type EvidencePhase = (typeof EVIDENCE_PHASES)[number];
