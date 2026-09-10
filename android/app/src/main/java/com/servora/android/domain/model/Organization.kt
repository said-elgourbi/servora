package com.servora.android.domain.model

/**
 * Stable, machine-readable organization status code. Never store a localized
 * display label as the value; the UI resolves the label from this code.
 */
enum class OrganizationStatus {
    ACTIVE,
    INACTIVE,
}

/**
 * An organization is the tenant boundary for every organization-owned entity
 * (customers today; jobs and later entities as they are added).
 *
 * Mirrors the `OrganizationDto` wire contract (camelCase, ISO-8601 UTC timestamps).
 */
data class Organization(
    val id: String,
    val name: String,
    val email: String?,
    val phone: String?,
    val status: OrganizationStatus,
    val createdAt: String,
    val updatedAt: String,
)
