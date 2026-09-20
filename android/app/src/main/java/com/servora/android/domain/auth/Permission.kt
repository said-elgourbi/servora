package com.servora.android.domain.auth

enum class Permission(val code: String, val aliases: Set<String> = emptySet()) {
    CUSTOMERS_VIEW("customers.view", aliases = setOf("view.customers", "CUSTOMER_VIEW")),
    CUSTOMERS_CREATE("customers.create", aliases = setOf("create.customers", "CUSTOMER_CREATE")),
    CUSTOMERS_EDIT("customers.edit", aliases = setOf("edit.customers", "CUSTOMER_UPDATE")),
    CUSTOMERS_ARCHIVE("customers.archive", aliases = setOf("archive.customers", "CUSTOMER_DELETE")),
    // Maintaining a Customer's contact persons is its own capability set (`BR-095`), following
    // `BR-085`'s Property set: a role may legitimately maintain a Customer without being trusted to
    // change the people the organization calls, so `customers.edit` authorizes no contact write.
    // Reading a Customer's contacts adds no capability — they are part of the projections the office
    // read (`customers.view`) and the assigned-customer read (`BR-092`) already authorize. A capability
    // named here is a UI gate only: the backend remains the authority (`BR-007`, `BR-011`).
    CUSTOMERS_CONTACTS_CREATE("customers.contacts.create"),
    CUSTOMERS_CONTACTS_EDIT("customers.contacts.edit"),
    CUSTOMERS_CONTACTS_REMOVE("customers.contacts.remove"),
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
    /**
     * Creating a Job (`BR-008`, `BR-094`). The code is the foundation catalogue's own — `BR-008` names
     * "create jobs" as a Manager default and the default Manager role already holds it — so this is the
     * capability the API enforces on `POST /jobs`, not an inference from the update one. A capability
     * named here is a UI gate only: the backend remains the authority (`BR-007`, `BR-011`).
     */
    JOB_CREATE("JOB_CREATE", aliases = setOf("jobs.create", "create.jobs")),
    TECHNICIAN_VIEW("TECHNICIAN_VIEW", aliases = setOf("technicians.view", "view.technicians")),
    SCHEDULE_VIEW_ORG("schedule.view_org"),
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
    // Taking accepted evidence out of ordinary use is a **Manager-level** capability (`BR-089`): the
    // default Technician role does not hold it, and it is not implied by adding or reading evidence. A
    // technician's own discard of an unsubmitted draft needs no capability at all (`BR-088`).
    EVIDENCE_PHOTO_REMOVE("evidence.photo.remove"),
    // Recording an audio note is its own capability, because the catalogue is per kind: a company may
    // let a member record a photo and not a voice note, and withdrawal is per kind (`ADR-015` D2,
    // `ADR-018` A7). It is the code `ADR-015` reserved and `ADR-018` created, and it is never implied
    // by the photo capability. A capability named here is a UI gate only: the backend remains the
    // authority (`BR-007`, `BR-011`).
    EVIDENCE_AUDIO_ADD("evidence.audio.add"),
    // Taking an accepted recording out of ordinary use is the audio kind's own **Manager-level**
    // capability (`BR-089`, `ADR-018` A7): the default Technician role does not hold it, it is not
    // implied by `evidence.audio.add` or by `evidence.photo.remove`, and the API enforces it on the
    // removal route. A capability named here is a UI gate only (`BR-007`, `BR-011`).
    EVIDENCE_AUDIO_REMOVE("evidence.audio.remove"),
    // The field capability `BR-009` names for a technician's read of their own assigned work
    // (`ADR-019` D1, D6). The API enforces it on `GET /home/technician` and on the Job read, and the
    // scope is the caller's own current assignments rather than the capability itself (`ADR-019`
    // D2). The remaining field capabilities `BR-009` names are added with the field action that uses
    // them, so no gate is named here before anything draws on it. A capability named here is a UI
    // gate only: the backend remains the authority (`BR-007`, `BR-011`).
    VISIT_VIEW_ASSIGNED("VISIT_VIEW_ASSIGNED"),
    VISIT_CREATE_SCHEDULE("visits.create_schedule"),
    VISIT_UPDATE_SCHEDULE("visits.update_schedule"),
    VISIT_ASSIGN_TECHNICIANS("visits.assign_technicians"),
    VISIT_REQUEST_FOLLOW_UP("visits.request_follow_up"),
    VISIT_REVIEW_REQUESTS("visits.review_requests"),
    // Driving an assigned Visit through its field lifecycle (`BR-074`, `BR-075`) is the first of the
    // three field capabilities the Job Details field action draws on (`BR-009`, `ADR-019` D1). The
    // API enforces it on `PATCH /jobs/:id/visits/:visitId/status` and scopes the route to the
    // caller's own current crew, so a Visit their assignment does not reach is answered `404`
    // (`ADR-019` D2, D3). The route's other authorization is the office's `JOB_UPDATE`, which needs no
    // crew membership (`BR-093`), so a session may reach the action through either.
    VISIT_UPDATE_ASSIGNED_STATUS("VISIT_UPDATE_ASSIGNED_STATUS"),
    // Adding a note to an assigned Visit (`BR-009`, `BR-027`). It is the capability the API enforces
    // on `POST /jobs/:id/visits/:visitId/notes` for a field caller, which is why a technician reaches
    // that route without holding the office's `JOB_UPDATE` (`docs/api/job-actions.md` §2).
    VISIT_ADD_NOTE("VISIT_ADD_NOTE"),
    // Recording the outcome a completion requires (`BR-077`, `BR-078`). The API asks for it as a
    // second question on the `COMPLETED` destination of the Visit status route, so a caller holding
    // only `VISIT_UPDATE_ASSIGNED_STATUS` is refused with `403` (`BR-009`, `BR-007`). A capability
    // named here is a UI gate only: the backend remains the authority (`BR-007`, `BR-011`).
    VISIT_RECORD_OUTCOME("VISIT_RECORD_OUTCOME"),
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
