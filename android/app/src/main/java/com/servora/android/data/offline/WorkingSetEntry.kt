package com.servora.android.data.offline

/**
 * The last state the backend reported for one projection, kept so a read can be served without
 * connectivity (`docs/architecture/offline-first-architecture.md` §2, `BR-013`).
 *
 * The working set is authoritative in no way: it is only ever written from an API answer, it is
 * never edited to hold a provisional value, and a successful read always replaces it (§2, §10).
 */
data class WorkingSetEntry(
    /** The authenticated subject the answer was read for; see [OutboxOperation.subjectId]. */
    val subjectId: String,
    /** The projection this answer belongs to; see [WorkingSetEntityTypes]. */
    val entityType: String,
    /** The entity the answer describes. */
    val entityId: String,
    /**
     * The context the read was made under, where the projection needs one.
     *
     * A Property's detail is read through a Customer, so the Customer id is kept with the row.
     */
    val scopeId: String?,
    /** The version the answer reported, where the projection carries one. */
    val version: Int?,
    /** The API's answer, as received, so the same mapper produces the same domain object. */
    val payload: String,
    /** When the backend reported it. */
    val reportedAt: Long,
)

/**
 * The stable keys the local store partitions cached projections by.
 *
 * These are local storage keys, not a wire or business vocabulary: they exist so one table can hold
 * more than one projection type. The payload of each entry stays the owning feature's contract.
 */
object WorkingSetEntityTypes {
    /**
     * The customer list as the backend reported it for the filter it was asked to apply.
     *
     * Unlike the two projections below, this one keeps **more than one row per entity type**: the
     * answer depends on the filter, so the filter is part of the row's [WorkingSetEntry.entityId]
     * rather than a parameter of the read. A row read under another filter is not this read's answer
     * and must never be served for it (`BR-001`).
     */
    const val CUSTOMER_LIST = "customer.list"

    /** A customer detail read, including its Properties and Jobs projections. */
    const val CUSTOMER_DETAIL = "customer.detail"

    /** One Property's lifecycle detail. */
    const val PROPERTY_DETAIL = "property.detail"

    /**
     * One Job as its details screen reads it (`GET /jobs/:id`).
     *
     * It is what makes a Job readable without connectivity, and with it the evidence that Job holds:
     * the Job's activity names its photo ids with their phase, note and time (`D5`, `BR-013`, `BR-080`).
     * The bytes are a separate question and are never held here (`§9`).
     */
    const val JOB_DETAILS = "job.details"

    /** One Job's chronological activity (`GET /jobs/:id/activity`, `BR-080`). */
    const val JOB_ACTIVITY = "job.activity"

    /**
     * The signed-in technician's own working day (`GET /home/technician`, `BR-013`).
     *
     * It is what makes "what do I need to do next?" a question the app answers without connectivity:
     * the day the backend last reported — the next Visit, the day's own Visits, the preview and the
     * conditions on the caller's own work — is kept here and served when the API cannot be reached
     * (`§2`, `§7`). It is keyed by the authenticated subject like every other row, so one
     * technician's day is never served to whoever signs in next (`§10`).
     */
    const val TECHNICIAN_HOME = "home.technician"

    /**
     * One local day of the signed-in technician's own schedule (`GET /schedule`, `BR-013`).
     *
     * `BR-013` names "viewing assigned work" as field work that has to survive a loss of
     * connectivity, and the technician's schedule is exactly that seen over days rather than over
     * one day. One row is held **per local date**, because the answer depends on the date the
     * backend was asked about: browsing back to a day already read answers from here when the API
     * cannot be reached, and a row read for another date is never served for this one (§13.4).
     *
     * Only a **field-scoped** answer is kept: the office board is a dispatcher's read and stays
     * online-only (`ADR-020` D6), so a schedule read that answered for the whole organization is
     * never written here.
     */
    const val TECHNICIAN_SCHEDULE = "schedule.technician"
}
