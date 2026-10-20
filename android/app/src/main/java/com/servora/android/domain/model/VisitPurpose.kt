package com.servora.android.domain.model

/**
 * Why a technician is attending a Visit (`BR-071`, `BR-078`).
 *
 * Purpose is orthogonal to status: purpose answers why the technician is attending, status answers where
 * the Visit is in its lifecycle, and outcome answers what resulted (`BR-074`, `BR-077`). The codes are the
 * backend's vocabulary (`BR-041`), so the client resolves a localized label for one and never invents
 * another. The catalogue is closed and exactly these two: a code the API does not have is refused rather
 * than presented (`BR-042`).
 *
 * Scheduling defaults to [WORK], because most operational Visits are Work Visits; [ASSESSMENT] is an
 * intentional override for a Visit that inspects, diagnoses or determines next steps. Purpose may only be
 * changed while a Visit is still `SCHEDULED`; the backend owns that rule and the client never decides it.
 */
enum class VisitPurpose {
    /** The technician attends to perform scheduled service or repair. */
    WORK,

    /** The technician attends to inspect, diagnose or determine next steps. */
    ASSESSMENT,
    ;

    companion object {
        /** The selector's vocabulary and its order: Work first, because that is the default. */
        val options: List<VisitPurpose> = listOf(WORK, ASSESSMENT)
    }
}

/**
 * Reads the wire code of a purpose, or `null` when this build does not know it.
 *
 * A code Servora does not have cannot be presented, so it is refused rather than shown as something
 * it is not (`BR-042`). An absent code is read as [VisitPurpose.WORK] by the callers that need a
 * default, because every Visit that predates the purpose field is a historical Work Visit.
 */
fun visitPurposeOrNull(code: String?): VisitPurpose? =
    code?.let { value -> VisitPurpose.entries.firstOrNull { purpose -> purpose.name == value } }
