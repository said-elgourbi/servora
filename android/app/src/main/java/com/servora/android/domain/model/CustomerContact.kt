package com.servora.android.domain.model

/**
 * A contact person for a customer. A customer may have any number of contacts.
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
    val createdAt: String,
    val updatedAt: String,
)
