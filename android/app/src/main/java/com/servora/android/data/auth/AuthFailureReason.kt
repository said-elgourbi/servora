package com.servora.android.data.auth

/**
 * Stable reasons an authentication call can fail.
 *
 * These cover only behaviour the backend contract defines (`BR-042`): anything this build
 * cannot classify stays [UNEXPECTED] instead of being guessed at, and the UI never invents a
 * message for a condition the API does not describe.
 *
 * The vocabulary is shared by every authentication call — password sign-in, phone/SMS sign-in
 * and password reset — because the API answers with one set of stable codes for all of them
 * (`docs/api/authentication.md`).
 */
enum class AuthFailureReason {
    /** The credentials or the verification code were rejected. */
    INVALID_CREDENTIALS,

    /**
     * A password-reset code was rejected (`RESET_CODE_INVALID`).
     *
     * Kept apart from [INVALID_CREDENTIALS] because the user's next action differs: a reset code
     * is replaced, a password is not.
     */
    RESET_CODE_INVALID,

    /** An SMS one-time password was rejected (`OTP_CODE_INVALID`). */
    OTP_CODE_INVALID,

    /** The payload was rejected before anything was checked (`VALIDATION_FAILED`). */
    VALIDATION,

    /** An attempt limit was reached (`TOO_MANY_REQUESTS`). */
    TOO_MANY_REQUESTS,

    /** The request never reached the backend. */
    NETWORK,

    /** The backend could not complete the request (5xx). */
    SERVER,

    /** A response this build cannot interpret, or an undeclared failure. */
    UNEXPECTED,
}

/**
 * Outcome of an authentication call that produces no session.
 *
 * Password reset and requesting a one-time code deliberately return nothing on success, so the
 * client cannot turn "an account exists" into a different result (`BR-044`). A failure carries
 * only the stable reason the UI needs to react to.
 */
sealed interface AuthActionResult {
    /** The backend accepted the request. */
    data object Success : AuthActionResult

    /** The request failed; [reason] decides what the UI reports. */
    data class Failure(val reason: AuthFailureReason) : AuthActionResult
}
