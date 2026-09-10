package com.servora.android.data.auth

import com.servora.android.data.device.DeviceIdentity
import com.servora.android.domain.model.IssuedSession
import java.io.IOException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

/**
 * How [DefaultAuthRepository] turns a `POST /auth/sign-in` answer into a [SignInResult].
 *
 * The backend decides whether credentials are valid (`BR-001`); these tests cover only how a
 * given HTTP answer is classified for the caller.
 */
class DefaultAuthRepositoryTest {

    private val deviceIdentity = DeviceIdentity(
        platform = "ANDROID",
        deviceId = "9c1f0b64-0000-4000-8000-000000000002",
        deviceName = "Test Device",
        appVersion = "0.1.0-debug",
    )

    @Test
    fun `sends the credentials with this installation's identity`() = runTest {
        val api = FakeAuthApi { signInResponse() }
        val repository = DefaultAuthRepository(api, JSON, deviceIdentity)

        val result = repository.signIn(EMAIL, PASSWORD)

        val request = requireNotNull(api.lastRequest)
        assertEquals(EMAIL, request.email)
        assertEquals(PASSWORD, request.password)
        assertEquals("ANDROID", request.device.platform)
        assertEquals(deviceIdentity.deviceId, request.device.deviceId)
        assertEquals("Test Device", request.device.deviceName)
        assertEquals("0.1.0-debug", request.device.appVersion)

        val session = assertSuccess(result)
        assertEquals("session-1", session.sessionId)
        assertEquals("access-token", session.tokens.accessToken)
        assertEquals("2026-01-01T00:00:00Z", session.tokens.accessTokenExpiresAt)
        assertEquals("refresh-token", session.tokens.refreshToken)
    }

    @Test
    fun `classifies rejected credentials`() = runTest {
        val result = repositoryFailingWith(httpFailure(401, errorBody("INVALID_CREDENTIALS", 401)))
            .signIn(EMAIL, PASSWORD)

        assertEquals(AuthFailureReason.INVALID_CREDENTIALS, assertFailure(result))
    }

    @Test
    fun `classifies an unauthenticated answer as rejected credentials`() = runTest {
        val result = repositoryFailingWith(httpFailure(401, errorBody("UNAUTHENTICATED", 401)))
            .signIn(EMAIL, PASSWORD)

        assertEquals(AuthFailureReason.INVALID_CREDENTIALS, assertFailure(result))
    }

    @Test
    fun `classifies a rejected payload as validation`() = runTest {
        val result = repositoryFailingWith(httpFailure(400, errorBody("VALIDATION_FAILED", 400)))
            .signIn(EMAIL, PASSWORD)

        assertEquals(AuthFailureReason.VALIDATION, assertFailure(result))
    }

    @Test
    fun `classifies an unclassified server answer as a server failure`() = runTest {
        val result = repositoryFailingWith(httpFailure(503)).signIn(EMAIL, PASSWORD)

        assertEquals(AuthFailureReason.SERVER, assertFailure(result))
    }

    @Test
    fun `classifies an unclassified client error as unexpected`() = runTest {
        val result = repositoryFailingWith(httpFailure(409, errorBody("SOMETHING_UNKNOWN", 409)))
            .signIn(EMAIL, PASSWORD)

        assertEquals(AuthFailureReason.UNEXPECTED, assertFailure(result))
    }

    @Test
    fun `tolerates a body that is not the error envelope`() = runTest {
        val result = repositoryFailingWith(httpFailure(502, "<html>bad gateway</html>"))
            .signIn(EMAIL, PASSWORD)

        assertEquals(AuthFailureReason.SERVER, assertFailure(result))
    }

    @Test
    fun `classifies a lost connection as a network failure`() = runTest {
        val result = repositoryFailingWith(IOException("no route to host"))
            .signIn(EMAIL, PASSWORD)

        assertEquals(AuthFailureReason.NETWORK, assertFailure(result))
    }

    @Test
    fun `classifies an unreadable body as unexpected`() = runTest {
        val result = repositoryFailingWith(SerializationException("unexpected token"))
            .signIn(EMAIL, PASSWORD)

        assertEquals(AuthFailureReason.UNEXPECTED, assertFailure(result))
    }

    private fun repositoryFailingWith(failure: Throwable): DefaultAuthRepository =
        DefaultAuthRepository(FakeAuthApi { throw failure }, JSON, deviceIdentity)

    private fun assertSuccess(result: SignInResult): IssuedSession {
        assertTrue("expected a session, got $result", result is SignInResult.Success)
        return (result as SignInResult.Success).session
    }

    private fun assertFailure(result: SignInResult): AuthFailureReason {
        assertTrue("expected a failure, got $result", result is SignInResult.Failure)
        return (result as SignInResult.Failure).reason
    }

    private fun signInResponse() = SignInResponseDto(
        sessionId = "session-1",
        accessToken = "access-token",
        accessTokenExpiresAt = "2026-01-01T00:00:00Z",
        refreshToken = "refresh-token",
    )

    /** Mirrors what Retrofit raises when the backend answers with a non-2xx status. */
    private fun httpFailure(status: Int, body: String = "") = HttpException(
        Response.error<SignInResponseDto>(status, body.toResponseBody(JSON_MEDIA_TYPE)),
    )

    /**
     * Error envelope as the backend documents it (`docs/api/authentication.md`). The client
     * classifies on `code`, the machine-readable value (`BR-041`).
     */
    private fun errorBody(code: String, statusCode: Int) =
        """{"statusCode":$statusCode,"code":"$code","message":"ignored"}"""

    private companion object {
        const val EMAIL = "tech@servora.test"
        const val PASSWORD = "correct-horse-battery-staple"

        val JSON = Json { ignoreUnknownKeys = true }
        val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}

/** API double standing in for the generated Retrofit implementation. */
private class FakeAuthApi(
    private val answer: suspend (SignInRequestDto) -> SignInResponseDto,
) : AuthApi {

    var lastRequest: SignInRequestDto? = null

    override suspend fun signIn(request: SignInRequestDto): SignInResponseDto {
        lastRequest = request
        return answer(request)
    }

    // The remaining endpoints belong to the password-reset and phone/SMS flows, which this
    // double exists to keep out of the sign-in contract: failing loudly is more honest than a
    // silent success a test could mistake for the real thing.
    override suspend fun requestPasswordReset(request: PasswordResetRequestDto) =
        unsupported("requestPasswordReset")

    override suspend fun verifyPasswordResetCode(
        request: PasswordResetVerifyRequestDto,
    ) = unsupported("verifyPasswordResetCode")

    override suspend fun completePasswordReset(
        request: PasswordResetCompleteRequestDto,
    ) = unsupported("completePasswordReset")

    override suspend fun requestSmsCode(request: SmsCodeRequestDto) = unsupported("requestSmsCode")

    override suspend fun verifySmsCode(
        request: SmsCodeVerifyRequestDto,
    ): SignInResponseDto = unsupported("verifySmsCode")

    private fun unsupported(endpoint: String): Nothing =
        error("$endpoint is not part of the sign-in contract under test")
}
