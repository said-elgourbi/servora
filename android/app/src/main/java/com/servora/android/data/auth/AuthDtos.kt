package com.servora.android.data.auth

import kotlinx.serialization.Serializable

/*
 * Wire contracts for `POST /auth/sign-in` (`docs/api/authentication.md`).
 *
 * These types mirror the JSON exactly (camelCase, UUID identifiers, ISO-8601 UTC
 * timestamps) and stay in the data layer: the rest of the app exchanges
 * `com.servora.android.domain.model.IssuedSession` instead.
 */

/** Request body: credentials plus the device the session is issued to. */
@Serializable
data class SignInRequestDto(
    val email: String,
    val password: String,
    val device: SignInDeviceDto,
)

/** Device block, so the backend can list and revoke sessions per device. */
@Serializable
data class SignInDeviceDto(
    val platform: String,
    val deviceId: String,
    val deviceName: String? = null,
    val appVersion: String? = null,
)

/** Response body of a successful sign-in. */
@Serializable
data class SignInResponseDto(
    val sessionId: String,
    val accessToken: String,
    val accessTokenExpiresAt: String,
    val refreshToken: String,
)

/**
 * Error envelope shared by the API. [code] is the stable machine-readable value
 * the client reacts to; [message] is diagnostic text and is never shown to users
 * as-is (`BR-028`).
 */
@Serializable
data class AuthErrorBodyDto(
    val statusCode: Int,
    val code: String,
    val message: String,
)

/*
 * Wire contracts for password reset and phone/SMS authentication
 * (`docs/api/authentication.md` §3.6–§3.10).
 *
 * Request bodies only mirror the API: the client composes no credential and decides no rule.
 * The responses of these calls carry no account information, which is why most of them have no
 * response DTO at all (`BR-044`).
 */

/** Request body: the identity a password reset is requested for (`BR-043`). */
@Serializable
data class PasswordResetRequestDto(
    val email: String,
)

/** Request body: the reset code a user was sent. */
@Serializable
data class PasswordResetVerifyRequestDto(
    val email: String,
    val code: String,
)

/** Request body: the reset code plus the password the user chose. */
@Serializable
data class PasswordResetCompleteRequestDto(
    val email: String,
    val code: String,
    val newPassword: String,
)

/** Request body: the phone number an SMS one-time password is requested for (`BR-019`). */
@Serializable
data class SmsCodeRequestDto(
    val phone: String,
)

/**
 * Request body: the phone number, the code, and the device the session is issued to.
 *
 * The device block is the same one sign-in sends, because a successful verification opens the
 * same kind of session.
 */
@Serializable
data class SmsCodeVerifyRequestDto(
    val phone: String,
    val code: String,
    val device: SignInDeviceDto,
)
