package com.servora.android.data.auth

import com.servora.android.data.device.DeviceIdentity
import com.servora.android.data.session.SessionManager
import com.servora.android.data.session.SessionStore
import com.servora.android.domain.model.IssuedSession
import java.io.IOException
import javax.inject.Inject
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import retrofit2.HttpException

/**
 * Authenticates a Servora user and performs the credential flows around it. The backend stays
 * the authority for every decision (`BR-001`, `BR-007`): this client composes requests and maps
 * outcomes, and never evaluates a credential, a code or an attempt limit itself.
 */
interface AuthRepository {
    /** Requests a session for [email]/[password] (`BR-018`). */
    suspend fun signIn(email: String, password: String): SignInResult

    /** Asks the backend to start a password reset for [email] (`BR-043`). */
    suspend fun requestPasswordReset(email: String): AuthActionResult

    /**
     * Checks a reset code without consuming it, so the UI can advance to the new-password step.
     */
    suspend fun verifyPasswordResetCode(
        email: String,
        code: String,
    ): AuthActionResult

    /** Redeems a reset code and sets [newPassword]. */
    suspend fun completePasswordReset(
        email: String,
        code: String,
        newPassword: String,
    ): AuthActionResult

    /** Asks for an SMS one-time password for [phone] (`BR-019`). */
    suspend fun requestSmsCode(phone: String): AuthActionResult

    /** Verifies an SMS one-time password, which signs the user in. */
    suspend fun verifySmsCode(phone: String, code: String): SignInResult
}

/**
 * Default [AuthRepository]: calls the `/auth` endpoints and maps transport failures onto
 * [AuthFailureReason], so callers never work with HTTP concepts.
 */
class DefaultAuthRepository @Inject constructor(
    private val api: AuthApi,
    private val json: Json,
    private val deviceIdentity: DeviceIdentity,
    private val sessionStore: SessionStore,
    private val sessionManager: SessionManager,
) : AuthRepository {

    override suspend fun signIn(email: String, password: String): SignInResult =
        when (
            val attempt = attempt {
                api.signIn(signInRequest(email, password)).toIssuedSessionWithFreshPermissions()
            }
        ) {
            is Attempt.Value -> {
                // Authenticated calls read the token from here, so the session is kept the moment
                // the backend issues it (`BR-001`). Persisting it is what lets a restarted process
                // restore the session instead of asking for credentials again (`BR-014`).
                sessionStore.store(attempt.value)
                sessionManager.onAuthenticated(attempt.value)
                SignInResult.Success(attempt.value)
            }

            is Attempt.Failed -> SignInResult.Failure(attempt.reason)
        }

    override suspend fun requestPasswordReset(email: String): AuthActionResult =
        when (
            val attempt = attempt {
                api.requestPasswordReset(PasswordResetRequestDto(email = email))
            }
        ) {
            is Attempt.Value -> AuthActionResult.Success
            is Attempt.Failed -> AuthActionResult.Failure(attempt.reason)
        }

    override suspend fun verifyPasswordResetCode(
        email: String,
        code: String,
    ): AuthActionResult =
        when (
            val attempt = attempt {
                api.verifyPasswordResetCode(
                    PasswordResetVerifyRequestDto(email = email, code = code),
                )
            }
        ) {
            is Attempt.Value -> AuthActionResult.Success
            is Attempt.Failed -> AuthActionResult.Failure(attempt.reason)
        }

    override suspend fun completePasswordReset(
        email: String,
        code: String,
        newPassword: String,
    ): AuthActionResult =
        when (
            val attempt = attempt {
                api.completePasswordReset(
                    PasswordResetCompleteRequestDto(
                        email = email,
                        code = code,
                        newPassword = newPassword,
                    ),
                )
            }
        ) {
            is Attempt.Value -> AuthActionResult.Success
            is Attempt.Failed -> AuthActionResult.Failure(attempt.reason)
        }

    override suspend fun requestSmsCode(phone: String): AuthActionResult =
        when (
            val attempt = attempt { api.requestSmsCode(SmsCodeRequestDto(phone = phone)) }
        ) {
            is Attempt.Value -> AuthActionResult.Success
            is Attempt.Failed -> AuthActionResult.Failure(attempt.reason)
        }

    override suspend fun verifySmsCode(phone: String, code: String): SignInResult =
        when (
            val attempt = attempt {
                api.verifySmsCode(
                    SmsCodeVerifyRequestDto(phone = phone, code = code, device = device()),
                ).toIssuedSessionWithFreshPermissions()
            }
        ) {
            is Attempt.Value -> {
                sessionStore.store(attempt.value)
                sessionManager.onAuthenticated(attempt.value)
                SignInResult.Success(attempt.value)
            }

            is Attempt.Failed -> SignInResult.Failure(attempt.reason)
        }

    private fun signInRequest(email: String, password: String) =
        SignInRequestDto(email = email, password = password, device = device())

    /** The device this installation reports, so the backend can list and revoke sessions. */
    private fun device(): SignInDeviceDto =
        SignInDeviceDto(
            platform = deviceIdentity.platform,
            deviceId = deviceIdentity.deviceId,
            deviceName = deviceIdentity.deviceName,
            appVersion = deviceIdentity.appVersion,
        )

    /**
     * Refreshes capabilities from the authenticated session endpoint after login.
     *
     * The sign-in response remains the fallback, so older API builds still authenticate normally,
     * but current builds give the UI one authoritative permission read before navigation is built.
     */
    private suspend fun SignInResponseDto.toIssuedSessionWithFreshPermissions(): IssuedSession {
        val freshPermissions =
            try {
                api.me(authorization = "Bearer $accessToken").permissions
            } catch (failure: IOException) {
                if (permissions.isEmpty()) {
                    throw failure
                }
                permissions
            } catch (failure: HttpException) {
                if (permissions.isEmpty()) {
                    throw failure
                }
                permissions
            } catch (failure: SerializationException) {
                if (permissions.isEmpty()) {
                    throw failure
                }
                permissions
            }
        return toIssuedSession(permissions = freshPermissions)
    }

    /**
     * Runs one call and classifies its failure.
     *
     * Shared by every operation so all of them report failures identically: a caller cannot tell
     * from a result which endpoint produced it, and no HTTP concept escapes this file.
     */
    private suspend fun <T> attempt(block: suspend () -> T): Attempt<T> =
        try {
            Attempt.Value(block())
        } catch (failure: HttpException) {
            Attempt.Failed(failure.toFailureReason())
        } catch (failure: IOException) {
            Attempt.Failed(AuthFailureReason.NETWORK)
        } catch (failure: SerializationException) {
            // A body this build cannot represent is a contract mismatch, not a user error.
            Attempt.Failed(AuthFailureReason.UNEXPECTED)
        }

    private fun HttpException.toFailureReason(): AuthFailureReason =
        when (errorCode()) {
            AuthErrorCode.INVALID_CREDENTIALS -> AuthFailureReason.INVALID_CREDENTIALS
            AuthErrorCode.RESET_CODE_INVALID -> AuthFailureReason.RESET_CODE_INVALID
            AuthErrorCode.OTP_CODE_INVALID -> AuthFailureReason.OTP_CODE_INVALID
            AuthErrorCode.VALIDATION_FAILED -> AuthFailureReason.VALIDATION
            AuthErrorCode.TOO_MANY_REQUESTS -> AuthFailureReason.TOO_MANY_REQUESTS
            AuthErrorCode.UNAUTHENTICATED -> AuthFailureReason.INVALID_CREDENTIALS
            AuthErrorCode.REFRESH_TOKEN_INVALID -> AuthFailureReason.UNEXPECTED
            null -> when {
                code() >= HTTP_SERVER_ERROR -> AuthFailureReason.SERVER
                else -> AuthFailureReason.UNEXPECTED
            }
        }

    /** Reads the envelope code, tolerating a body that is missing or not JSON. */
    private fun HttpException.errorCode(): AuthErrorCode? {
        val body = runCatching { response()?.errorBody()?.string() }.getOrNull()
        if (body.isNullOrBlank()) {
            return null
        }
        return runCatching {
            AuthErrorCode.fromWireValue(
                json.decodeFromString(AuthErrorBodyDto.serializer(), body).code,
            )
        }.getOrNull()
    }

    private companion object {
        const val HTTP_SERVER_ERROR = 500
    }
}

/** The outcome of one API call, before it is shaped into a flow-specific result. */
private sealed interface Attempt<out T> {
    data class Value<T>(val value: T) : Attempt<T>

    data class Failed(val reason: AuthFailureReason) : Attempt<Nothing>
}
