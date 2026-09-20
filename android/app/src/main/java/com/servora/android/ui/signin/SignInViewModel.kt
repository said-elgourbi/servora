package com.servora.android.ui.signin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
 * Drives [SignInScreen].
 *
 * The ViewModel holds no session state of its own: it collects user input, asks
 * [AuthRepository] to sign in, and reports what the repository decided. The backend stays the
 * authority for whether a sign-in actually succeeded (`BR-001`, `BR-007`).
 */
@HiltViewModel
class SignInViewModel @Inject constructor(
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SignInUiState())
    val uiState: StateFlow<SignInUiState> = _uiState.asStateFlow()

    fun onEmailChange(email: String) {
        _uiState.update { state ->
            state.copy(
                email = email,
                fieldError = state.fieldError.clearedIf(SignInFieldError.EMAIL),
                failureReason = null,
            )
        }
    }

    fun onPasswordChange(password: String) {
        _uiState.update { state ->
            state.copy(
                password = password,
                fieldError = state.fieldError.clearedIf(SignInFieldError.PASSWORD),
                failureReason = null,
            )
        }
    }

    fun onTogglePasswordVisibility() {
        _uiState.update { it.copy(passwordVisible = !it.passwordVisible) }
    }

    /**
     * Signs in with a local development account in one step, for the temporary dev shortcut on the
     * sign-in screen (`docs/tracker/049-android-dev-sign-in-buttons.md`).
     *
     * It only prefills the form and reuses [onSubmit], so the attempt goes through exactly the same
     * validation, repository call and state handling as a typed one. There is no second
     * authentication route (`BR-018`, `BR-007`), and the in-flight guard in [onSubmit] still
     * applies: a second tap while an attempt is running changes nothing.
     */
    fun onDevSignIn(email: String, password: String) {
        if (_uiState.value.isSubmitting) {
            return
        }
        _uiState.update {
            it.copy(email = email, password = password, fieldError = null, failureReason = null)
        }
        onSubmit()
    }

    fun onSubmit() {
        val current = _uiState.value
        if (current.isSubmitting) {
            return
        }

        val email = current.email.trim()
        val fieldError = when {
            email.isEmpty() -> SignInFieldError.EMAIL
            current.password.isEmpty() -> SignInFieldError.PASSWORD
            else -> null
        }
        if (fieldError != null) {
            _uiState.update {
                it.copy(email = email, fieldError = fieldError, failureReason = null)
            }
            return
        }

        _uiState.update {
            it.copy(email = email, isSubmitting = true, fieldError = null, failureReason = null)
        }

        viewModelScope.launch {
            when (val result = authRepository.signIn(email, current.password)) {
                is SignInResult.Success -> _uiState.update {
                    // The password has served its purpose once a session exists. The app moves to
                    // the authenticated area because the session manager saw the issued session,
                    // so this screen keeps no session state of its own.
                    it.copy(isSubmitting = false, password = "")
                }

                is SignInResult.Failure -> _uiState.update {
                    it.copy(isSubmitting = false, failureReason = result.reason)
                }
            }
        }
    }

    private fun SignInFieldError?.clearedIf(field: SignInFieldError): SignInFieldError? =
        if (this == field) null else this
}
