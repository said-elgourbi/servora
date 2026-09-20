package com.servora.android.data.customers

import kotlinx.serialization.Serializable

/*
 * Wire contracts for the customer write endpoints.
 *
 * `POST /customers` is a discriminated create: the request carries `type` and exactly one subtype
 * payload, and it answers with the created customer header plus the subtype record the backend
 * stored. The three contact routes answer with the created contact (`POST`), the updated contact
 * (`PATCH`) and no body (`DELETE`, `docs/api/customers.md` §5.2).
 *
 * The two request shapes are concrete classes rather than one polymorphic type, so the discriminator
 * the API reads is the `type` member and nothing else (`BR-041`). Values stay in the stable codes the
 * API exchanges; no display text crosses the wire.
 *
 * The shared `Json` configuration ignores unknown fields, so a later API addition does not break
 * this build (`dev.md` §7). Optional members default to `null`/`false`, and the configuration omits
 * members that keep their default, so an unfilled optional field is simply absent from the request and
 * the API leaves what it does not state as the contact holds it (`BR-095`, `ADR-022` D9) — the same
 * shape `UpdateCustomerRequest` and `UpdatePropertyRequest` already use.
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
 * `POST /customers/{id}/contacts` — a contact person recorded on an existing customer (`BR-023`,
 * `BR-095`).
 *
 * A contact's own email and phone are separate from the customer header's, which the create form
 * already captures, so those members are sent only when the form recorded them for the contact.
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
 * `PATCH /customers/{id}/contacts/{contactId}` — the fields an edit supplies (`BR-095`).
 *
 * The edit is **partial**: a member the request does not carry is left as the contact holds it, which
 * is what protects a value no client edits — the free-text `role` — from being cleared by an edit that
 * never knew about it (`ADR-022` D9). `isPrimary` is nullable for the same reason: an edit that states
 * it changes the customer's primary contact in one operation, and one that omits it leaves the flag
 * alone.
 *
 * `expectedVersion` is **required**: it is the version the caller read, and a mutation naming none would
 * write over newer state blindly (`BR-032`, `BR-086`).
 */
@Serializable
data class UpdateCustomerContactRequest(
    val firstName: String? = null,
    val lastName: String? = null,
    val email: String? = null,
    val phone: String? = null,
    val role: String? = null,
    val isPrimary: Boolean? = null,
    val expectedVersion: Int,
)

/**
 * `DELETE /customers/{id}/contacts/{contactId}` — what a removal states (`BR-095`).
 *
 * The removal is **soft** and its acting member comes from the request's own authorization, so the only
 * thing the caller states is the version it read (`ADR-022` D8, D9). No reason is required and none is
 * modelled (`BR-042`).
 */
@Serializable
data class RemoveCustomerContactRequest(
    val expectedVersion: Int,
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
