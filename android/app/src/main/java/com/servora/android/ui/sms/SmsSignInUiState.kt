package com.servora.android.ui.sms

import androidx.compose.runtime.Immutable
import com.servora.android.data.auth.AuthFailureReason

/**
 * Everything the phone/SMS sign-in screens render (`BR-019`).
 *
 * The copy for the code step never says whether the number belongs to an account, and the
 * resend action is only a request: the backend owns the cooldown and the attempt limits
 * (`BR-045`).
 */
@Immutable
data class SmsSignInUiState(
    val step: SmsSignInStep = SmsSignInStep.PHONE,
    val phone: String = "",
    val code: String = "",
    val isSubmitting: Boolean = false,
    val fieldError: SmsSignInFieldError? = null,
    val failureReason: AuthFailureReason? = null,
) {
    val isSubmitEnabled: Boolean get() = !isSubmitting

    /** Whether the user can go back to the number step. */
    val canStepBack: Boolean get() = step == SmsSignInStep.CODE
}

/** Where the user is in the phone sign-in flow. */
enum class SmsSignInStep {
    /** The number to send a one-time password to. */
    PHONE,

    /** The one-time password that was sent. */
    CODE,
}

/** The field a client-side validation problem belongs to. */
enum class SmsSignInFieldError { PHONE, CODE }
