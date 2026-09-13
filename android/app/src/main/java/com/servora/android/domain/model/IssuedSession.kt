package com.servora.android.domain.model

/**
 * Result of a successful authentication: the session the server opened, plus the
 * credential pair that goes with it.
 *
 * Mirrors the `SignInResponse` wire contract (`POST /auth/sign-in`,
 * `docs/api/authentication.md` §3.1). The server names the session, so [sessionId]
 * identifies the row that a later sign-out or session listing acts on.
 */
data class IssuedSession(
    val sessionId: String,
    val tokens: AuthTokens,
    val permissions: Set<String> = emptySet(),
)
