package com.servora.android.domain.model

/** Stable, machine-readable address type code. */
enum class CustomerAddressType {
    SERVICE,
    BILLING,
    OTHER,
}

/**
 * An address for a customer. A customer may have any number of addresses.
 */
data class CustomerAddress(
    val id: String,
    val customerId: String,
    val type: CustomerAddressType,
    val addressLine1: String,
    val addressLine2: String?,
    val city: String,
    val province: String,
    val postalCode: String,
    val country: String,
    val isDefault: Boolean,
    val createdAt: String,
    val updatedAt: String,
)
