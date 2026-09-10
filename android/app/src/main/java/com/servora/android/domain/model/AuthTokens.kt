package com.servora.android.domain.model

/**
 * Credential pair produced by successful authentication.
 *
 * Mirrors the `IssuedAuthTokens` wire contract (camelCase, ISO-8601 UTC timestamps).
 * The raw [refreshToken] is returned to the client exactly once: the backend stores only
 * its hash, so the copy held here is the only one that exists. It must therefore be kept
 * in encrypted storage and never logged, and [accessTokenExpiresAt] is the server's
 * expiry instant rather than a device-computed one.
 */
data class AuthTokens(
    val accessToken: String,
    val accessTokenExpiresAt: String,
    val refreshToken: String,
)
