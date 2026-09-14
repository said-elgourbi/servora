package com.servora.android.domain.model

/**
 * A Property's lifecycle state (`BR-082`).
 *
 * `ACTIVE` is available for new work and is what the default lists and selectors show; `ARCHIVED` is
 * out of active use but remains a retrievable record. The codes are the backend's vocabulary; the UI
 * resolves a localized label for one and never invents its own (`BR-041`).
 */
enum class PropertyStatus {
    ACTIVE,
    ARCHIVED,
}

/** The authoritative open-work counts the archive confirmation displays (`BR-083`). */
data class PropertyArchiveImpact(
    val activeJobCount: Int,
    val activeVisitCount: Int,
) {
    /** Whether archiving would leave current work running under an archived Property. */
    val hasOpenWork: Boolean
        get() = activeJobCount > 0 || activeVisitCount > 0
}

/**
 * One of an organization's Properties, with the values a lifecycle screen renders
 * (`BR-081` – `BR-086`).
 *
 * Every derived value — the open-work counts, the job count, the last service date and whether
 * permanent deletion is currently allowed — is the API's answer, so the screen presents it rather
 * than deriving its own (`BR-001`, `BR-041`).
 */
data class PropertyDetail(
    val id: String,
    val name: String?,
    val addressLine1: String,
    val addressLine2: String?,
    val city: String,
    val province: String,
    val postalCode: String,
    val country: String,
    val notes: String?,
    val status: PropertyStatus,
    /** The version the client last saw; a mutation carries it back (`BR-086`). */
    val version: Int,
    /** ISO-8601 UTC instant of the current archive event, or `null` while active. */
    val archivedAt: String?,
    val jobCount: Int,
    /** ISO-8601 UTC instant of the newest completed Visit; `null` when never serviced. */
    val lastServiceAt: String?,
    val archiveImpact: PropertyArchiveImpact,
    /** Whether the API would allow permanent deletion right now (`BR-082`). */
    val canBePermanentlyDeleted: Boolean,
)
