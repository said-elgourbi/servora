package com.servora.android.data.auth

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST

/**
 * Retrofit contract for the authentication endpoints (`docs/api/authentication.md`).
 *
 * Sign-in is public: a rejected attempt fails with [retrofit2.HttpException] whose
 * body carries an [AuthErrorBodyDto]. Turning that into a user-meaningful outcome
 * is the caller's job, so this interface stays a faithful description of the wire.
 */
interface AuthApi {
    /** `POST /auth/sign-in` — exchanges credentials for a session and a token pair. */
    @POST("auth/sign-in")
    suspend fun signIn(@Body request: SignInRequestDto): SignInResponseDto

    /**
     * `POST /auth/refresh` — rotates the refresh token for a new pair and the same session
     * (`docs/api/authentication.md` §3.2).
     *
     * Used when the backend refuses a short-lived access token (`401`), so an authenticated read
     * can be retried without signing the user in again.
     */
    @POST("auth/refresh")
    suspend fun refresh(@Body request: RefreshRequestDto): SignInResponseDto

    /**
     * `POST /auth/sign-out` — revokes the session the presented access token names
     * (`docs/api/authentication.md` §3.3).
     *
     * It answers `204` with no body. Sign-out must not depend on this call succeeding: the device
     * forgets the session first and only then tries to revoke it, so an offline sign-out still signs
     * the user out.
     */
    @POST("auth/sign-out")
    suspend fun signOut(@Header("Authorization") authorization: String)


    /** `GET /auth/me` — returns the authenticated user's effective permission codes. */
    @GET("auth/me")
    suspend fun me(@Header("Authorization") authorization: String): AuthMeDto

    /**
     * `POST /auth/password-reset/request` — asks the backend to start a reset.
     *
     * The API answers the same way whether or not the address has an account, and returns no
     * body, so this call produces no result the UI could misinterpret (`BR-044`).
     */
    @POST("auth/password-reset/request")
    suspend fun requestPasswordReset(@Body request: PasswordResetRequestDto)

    /** `POST /auth/password-reset/verify` — checks a reset code without consuming it. */
    @POST("auth/password-reset/verify")
    suspend fun verifyPasswordResetCode(@Body request: PasswordResetVerifyRequestDto)

    /** `POST /auth/password-reset/complete` — redeems a reset code and sets the password. */
    @POST("auth/password-reset/complete")
    suspend fun completePasswordReset(@Body request: PasswordResetCompleteRequestDto)

    /** `POST /auth/sms/request` — asks for an SMS one-time password (`BR-019`). */
    @POST("auth/sms/request")
    suspend fun requestSmsCode(@Body request: SmsCodeRequestDto)

    /**
     * `POST /auth/sms/verify` — verifies an OTP and signs in.
     *
     * The response is the same session and token pair as sign-in: a phone verification is an
     * authentication, not a second kind of session.
     */
    @POST("auth/sms/verify")
    suspend fun verifySmsCode(@Body request: SmsCodeVerifyRequestDto): SignInResponseDto
}
