package com.servora.android.domain.model

/**
 * Stable, machine-readable code for the client platform that owns a session.
 *
 * Mirrors `AUTH_SESSION_PLATFORMS` on the API and the `auth_sessions.platform` CHECK
 * constraint. A future platform is added on the server first; the code is never a
 * display label.
 */
enum class AuthSessionPlatform {
    ANDROID,
    WEB,
}

/**
 * One signed-in session for a user on one client installation.
 *
 * A user may have several concurrent sessions and the same physical device may hold a
 * new session after re-installing, so sessions are identified by their own [id] rather
 * than by user or device. Mirrors the `AuthSessionDto` wire contract (camelCase,
 * ISO-8601 UTC timestamps).
 *
 * Token material is deliberately absent: refresh tokens are stored server-side as
 * hashes only, and the client keeps its own copy in secure storage. [revokedAt] is null
 * for an active session and carries the revocation instant otherwise, which is how
 * "signed out on another device" is represented.
 */
data class AuthSession(
    val id: String,
    val userId: String,
    val platform: AuthSessionPlatform,
    val deviceId: String,
    val deviceName: String?,
    val appVersion: String?,
    val ipAddress: String?,
    val userAgent: String?,
    val createdAt: String,
    val updatedAt: String,
    val lastUsedAt: String,
    val expiresAt: String,
    val revokedAt: String?,
)
