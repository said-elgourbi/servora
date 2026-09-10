package com.servora.android.ui.auth

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.servora.android.R
import com.servora.android.ui.passwordreset.PasswordResetScreen
import com.servora.android.ui.passwordreset.PasswordResetUiState
import com.servora.android.ui.signin.SignInScreen
import com.servora.android.ui.signin.SignInUiState
import com.servora.android.ui.sms.SmsSignInScreen
import com.servora.android.ui.sms.SmsSignInUiState
import com.servora.android.ui.theme.ServoraTheme
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Keyboard-dismissal behaviour of the authentication screens.
 *
 * The keyboard belongs to the system IME, and an instrumentation test cannot show or hide it
 * deterministically. These tests therefore drive the same value the screens read in production
 * (`WindowInsets.isImeVisible`) through the `imeVisible` seam and assert the app's reaction to the
 * visible → hidden transition — the one thing every dismissal path produces (back gesture, the
 * IME's Done/dismiss action, a resize).
 *
 * "Focus and label state after a dismissal" is asserted as: the focused field loses focus and the
 * Material label returns to its resting position. Field appearance is derived from focus, so a
 * stuck label and a stuck focus are the same defect.
 *
 * The real-device confirmation that the system back press produces that transition for the running
 * app is the on-device measurement recorded in the change report; this suite pins the app-side
 * reaction.
 */
@RunWith(AndroidJUnit4::class)
class ClearFocusWhenImeHiddenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun dismissalClearsFocusAndReturnsTheLabelToItsRestingPosition() {
        val imeVisible = mutableStateOf(false)
        composeTestRule.setContent {
            Column {
                ClearFocusWhenImeHidden(imeVisible = imeVisible.value)
                OutlinedTextField(
                    value = "",
                    onValueChange = {},
                    label = { Text(LABEL) },
                    placeholder = { Text(PLACEHOLDER) },
                )
            }
        }

        val restingTop = labelTop(LABEL)
        val field = firstTextField()
        field.performClick()
        field.assertIsFocused()

        val focusedTop = labelTop(LABEL)
        assertTrue(
            "the label should float while focused (resting=$restingTop focused=$focusedTop)",
            focusedTop < restingTop,
        )

        dismissKeyboard(imeVisible)

        field.assertIsNotFocused()
        assertEquals(
            "the label should be back at its resting position",
            restingTop,
            labelTop(LABEL),
            LABEL_POSITION_TOLERANCE,
        )
    }

    @Test
    fun focusIsKeptWhenTheKeyboardNeverAppeared() {
        val imeVisible = mutableStateOf(false)
        composeTestRule.setContent {
            Column {
                ClearFocusWhenImeHidden(imeVisible = imeVisible.value)
                OutlinedTextField(value = "", onValueChange = {}, label = { Text(LABEL) })
            }
        }

        val field = firstTextField()
        field.performClick()
        field.assertIsFocused()

        // Focus was requested while no keyboard is up: nothing may drop it.
        composeTestRule.waitForIdle()
        field.assertIsFocused()
    }

    @Test
    fun focusIsKeptWhileTheKeyboardIsVisible() {
        val imeVisible = mutableStateOf(false)
        composeTestRule.setContent {
            Column {
                ClearFocusWhenImeHidden(imeVisible = imeVisible.value)
                OutlinedTextField(value = "", onValueChange = {}, label = { Text(LABEL) })
            }
        }

        val field = firstTextField()
        field.performClick()
        setImeVisible(imeVisible, true)

        field.assertIsFocused()
        composeTestRule.waitForIdle()
        field.assertIsFocused()
    }

    @Test
    fun aSecondDismissalClearsFocusAgain() {
        val imeVisible = mutableStateOf(false)
        composeTestRule.setContent {
            Column {
                ClearFocusWhenImeHidden(imeVisible = imeVisible.value)
                OutlinedTextField(value = "", onValueChange = {}, label = { Text(LABEL) })
            }
        }

        firstTextField().performClick()
        dismissKeyboard(imeVisible)
        firstTextField().assertIsNotFocused()

        // Opening and dismissing the keyboard a second time must clear focus again: the effect
        // tracks the transition, not "the keyboard has been seen at least once".
        firstTextField().performClick()
        firstTextField().assertIsFocused()
        dismissKeyboard(imeVisible)

        firstTextField().assertIsNotFocused()
    }

    @Test
    fun signInScreenReturnsTheEmailFieldToItsRestingStateWhenTheKeyboardIsDismissed() {
        val imeVisible = mutableStateOf(false)
        val emailLabel = string(R.string.sign_in_email_label)
        composeTestRule.setContent {
            ServoraTheme {
                SignInScreen(
                    uiState = SignInUiState(),
                    onEmailChange = {},
                    onPasswordChange = {},
                    onTogglePasswordVisibility = {},
                    onSubmit = {},
                    imeVisible = imeVisible.value,
                )
            }
        }

        val restingTop = labelTop(emailLabel)
        val emailField = firstTextField()
        emailField.performClick()
        emailField.assertIsFocused()
        assertTrue("the email label should float while focused", labelTop(emailLabel) < restingTop)

        dismissKeyboard(imeVisible)

        emailField.assertIsNotFocused()
        waitForLabelAtRest(emailLabel, restingTop)
    }

    @Test
    fun thePasswordDoneActionSubmitsAndTheKeyboardHidingThenClearsFocus() {
        val imeVisible = mutableStateOf(false)
        var submitted = false
        composeTestRule.setContent {
            ServoraTheme {
                SignInScreen(
                    uiState = SignInUiState(),
                    onEmailChange = {},
                    onPasswordChange = {},
                    onTogglePasswordVisibility = {},
                    onSubmit = { submitted = true },
                    imeVisible = imeVisible.value,
                )
            }
        }

        val passwordField = secondTextField()
        passwordField.performClick()
        setImeVisible(imeVisible, true)
        passwordField.assertIsFocused()

        passwordField.performImeAction()

        assertTrue("the Done action should submit the form", submitted)
        // Done does not move focus by itself; the keyboard going away afterwards does.
        passwordField.assertIsFocused()
        setImeVisible(imeVisible, false)
        composeTestRule.waitForIdle()
        passwordField.assertIsNotFocused()
    }

    @Test
    fun smsScreenDropsThePhoneFieldWhenTheKeyboardIsDismissed() {
        val imeVisible = mutableStateOf(false)
        composeTestRule.setContent {
            ServoraTheme {
                SmsSignInScreen(
                    uiState = SmsSignInUiState(),
                    onPhoneChange = {},
                    onCodeChange = {},
                    onStepBack = {},
                    onResendCode = {},
                    onSubmit = {},
                    onBackToSignIn = {},
                    imeVisible = imeVisible.value,
                )
            }
        }

        val phoneField = firstTextField()
        phoneField.performClick()
        phoneField.assertIsFocused()

        dismissKeyboard(imeVisible)

        phoneField.assertIsNotFocused()
    }

    @Test
    fun passwordResetScreenDropsTheIdentityFieldWhenTheKeyboardIsDismissed() {
        val imeVisible = mutableStateOf(false)
        composeTestRule.setContent {
            ServoraTheme {
                PasswordResetScreen(
                    uiState = PasswordResetUiState(),
                    onEmailChange = {},
                    onCodeChange = {},
                    onNewPasswordChange = {},
                    onTogglePasswordVisibility = {},
                    onStepBack = {},
                    onSubmit = {},
                    onBackToSignIn = {},
                    imeVisible = imeVisible.value,
                )
            }
        }

        val identityField = firstTextField()
        identityField.performClick()
        identityField.assertIsFocused()

        dismissKeyboard(imeVisible)

        identityField.assertIsNotFocused()
    }

    private fun firstTextField() = composeTestRule.onAllNodes(hasSetTextAction())[0]

    private fun secondTextField() = composeTestRule.onAllNodes(hasSetTextAction())[1]

    /** Top of the field's label, which Material moves when the field takes or loses focus. */
    private fun labelTop(label: String): Float =
        composeTestRule.onAllNodesWithText(label, useUnmergedTree = true)[0]
            .getUnclippedBoundsInRoot()
            .top
            .value

    private fun setImeVisible(imeVisible: MutableState<Boolean>, visible: Boolean) {
        composeTestRule.runOnIdle { imeVisible.value = visible }
    }

    /** Shows the keyboard and dismisses it: the transition every dismissal path produces. */
    private fun dismissKeyboard(imeVisible: MutableState<Boolean>) {
        setImeVisible(imeVisible, true)
        composeTestRule.waitForIdle()
        setImeVisible(imeVisible, false)
        composeTestRule.waitForIdle()
    }

    /**
     * Waits for the label to settle back at its resting position. The keyboard is a window inset
     * driven outside the Compose clock, so the return is polled rather than asserted immediately.
     */
    private fun waitForLabelAtRest(label: String, restingTop: Float) {
        composeTestRule.waitUntil(timeoutMillis = SETTLE_TIMEOUT_MILLIS) {
            abs(labelTop(label) - restingTop) <= LABEL_POSITION_TOLERANCE
        }
    }

    private fun string(resId: Int): String =
        ApplicationProvider.getApplicationContext<Context>().getString(resId)

    private companion object {
        const val LABEL = "Email"
        const val PLACEHOLDER = "you@company.com"
        const val LABEL_POSITION_TOLERANCE = 1f
        const val SETTLE_TIMEOUT_MILLIS = 5_000L
    }
}
