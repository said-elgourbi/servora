package com.servora.android.data.session

import com.servora.android.domain.model.AuthTokens
import com.servora.android.domain.model.IssuedSession
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

/** The on-device representation of a session carries exactly the restoring fields and round-trips. */
class StoredSessionTest {

    @Test
    fun `round-trips a session through its stored form`() {
        val session = IssuedSession(
            sessionId = "session-1",
            tokens = AuthTokens(
                accessToken = "access-1",
                accessTokenExpiresAt = "2026-01-01T00:10:00Z",
                refreshToken = "refresh-1",
            ),
            permissions = setOf("customers.view", "customers.create"),
        )

        assertEquals(session, session.toStoredSession().toIssuedSession())
    }

    @Test
    fun `serialises to and from json`() {
        val session = IssuedSession(
            sessionId = "session-1",
            tokens = AuthTokens(
                accessToken = "access-1",
                accessTokenExpiresAt = "2026-01-01T00:10:00Z",
                refreshToken = "refresh-1",
            ),
            permissions = setOf("customers.view"),
        )

        val encoded = json.encodeToString(
            StoredSessionDto.serializer(),
            session.toStoredSession(),
        )
        val decoded = json
            .decodeFromString(StoredSessionDto.serializer(), encoded)
            .toIssuedSession()

        assertEquals(session, decoded)
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}
