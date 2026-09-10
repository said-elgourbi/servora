package com.servora.android.ui.passwordreset

import com.servora.android.data.auth.AuthActionResult
import com.servora.android.data.auth.AuthFailureReason
import com.servora.android.data.auth.AuthRepository
import com.servora.android.data.auth.SignInResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
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
 * What the reset flow shows, and what it asks [AuthRepository] for.
 *
 * Whether an identity exists, whether a code is valid and how many attempts remain are backend
 * decisions (`BR-001`, `BR-007`), so these tests assert that a repository answer is reported —
 * never that a code is "right".
 */
class PasswordResetViewModelTest {

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
    fun `starts at the identity step with an empty form`() = runTest(dispatcher) {
        val viewModel = PasswordResetViewModel(RecordingResetRepository())

        assertEquals(PasswordResetStep.IDENTITY, viewModel.uiState.value.step)
        assertEquals("", viewModel.uiState.value.email)
        assertNull(viewModel.uiState.value.failureReason)
        assertFalse(viewModel.uiState.value.canStepBack)
    }

    @Test
    fun `refuses an empty identity without calling the backend`() = runTest(dispatcher) {
        val repository = RecordingResetRepository()
        val viewModel = PasswordResetViewModel(repository)

        viewModel.onSubmit()
        advanceUntilIdle()

        assertEquals(PasswordResetFieldError.EMAIL, viewModel.uiState.value.fieldError)
        assertTrue(repository.calls.isEmpty())
    }

    @Test
    fun `advances to the code step after a successful request`() = runTest(dispatcher) {
        val repository = RecordingResetRepository()
        val viewModel = PasswordResetViewModel(repository)

        viewModel.onEmailChange("  user@example.com  ")
        viewModel.onSubmit()
        advanceUntilIdle()

        assertEquals(PasswordResetStep.CODE, viewModel.uiState.value.step)
        assertEquals("user@example.com", viewModel.uiState.value.email)
        assertEquals(listOf("requestPasswordReset:user@example.com"), repository.calls)
        assertTrue(viewModel.uiState.value.canStepBack)
    }

    @Test
    fun `reports a rejected request and stays on the identity step`() = runTest(dispatcher) {
        val repository = RecordingResetRepository()
        repository.requestResult = AuthActionResult.Failure(AuthFailureReason.NETWORK)
        val viewModel = PasswordResetViewModel(repository)

        viewModel.onEmailChange("user@example.com")
        viewModel.onSubmit()
        advanceUntilIdle()

        assertEquals(PasswordResetStep.IDENTITY, viewModel.uiState.value.step)
        assertEquals(AuthFailureReason.NETWORK, viewModel.uiState.value.failureReason)
        assertFalse(viewModel.uiState.value.isSubmitting)
    }

    @Test
    fun `keeps only digits and at most six characters in the code field`() =
        runTest(dispatcher) {
            val viewModel = PasswordResetViewModel(RecordingResetRepository())

            viewModel.onCodeChange("12ab34cd5678")

            assertEquals("123456", viewModel.uiState.value.code)
        }

    @Test
    fun `refuses a code shorter than six digits without calling the backend`() =
        runTest(dispatcher) {
            val repository = RecordingResetRepository()
            val viewModel = viewModelAt(PasswordResetStep.CODE, repository)

            viewModel.onCodeChange("123")
            viewModel.onSubmit()
            advanceUntilIdle()

            assertEquals(PasswordResetFieldError.CODE, viewModel.uiState.value.fieldError)
            assertTrue(repository.calls.none { it.startsWith("verifyPasswordResetCode") })
        }

    @Test
    fun `advances to the new-password step after a successful verification`() =
        runTest(dispatcher) {
            val repository = RecordingResetRepository()
            val viewModel = viewModelAt(PasswordResetStep.CODE, repository)
            viewModel.onCodeChange("654321")

            viewModel.onSubmit()
            advanceUntilIdle()

            assertEquals(PasswordResetStep.NEW_PASSWORD, viewModel.uiState.value.step)
            assertEquals(
                listOf(
                    "requestPasswordReset:user@example.com",
                    "verifyPasswordResetCode:user@example.com:654321",
                ),
                repository.calls,
            )
        }

    @Test
    fun `reports a rejected code and stays on the code step`() = runTest(dispatcher) {
        val repository = RecordingResetRepository()
        repository.verifyResult =
            AuthActionResult.Failure(AuthFailureReason.RESET_CODE_INVALID)
        val viewModel = viewModelAt(PasswordResetStep.CODE, repository)
        viewModel.onCodeChange("654321")

        viewModel.onSubmit()
        advanceUntilIdle()

        assertEquals(PasswordResetStep.CODE, viewModel.uiState.value.step)
        assertEquals(
            AuthFailureReason.RESET_CODE_INVALID,
            viewModel.uiState.value.failureReason,
        )
        // The code the user typed is kept, so only the code itself has to be retyped.
        assertEquals("654321", viewModel.uiState.value.code)
    }

    @Test
    fun `refuses an empty new password without calling the backend`() = runTest(dispatcher) {
        val repository = RecordingResetRepository()
        val viewModel = viewModelAt(PasswordResetStep.NEW_PASSWORD, repository)

        viewModel.onSubmit()
        advanceUntilIdle()

        assertEquals(PasswordResetFieldError.PASSWORD, viewModel.uiState.value.fieldError)
        assertTrue(repository.calls.none { it.startsWith("completePasswordReset") })
    }

    @Test
    fun `reaches the done step and clears the credentials after a successful completion`() =
        runTest(dispatcher) {
            val repository = RecordingResetRepository()
            val viewModel = viewModelAt(PasswordResetStep.NEW_PASSWORD, repository)
            viewModel.onNewPasswordChange("a-new-password")

            viewModel.onSubmit()
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertEquals(PasswordResetStep.DONE, state.step)
            assertEquals("", state.code)
            assertEquals("", state.newPassword)
            assertEquals(
                "completePasswordReset:user@example.com:654321:a-new-password",
                repository.calls.last(),
            )
            // The flow is over: there is nothing left to step back to.
            assertFalse(state.canStepBack)
        }

    @Test
    fun `reports a rejected completion and keeps the new password`() = runTest(dispatcher) {
        val repository = RecordingResetRepository()
        repository.completeResult =
            AuthActionResult.Failure(AuthFailureReason.TOO_MANY_REQUESTS)
        val viewModel = viewModelAt(PasswordResetStep.NEW_PASSWORD, repository)
        viewModel.onNewPasswordChange("a-new-password")

        viewModel.onSubmit()
        advanceUntilIdle()

        assertEquals(PasswordResetStep.NEW_PASSWORD, viewModel.uiState.value.step)
        assertEquals(AuthFailureReason.TOO_MANY_REQUESTS, viewModel.uiState.value.failureReason)
        assertEquals("a-new-password", viewModel.uiState.value.newPassword)
    }

    @Test
    fun `stepping back from the code step clears the code`() = runTest(dispatcher) {
        val viewModel = viewModelAt(PasswordResetStep.CODE, RecordingResetRepository())
        viewModel.onCodeChange("654321")

        viewModel.onStepBack()

        assertEquals(PasswordResetStep.IDENTITY, viewModel.uiState.value.step)
        assertEquals("", viewModel.uiState.value.code)
        assertEquals("user@example.com", viewModel.uiState.value.email)
    }

    @Test
    fun `reports progress while a request is in flight`() = runTest(dispatcher) {
        val repository = RecordingResetRepository()
        val gate = CompletableDeferred<Unit>()
        repository.gate = gate
        val viewModel = PasswordResetViewModel(repository)

        viewModel.onEmailChange("user@example.com")
        viewModel.onSubmit()

        assertTrue(viewModel.uiState.value.isSubmitting)
        assertFalse(viewModel.uiState.value.isSubmitEnabled)

        gate.complete(Unit)
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isSubmitting)
        assertEquals(PasswordResetStep.CODE, viewModel.uiState.value.step)
    }

    /**
     * A view model already sitting on [step], reached through the flow rather than by poking at
     * state, so a test never depends on a transition the user cannot perform.
     */
    private fun TestScope.viewModelAt(
        step: PasswordResetStep,
        repository: RecordingResetRepository,
    ): PasswordResetViewModel {
        val viewModel = PasswordResetViewModel(repository)
        viewModel.onEmailChange("user@example.com")
        viewModel.onSubmit()
        advanceUntilIdle()

        if (step == PasswordResetStep.NEW_PASSWORD) {
            viewModel.onCodeChange("654321")
            viewModel.onSubmit()
            advanceUntilIdle()
        }

        return viewModel
    }
}

/** A repository whose answers each test decides, and which records what it was asked. */
private class RecordingResetRepository : AuthRepository {
    var requestResult: AuthActionResult = AuthActionResult.Success
    var verifyResult: AuthActionResult = AuthActionResult.Success
    var completeResult: AuthActionResult = AuthActionResult.Success

    /** When set, calls wait for it, so a test can observe the in-flight state. */
    var gate: CompletableDeferred<Unit>? = null

    val calls = mutableListOf<String>()

    override suspend fun signIn(email: String, password: String): SignInResult =
        error("password sign-in is not part of the reset flow")

    override suspend fun requestPasswordReset(email: String): AuthActionResult {
        calls += "requestPasswordReset:$email"
        gate?.await()
        return requestResult
    }

    override suspend fun verifyPasswordResetCode(
        email: String,
        code: String,
    ): AuthActionResult {
        calls += "verifyPasswordResetCode:$email:$code"
        gate?.await()
        return verifyResult
    }

    override suspend fun completePasswordReset(
        email: String,
        code: String,
        newPassword: String,
    ): AuthActionResult {
        calls += "completePasswordReset:$email:$code:$newPassword"
        gate?.await()
        return completeResult
    }

    override suspend fun requestSmsCode(phone: String): AuthActionResult =
        error("SMS is not part of the reset flow")

    override suspend fun verifySmsCode(phone: String, code: String): SignInResult =
        error("SMS is not part of the reset flow")
}
