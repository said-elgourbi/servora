package com.servora.android.data.session

import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Reading the subject local state is scoped by.
 *
 * The token is the server's HS256 access token: `sub` is the user id and `sid` the session. Only
 * `sub` is read, and nothing here decides anything — the API authorizes every call (`BR-001`).
 */
class AuthenticatedSubjectTest {

    @Test
    fun `reads the subject claim of a compact jws`() {
        val token = accessToken(subject = "user-1", session = "session-1")

        assertEquals("user-1", accessTokenSubject(token))
    }

    @Test
    fun `reads an unpadded payload`() {
        // A real token is not padded, so the length of the claims set is not a multiple of four.
        val token = accessToken(subject = "u", session = "s")

        assertEquals("u", accessTokenSubject(token))
    }

    @Test
    fun `returns null when the token has no subject`() {
        val token = signed("{\"sid\":\"session-1\"}")

        assertNull(accessTokenSubject(token))
    }

    @Test
    fun `returns null when the token is not a jws`() {
        assertNull(accessTokenSubject("not-a-token"))
        assertNull(accessTokenSubject("header.payload"))
        assertNull(accessTokenSubject(""))
    }

    @Test
    fun `returns null when the claims set is not readable`() {
        val token = signed("not json")

        assertNull(accessTokenSubject(token))
    }

    @Test
    fun `returns null when the subject is blank`() {
        val token = signed("{\"sub\":\"   \",\"sid\":\"session-1\"}")

        assertNull(accessTokenSubject(token))
    }

    /** Builds a compact JWS whose claims carry the given subject and session. */
    private fun accessToken(subject: String, session: String): String =
        signed("{\"sub\":\"$subject\",\"sid\":\"$session\"}")

    /**
     * Builds a compact JWS with [claims] as its payload.
     *
     * The signature is not verified by the client, so the value only has to be shaped like one.
     */
    private fun signed(claims: String): String {
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val header = encoder.encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".toByteArray())
        val payload = encoder.encodeToString(claims.toByteArray())
        val signature = encoder.encodeToString("signature".toByteArray())
        return "$header.$payload.$signature"
    }
}
