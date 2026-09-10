package com.servora.android.ui.passwordreset

import androidx.compose.runtime.Immutable
import com.servora.android.data.auth.AuthFailureReason

/**
 * Everything the password-reset screens render (`BR-043`).
 *
 * The flow is a small state machine rather than a set of screens, because the API is a sequence
 * of calls that depend on one another: the identity is submitted, then the code, then the new
 * password. Each step's copy is deliberately generic, so nothing on screen says whether an
 * account exists (`BR-044`).
 */
@Immutable
data class PasswordResetUiState(
    val step: PasswordResetStep = PasswordResetStep.IDENTITY,
    val email: String = "",
    val code: String = "",
    val newPassword: String = "",
    val passwordVisible: Boolean = false,
    val isSubmitting: Boolean = false,
    /** A client-side problem with one field, cleared as soon as that field is edited. */
    val fieldError: PasswordResetFieldError? = null,
    /** Why the last attempt failed as reported by the repository, cleared on the next attempt. */
    val failureReason: AuthFailureReason? = null,
) {
    /** The action stays tappable while the form is empty so an empty field can be reported. */
    val isSubmitEnabled: Boolean get() = !isSubmitting

    /** Whether the flow can step back, so a header action is only offered when it works. */
    val canStepBack: Boolean
        get() = step == PasswordResetStep.CODE || step == PasswordResetStep.NEW_PASSWORD
}

/** Where the user is in the reset flow. */
enum class PasswordResetStep {
    /** The identity the reset is requested for. */
    IDENTITY,

    /** The code that was delivered. */
    CODE,

    /** The password to replace the old one with. */
    NEW_PASSWORD,

    /** The reset succeeded. */
    DONE,
}

/** The field a client-side validation problem belongs to. */
enum class PasswordResetFieldError { EMAIL, CODE, PASSWORD }
