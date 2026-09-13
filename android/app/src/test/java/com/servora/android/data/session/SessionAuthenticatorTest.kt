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
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

/**
 * How [DefaultSessionAuthenticator] renews a session the backend refused
 * (`docs/api/authentication.md` §3.2).
 *
 * A renewal rotates the refresh token, so these tests cover the two behaviours that make that
 * safe: the new pair replaces the stored one, and a caller that raced another renewal reuses the
 * fresh token instead of spending the rotation again.
 */
class SessionAuthenticatorTest {

    @Test
    fun `returns the stored access token`() = runTest {
        val authenticator = DefaultSessionAuthenticator(
            FakeAuthApi(),
            sessionStore(accessToken = "access-1", refreshToken = "refresh-1"),
        )

        assertEquals("access-1", authenticator.accessToken())
    }

    @Test
    fun `returns no token when no session is held`() {
        val authenticator = DefaultSessionAuthenticator(FakeAuthApi(), inMemorySessionStore())

        assertNull(authenticator.accessToken())
    }

    @Test
    fun `rotates the refresh token and keeps the renewed session`() = runTest {
        val api = FakeAuthApi(
            refreshAnswer = {
                SignInResponseDto(
                    sessionId = "session-1",
                    accessToken = "access-2",
                    accessTokenExpiresAt = "2026-01-01T00:15:00Z",
                    refreshToken = "refresh-2",
                    permissions = listOf("customers.view"),
                )
            },
        )
        val store = sessionStore(accessToken = "access-1", refreshToken = "refresh-1")
        val authenticator = DefaultSessionAuthenticator(api, store)

        val renewal = authenticator.renew("access-1")

        assertEquals(SessionRenewal.Renewed(accessToken = "access-2"), renewal)
        assertEquals("refresh-1", api.lastRefreshToken)
        assertEquals("access-2", store.accessToken())
        assertEquals("refresh-2", store.refreshToken())
    }

    @Test
    fun `reuses a token another caller already renewed without rotating again`() = runTest {
        val api = FakeAuthApi(
            refreshAnswer = {
                SignInResponseDto(
                    sessionId = "session-1",
                    accessToken = "access-2",
                    accessTokenExpiresAt = "2026-01-01T00:15:00Z",
                    refreshToken = "refresh-2",
                )
            },
        )
        val store = sessionStore(accessToken = "access-1", refreshToken = "refresh-1")
        val authenticator = DefaultSessionAuthenticator(api, store)

        authenticator.renew("access-1")
        val second = authenticator.renew("access-1")

        assertEquals(SessionRenewal.Renewed(accessToken = "access-2"), second)
        assertEquals(1, api.refreshCalls)
    }

    @Test
    fun `clears the session when the refresh token is refused`() = runTest {
        val api = FakeAuthApi(refreshAnswer = { throw httpFailure(401) })
        val store = sessionStore(accessToken = "access-1", refreshToken = "refresh-1")
        val authenticator = DefaultSessionAuthenticator(api, store)

        assertEquals(SessionRenewal.Rejected, authenticator.renew("access-1"))
        assertNull(store.accessToken())
        assertNull(store.refreshToken())
    }

    @Test
    fun `keeps the session when the renewal cannot reach the backend`() = runTest {
        val api = FakeAuthApi(refreshAnswer = { throw IOException("offline") })
        val store = sessionStore(accessToken = "access-1", refreshToken = "refresh-1")
        val authenticator = DefaultSessionAuthenticator(api, store)

        assertEquals(SessionRenewal.Unavailable, authenticator.renew("access-1"))
        assertEquals("access-1", store.accessToken())
    }

    @Test
    fun `reports rejected when no session is held`() = runTest {
        val authenticator = DefaultSessionAuthenticator(FakeAuthApi(), inMemorySessionStore())

        assertEquals(SessionRenewal.Rejected, authenticator.renew("access-1"))
    }

    private suspend fun sessionStore(accessToken: String, refreshToken: String) =
        inMemorySessionStore().apply {
            store(
                IssuedSession(
                    sessionId = "session-1",
                    tokens = AuthTokens(
                        accessToken = accessToken,
                        accessTokenExpiresAt = "2026-01-01T00:00:00Z",
                        refreshToken = refreshToken,
                    ),
                    permissions = setOf("customers.view"),
                ),
            )
        }

    private fun httpFailure(status: Int) = HttpException(
        Response.error<Unit>(
            status,
            """{"statusCode":$status,"code":"REFRESH_TOKEN_INVALID","message":"ignored"}"""
                .toResponseBody("application/json".toMediaType()),
        ),
    )
}

/** An [AuthApi] that answers a refresh and refuses to stand in for the other endpoints. */
private class FakeAuthApi(
    private val refreshAnswer: suspend (RefreshRequestDto) -> SignInResponseDto = {
        error("unexpected refresh")
    },
) : AuthApi {

    var lastRefreshToken: String? = null
    var refreshCalls: Int = 0

    override suspend fun refresh(request: RefreshRequestDto): SignInResponseDto {
        refreshCalls += 1
        lastRefreshToken = request.refreshToken
        return refreshAnswer(request)
    }

    override suspend fun signIn(request: SignInRequestDto): SignInResponseDto = unsupported()
    override suspend fun me(authorization: String): AuthMeDto = unsupported()
    override suspend fun signOut(authorization: String) = unsupported()
    override suspend fun requestPasswordReset(request: PasswordResetRequestDto) = unsupported()
    override suspend fun verifyPasswordResetCode(request: PasswordResetVerifyRequestDto) =
        unsupported()

    override suspend fun completePasswordReset(request: PasswordResetCompleteRequestDto) =
        unsupported()

    override suspend fun requestSmsCode(request: SmsCodeRequestDto) = unsupported()
    override suspend fun verifySmsCode(request: SmsCodeVerifyRequestDto): SignInResponseDto =
        unsupported()

    private fun unsupported(): Nothing = error("only refresh is part of this contract")
}
