package com.servora.android.ui.signin

import com.servora.android.data.auth.AuthRepository
import com.servora.android.data.auth.AuthActionResult
import com.servora.android.data.auth.AuthFailureReason
import com.servora.android.data.auth.SignInResult
import com.servora.android.domain.model.AuthTokens
import com.servora.android.domain.model.IssuedSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * What [SignInViewModel] shows, and what it asks [AuthRepository] for.
 *
 * Whether a credential is correct is the backend's decision (`BR-001`), so these tests
 * assert that a repository answer is reported rather than that a password is "right".
 */
class SignInViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun installMainDispatcher() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun restoreMainDispatcher() {
        Dispatchers.resetMain()
    }

    @Test
    fun `starts with the empty form`() = runTest(dispatcher) {
        val viewModel = SignInViewModel(FakeAuthRepository())

        assertEquals(SignInUiState(), viewModel.uiState.value)
    }

    @Test
    fun `does not ask the repository when the email is blank`() = runTest(dispatcher) {
        val repository = FakeAuthRepository()
        val viewModel = SignInViewModel(repository)
        viewModel.onPasswordChange(PASSWORD)

        viewModel.onSubmit()
        advanceUntilIdle()

        assertEquals(SignInFieldError.EMAIL, viewModel.uiState.value.fieldError)
        assertTrue(repository.attempts.isEmpty())
        assertFalse(viewModel.uiState.value.signedIn)
    }

    @Test
    fun `does not ask the repository when the email is only whitespace`() = runTest(dispatcher) {
        val repository = FakeAuthRepository()
        val viewModel = SignInViewModel(repository)
        viewModel.onEmailChange("   ")
        viewModel.onPasswordChange(PASSWORD)

        viewModel.onSubmit()
        advanceUntilIdle()

        assertEquals(SignInFieldError.EMAIL, viewModel.uiState.value.fieldError)
        assertTrue(repository.attempts.isEmpty())
    }

    @Test
    fun `does not ask the repository when the password is blank`() = runTest(dispatcher) {
        val repository = FakeAuthRepository()
        val viewModel = SignInViewModel(repository)
        viewModel.onEmailChange(EMAIL)

        viewModel.onSubmit()
        advanceUntilIdle()

        assertEquals(SignInFieldError.PASSWORD, viewModel.uiState.value.fieldError)
        assertTrue(repository.attempts.isEmpty())
    }

    @Test
    fun `submits the trimmed email`() = runTest(dispatcher) {
        val repository = FakeAuthRepository()
        val viewModel = SignInViewModel(repository)
        viewModel.onEmailChange("  $EMAIL  ")
        viewModel.onPasswordChange(PASSWORD)

        viewModel.onSubmit()
        advanceUntilIdle()

        assertEquals(listOf(EMAIL to PASSWORD), repository.attempts)
    }

    @Test
    fun `reports the issued session and drops the password`() = runTest(dispatcher) {
        val repository = FakeAuthRepository(result = SignInResult.Success(SESSION))
        val viewModel = SignInViewModel(repository)
        viewModel.onEmailChange(EMAIL)
        viewModel.onPasswordChange(PASSWORD)

        viewModel.onSubmit()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.signedIn)
        assertFalse(state.isSubmitting)
        assertEquals("", state.password)
        assertNull(state.failureReason)
        assertNull(state.fieldError)
    }

    @Test
    fun `reports the failure the repository returned`() = runTest(dispatcher) {
        val repository = FakeAuthRepository(
            result = SignInResult.Failure(AuthFailureReason.SERVER),
        )
        val viewModel = SignInViewModel(repository)
        viewModel.onEmailChange(EMAIL)
        viewModel.onPasswordChange(PASSWORD)

        viewModel.onSubmit()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(AuthFailureReason.SERVER, state.failureReason)
        assertFalse(state.signedIn)
        assertFalse(state.isSubmitting)
    }

    @Test
    fun `keeps the typed password after a failure`() = runTest(dispatcher) {
        val repository = FakeAuthRepository(
            result = SignInResult.Failure(AuthFailureReason.INVALID_CREDENTIALS),
        )
        val viewModel = SignInViewModel(repository)
        viewModel.onEmailChange(EMAIL)
        viewModel.onPasswordChange(PASSWORD)

        viewModel.onSubmit()
        advanceUntilIdle()

        assertEquals(PASSWORD, viewModel.uiState.value.password)
    }

    @Test
    fun `stays busy until the attempt finishes and ignores a second submit`() =
        runTest(dispatcher) {
            val repository = FakeAuthRepository().apply { holdNextAttempt() }
            val viewModel = SignInViewModel(repository)
            viewModel.onEmailChange(EMAIL)
            viewModel.onPasswordChange(PASSWORD)

            viewModel.onSubmit()
            advanceUntilIdle()

            assertTrue(viewModel.uiState.value.isSubmitting)
            assertFalse(viewModel.uiState.value.isSubmitEnabled)

            viewModel.onSubmit()
            advanceUntilIdle()

            assertEquals(1, repository.attempts.size)

            repository.release()
            advanceUntilIdle()

            assertFalse(viewModel.uiState.value.isSubmitting)
        }

    @Test
    fun `clears a field error as soon as the field is edited`() = runTest(dispatcher) {
        val viewModel = SignInViewModel(FakeAuthRepository())

        viewModel.onSubmit()
        advanceUntilIdle()
        assertEquals(SignInFieldError.EMAIL, viewModel.uiState.value.fieldError)

        viewModel.onEmailChange(EMAIL)

        assertNull(viewModel.uiState.value.fieldError)
    }

    @Test
    fun `clears a reported failure when the user types again`() = runTest(dispatcher) {
        val repository = FakeAuthRepository(
            result = SignInResult.Failure(AuthFailureReason.NETWORK),
        )
        val viewModel = SignInViewModel(repository)
        viewModel.onEmailChange(EMAIL)
        viewModel.onPasswordChange(PASSWORD)

        viewModel.onSubmit()
        advanceUntilIdle()
        assertEquals(AuthFailureReason.NETWORK, viewModel.uiState.value.failureReason)

        viewModel.onPasswordChange("$PASSWORD-2")

        assertNull(viewModel.uiState.value.failureReason)
    }

    @Test
    fun `toggles password visibility`() = runTest(dispatcher) {
        val viewModel = SignInViewModel(FakeAuthRepository())

        viewModel.onTogglePasswordVisibility()
        assertTrue(viewModel.uiState.value.passwordVisible)

        viewModel.onTogglePasswordVisibility()
        assertFalse(viewModel.uiState.value.passwordVisible)
    }

    private companion object {
        const val EMAIL = "tech@servora.test"
        const val PASSWORD = "correct-horse-battery-staple"

        val SESSION = IssuedSession(
            sessionId = "0f9a2c1e-0000-4000-8000-000000000001",
            tokens = AuthTokens(
                accessToken = "access-token",
                accessTokenExpiresAt = "2026-01-01T00:00:00Z",
                refreshToken = "refresh-token",
            ),
        )
    }
}

/** Repository double: records what it was asked and answers with a fixed outcome. */
private class FakeAuthRepository(
    private val result: SignInResult = SignInResult.Failure(AuthFailureReason.INVALID_CREDENTIALS),
) : AuthRepository {

    val attempts = mutableListOf<Pair<String, String>>()

    private val inFlight = CompletableDeferred<Unit>()
    private var holding = false

    /** Makes the next attempt stay in flight until [release] is called. */
    fun holdNextAttempt() {
        holding = true
    }

    fun release() {
        inFlight.complete(Unit)
    }

    override suspend fun signIn(email: String, password: String): SignInResult {
        attempts += email to password
        if (holding) {
            inFlight.await()
        }
        return result
    }

    // The password-reset and phone/SMS operations belong to other flows; failing loudly keeps a
    // test from mistaking an unimplemented double for real behaviour.
    override suspend fun requestPasswordReset(email: String): AuthActionResult =
        unsupported("requestPasswordReset")

    override suspend fun verifyPasswordResetCode(
        email: String,
        code: String,
    ): AuthActionResult = unsupported("verifyPasswordResetCode")

    override suspend fun completePasswordReset(
        email: String,
        code: String,
        newPassword: String,
    ): AuthActionResult = unsupported("completePasswordReset")

    override suspend fun requestSmsCode(phone: String): AuthActionResult =
        unsupported("requestSmsCode")

    override suspend fun verifySmsCode(phone: String, code: String): SignInResult =
        unsupported("verifySmsCode")

    private fun unsupported(operation: String): Nothing =
        error("$operation is not part of the sign-in flow under test")
}
