package com.servora.android.data.customers

import kotlinx.serialization.Serializable

/*
 * Wire contract for `GET /customers`.
 *
 * Mirrors `CustomerDto` (camelCase, UUID identifiers, ISO-8601 UTC timestamps) and stays in the
 * data layer: the rest of the app exchanges `com.servora.android.domain.model.Customer` instead.
 * The shared `Json` configuration ignores unknown fields, so a later API addition does not break
 * this build (`dev.md` §7).
 */

/** One customer header row as the list endpoint returns it. */
@Serializable
data class CustomerDto(
    val id: String,
    val organizationId: String,
    val type: String,
    val displayName: String,
    val email: String? = null,
    val phone: String? = null,
    val billingEmail: String? = null,
    val billingPhone: String? = null,
    val notes: String? = null,
    val status: String,
    val propertyCount: Int = 0,
    val jobCount: Int = 0,
    val createdAt: String,
    val updatedAt: String,
)
