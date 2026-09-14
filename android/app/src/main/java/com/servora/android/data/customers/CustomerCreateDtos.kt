package com.servora.android.data.customers

import kotlinx.serialization.Serializable

/*
 * Wire contracts for the customer write endpoints.
 *
 * `POST /customers` is a discriminated create: the request carries `type` and exactly one subtype
 * payload, and it answers with the created customer header plus the subtype record the backend
 * stored. `POST /customers/{id}/contacts` answers with the created contact.
 *
 * The two request shapes are concrete classes rather than one polymorphic type, so the discriminator
 * the API reads is the `type` member and nothing else (`BR-041`). Values stay in the stable codes the
 * API exchanges; no display text crosses the wire.
 *
 * The shared `Json` configuration ignores unknown fields, so a later API addition does not break
 * this build (`dev.md` §7). Optional members default to `null`/`false`, and the configuration omits
 * members that keep their default, so an unfilled optional field is simply absent from the request.
 */

/** The `individual` payload of an `INDIVIDUAL` customer create (`BR-023`). */
@Serializable
data class IndividualCustomerPayload(
    val firstName: String,
    val lastName: String,
    val dateOfBirth: String? = null,
)

/** The `company` payload of a `COMPANY` customer create (`BR-023`). */
@Serializable
data class CompanyCustomerPayload(
    val legalName: String,
    val businessName: String? = null,
    val taxNumber: String? = null,
)

/**
 * `POST /customers` for an individual customer.
 *
 * `displayName` is the name the list and detail show; it is required by the API and derived from the
 * first and last name the form captured.
 */
@Serializable
data class CreateIndividualCustomerRequest(
    val type: String,
    val displayName: String,
    val email: String? = null,
    val phone: String? = null,
    val notes: String? = null,
    val individual: IndividualCustomerPayload,
)

/** `POST /customers` for a business customer; `displayName` is the company name. */
@Serializable
data class CreateCompanyCustomerRequest(
    val type: String,
    val displayName: String,
    val email: String? = null,
    val phone: String? = null,
    val notes: String? = null,
    val company: CompanyCustomerPayload,
)

/**
 * What `POST /customers` and `PATCH /customers/{id}` answer with: the customer header and its
 * subtype record.
 *
 * Only the header is needed to continue, because the subtype payload is not written by this client.
 */
@Serializable
data class CreatedCustomerDto(
    val customer: CustomerDto,
    val individual: CustomerIndividualDto? = null,
    val company: CustomerCompanyDto? = null,
)

/**
 * `POST /customers/{id}/contacts` — the primary contact recorded while a business customer is
 * created (`BR-023`).
 *
 * A contact's own email and phone are separate from the customer header's, which the form already
 * captures, so those members are left absent rather than duplicating the header's values.
 */
@Serializable
data class CreateCustomerContactRequest(
    val firstName: String,
    val lastName: String,
    val email: String? = null,
    val phone: String? = null,
    val role: String? = null,
    val isPrimary: Boolean = false,
    val isBillingContact: Boolean = false,
    val isJobContact: Boolean = false,
)

/**
 * The discriminated customer create a caller can ask for.
 *
 * The API's create is discriminated by `type`, so the caller states which shape it is building and
 * the repository picks the matching wire request. Nothing polymorphic is serialised, so the
 * `type` member the API reads is never accompanied by a second library discriminator.
 */
sealed interface CreateCustomerRequest {
    /** An `INDIVIDUAL` customer create. */
    data class Individual(
        val request: CreateIndividualCustomerRequest,
    ) : CreateCustomerRequest

    /** A `COMPANY` customer create. */
    data class Company(
        val request: CreateCompanyCustomerRequest,
    ) : CreateCustomerRequest
}
