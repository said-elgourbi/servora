package com.servora.android.domain.model

/** Stable, machine-readable user status code. */
enum class UserStatus {
    ACTIVE,
    INACTIVE,
    SUSPENDED,
}

/**
 * Authentication identity only: email plus server-managed credentials.
 *
 * Personal information lives in [UserProfile] and organizational role in
 * [OrganizationMember]. The password hash is never returned by the API and is
 * therefore absent from this model.
 */
data class User(
    val id: String,
    val email: String,
    val phone: String?,
    val status: UserStatus,
    val lastLoginAt: String?,
    val createdAt: String,
    val updatedAt: String,
)
