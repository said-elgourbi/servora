package com.servora.android.data.customers

import kotlinx.serialization.Serializable

/*
 * Wire contract for the customer detail reads.
 *
 * `GET /customers/{id}` returns the customer header with its derived counts, its subtype record and
 * its contacts; `GET /customers/{id}/properties` and `GET /customers/{id}/jobs` return the two
 * section projections (`BR-081`). Values stay in the stable codes the API exchanges — the derived
 * date, technicians and last-service values come from the backend, and this layer only carries them
 * (`BR-041`).
 *
 * The shared `Json` configuration ignores unknown fields, so a later API addition does not break
 * this build (`dev.md` §7).
 */

/** `GET /customers/{id}` — the detail's header, subtype and contacts. */
@Serializable
data class CustomerDetailDto(
    val customer: CustomerDto,
    val individual: CustomerIndividualDto? = null,
    val company: CustomerCompanyDto? = null,
    val contacts: List<CustomerContactDto> = emptyList(),
)

@Serializable
data class CustomerIndividualDto(
    val customerId: String,
    val firstName: String,
    val lastName: String,
    val dateOfBirth: String? = null,
)

@Serializable
data class CustomerCompanyDto(
    val customerId: String,
    val legalName: String,
    val businessName: String? = null,
    val taxNumber: String? = null,
)

@Serializable
data class CustomerContactDto(
    val id: String,
    val customerId: String,
    val firstName: String,
    val lastName: String,
    val email: String? = null,
    val phone: String? = null,
    val role: String? = null,
    val isPrimary: Boolean = false,
    val isBillingContact: Boolean = false,
    val isJobContact: Boolean = false,
    val createdAt: String,
    val updatedAt: String,
)

/** `GET /customers/{id}/properties` — one Property row. */
@Serializable
data class CustomerPropertyDto(
    val id: String,
    val name: String? = null,
    val addressLine1: String,
    val addressLine2: String? = null,
    val city: String,
    val province: String,
    val postalCode: String,
    val country: String,
    val jobCount: Int = 0,
    /** The most recent completed Visit's scheduled start, or `null` when never serviced. */
    val lastServiceAt: String? = null,
)

/**
 * `POST /customers/{id}/properties` — the fields a Property is created with (`BR-049`).
 *
 * These are exactly the authoritative Property fields: the street, the optional unit, the city, the
 * province code, the postal code, the optional name and the optional notes. The country is not a
 * client field — the backend stores the value the address model carries — so it is never sent.
 */
@Serializable
data class CreatePropertyRequest(
    val name: String? = null,
    val addressLine1: String,
    val addressLine2: String? = null,
    val city: String,
    val province: String,
    val postalCode: String,
    val notes: String? = null,
)

/** `GET /customers/{id}/jobs` — one Job row, with the selected Visit's derived values. */
@Serializable
data class CustomerJobDto(
    val id: String,
    val jobNumber: Int,
    val title: String,
    val description: String? = null,
    val typeCode: String? = null,
    val status: String,
    val propertyId: String? = null,
    val propertyAddress: CustomerJobAddressDto? = null,
    val scheduledStart: String? = null,
    val technicians: List<CustomerJobTechnicianDto> = emptyList(),
)

@Serializable
data class CustomerJobAddressDto(
    val propertyName: String? = null,
    val addressLine1: String? = null,
    val addressLine2: String? = null,
    val city: String? = null,
    val province: String? = null,
    val postalCode: String? = null,
    val country: String? = null,
)

@Serializable
data class CustomerJobTechnicianDto(
    val membershipId: String,
    val name: String? = null,
    val roleCode: String,
)
