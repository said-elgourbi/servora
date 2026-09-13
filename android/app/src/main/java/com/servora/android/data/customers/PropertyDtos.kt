package com.servora.android.data.customers

import kotlinx.serialization.Serializable

/*
 * Wire contract for the Property lifecycle (`BR-082` – `BR-086`).
 *
 * The detail response carries the Property's authoritative values plus the derived counts the
 * archive confirmation shows. Every derived value — the open-work counts, the job count, the last
 * service date and whether deletion is currently allowed — is the backend's answer; the client only
 * presents it (`BR-001`, `BR-041`).
 */

/** `GET /customers/{customerId}/properties/{propertyId}` — one Property, `ACTIVE` or `ARCHIVED`. */
@Serializable
data class PropertyDetailDto(
    val id: String,
    val name: String? = null,
    val addressLine1: String,
    val addressLine2: String? = null,
    val city: String,
    val province: String,
    val postalCode: String,
    val country: String,
    val notes: String? = null,
    val status: String = "ACTIVE",
    val version: Int = 1,
    val archivedAt: String? = null,
    val jobCount: Int = 0,
    val lastServiceAt: String? = null,
    val archiveImpact: PropertyArchiveImpactDto = PropertyArchiveImpactDto(),
    val canBePermanentlyDeleted: Boolean = false,
)

/**
 * The authoritative open-work counts the archive confirmation displays (`BR-083`).
 *
 * This is the `archiveWarningOpenWork` classification: active Jobs are those whose status is not
 * terminal, and active Visits are those of those Jobs whose status is not terminal — including a
 * `DRAFT` Visit.
 */
@Serializable
data class PropertyArchiveImpactDto(
    val activeJobCount: Int = 0,
    val activeVisitCount: Int = 0,
)

/**
 * `PATCH|PUT /customers/{customerId}/properties/{propertyId}` — the fields an edit supplies.
 *
 * These are exactly the fields a create supplies, so the Add Property form is reused. The country is
 * still not a client field. `expectedVersion` is the version the client last saw.
 */
@Serializable
data class UpdatePropertyRequest(
    val name: String? = null,
    val addressLine1: String,
    val addressLine2: String? = null,
    val city: String,
    val province: String,
    val postalCode: String,
    val notes: String? = null,
    val expectedVersion: Int? = null,
)

/**
 * The body an archive or restore carries.
 *
 * A reason is not required by any rule. `clientOperationId` is the idempotency key an operation
 * carries so a replay is never applied twice (`BR-031`), and `capturedAt` is the device time, kept
 * for display only. `expectedVersion` is the optimistic-concurrency guard (`BR-086`).
 */
@Serializable
data class PropertyLifecycleRequest(
    val note: String? = null,
    val clientOperationId: String? = null,
    val capturedAt: String? = null,
    val expectedVersion: Int? = null,
)
