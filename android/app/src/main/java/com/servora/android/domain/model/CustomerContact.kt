package com.servora.android.domain.model

/**
 * A contact person for a customer. A customer may have any number of contacts.
 *
 * [version] is the optimistic-concurrency token the API reports for the contact and takes back as
 * `expectedVersion` when the contact is edited or removed (`BR-095`, `ADR-022` D9). A mutation naming a
 * version the contact has left is refused rather than applied, so the client has to carry the version it
 * last read (`BR-032`, `BR-086`).
 */
data class CustomerContact(
    val id: String,
    val customerId: String,
    val firstName: String,
    val lastName: String,
    val email: String?,
    val phone: String?,
    val role: String?,
    val isPrimary: Boolean,
    val isBillingContact: Boolean,
    val isJobContact: Boolean,
    /** The version the contact was last written with; `expectedVersion` on an edit or a removal. */
    val version: Int,
    val createdAt: String,
    val updatedAt: String,
)
