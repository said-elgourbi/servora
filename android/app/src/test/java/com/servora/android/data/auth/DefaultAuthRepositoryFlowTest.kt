package com.servora.android.data.auth

import com.servora.android.data.device.DeviceIdentity
import java.io.IOException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

/**
 * How [DefaultAuthRepository] turns the password-reset and phone/SMS answers into results.
 *
 * Every decision (does the account exist, is this code right, is the attempt limit reached) is
 * the backend's (`BR-001`); these tests cover only how a given HTTP answer is classified, which
 * is the part the UI reacts to.
 */
class DefaultAuthRepositoryFlowTest {

    private val deviceIdentity = DeviceIdentity(
        platform = "ANDROID",
        deviceId = "9c1f0b64-0000-4000-8000-000000000002",
        deviceName = "Test Device",
        appVersion = "0.1.0-debug",
    )

    @Test
    fun `requests a password reset with the submitted identity`() = runTest {
        val api = RecordingAuthApi()

        val result = DefaultAuthRepository(api, FLOW_JSON, deviceIdentity)
            .requestPasswordReset(FLOW_EMAIL)

        assertEquals(AuthActionResult.Success, result)
        assertEquals(listOf("requestPasswordReset:$FLOW_EMAIL"), api.calls)
    }

    @Test
    fun `verifies and completes a reset with the code the user typed`() = runTest {
        val api = RecordingAuthApi()
        val repository = DefaultAuthRepository(api, FLOW_JSON, deviceIdentity)

        assertEquals(AuthActionResult.Success, repository.verifyPasswordResetCode(FLOW_EMAIL, FLOW_CODE))
        assertEquals(
            AuthActionResult.Success,
            repository.completePasswordReset(FLOW_EMAIL, FLOW_CODE, "a-new-password"),
        )

        assertEquals(
            listOf(
                "verifyPasswordResetCode:$FLOW_EMAIL:$FLOW_CODE",
                "completePasswordReset:$FLOW_EMAIL:$FLOW_CODE:a-new-password",
            ),
            api.calls,
        )
    }

    @Test
    fun `classifies a rejected reset code`() = runTest {
        val result = flowRepositoryFailingWith(flowFailure(401, "RESET_CODE_INVALID"))
            .verifyPasswordResetCode(FLOW_EMAIL, FLOW_CODE)

        assertEquals(AuthFailureReason.RESET_CODE_INVALID, flowAssertFailure(result))
    }

    @Test
    fun `classifies a rejected one-time password`() = runTest {
        val result = flowRepositoryFailingWith(flowFailure(401, "OTP_CODE_INVALID"))
            .verifySmsCode(FLOW_PHONE, FLOW_CODE)

        assertEquals(AuthFailureReason.OTP_CODE_INVALID, flowAssertFailure(result))
    }

    @Test
    fun `classifies an attempt limit`() = runTest {
        val result = flowRepositoryFailingWith(flowFailure(429, "TOO_MANY_REQUESTS"))
            .requestSmsCode(FLOW_PHONE)

        assertEquals(AuthFailureReason.TOO_MANY_REQUESTS, flowAssertFailure(result))
    }

    @Test
    fun `classifies a rejected payload and a missing connection`() = runTest {
        assertEquals(
            AuthFailureReason.VALIDATION,
            flowAssertFailure(
                flowRepositoryFailingWith(flowFailure(400, "VALIDATION_FAILED"))
                    .requestPasswordReset("not-an-address"),
            ),
        )
        assertEquals(
            AuthFailureReason.NETWORK,
            flowAssertFailure(
                flowRepositoryFailingWith(IOException("connection refused")).requestSmsCode(FLOW_PHONE),
            ),
        )
    }

    @Test
    fun `verifying an SMS code sends the device and returns the session`() = runTest {
        val api = RecordingAuthApi()

        val result = DefaultAuthRepository(api, FLOW_JSON, deviceIdentity).verifySmsCode(FLOW_PHONE, FLOW_CODE)

        assertEquals(listOf("verifySmsCode:$FLOW_PHONE:${deviceIdentity.deviceId}"), api.calls)
        val session = result as SignInResult.Success
        assertEquals("session-2", session.session.sessionId)
        assertEquals("refresh-2", session.session.tokens.refreshToken)
    }

    @Test
    fun `still classifies rejected password credentials`() = runTest {
        val result = flowRepositoryFailingWith(flowFailure(401, "INVALID_CREDENTIALS"))
            .signIn(FLOW_EMAIL, "a-password")

        assertEquals(AuthFailureReason.INVALID_CREDENTIALS, flowAssertFailure(result))
    }
}

private const val FLOW_EMAIL = "user@example.com"
private const val FLOW_CODE = "123456"
private const val FLOW_PHONE = "+15145550100"

private val FLOW_JSON = Json { ignoreUnknownKeys = true }

private val FLOW_DEVICE = DeviceIdentity(
    platform = "ANDROID",
    deviceId = "9c1f0b64-0000-4000-8000-000000000002",
    deviceName = "Test Device",
    appVersion = "0.1.0-debug",
)

/** An [AuthApi] that records what it was asked and can be made to fail on demand. */
private class RecordingAuthApi : AuthApi {
    var failWith: Throwable? = null
    val calls = mutableListOf<String>()

    private fun record(call: String) {
        calls += call
        failWith?.let { throw it }
    }

    override suspend fun signIn(request: SignInRequestDto): SignInResponseDto {
        record("signIn:${request.email}")
        return session("session-1", "refresh-1")
    }

    override suspend fun requestPasswordReset(request: PasswordResetRequestDto) {
        record("requestPasswordReset:${request.email}")
    }

    override suspend fun verifyPasswordResetCode(request: PasswordResetVerifyRequestDto) {
        record("verifyPasswordResetCode:${request.email}:${request.code}")
    }

    override suspend fun completePasswordReset(request: PasswordResetCompleteRequestDto) {
        record("completePasswordReset:${request.email}:${request.code}:${request.newPassword}")
    }

    override suspend fun requestSmsCode(request: SmsCodeRequestDto) {
        record("requestSmsCode:${request.phone}")
    }

    override suspend fun verifySmsCode(request: SmsCodeVerifyRequestDto): SignInResponseDto {
        record("verifySmsCode:${request.phone}:${request.device.deviceId}")
        return session("session-2", "refresh-2")
    }

    private fun session(sessionId: String, refreshToken: String) =
        SignInResponseDto(
            sessionId = sessionId,
            accessToken = "access-$sessionId",
            accessTokenExpiresAt = "2026-01-01T00:00:00Z",
            refreshToken = refreshToken,
        )
}

/** Builds the error envelope the API returns, so classification is tested against real FLOW_JSON. */
private fun flowFailure(status: Int, code: String): HttpException =
    HttpException(
        Response.error<Unit>(
            status,
            """{"statusCode":$status,"code":"$code","message":"presentation text"}"""
                .toResponseBody("application/json".toMediaType()),
        ),
    )

private fun flowRepositoryFailingWith(throwable: Throwable) =
    DefaultAuthRepository(
        RecordingAuthApi().apply { failWith = throwable },
        FLOW_JSON,
        FLOW_DEVICE,
    )

private fun flowAssertFailure(result: AuthActionResult): AuthFailureReason =
    when (result) {
        is AuthActionResult.Failure -> result.reason
        is AuthActionResult.Success -> throw AssertionError("expected a failure, got success")
    }

private fun flowAssertFailure(result: SignInResult): AuthFailureReason =
    when (result) {
        is SignInResult.Failure -> result.reason
        is SignInResult.Success -> throw AssertionError("expected a failure, got a session")
    }
