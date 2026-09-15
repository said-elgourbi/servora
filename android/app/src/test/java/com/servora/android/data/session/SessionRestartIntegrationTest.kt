package com.servora.android.data.session

import com.servora.android.data.auth.AuthApi
import com.servora.android.data.auth.AuthMeDto
import com.servora.android.data.auth.DefaultAuthRepository
import com.servora.android.data.auth.PasswordResetCompleteRequestDto
import com.servora.android.data.auth.PasswordResetRequestDto
import com.servora.android.data.auth.PasswordResetVerifyRequestDto
import com.servora.android.data.auth.RefreshRequestDto
import com.servora.android.data.auth.SignInRequestDto
import com.servora.android.data.auth.SignInResponseDto
import com.servora.android.data.auth.SignInResult
import com.servora.android.data.auth.SmsCodeRequestDto
import com.servora.android.data.auth.SmsCodeVerifyRequestDto
import com.servora.android.data.device.DeviceIdentity
import com.servora.android.data.offline.FakeOfflineSessionLifecycle
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

/**
 * End-to-end restart behaviour: a session persisted by one process is restored by the next.
 *
 * The existing `SessionManagerTest` hands the manager a store that already holds the session in
 * memory, so it never exercises the disk read at startup. This wires the real
 * `DefaultAuthRepository` -> `SessionStore` -> `DefaultSessionManager` graph over one shared
 * [SessionStorage] and builds a *second* graph for the "restart", which is the interaction the
 * reported defect lives in.
 */
class SessionRestartIntegrationTest {

    @Test
    fun `a sign-in survives a process restart while the access token is valid`() = runTest {
        val storage = InMemorySessionStorage()

        val first = Process(storage, RestartFakeAuthApi())
        assertTrue(first.repository.signIn(EMAIL, PASSWORD) is SignInResult.Success)
        assertEquals(AuthState.SignedIn(PERMISSIONS), first.manager.state.value)

        val reopened = Process(storage, RestartFakeAuthApi())
        reopened.manager.restore()

        assertEquals(AuthState.SignedIn(PERMISSIONS), reopened.manager.state.value)
        assertEquals("access-1", reopened.store.accessToken())
    }

    @Test
    fun `an expired access token is renewed on startup and the renewal is persisted`() = runTest {
        val storage = InMemorySessionStorage()

        val first = Process(storage, RestartFakeAuthApi(signInExpiresAt = EXPIRED))
        assertTrue(first.repository.signIn(EMAIL, PASSWORD) is SignInResult.Success)

        // The restarted process finds an expired access token and must refresh silently.
        val second = Process(storage, RestartFakeAuthApi(signInExpiresAt = EXPIRED))
        second.manager.restore()

        assertEquals(AuthState.SignedIn(PERMISSIONS), second.manager.state.value)
        assertEquals("access-2", second.store.accessToken())
        assertNotNull(storage.stored)
        assertEquals("access-2", storage.stored?.tokens?.accessToken)
    }

    @Test
    fun `a session the backend refuses on startup is cleared and signs the user out`() = runTest {
        val storage = InMemorySessionStorage()

        val first = Process(storage, RestartFakeAuthApi(signInExpiresAt = EXPIRED))
        assertTrue(first.repository.signIn(EMAIL, PASSWORD) is SignInResult.Success)

        val second = Process(storage, RestartFakeAuthApi(refusedRefresh = true))
        second.manager.restore()

        assertEquals(AuthState.SignedOut, second.manager.state.value)
        assertNull(storage.stored)
    }

    @Test
    fun `sign-out clears the session for the next process`() = runTest {
        val storage = InMemorySessionStorage()

        val first = Process(storage, RestartFakeAuthApi())
        assertTrue(first.repository.signIn(EMAIL, PASSWORD) is SignInResult.Success)
        first.manager.signOut()

        assertEquals(AuthState.SignedOut, first.manager.state.value)
        assertNull(storage.stored)

        val reopened = Process(storage, RestartFakeAuthApi())
        reopened.manager.restore()

        assertEquals(AuthState.SignedOut, reopened.manager.state.value)
    }

    /** One process's DI graph, recreated for each simulated restart. */
    private class Process(storage: SessionStorage, api: AuthApi) {
        val store = SessionStore(storage)
        val manager = DefaultSessionManager(
            sessionStore = store,
            sessionAuthenticator = DefaultSessionAuthenticator(api, store),
            authApi = api,
            clock = CLOCK,
            // The offline hooks are exercised by their own tests; this one is about persistence.
            offline = FakeOfflineSessionLifecycle(),
        )
        val repository = DefaultAuthRepository(api, JSON, DEVICE, store, manager)
    }

    private companion object {
        const val EMAIL = "manager@servora.test"
        const val PASSWORD = "correct-horse-battery"
        const val EXPIRED = "2025-12-31T23:59:00Z"
        val PERMISSIONS = setOf("customers.view")
        val CLOCK: Clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC)
        val JSON = Json { ignoreUnknownKeys = true }
        val DEVICE = DeviceIdentity(
            platform = "ANDROID",
            deviceId = "9c1f0b64-0000-4000-8000-000000000003",
            deviceName = "Test Device",
            appVersion = "0.1.0-debug",
        )
    }
}


/** An [AuthApi] that issues a session, rotates it on refresh, and can refuse a refresh. */
private class RestartFakeAuthApi(
    private val signInExpiresAt: String = "2026-01-01T00:10:00Z",
    private val refusedRefresh: Boolean = false,
) : AuthApi {

    override suspend fun signIn(request: SignInRequestDto): SignInResponseDto =
        SignInResponseDto(
            sessionId = "session-1",
            accessToken = "access-1",
            accessTokenExpiresAt = signInExpiresAt,
            refreshToken = "refresh-1",
            permissions = listOf("customers.view"),
        )

    override suspend fun me(authorization: String): AuthMeDto =
        AuthMeDto(userId = "user-1", permissions = listOf("customers.view"))

    override suspend fun refresh(request: RefreshRequestDto): SignInResponseDto {
        if (refusedRefresh) {
            throw HttpException(
                Response.error<Unit>(
                    401,
                    """{"statusCode":401,"code":"REFRESH_TOKEN_INVALID","message":"ignored"}"""
                        .toResponseBody("application/json".toMediaType()),
                ),
            )
        }
        return SignInResponseDto(
            sessionId = "session-1",
            accessToken = "access-2",
            accessTokenExpiresAt = "2026-01-01T00:15:00Z",
            refreshToken = "refresh-2",
            permissions = listOf("customers.view"),
        )
    }

    override suspend fun signOut(authorization: String) = Unit
    override suspend fun requestPasswordReset(request: PasswordResetRequestDto) = Unit
    override suspend fun verifyPasswordResetCode(request: PasswordResetVerifyRequestDto) = Unit

    override suspend fun completePasswordReset(request: PasswordResetCompleteRequestDto) = Unit
    override suspend fun requestSmsCode(request: SmsCodeRequestDto) = Unit
    override suspend fun verifySmsCode(request: SmsCodeVerifyRequestDto): SignInResponseDto =
        throw IOException("not part of this flow")
}
