package com.servora.android.ui.auth

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.servora.android.ui.passwordreset.PasswordResetScreen
import com.servora.android.ui.passwordreset.PasswordResetViewModel
import com.servora.android.ui.signin.SignInScreen
import com.servora.android.ui.signin.SignInViewModel
import com.servora.android.ui.sms.SmsSignInScreen
import com.servora.android.ui.sms.SmsSignInViewModel

/** The authentication destinations the app can show before any session exists. */
enum class AuthDestination {
    /** Email/password sign-in (`BR-018`), the entry point. */
    SIGN_IN,

    /** Forgotten password (`BR-043`). */
    PASSWORD_RESET,

    /** Phone/SMS sign-in (`BR-019`). */
    SMS_SIGN_IN,
}

/**
 * Hosts the pre-session authentication flow.
 *
 * Why a state machine rather than a navigation graph: the flow is three destinations that come
 * back to sign-in, and the application has no navigation library or signed-in destination yet.
 * Adding one dependency for this would be more machinery than the flow needs (`dev.md` §4);
 * when a signed-in area exists, this composable is what a navigation graph would replace.
 *
 * The destination survives configuration changes through [rememberSaveable], and the system back
 * gesture leaves the flow rather than stepping through it, because each screen offers its own
 * explicit way back.
 */
@Composable
fun AuthFlowScreen(
    signInViewModel: SignInViewModel,
    passwordResetViewModel: PasswordResetViewModel,
    smsSignInViewModel: SmsSignInViewModel,
    modifier: Modifier = Modifier,
) {
    var destination by rememberSaveable { mutableStateOf(AuthDestination.SIGN_IN.name) }

    fun show(target: AuthDestination) {
        // Returning to sign-in abandons whatever flow was open, so its transient state is cleared
        // (`BR-043`): the password-reset ViewModel outlives the destination it was shown for.
        if (target == AuthDestination.SIGN_IN) {
            passwordResetViewModel.reset()
        }
        destination = target.name
    }

    BackHandler(enabled = destination != AuthDestination.SIGN_IN.name) {
        show(AuthDestination.SIGN_IN)
    }

    when (AuthDestination.valueOf(destination)) {
        AuthDestination.SIGN_IN ->
            SignInScreen(
                viewModel = signInViewModel,
                onForgotPassword = { show(AuthDestination.PASSWORD_RESET) },
                onSmsSignIn = { show(AuthDestination.SMS_SIGN_IN) },
                modifier = modifier,
            )

        AuthDestination.PASSWORD_RESET ->
            PasswordResetScreen(
                viewModel = passwordResetViewModel,
                onBackToSignIn = { show(AuthDestination.SIGN_IN) },
                modifier = modifier,
            )

        AuthDestination.SMS_SIGN_IN ->
            SmsSignInScreen(
                viewModel = smsSignInViewModel,
                onBackToSignIn = { show(AuthDestination.SIGN_IN) },
                modifier = modifier,
            )
    }
}
