package com.servora.android.ui.passwordreset

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.servora.android.data.auth.AuthActionResult
import com.servora.android.data.auth.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Drives the password-reset flow (`BR-043`).
 *
 * The ViewModel holds no credential state of its own beyond what the user is typing: it asks
 * [AuthRepository] to perform each step and reports what the repository decided. Whether a code
 * is valid, how many attempts remain and whether an identity exists are all backend decisions
 * (`BR-001`, `BR-007`).
 */
@HiltViewModel
class PasswordResetViewModel @Inject constructor(
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(PasswordResetUiState())
    val uiState: StateFlow<PasswordResetUiState> = _uiState.asStateFlow()

    fun onEmailChange(email: String) {
        _uiState.update { state ->
            state.copy(
                email = email,
                fieldError = state.fieldError.clearedIf(PasswordResetFieldError.EMAIL),
                failureReason = null,
            )
        }
    }

    fun onCodeChange(code: String) {
        _uiState.update { state ->
            state.copy(
                code = code.filter(Char::isDigit).take(AUTH_CODE_LENGTH),
                fieldError = state.fieldError.clearedIf(PasswordResetFieldError.CODE),
                failureReason = null,
            )
        }
    }

    fun onNewPasswordChange(newPassword: String) {
        _uiState.update { state ->
            state.copy(
                newPassword = newPassword,
                fieldError = state.fieldError.clearedIf(PasswordResetFieldError.PASSWORD),
                failureReason = null,
            )
        }
    }

    fun onTogglePasswordVisibility() {
        _uiState.update { it.copy(passwordVisible = !it.passwordVisible) }
    }

    /** Returns to the previous step, so a mistyped identity or code is not a dead end. */
    fun onStepBack() {
        _uiState.update { state ->
            val stepped =
                when (state.step) {
                    PasswordResetStep.CODE ->
                        state.copy(step = PasswordResetStep.IDENTITY, code = "")
                    PasswordResetStep.NEW_PASSWORD ->
                        state.copy(step = PasswordResetStep.CODE, newPassword = "")
                    else -> state
                }
            stepped.copy(fieldError = null, failureReason = null)
        }
    }

    /** Runs whichever step the user is on. */
    fun onSubmit() {
        val state = _uiState.value
        if (state.isSubmitting) {
            return
        }

        when (state.step) {
            PasswordResetStep.IDENTITY -> requestReset()
            PasswordResetStep.CODE -> verifyCode()
            PasswordResetStep.NEW_PASSWORD -> completeReset()
            PasswordResetStep.DONE -> Unit
        }
    }

    private fun requestReset() {
        val email = _uiState.value.email.trim()
        if (email.isEmpty()) {
            _uiState.update {
                it.copy(fieldError = PasswordResetFieldError.EMAIL, failureReason = null)
            }
            return
        }

        submit({ authRepository.requestPasswordReset(email) }) {
            // The backend answers generically, so the flow advances without claiming that an
            // account exists (`BR-044`).
            it.copy(step = PasswordResetStep.CODE, email = email)
        }
    }

    private fun verifyCode() {
        val state = _uiState.value
        if (state.code.length != AUTH_CODE_LENGTH) {
            _uiState.update {
                it.copy(fieldError = PasswordResetFieldError.CODE, failureReason = null)
            }
            return
        }

        submit({ authRepository.verifyPasswordResetCode(state.email, state.code) }) {
            it.copy(step = PasswordResetStep.NEW_PASSWORD)
        }
    }

    private fun completeReset() {
        val state = _uiState.value
        if (state.newPassword.isEmpty()) {
            _uiState.update {
                it.copy(fieldError = PasswordResetFieldError.PASSWORD, failureReason = null)
            }
            return
        }

        submit(
            { authRepository.completePasswordReset(state.email, state.code, state.newPassword) },
        ) {
            // The password has served its purpose once it has been stored, and the flow is over:
            // the user returns to normal sign-in (`BR-043`).
            it.copy(
                step = PasswordResetStep.DONE,
                code = "",
                newPassword = "",
                passwordVisible = false,
            )
        }
    }

    /**
     * Calls one repository operation and folds its outcome into the state.
     *
     * The client-side checks above only prevent a call that cannot be correct; a rejection the
     * backend produces is always reported as [AuthActionResult.Failure], never swallowed.
     */
    private fun submit(
        operation: suspend () -> AuthActionResult,
        onSuccess: (PasswordResetUiState) -> PasswordResetUiState,
    ) {
        _uiState.update { it.copy(isSubmitting = true, fieldError = null, failureReason = null) }

        viewModelScope.launch {
            when (val result = operation()) {
                is AuthActionResult.Success ->
                    _uiState.update { onSuccess(it).copy(isSubmitting = false) }

                is AuthActionResult.Failure ->
                    // A failed attempt invalidates nothing the user typed: keep both fields so
                    // the other one can be corrected.
                    _uiState.update {
                        it.copy(isSubmitting = false, failureReason = result.reason)
                    }
            }
        }
    }

    private fun PasswordResetFieldError?.clearedIf(
        field: PasswordResetFieldError,
    ): PasswordResetFieldError? = if (this == field) null else this

    private companion object {
        /** `BR-019`/`BR-043` fix the code at six digits; the field is capped to match. */
        const val AUTH_CODE_LENGTH = 6
    }
}
