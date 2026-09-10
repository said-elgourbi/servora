package com.servora.android.domain.model

/**
 * Personal information for a user, kept separate from authentication identity
 * ([User]) and organizational role ([OrganizationMember]).
 */
data class UserProfile(
    val userId: String,
    val firstName: String,
    val lastName: String,
    val displayName: String?,
    val phone: String?,
    val avatarUrl: String?,
    /** BCP-47 language tag, e.g. `en-CA` or `fr-CA`. */
    val locale: String,
    val timezone: String,
    val createdAt: String,
    val updatedAt: String,
)
