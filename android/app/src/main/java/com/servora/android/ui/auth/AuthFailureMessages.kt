package com.servora.android.ui.auth

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.servora.android.R
import com.servora.android.data.auth.AuthFailureReason

/**
 * Localized copy for a failed authentication call.
 *
 * One mapping for every authentication screen, because the API answers with one set of stable
 * codes for all of them (`BR-041`): the same code must not read differently depending on which
 * screen produced it.
 *
 * Note what the copy does *not* say: nothing distinguishes "no such account" from "wrong
 * credential" or "wrong code", so the text cannot leak account existence (`BR-044`).
 */
@Composable
fun authFailureMessage(reason: AuthFailureReason): String =
    stringResource(
        when (reason) {
            AuthFailureReason.INVALID_CREDENTIALS -> R.string.auth_error_invalid_credentials
            AuthFailureReason.RESET_CODE_INVALID -> R.string.auth_error_reset_code_invalid
            AuthFailureReason.OTP_CODE_INVALID -> R.string.auth_error_otp_code_invalid
            AuthFailureReason.VALIDATION -> R.string.auth_error_validation
            AuthFailureReason.TOO_MANY_REQUESTS -> R.string.auth_error_too_many_requests
            AuthFailureReason.NETWORK -> R.string.auth_error_network
            AuthFailureReason.SERVER,
            AuthFailureReason.UNEXPECTED -> R.string.auth_error_server
        },
    )
