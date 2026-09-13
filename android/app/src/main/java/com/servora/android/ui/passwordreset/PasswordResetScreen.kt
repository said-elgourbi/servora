package com.servora.android.ui.passwordreset

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import com.servora.android.ui.auth.ClearFocusWhenImeHidden
import com.servora.android.ui.auth.authFailureMessage

/**
 * Forgotten-password flow (`BR-043`, Figma `fp-username` → `fp-verify` → `fp-newpassword` →
 * `fp-done`).
 *
 * The screen renders one step at a time and reports intent to [PasswordResetViewModel]; it makes
 * no decision about the identity, the code or the password policy, which are the backend's
 * (`BR-001`, `BR-007`). Authentication requires connectivity, so there is no offline path here
 * (`ADR-006` D9).
 */
@Composable
fun PasswordResetScreen(
    viewModel: PasswordResetViewModel,
    onBackToSignIn: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsState()
    PasswordResetScreen(
        uiState = uiState,
        onEmailChange = viewModel::onEmailChange,
        onCodeChange = viewModel::onCodeChange,
        onNewPasswordChange = viewModel::onNewPasswordChange,
        onConfirmPasswordChange = viewModel::onConfirmPasswordChange,
        onTogglePasswordVisibility = viewModel::onTogglePasswordVisibility,
        onStepBack = viewModel::onStepBack,
        onSubmit = viewModel::onSubmit,
        onBackToSignIn = onBackToSignIn,
        modifier = modifier,
    )
}

/**
 * Stateless rendering of the flow, so the layout can be driven by any state.
 *
 * [imeVisible] lets tests drive the keyboard/field-focus interaction
 * ([ClearFocusWhenImeHidden]); production callers use the window's IME inset visibility.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PasswordResetScreen(
    uiState: PasswordResetUiState,
    onEmailChange: (String) -> Unit,
    onCodeChange: (String) -> Unit,
    onNewPasswordChange: (String) -> Unit,
    onConfirmPasswordChange: (String) -> Unit,
    onTogglePasswordVisibility: () -> Unit,
    onStepBack: () -> Unit,
    onSubmit: () -> Unit,
    onBackToSignIn: () -> Unit,
    modifier: Modifier = Modifier,
    imeVisible: Boolean = WindowInsets.isImeVisible,
) {
    ClearFocusWhenImeHidden(imeVisible = imeVisible)
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier =
                Modifier.fillMaxSize()
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
                    text = stringResource(R.string.password_reset_title),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(subtitleFor(uiState.step)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(32.dp))

                when (uiState.step) {
                    PasswordResetStep.IDENTITY -> IdentityStep(uiState, onEmailChange, onSubmit)
                    PasswordResetStep.CODE -> CodeStep(uiState, onCodeChange, onSubmit)
                    PasswordResetStep.NEW_PASSWORD ->
                        NewPasswordStep(
                            uiState,
                            onNewPasswordChange,
                            onConfirmPasswordChange,
                            onTogglePasswordVisibility,
                            onSubmit,
                        )
                    PasswordResetStep.DONE -> Unit
                }

                uiState.failureReason?.let { reason ->
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = authFailureMessage(reason),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                    )
                }

                Spacer(Modifier.height(16.dp))
                if (uiState.step != PasswordResetStep.DONE) SubmitButton(uiState, onSubmit)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    if (uiState.canStepBack) {
                        TextButton(onClick = onStepBack, enabled = !uiState.isSubmitting) {
                            Text(stringResource(R.string.password_reset_back))
                        }
                    }
                    TextButton(onClick = onBackToSignIn, enabled = !uiState.isSubmitting) {
                        Text(stringResource(R.string.password_reset_back_to_sign_in))
                    }
                }
            }
        }
    }
}

/** The step's explanatory copy. It never states whether the identity has an account. */
private fun subtitleFor(step: PasswordResetStep): Int =
    when (step) {
        PasswordResetStep.IDENTITY -> R.string.password_reset_subtitle_identity
        PasswordResetStep.CODE -> R.string.password_reset_subtitle_code
        PasswordResetStep.NEW_PASSWORD -> R.string.password_reset_subtitle_new_password
        PasswordResetStep.DONE -> R.string.password_reset_done_message
    }

private fun submitLabel(step: PasswordResetStep): Int =
    when (step) {
        PasswordResetStep.IDENTITY -> R.string.password_reset_action_send
        PasswordResetStep.CODE -> R.string.password_reset_action_verify
        PasswordResetStep.NEW_PASSWORD -> R.string.password_reset_action_save
        PasswordResetStep.DONE -> R.string.password_reset_back_to_sign_in
    }

private fun submitPendingLabel(step: PasswordResetStep): Int =
    when (step) {
        PasswordResetStep.IDENTITY -> R.string.password_reset_action_sending
        PasswordResetStep.CODE -> R.string.password_reset_action_verifying
        PasswordResetStep.NEW_PASSWORD -> R.string.password_reset_action_saving
        PasswordResetStep.DONE -> R.string.password_reset_back_to_sign_in
    }

@Composable
private fun IdentityStep(
    uiState: PasswordResetUiState,
    onEmailChange: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    AuthTextField(
        value = uiState.email,
        onValueChange = onEmailChange,
        enabled = !uiState.isSubmitting,
        label = stringResource(R.string.sign_in_email_label),
        placeholder = stringResource(R.string.sign_in_email_placeholder),
        isError = uiState.fieldError == PasswordResetFieldError.EMAIL,
        supportingText = stringResource(R.string.password_reset_error_email_required),
        keyboardOptions =
            KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onSubmit() }),
    )
}

@Composable
private fun CodeStep(
    uiState: PasswordResetUiState,
    onCodeChange: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    AuthTextField(
        value = uiState.code,
        onValueChange = onCodeChange,
        enabled = !uiState.isSubmitting,
        label = stringResource(R.string.password_reset_code_label),
        placeholder = stringResource(R.string.password_reset_code_placeholder),
        isError = uiState.fieldError == PasswordResetFieldError.CODE,
        supportingText = stringResource(R.string.password_reset_error_code_required),
        keyboardOptions =
            KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onSubmit() }),
    )
}

@Composable
private fun NewPasswordStep(
    uiState: PasswordResetUiState,
    onNewPasswordChange: (String) -> Unit,
    onConfirmPasswordChange: (String) -> Unit,
    onTogglePasswordVisibility: () -> Unit,
    onSubmit: () -> Unit,
) {
    val focusManager = LocalFocusManager.current
    val visualTransformation =
        if (uiState.passwordVisible) {
            VisualTransformation.None
        } else {
            PasswordVisualTransformation()
        }
    AuthTextField(
        value = uiState.newPassword,
        onValueChange = onNewPasswordChange,
        enabled = !uiState.isSubmitting,
        label = stringResource(R.string.password_reset_new_password_label),
        placeholder = stringResource(R.string.sign_in_password_placeholder),
        isError = uiState.fieldError == PasswordResetFieldError.PASSWORD,
        supportingText = stringResource(R.string.password_reset_error_password_required),
        visualTransformation = visualTransformation,
        keyboardOptions =
            KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next),
        keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) }),
    )
    Spacer(Modifier.height(12.dp))
    AuthTextField(
        value = uiState.confirmPassword,
        onValueChange = onConfirmPasswordChange,
        enabled = !uiState.isSubmitting,
        label = stringResource(R.string.password_reset_confirm_password_label),
        placeholder = stringResource(R.string.sign_in_password_placeholder),
        isError = uiState.fieldError == PasswordResetFieldError.CONFIRM_PASSWORD,
        supportingText = stringResource(R.string.password_reset_error_passwords_do_not_match),
        visualTransformation = visualTransformation,
        keyboardOptions =
            KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onSubmit() }),
    )
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        TextButton(onClick = onTogglePasswordVisibility, enabled = !uiState.isSubmitting) {
            Text(
                stringResource(
                    if (uiState.passwordVisible) {
                        R.string.sign_in_hide_password
                    } else {
                        R.string.sign_in_show_password
                    },
                ),
            )
        }
    }
}

/** One text field, so the three steps cannot drift apart in behaviour or accessibility. */
@Composable
private fun AuthTextField(
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    label: String,
    placeholder: String,
    isError: Boolean,
    supportingText: String,
    keyboardOptions: KeyboardOptions,
    keyboardActions: KeyboardActions,
    visualTransformation: VisualTransformation = VisualTransformation.None,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        enabled = enabled,
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        singleLine = true,
        isError = isError,
        supportingText =
            if (isError) {
                { Text(supportingText) }
            } else {
                null
            },
        shape = MaterialTheme.shapes.medium,
        visualTransformation = visualTransformation,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
    )
}

@Composable
private fun SubmitButton(uiState: PasswordResetUiState, onSubmit: () -> Unit) {
    Button(
        onClick = onSubmit,
        enabled = uiState.isSubmitEnabled,
        modifier = Modifier.fillMaxWidth().height(52.dp),
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
            text =
                stringResource(
                    if (uiState.isSubmitting) {
                        submitPendingLabel(uiState.step)
                    } else {
                        submitLabel(uiState.step)
                    },
                ),
        )
    }
}
