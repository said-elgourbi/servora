package com.servora.android.ui.sms

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.servora.android.data.auth.AuthActionResult
import com.servora.android.data.auth.AuthRepository
import com.servora.android.data.auth.SignInResult
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Drives phone/SMS sign-in (`BR-019`).
 *
 * The ViewModel never decides whether a number is eligible, whether a code is correct, or how
 * long the user must wait before requesting another one: those are backend decisions
 * (`BR-001`, `BR-007`, `BR-045`). It reports what the repository decided, and the resend action
 * is simply another request the backend may refuse.
 */
@HiltViewModel
class SmsSignInViewModel @Inject constructor(
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SmsSignInUiState())
    val uiState: StateFlow<SmsSignInUiState> = _uiState.asStateFlow()

    fun onPhoneChange(phone: String) {
        _uiState.update { state ->
            state.copy(
                phone = phone,
                fieldError = state.fieldError.clearedIf(SmsSignInFieldError.PHONE),
                failureReason = null,
            )
        }
    }

    fun onCodeChange(code: String) {
        _uiState.update { state ->
            state.copy(
                code = code.filter(Char::isDigit).take(AUTH_CODE_LENGTH),
                fieldError = state.fieldError.clearedIf(SmsSignInFieldError.CODE),
                failureReason = null,
            )
        }
    }

    /** Returns to the number step, so a mistyped number is not a dead end. */
    fun onStepBack() {
        _uiState.update {
            it.copy(step = SmsSignInStep.PHONE, code = "", fieldError = null, failureReason = null)
        }
    }

    /** Resends a code for the number already entered; the backend owns the cooldown. */
    fun onResendCode() {
        val state = _uiState.value
        if (state.isSubmitting || state.phone.isBlank()) {
            return
        }
        requestCode(state.phone, keepStep = true)
    }

    fun onSubmit() {
        val state = _uiState.value
        if (state.isSubmitting) {
            return
        }

        when (state.step) {
            SmsSignInStep.PHONE -> requestCode(state.phone, keepStep = false)
            SmsSignInStep.CODE -> verifyCode()
        }
    }

    private fun requestCode(phone: String, keepStep: Boolean) {
        if (phone.isBlank()) {
            _uiState.update {
                it.copy(fieldError = SmsSignInFieldError.PHONE, failureReason = null)
            }
            return
        }

        _uiState.update { it.copy(isSubmitting = true, fieldError = null, failureReason = null) }

        viewModelScope.launch {
            val result = authRepository.requestSmsCode(phone)
            _uiState.update { state ->
                val advanced =
                    when (result) {
                        // The backend answers generically, so the code step is shown without
                        // claiming that the number has an account (`BR-044`).
                        is AuthActionResult.Success ->
                            if (keepStep) {
                                state
                            } else {
                                state.copy(step = SmsSignInStep.CODE, code = "")
                            }

                        is AuthActionResult.Failure -> state
                    }

                when (result) {
                    is AuthActionResult.Success ->
                        advanced.copy(phone = phone, isSubmitting = false)
                    is AuthActionResult.Failure ->
                        advanced.copy(isSubmitting = false, failureReason = result.reason)
                }
            }
        }
    }

    private fun verifyCode() {
        val state = _uiState.value
        if (state.code.length != AUTH_CODE_LENGTH) {
            _uiState.update {
                it.copy(fieldError = SmsSignInFieldError.CODE, failureReason = null)
            }
            return
        }

        _uiState.update { it.copy(isSubmitting = true, fieldError = null, failureReason = null) }

        viewModelScope.launch {
            when (val result = authRepository.verifySmsCode(state.phone, state.code)) {
                is SignInResult.Success ->
                    _uiState.update {
                        // The code has served its purpose once a session exists.
                        it.copy(isSubmitting = false, code = "", signedIn = true)
                    }

                is SignInResult.Failure ->
                    _uiState.update {
                        it.copy(isSubmitting = false, failureReason = result.reason)
                    }
            }
        }
    }

    private fun SmsSignInFieldError?.clearedIf(
        field: SmsSignInFieldError,
    ): SmsSignInFieldError? = if (this == field) null else this

    private companion object {
        /** `BR-019` fixes the one-time password at six digits; the field is capped to match. */
        const val AUTH_CODE_LENGTH = 6
    }
}
