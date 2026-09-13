package com.servora.android.ui.sms

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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.servora.android.R
import com.servora.android.ui.auth.ClearFocusWhenImeHidden
import com.servora.android.ui.auth.authFailureMessage

/**
 * Phone/SMS sign-in (`BR-019`, Figma `login` → `sms-phone` → `sms-otp`).
 *
 * The screen reports intent to [SmsSignInViewModel] and renders the outcome. It never decides
 * whether the number is eligible, whether the code is right, or whether a resend is allowed —
 * all of that is the backend's (`BR-001`, `BR-007`, `BR-045`).
 */
@Composable
fun SmsSignInScreen(
    viewModel: SmsSignInViewModel,
    onBackToSignIn: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsState()
    SmsSignInScreen(
        uiState = uiState,
        onPhoneChange = viewModel::onPhoneChange,
        onCodeChange = viewModel::onCodeChange,
        onStepBack = viewModel::onStepBack,
        onResendCode = viewModel::onResendCode,
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
internal fun SmsSignInScreen(
    uiState: SmsSignInUiState,
    onPhoneChange: (String) -> Unit,
    onCodeChange: (String) -> Unit,
    onStepBack: () -> Unit,
    onResendCode: () -> Unit,
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
                    text = stringResource(R.string.sms_sign_in_title),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    // The copy never claims the number has an account (`BR-044`).
                    text =
                        stringResource(
                            if (uiState.step == SmsSignInStep.PHONE) {
                                R.string.sms_sign_in_subtitle_phone
                            } else {
                                R.string.sms_sign_in_subtitle_code
                            },
                        ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(32.dp))

                if (uiState.step == SmsSignInStep.PHONE) {
                    PhoneStep(uiState, onPhoneChange, onSubmit)
                } else {
                    CodeStep(uiState, onCodeChange, onSubmit)
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
                SubmitButton(uiState, onSubmit)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    if (uiState.step == SmsSignInStep.CODE) {
                        TextButton(onClick = onResendCode, enabled = !uiState.isSubmitting) {
                            Text(stringResource(R.string.sms_sign_in_action_resend))
                        }
                    }
                    if (uiState.canStepBack) {
                        TextButton(onClick = onStepBack, enabled = !uiState.isSubmitting) {
                            Text(stringResource(R.string.sms_sign_in_back))
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

@Composable
private fun PhoneStep(
    uiState: SmsSignInUiState,
    onPhoneChange: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    SmsTextField(
        value = uiState.phone,
        onValueChange = onPhoneChange,
        enabled = !uiState.isSubmitting,
        label = stringResource(R.string.sms_sign_in_phone_label),
        placeholder = stringResource(R.string.sms_sign_in_phone_placeholder),
        isError = uiState.fieldError == SmsSignInFieldError.PHONE,
        supportingText = stringResource(R.string.sms_sign_in_error_phone_required),
        // `Phone` keeps the dialling keypad, which is what the number actually needs.
        keyboardType = KeyboardType.Phone,
        onSubmit = onSubmit,
    )
}

@Composable
private fun CodeStep(
    uiState: SmsSignInUiState,
    onCodeChange: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    SmsTextField(
        value = uiState.code,
        onValueChange = onCodeChange,
        enabled = !uiState.isSubmitting,
        label = stringResource(R.string.sms_sign_in_code_label),
        placeholder = stringResource(R.string.sms_sign_in_code_placeholder),
        isError = uiState.fieldError == SmsSignInFieldError.CODE,
        supportingText = stringResource(R.string.sms_sign_in_error_code_required),
        keyboardType = KeyboardType.NumberPassword,
        onSubmit = onSubmit,
    )
}

/** One text field, so both steps cannot drift apart in behaviour or accessibility. */
@Composable
private fun SmsTextField(
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    label: String,
    placeholder: String,
    isError: Boolean,
    supportingText: String,
    keyboardType: KeyboardType,
    onSubmit: () -> Unit,
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
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onSubmit() }),
    )
}

@Composable
private fun SubmitButton(uiState: SmsSignInUiState, onSubmit: () -> Unit) {
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
                    when {
                        uiState.isSubmitting && uiState.step == SmsSignInStep.PHONE ->
                            R.string.sms_sign_in_action_requesting
                        uiState.isSubmitting -> R.string.sms_sign_in_action_verifying
                        uiState.step == SmsSignInStep.PHONE ->
                            R.string.sms_sign_in_action_request_code
                        else -> R.string.sms_sign_in_action_verify
                    },
                ),
        )
    }
}
