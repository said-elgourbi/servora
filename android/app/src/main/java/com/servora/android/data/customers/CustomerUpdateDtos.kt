package com.servora.android.data.customers

import kotlinx.serialization.Serializable

/*
 * Wire contract for editing a customer (`BR-023`, `BR-087`).
 *
 * An edit states the customer's kind, the name the list and detail show, and the fields that kind
 * requires. When the stated type is not the customer's current one the API converts the customer: it
 * replaces the subtype record in the same transaction and records the conversion. Nothing is carried
 * across from the previous type, so this request never sends both subtype payloads.
 *
 * The API answers `PATCH /customers/{id}` with the same shape a create answers with
 * ([CreatedCustomerDto]): the customer header plus the subtype record it now has.
 */

/**
 * `PATCH|PUT /customers/{id}` — the customer's edited state (`BR-023`, `BR-087`).
 *
 * Exactly one of [individual] and [company] is sent, and which one follows [type]: the API rejects a
 * body that states a type and carries the other subrecord. Blank optional members are absent rather
 * than empty, and a member that is absent is left unchanged by the API.
 */
@Serializable
data class UpdateCustomerRequest(
    val type: String,
    val displayName: String,
    val email: String? = null,
    val phone: String? = null,
    val notes: String? = null,
    val status: String? = null,
    val individual: IndividualCustomerPayload? = null,
    val company: CompanyCustomerPayload? = null,
)
