package com.servora.android.ui.auth

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.servora.android.data.session.AuthState
import com.servora.android.domain.auth.asPermissionChecker
import com.servora.android.ui.customers.AddContactViewModel
import com.servora.android.ui.customers.AddCustomerViewModel
import com.servora.android.ui.customers.AddPropertyViewModel
import com.servora.android.ui.customers.CustomersViewModel
import com.servora.android.ui.customers.EditContactViewModel
import com.servora.android.ui.customers.EditCustomerViewModel
import com.servora.android.ui.customers.EditPropertyViewModel
import com.servora.android.ui.customers.PropertyDetailViewModel
import com.servora.android.ui.customers.RemoveContactViewModel
import com.servora.android.ui.customers.ServoraHomeScreen
import com.servora.android.ui.customers.customerPermissionsUiState
import com.servora.android.ui.home.ManagerHomeViewModel
import com.servora.android.ui.home.TechnicianHomeViewModel
import com.servora.android.ui.jobs.CreateJobViewModel
import com.servora.android.ui.jobs.JobDetailsViewModel
import com.servora.android.ui.passwordreset.PasswordResetScreen
import com.servora.android.ui.passwordreset.PasswordResetViewModel
import com.servora.android.ui.schedule.ScheduleViewModel
import com.servora.android.ui.schedule.TechnicianScheduleViewModel
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
 * The authentication gate the whole app sits behind.
 *
 * The screen shown is derived from the session state, never from a per-screen flag, so a session
 * restored after process death reaches the authenticated area through the same path as a fresh
 * sign-in (`BR-014`). While the persisted session is being read (and, for an expired access token,
 * renewed) the app shows a loading state instead of sign-in, so no sign-in screen flashes before a
 * session is restored.
 *
 * Why a state machine rather than the navigation graph: the pre-session flow is three destinations
 * that each offer their own explicit way back to sign-in, so it has no back stack to keep. The
 * signed-in area, which does, uses `androidx.navigation` instead
 * (`docs/decisions/010-android-navigation.md`); this composable is the gate in front of it, not a
 * destination inside it.
 *
 * The destination survives configuration changes through [rememberSaveable], and the system back
 * gesture leaves the flow rather than stepping through it, because each screen offers its own
 * explicit way back.
 */
@Composable
fun AuthFlowScreen(
    sessionViewModel: SessionViewModel,
    signInViewModel: SignInViewModel,
    passwordResetViewModel: PasswordResetViewModel,
    smsSignInViewModel: SmsSignInViewModel,
    customersViewModel: CustomersViewModel,
    addCustomerViewModel: AddCustomerViewModel,
    editCustomerViewModel: EditCustomerViewModel,
    addPropertyViewModel: AddPropertyViewModel,
    propertyDetailViewModel: PropertyDetailViewModel,
    editPropertyViewModel: EditPropertyViewModel,
    addContactViewModel: AddContactViewModel,
    editContactViewModel: EditContactViewModel,
    removeContactViewModel: RemoveContactViewModel,
    managerHomeViewModel: ManagerHomeViewModel,
    technicianHomeViewModel: TechnicianHomeViewModel,
    jobDetailsViewModel: JobDetailsViewModel,
    createJobViewModel: CreateJobViewModel,
    scheduleViewModel: ScheduleViewModel,
    technicianScheduleViewModel: TechnicianScheduleViewModel,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val authState by sessionViewModel.state.collectAsState()

    when (val state = authState) {
        AuthState.Checking -> AuthLoadingScreen(modifier)

        AuthState.SignedOut ->
            AuthDestinations(
                signInViewModel = signInViewModel,
                passwordResetViewModel = passwordResetViewModel,
                smsSignInViewModel = smsSignInViewModel,
                modifier = modifier,
            )

        is AuthState.SignedIn ->
            ServoraHomeScreen(
                permissions = customerPermissionsUiState(state.permissions.asPermissionChecker()),
                customersViewModel = customersViewModel,
                addCustomerViewModel = addCustomerViewModel,
                editCustomerViewModel = editCustomerViewModel,
                addPropertyViewModel = addPropertyViewModel,
                propertyDetailViewModel = propertyDetailViewModel,
                editPropertyViewModel = editPropertyViewModel,
                addContactViewModel = addContactViewModel,
                editContactViewModel = editContactViewModel,
                removeContactViewModel = removeContactViewModel,
                managerHomeViewModel = managerHomeViewModel,
                technicianHomeViewModel = technicianHomeViewModel,
                jobDetailsViewModel = jobDetailsViewModel,
                createJobViewModel = createJobViewModel,
                scheduleViewModel = scheduleViewModel,
                technicianScheduleViewModel = technicianScheduleViewModel,
                onSignOut = onSignOut,
                modifier = modifier,
            )
    }
}

/**
 * The authentication flow shown while no session is held, driven by its own destination state.
 *
 * The destination lives here rather than in [AuthFlowScreen] so it exists only while the app is
 * signed out: leaving the authenticated area (sign-out) re-enters the flow at sign-in, with no
 * stale destination to restore.
 */
@Composable
private fun AuthDestinations(
    signInViewModel: SignInViewModel,
    passwordResetViewModel: PasswordResetViewModel,
    smsSignInViewModel: SmsSignInViewModel,
    modifier: Modifier,
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

/**
 * Shown while the stored session is read and, when its access token has expired, renewed.
 *
 * It is deliberately blank apart from a progress indicator: it exists to avoid a sign-in flash, not
 * to inform (`BR-014`).
 */
@Composable
private fun AuthLoadingScreen(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}
