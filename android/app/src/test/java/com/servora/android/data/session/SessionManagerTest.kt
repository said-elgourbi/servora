package com.servora.android.data.session

import com.servora.android.data.auth.AuthApi
import com.servora.android.data.auth.AuthMeDto
import com.servora.android.data.auth.PasswordResetCompleteRequestDto
import com.servora.android.data.auth.PasswordResetRequestDto
import com.servora.android.data.auth.PasswordResetVerifyRequestDto
import com.servora.android.data.auth.RefreshRequestDto
import com.servora.android.data.auth.SignInRequestDto
import com.servora.android.data.auth.SignInResponseDto
import com.servora.android.data.auth.SmsCodeRequestDto
import com.servora.android.data.auth.SmsCodeVerifyRequestDto
import com.servora.android.domain.model.AuthTokens
import com.servora.android.domain.model.IssuedSession
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The startup decision [DefaultSessionManager] makes from a stored session.
 *
 * These are the lifecycle cases a restart must handle: no session, a still-valid access token, an
 * expired one that still refreshes, an expired one the backend refuses, and an expired one when the
 * backend cannot be reached.
 */
class SessionManagerTest {

    @Test
    fun `signs out when no session is stored`() = runTest {
        val manager = manager(inMemorySessionStore())

        manager.restore()

        assertEquals(AuthState.SignedOut, manager.state.value)
    }

    @Test
    fun `restores a session whose access token is still valid, without renewing`() = runTest {
        val authenticator = FakeSessionAuthenticator()
        val store = inMemorySessionStore()
        store.store(session(expiresAt = "2026-01-01T00:10:00Z"))

        val manager = manager(store, authenticator)
        manager.restore()

        assertEquals(AuthState.SignedIn(setOf("customers.view")), manager.state.value)
        assertEquals(0, authenticator.renewCalls)
    }

    @Test
    fun `renews an expired session before signing in`() = runTest {
        val authenticator =
            FakeSessionAuthenticator(SessionRenewal.Renewed(accessToken = "access-2"))
        val store = inMemorySessionStore()
        store.store(session(expiresAt = "2025-12-31T23:59:00Z", accessToken = "access-1"))

        val manager = manager(store, authenticator)
        manager.restore()

        assertEquals(AuthState.SignedIn(setOf("customers.view")), manager.state.value)
        assertEquals("access-1", authenticator.renewedToken)
    }

    @Test
    fun `clears an expired session the backend refuses`() = runTest {
        val store = inMemorySessionStore()
        store.store(session(expiresAt = "2025-12-31T23:59:00Z"))
        val manager = manager(store, FakeSessionAuthenticator(SessionRenewal.Rejected))

        manager.restore()

        assertEquals(AuthState.SignedOut, manager.state.value)
        assertNull(store.current())
    }

    @Test
    fun `keeps an expired session when the renewal cannot reach the backend`() = runTest {
        val store = inMemorySessionStore()
        store.store(session(expiresAt = "2025-12-31T23:59:00Z"))
        val manager = manager(store, FakeSessionAuthenticator(SessionRenewal.Unavailable))

        manager.restore()

        assertEquals(AuthState.SignedIn(setOf("customers.view")), manager.state.value)
        assertNotNull(store.current())
    }

    @Test
    fun `restores only once`() = runTest {
        val store = inMemorySessionStore()
        store.store(session(expiresAt = "2026-01-01T00:10:00Z"))
        val manager = manager(store)
        manager.restore()

        // A second start must not re-read storage, which would undo the session just restored.
        store.clear()
        manager.restore()

        assertEquals(AuthState.SignedIn(setOf("customers.view")), manager.state.value)
    }

    @Test
    fun `sign-out clears the session and asks the backend to revoke it`() = runTest {
        val api = RecordingSignOutAuthApi()
        val store = inMemorySessionStore()
        store.store(session(expiresAt = "2026-01-01T00:10:00Z"))
        val manager = manager(store, authApi = api)
        manager.restore()

        manager.signOut()

        assertEquals(AuthState.SignedOut, manager.state.value)
        assertNull(store.current())
        assertEquals("Bearer access-1", api.signedOutAuthorization)
    }

    @Test
    fun `sign-out stays signed out when the backend cannot be reached`() = runTest {
        val api = RecordingSignOutAuthApi(signOutFails = true)
        val store = inMemorySessionStore()
        store.store(session(expiresAt = "2026-01-01T00:10:00Z"))
        val manager = manager(store, authApi = api)

        manager.signOut()

        assertEquals(AuthState.SignedOut, manager.state.value)
        assertNull(store.current())
    }

    private fun manager(
        store: SessionStore,
        authenticator: SessionAuthenticator = FakeSessionAuthenticator(),
        authApi: AuthApi = RecordingSignOutAuthApi(),
    ): DefaultSessionManager =
        DefaultSessionManager(store, authenticator, authApi, CLOCK)

    private fun session(
        expiresAt: String,
        accessToken: String = "access-1",
    ) = IssuedSession(
        sessionId = "session-1",
        tokens = AuthTokens(
            accessToken = accessToken,
            accessTokenExpiresAt = expiresAt,
            refreshToken = "refresh-1",
        ),
        permissions = setOf("customers.view"),
    )

    private companion object {
        val CLOCK: Clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC)
    }
}

/** A [SessionAuthenticator] whose renewal outcome each test decides. */
private class FakeSessionAuthenticator(
    private val renewal: SessionRenewal = SessionRenewal.Rejected,
) : SessionAuthenticator {

    var renewCalls = 0
    var renewedToken: String? = null

    override fun accessToken(): String? = null

    override suspend fun renew(rejectedToken: String): SessionRenewal {
        renewCalls += 1
        renewedToken = rejectedToken
        return renewal
    }
}

/** An [AuthApi] that records the sign-out it was asked to perform. */
private class RecordingSignOutAuthApi(
    private val signOutFails: Boolean = false,
) : AuthApi {

    var signedOutAuthorization: String? = null

    override suspend fun signOut(authorization: String) {
        if (signOutFails) {
            throw IOException("offline")
        }
        signedOutAuthorization = authorization
    }

    override suspend fun signIn(request: SignInRequestDto): SignInResponseDto = unsupported()
    override suspend fun refresh(request: RefreshRequestDto): SignInResponseDto = unsupported()
    override suspend fun me(authorization: String): AuthMeDto = unsupported()
    override suspend fun requestPasswordReset(request: PasswordResetRequestDto) = unsupported()
    override suspend fun verifyPasswordResetCode(request: PasswordResetVerifyRequestDto) =
        unsupported()

    override suspend fun completePasswordReset(request: PasswordResetCompleteRequestDto) =
        unsupported()

    override suspend fun requestSmsCode(request: SmsCodeRequestDto) = unsupported()
    override suspend fun verifySmsCode(request: SmsCodeVerifyRequestDto): SignInResponseDto =
        unsupported()

    private fun unsupported(): Nothing = error("only sign-out is part of this contract")
}
