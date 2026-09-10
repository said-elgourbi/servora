package com.servora.android.data.auth

/**
 * Stable error codes returned by the authentication endpoints
 * (`docs/api/authentication.md`).
 *
 * The wire value is the machine-readable domain code: behaviour never depends on
 * the user-facing text that accompanies it (`dev.md` §8, `BR-041`).
 */
enum class AuthErrorCode(val wireValue: String) {
    /** The request body was rejected before any credential was checked. */
    VALIDATION_FAILED("VALIDATION_FAILED"),

    /** The email/password pair did not match a user. */
    INVALID_CREDENTIALS("INVALID_CREDENTIALS"),

    /** The request carried no usable authentication. */
    UNAUTHENTICATED("UNAUTHENTICATED"),

    /** The presented refresh token is unknown, expired or revoked. */
    REFRESH_TOKEN_INVALID("REFRESH_TOKEN_INVALID"),

    /** A password-reset code was wrong, expired, superseded or consumed. */
    RESET_CODE_INVALID("RESET_CODE_INVALID"),

    /** An SMS one-time password was wrong, expired, superseded or consumed. */
    OTP_CODE_INVALID("OTP_CODE_INVALID"),

    /** An authentication attempt exceeded a configured limit. */
    TOO_MANY_REQUESTS("TOO_MANY_REQUESTS"),
    ;

    companion object {
        /** Resolves a wire code, returning `null` for codes this build does not know. */
        fun fromWireValue(wireValue: String?): AuthErrorCode? =
            entries.firstOrNull { it.wireValue == wireValue }
    }
}
