package com.servora.android.ui.signin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.servora.android.R
import com.servora.android.data.auth.AuthFailureReason
import com.servora.android.ui.appearance.AppearanceControls
import com.servora.android.ui.auth.ClearFocusWhenImeHidden

/**
 * Sign-in entry point of the Android application.
 *
 * Scope of this slice: authenticate against `POST /auth/sign-in` and show the outcome. There is
 * no signed-in destination yet, so a successful attempt surfaces a confirmation instead of
 * navigating (`BR-042`: no behaviour is invented for something the product has not defined).
 */
@Composable
fun SignInScreen(
    viewModel: SignInViewModel,
    onForgotPassword: () -> Unit = {},
    onSmsSignIn: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsState()
    SignInScreen(
        uiState = uiState,
        onEmailChange = viewModel::onEmailChange,
        onPasswordChange = viewModel::onPasswordChange,
        onTogglePasswordVisibility = viewModel::onTogglePasswordVisibility,
        onSubmit = viewModel::onSubmit,
        onForgotPassword = onForgotPassword,
        onSmsSignIn = onSmsSignIn,
        modifier = modifier,
    )
}

/**
 * Stateless rendering of the screen, so the layout can be driven by any state.
 *
 * [imeVisible] lets tests drive the keyboard/field-focus interaction
 * ([ClearFocusWhenImeHidden]); production callers use the window's IME inset visibility.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SignInScreen(
    uiState: SignInUiState,
    onEmailChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onTogglePasswordVisibility: () -> Unit,
    onSubmit: () -> Unit,
    onForgotPassword: () -> Unit = {},
    onSmsSignIn: () -> Unit = {},
    modifier: Modifier = Modifier,
    imeVisible: Boolean = WindowInsets.isImeVisible,
) {
    val focusManager = LocalFocusManager.current
    ClearFocusWhenImeHidden(imeVisible = imeVisible)

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize()) {
            AppearanceControls(
                // Pinned in the page margins above the form, as designed (`px-5 pt-5`), and inside
                // the status-bar inset: the window draws under the system bars (edge-to-edge is
                // enforced from Android 15, and the app never opts out), so the pinned row is
                // offset by the inset the platform reports rather than by a fixed height. On
                // devices where the system still insets the window, the reported inset is zero and
                // the row keeps the designed 20 dp margin.
                modifier = Modifier
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(start = 20.dp, end = 20.dp, top = 20.dp),
            )

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .imePadding()
                    .padding(horizontal = 24.dp, vertical = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Column(
                    modifier = Modifier.widthIn(max = 480.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = stringResource(R.string.app_name),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.sign_in_brand_subtitle),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(32.dp))

                    OutlinedTextField(
                        value = uiState.email,
                        onValueChange = onEmailChange,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !uiState.isSubmitting,
                        label = { Text(stringResource(R.string.sign_in_email_label)) },
                        placeholder = { Text(stringResource(R.string.sign_in_email_placeholder)) },
                        singleLine = true,
                        isError = uiState.fieldError == SignInFieldError.EMAIL,
                        supportingText = if (uiState.fieldError == SignInFieldError.EMAIL) {
                            { Text(stringResource(R.string.sign_in_error_email_required)) }
                        } else {
                            null
                        },
                        shape = MaterialTheme.shapes.medium,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Email,
                            imeAction = ImeAction.Next,
                        ),
                        keyboardActions = KeyboardActions(
                            onNext = { focusManager.moveFocus(FocusDirection.Down) },
                        ),
                    )

                    OutlinedTextField(
                        value = uiState.password,
                        onValueChange = onPasswordChange,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !uiState.isSubmitting,
                        label = { Text(stringResource(R.string.sign_in_password_label)) },
                        placeholder = { Text(stringResource(R.string.sign_in_password_placeholder)) },
                        singleLine = true,
                        isError = uiState.fieldError == SignInFieldError.PASSWORD,
                        supportingText = if (uiState.fieldError == SignInFieldError.PASSWORD) {
                            { Text(stringResource(R.string.sign_in_error_password_required)) }
                        } else {
                            null
                        },
                        shape = MaterialTheme.shapes.medium,
                        visualTransformation = if (uiState.passwordVisible) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Done,
                        ),
                        keyboardActions = KeyboardActions(onDone = { onSubmit() }),
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        TextButton(onClick = onForgotPassword, enabled = !uiState.isSubmitting) {
                            Text(stringResource(R.string.sign_in_forgot_password))
                        }
                        TextButton(
                            onClick = onTogglePasswordVisibility,
                            enabled = !uiState.isSubmitting,
                        ) {
                            Text(
                                text = stringResource(
                                    if (uiState.passwordVisible) {
                                        R.string.sign_in_hide_password
                                    } else {
                                        R.string.sign_in_show_password
                                    },
                                ),
                            )
                        }
                    }

                    SignInStatus(uiState)
                    Spacer(Modifier.height(16.dp))

                    Button(
                        onClick = onSubmit,
                        enabled = uiState.isSubmitEnabled,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        if (uiState.isSubmitting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                            Spacer(Modifier.width(12.dp))
                        }
                        Text(
                            text = stringResource(
                                if (uiState.isSubmitting) {
                                    R.string.sign_in_action_pending
                                } else {
                                    R.string.sign_in_action
                                },
                            ),
                        )
                    }

                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = onSmsSignIn,
                        enabled = !uiState.isSubmitting,
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        Text(stringResource(R.string.sign_in_sms_action))
                    }

                    Spacer(Modifier.height(24.dp))
                    Text(
                        text = stringResource(R.string.sign_in_copyright),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

/** Renders the reason the last attempt failed, if it did. */
@Composable
private fun SignInStatus(uiState: SignInUiState, modifier: Modifier = Modifier) {
    val reason = uiState.failureReason ?: return
    val failure = reason.localizedMessage()
    SignInAlert(
        title = failure.title,
        message = failure.message,
        containerColor = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        modifier = modifier,
    )
}

/** Localized copy for the failure reasons a sign-in attempt can produce. */
@Composable
private fun AuthFailureReason.localizedMessage(): SignInFailureMessage = when (this) {
    AuthFailureReason.INVALID_CREDENTIALS -> SignInFailureMessage(
        title = null,
        message = stringResource(R.string.sign_in_error_invalid_credentials),
    )

    AuthFailureReason.VALIDATION -> SignInFailureMessage(
        title = null,
        message = stringResource(R.string.sign_in_error_validation),
    )

    AuthFailureReason.NETWORK -> SignInFailureMessage(
        title = stringResource(R.string.sign_in_error_network_title),
        message = stringResource(R.string.sign_in_error_network_message),
    )

    // A code failure can only arrive on a screen that asked for a code; the sign-in screen never
    // does, so it shows the shared one-time-code copy rather than inventing its own.
    AuthFailureReason.RESET_CODE_INVALID -> SignInFailureMessage(
        title = null,
        message = stringResource(R.string.auth_error_reset_code_invalid),
    )

    AuthFailureReason.OTP_CODE_INVALID -> SignInFailureMessage(
        title = null,
        message = stringResource(R.string.auth_error_otp_code_invalid),
    )

    AuthFailureReason.TOO_MANY_REQUESTS -> SignInFailureMessage(
        title = null,
        message = stringResource(R.string.auth_error_too_many_requests),
    )

    // An unexpected failure is presented like a server failure rather than inventing new copy.
    AuthFailureReason.SERVER, AuthFailureReason.UNEXPECTED -> SignInFailureMessage(
        title = stringResource(R.string.sign_in_error_server_title),
        message = stringResource(R.string.sign_in_error_server_message),
    )
}

private data class SignInFailureMessage(val title: String?, val message: String)

/** Message surface shared by the success and failure states. */
@Composable
private fun SignInAlert(
    title: String?,
    message: String,
    containerColor: Color,
    contentColor: Color,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = containerColor,
        contentColor = contentColor,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            if (title != null) {
                Text(text = title, style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
            }
            Text(text = message, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
