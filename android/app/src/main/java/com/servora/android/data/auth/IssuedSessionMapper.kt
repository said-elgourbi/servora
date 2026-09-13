package com.servora.android.data.auth

import com.servora.android.domain.model.AuthTokens
import com.servora.android.domain.model.IssuedSession

/**
 * Maps the session body the backend issues for sign-in, SMS sign-in and refresh onto the domain
 * model (`docs/api/authentication.md` §3.1–§3.2).
 *
 * [permissions] defaults to the body's own set. The sign-in flows pass the permissions read from
 * `GET /auth/me` instead, so the UI is built from one authoritative authorization read; a refresh
 * uses the body's set because the backend resolves it the same way.
 */
internal fun SignInResponseDto.toIssuedSession(
    permissions: Collection<String> = this.permissions,
): IssuedSession =
    IssuedSession(
        sessionId = sessionId,
        tokens = AuthTokens(
            accessToken = accessToken,
            accessTokenExpiresAt = accessTokenExpiresAt,
            refreshToken = refreshToken,
        ),
        permissions = permissions.toSet(),
    )
