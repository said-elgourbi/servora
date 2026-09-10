package com.servora.android.domain.model

/**
 * Foundation roles (BR-003). Custom, organization-specific roles are a later
 * feature; the role is always a stable code, never a display label.
 */
enum class MemberRole {
    MANAGER,
    TECHNICIAN,
}

/** Stable, machine-readable membership status code. */
enum class MemberStatus {
    ACTIVE,
    INACTIVE,
}

/**
 * Links a [User] to an [Organization] with exactly one [role]. A user may
 * belong to more than one organization.
 */
data class OrganizationMember(
    val id: String,
    val organizationId: String,
    val userId: String,
    val role: MemberRole,
    val status: MemberStatus,
    val joinedAt: String,
    val createdAt: String,
    val updatedAt: String,
)
