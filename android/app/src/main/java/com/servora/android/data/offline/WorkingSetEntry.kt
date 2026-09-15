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
    /** A customer detail read, including its Properties and Jobs projections. */
    const val CUSTOMER_DETAIL = "customer.detail"

    /** One Property's lifecycle detail. */
    const val PROPERTY_DETAIL = "property.detail"
}
