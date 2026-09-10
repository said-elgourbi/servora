package com.servora.android.ui.signin

import androidx.compose.runtime.Immutable
import com.servora.android.data.auth.AuthFailureReason

/**
 * Everything the sign-in screen renders.
 *
 * The screen owns no state of its own: it renders this and reports user intent back to
 * [SignInViewModel].
 */
@Immutable
data class SignInUiState(
    val email: String = "",
    val password: String = "",
    val passwordVisible: Boolean = false,
    val isSubmitting: Boolean = false,
    /** A client-side problem with one field, cleared as soon as that field is edited. */
    val fieldError: SignInFieldError? = null,
    /** Why the last attempt failed as reported by the repository, cleared on the next attempt. */
    val failureReason: AuthFailureReason? = null,
    /** The backend issued a session. Until a home screen exists this is the end of the flow. */
    val signedIn: Boolean = false,
) {
    /** The action stays tappable while the form is empty so an empty field can be reported. */
    val isSubmitEnabled: Boolean get() = !isSubmitting
}

/** The field a client-side validation problem belongs to. */
enum class SignInFieldError { EMAIL, PASSWORD }