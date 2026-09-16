package com.servora.android.domain.auth

enum class Permission(val code: String, val aliases: Set<String> = emptySet()) {
    CUSTOMERS_VIEW("customers.view", aliases = setOf("view.customers", "CUSTOMER_VIEW")),
    CUSTOMERS_CREATE("customers.create", aliases = setOf("create.customers", "CUSTOMER_CREATE")),
    CUSTOMERS_EDIT("customers.edit", aliases = setOf("edit.customers", "CUSTOMER_UPDATE")),
    CUSTOMERS_ARCHIVE("customers.archive", aliases = setOf("archive.customers", "CUSTOMER_DELETE")),
    // Property capabilities are independent of the customer capabilities (`BR-085`); a Property
    // action is never inferred from a customer permission.
    PROPERTIES_VIEW("properties.view"),
    PROPERTIES_CREATE("properties.create"),
    PROPERTIES_EDIT("properties.edit"),
    PROPERTIES_ARCHIVE("properties.archive"),
    PROPERTIES_DELETE("properties.delete"),
    // The Job and Visit management capabilities (`BR-008`, `BR-066`). The codes the API enforces
    // today are the foundation catalogue's, and the aliases let the same capability be recognised
    // under the `resource.action` spelling the Jobs feature may define later (`BR-006`, `BR-041`).
    // A capability named here is a UI gate only: the backend remains the authority (`BR-007`).
    JOB_UPDATE("JOB_UPDATE", aliases = setOf("jobs.update", "update.jobs")),
    TECHNICIAN_VIEW("TECHNICIAN_VIEW", aliases = setOf("technicians.view", "view.technicians")),
    // The evidence capabilities the API enforces on the Job photo routes (`BR-006`, `BR-015`,
    // `BR-027`; `docs/decisions/015-evidence-capabilities.md`). Adding evidence is its own capability
    // rather than a Job one, because the person who records it is the technician on site, who holds
    // neither `JOB_UPDATE` nor `customers.view` (`BR-009`). A capability named here is a UI gate only:
    // the backend remains the authority (`BR-007`, `BR-011`).
    EVIDENCE_PHOTO_ADD("evidence.photo.add"),
    // Reading evidence back is its own capability, exactly as the API enforces it
    // (`docs/decisions/015-evidence-capabilities.md` D2). Saving a photo on the device and sharing it
    // with another application both read the photo's bytes, so both are drawn on this capability and
    // not on the one that recorded the photo (`BR-006`, `BR-007`, `BR-011`).
    EVIDENCE_VIEW("evidence.view"),
}

class PermissionChecker(granted: Set<String>) {
    private val granted = granted.mapTo(mutableSetOf(), ::canonicalPermissionCode)

    fun has(permission: Permission): Boolean = permission.code in granted
}

fun Set<String>.asPermissionChecker(): PermissionChecker = PermissionChecker(this)

private fun canonicalPermissionCode(code: String): String =
    Permission.entries.firstOrNull { permission ->
        code == permission.code || code in permission.aliases
    }?.code ?: code
