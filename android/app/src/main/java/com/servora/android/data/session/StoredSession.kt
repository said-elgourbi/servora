package com.servora.android.data.session

import com.servora.android.domain.model.AuthTokens
import com.servora.android.domain.model.IssuedSession
import kotlinx.serialization.Serializable

/**
 * On-device representation of an [IssuedSession].
 *
 * This lives in the data layer, not the domain: it is a storage format and must not become a wire
 * or business contract (`dev.md` §6, §8). It carries exactly what restoring a session needs — the
 * session identity, the credential pair and the effective permissions read at sign-in — and nothing
 * else. No password or other raw credential is ever stored (`dev.md` §11).
 */
@Serializable
internal data class StoredSessionDto(
    val sessionId: String,
    val accessToken: String,
    val accessTokenExpiresAt: String,
    val refreshToken: String,
    val permissions: List<String> = emptyList(),
)

internal fun IssuedSession.toStoredSession(): StoredSessionDto =
    StoredSessionDto(
        sessionId = sessionId,
        accessToken = tokens.accessToken,
        accessTokenExpiresAt = tokens.accessTokenExpiresAt,
        refreshToken = tokens.refreshToken,
        permissions = permissions.toList(),
    )

internal fun StoredSessionDto.toIssuedSession(): IssuedSession =
    IssuedSession(
        sessionId = sessionId,
        tokens = AuthTokens(
            accessToken = accessToken,
            accessTokenExpiresAt = accessTokenExpiresAt,
            refreshToken = refreshToken,
        ),
        permissions = permissions.toSet(),
    )
