package com.servora.android.ui.sms

import com.servora.android.data.auth.AuthActionResult
import com.servora.android.data.auth.AuthFailureReason
import com.servora.android.data.auth.AuthRepository
import com.servora.android.data.auth.SignInResult
import com.servora.android.domain.model.AuthTokens
import com.servora.android.domain.model.IssuedSession
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
 * What phone/SMS sign-in shows, and what it asks [AuthRepository] for.
 *
 * Whether the number has an account, whether the code is right and whether a resend is allowed
 * are backend decisions (`BR-001`, `BR-045`), so these tests assert that a repository answer is
 * reported rather than that a number or a code is "right".
 */
class SmsSignInViewModelTest {

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
    fun `starts at the phone step with an empty form`() = runTest(dispatcher) {
        val viewModel = SmsSignInViewModel(RecordingSmsRepository())

        assertEquals(SmsSignInStep.PHONE, viewModel.uiState.value.step)
        assertEquals("", viewModel.uiState.value.phone)
        assertFalse(viewModel.uiState.value.signedIn)
        assertFalse(viewModel.uiState.value.canStepBack)
    }

    @Test
    fun `refuses a blank number without calling the backend`() = runTest(dispatcher) {
        val repository = RecordingSmsRepository()
        val viewModel = SmsSignInViewModel(repository)

        viewModel.onPhoneChange("   ")
        viewModel.onSubmit()
        advanceUntilIdle()

        assertEquals(SmsSignInFieldError.PHONE, viewModel.uiState.value.fieldError)
        assertTrue(repository.calls.isEmpty())
    }

    @Test
    fun `advances to the code step after a successful request`() = runTest(dispatcher) {
        val repository = RecordingSmsRepository()
        val viewModel = SmsSignInViewModel(repository)

        viewModel.onPhoneChange(SMS_PHONE)
        viewModel.onSubmit()
        advanceUntilIdle()

        assertEquals(SmsSignInStep.CODE, viewModel.uiState.value.step)
        assertEquals(listOf("requestSmsCode:$SMS_PHONE"), repository.calls)
        assertTrue(viewModel.uiState.value.canStepBack)
    }

    @Test
    fun `reports a rejected request and stays at the phone step`() = runTest(dispatcher) {
        val repository = RecordingSmsRepository()
        repository.requestResult = AuthActionResult.Failure(AuthFailureReason.NETWORK)
        val viewModel = SmsSignInViewModel(repository)

        viewModel.onPhoneChange(SMS_PHONE)
        viewModel.onSubmit()
        advanceUntilIdle()

        assertEquals(SmsSignInStep.PHONE, viewModel.uiState.value.step)
        assertEquals(AuthFailureReason.NETWORK, viewModel.uiState.value.failureReason)
        assertFalse(viewModel.uiState.value.isSubmitting)
    }

    @Test
    fun `reporting a resend keeps the code step and reports the backend's refusal`() =
        runTest(dispatcher) {
            val repository = RecordingSmsRepository()
            val viewModel = viewModelAt(SmsSignInStep.CODE, repository)
            repository.requestResult =
                AuthActionResult.Failure(AuthFailureReason.TOO_MANY_REQUESTS)

            viewModel.onResendCode()
            advanceUntilIdle()

            // The cooldown is the backend's (`BR-019`): the client asks and reports the answer.
            assertEquals(SmsSignInStep.CODE, viewModel.uiState.value.step)
            assertEquals(
                AuthFailureReason.TOO_MANY_REQUESTS,
                viewModel.uiState.value.failureReason,
            )
        }

    @Test
    fun `keeps only digits and at most six characters in the code field`() =
        runTest(dispatcher) {
            val viewModel = SmsSignInViewModel(RecordingSmsRepository())

            viewModel.onCodeChange("98xy76cd5432")

            assertEquals("987654", viewModel.uiState.value.code)
        }

    @Test
    fun `refuses a code shorter than six digits without calling the backend`() =
        runTest(dispatcher) {
            val repository = RecordingSmsRepository()
            val viewModel = viewModelAt(SmsSignInStep.CODE, repository)

            viewModel.onCodeChange("12")
            viewModel.onSubmit()
            advanceUntilIdle()

            assertEquals(SmsSignInFieldError.CODE, viewModel.uiState.value.fieldError)
            assertTrue(repository.calls.none { it.startsWith("verifySmsCode") })
        }

    @Test
    fun `signs in after a successful verification and clears the code`() = runTest(dispatcher) {
        val repository = RecordingSmsRepository()
        val viewModel = viewModelAt(SmsSignInStep.CODE, repository)
        viewModel.onCodeChange(SMS_CODE)

        viewModel.onSubmit()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.signedIn)
        assertEquals("", state.code)
        assertFalse(state.isSubmitting)
        assertEquals("verifySmsCode:$SMS_PHONE:$SMS_CODE", repository.calls.last())
    }

    @Test
    fun `reports a rejected code and stays at the code step`() = runTest(dispatcher) {
        val repository = RecordingSmsRepository()
        repository.verifyResult =
            SignInResult.Failure(AuthFailureReason.OTP_CODE_INVALID)
        val viewModel = viewModelAt(SmsSignInStep.CODE, repository)
        viewModel.onCodeChange(SMS_CODE)

        viewModel.onSubmit()
        advanceUntilIdle()

        assertEquals(SmsSignInStep.CODE, viewModel.uiState.value.step)
        assertEquals(AuthFailureReason.OTP_CODE_INVALID, viewModel.uiState.value.failureReason)
        assertFalse(viewModel.uiState.value.signedIn)
    }

    @Test
    fun `stepping back returns to the phone step and clears the code`() = runTest(dispatcher) {
        val viewModel = viewModelAt(SmsSignInStep.CODE, RecordingSmsRepository())
        viewModel.onCodeChange(SMS_CODE)

        viewModel.onStepBack()

        assertEquals(SmsSignInStep.PHONE, viewModel.uiState.value.step)
        assertEquals("", viewModel.uiState.value.code)
        assertEquals(SMS_PHONE, viewModel.uiState.value.phone)
    }

    @Test
    fun `reports progress while a request is in flight`() = runTest(dispatcher) {
        val repository = RecordingSmsRepository()
        val gate = CompletableDeferred<Unit>()
        repository.gate = gate
        val viewModel = SmsSignInViewModel(repository)

        viewModel.onPhoneChange(SMS_PHONE)
        viewModel.onSubmit()

        assertTrue(viewModel.uiState.value.isSubmitting)
        assertFalse(viewModel.uiState.value.isSubmitEnabled)
        // A second tap while the first is in flight must not send a second request.
        viewModel.onSubmit()

        gate.complete(Unit)
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isSubmitting)
        assertEquals(listOf("requestSmsCode:$SMS_PHONE"), repository.calls)
    }

    /**
     * A view model already sitting on [step], reached through the flow rather than by poking at
     * state, so a test never depends on a transition the user cannot perform.
     */
    private fun TestScope.viewModelAt(
        step: SmsSignInStep,
        repository: RecordingSmsRepository,
    ): SmsSignInViewModel {
        val viewModel = SmsSignInViewModel(repository)
        viewModel.onPhoneChange(SMS_PHONE)
        viewModel.onSubmit()
        advanceUntilIdle()

        if (step == SmsSignInStep.PHONE) {
            viewModel.onStepBack()
        }

        return viewModel
    }
}

private const val SMS_PHONE = "+15145550100"
private const val SMS_CODE = "123456"

/** A repository whose answers each test decides, and which records what it was asked. */
private class RecordingSmsRepository : AuthRepository {
    var requestResult: AuthActionResult = AuthActionResult.Success
    var verifyResult: SignInResult = SignInResult.Success(
        IssuedSession(
            sessionId = "session-1",
            tokens = AuthTokens(
                accessToken = "access-token",
                accessTokenExpiresAt = "2026-01-01T00:00:00Z",
                refreshToken = "refresh-token",
            ),
        ),
    )

    /** When set, calls wait for it, so a test can observe the in-flight state. */
    var gate: CompletableDeferred<Unit>? = null

    val calls = mutableListOf<String>()

    override suspend fun signIn(email: String, password: String): SignInResult =
        error("password sign-in is not part of the SMS flow")

    override suspend fun requestPasswordReset(email: String): AuthActionResult =
        error("password reset is not part of the SMS flow")

    override suspend fun verifyPasswordResetCode(
        email: String,
        code: String,
    ): AuthActionResult = error("password reset is not part of the SMS flow")

    override suspend fun completePasswordReset(
        email: String,
        code: String,
        newPassword: String,
    ): AuthActionResult = error("password reset is not part of the SMS flow")

    override suspend fun requestSmsCode(phone: String): AuthActionResult {
        calls += "requestSmsCode:$phone"
        gate?.await()
        return requestResult
    }

    override suspend fun verifySmsCode(phone: String, code: String): SignInResult {
        calls += "verifySmsCode:$phone:$code"
        gate?.await()
        return verifyResult
    }
}