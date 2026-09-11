package com.servora.android.ui.passwordreset

import android.content.Context
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.servora.android.R
import com.servora.android.ui.theme.ServoraTheme
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What the password-reset screen renders for each state of the flow (`BR-043`).
 *
 * Two assertions guard client-only behaviour the backend never sees: the confirmation field exists,
 * and a typo is reported where the typo was made. The third guards a regression — the completion
 * step offered "Back to sign in" twice, once as the primary action and once as the footer link.
 * Gating the primary action on a step with no submission is what removed the duplicate.
 *
 * The validation that *gates* submission lives in `PasswordResetViewModel` and is covered by
 * `PasswordResetViewModelTest`; these tests cover rendering and the callback wiring only.
 */
@RunWith(AndroidJUnit4::class)
class PasswordResetScreenTest {

    @get:Rule val composeTestRule = createComposeRule()

    @Test
    fun newPasswordStepAsksForThePasswordTwice() {
        render(PasswordResetUiState(step = PasswordResetStep.NEW_PASSWORD))

        composeTestRule.onAllNodes(hasSetTextAction()).assertCountEquals(2)
        composeTestRule
            .onAllNodesWithText(
                string(R.string.password_reset_new_password_label),
                useUnmergedTree = true,
            )
            .assertCountEquals(1)
        composeTestRule
            .onAllNodesWithText(
                string(R.string.password_reset_confirm_password_label),
                useUnmergedTree = true,
            )
            .assertCountEquals(1)
    }

    @Test
    fun newPasswordStepReportsAMismatchOnTheConfirmationField() {
        render(
            PasswordResetUiState(
                step = PasswordResetStep.NEW_PASSWORD,
                newPassword = "a-new-password",
                confirmPassword = "a-typo",
                fieldError = PasswordResetFieldError.CONFIRM_PASSWORD,
            ),
        )

        composeTestRule
            .onAllNodesWithText(
                string(R.string.password_reset_error_passwords_do_not_match),
                useUnmergedTree = true,
            )
            .assertCountEquals(1)
    }

    @Test
    fun doneStepOffersASingleWayBackToSignIn() {
        var backToSignInCount = 0
        render(PasswordResetUiState(step = PasswordResetStep.DONE)) { backToSignInCount++ }

        composeTestRule
            .onAllNodesWithText(string(R.string.password_reset_back_to_sign_in))
            .assertCountEquals(1)
        composeTestRule
            .onAllNodesWithText(string(R.string.password_reset_done_message))
            .assertCountEquals(1)

        composeTestRule
            .onAllNodesWithText(string(R.string.password_reset_back_to_sign_in))
            .onFirst()
            .performScrollTo()
            .performClick()
        assertEquals(1, backToSignInCount)
    }

    /**
     * The two strings this screen added are business copy, so both languages must render. The
     * French lookup is asserted against the English copy first: without that guard, a missing
     * `values-fr` entry would silently make this test compare the English text with itself.
     */
    @Test
    fun frenchLocaleRendersTheFrenchCopy() {
        val french = frenchResourcesContext()
        val confirmLabel = french.getString(R.string.password_reset_confirm_password_label)
        val mismatch = french.getString(R.string.password_reset_error_passwords_do_not_match)
        assertNotEquals(string(R.string.password_reset_confirm_password_label), confirmLabel)
        assertNotEquals(string(R.string.password_reset_error_passwords_do_not_match), mismatch)

        composeTestRule.setContent {
            CompositionLocalProvider(
                LocalContext provides french,
                LocalConfiguration provides french.resources.configuration,
            ) {
                ServoraContent(
                    uiState =
                        PasswordResetUiState(
                            step = PasswordResetStep.NEW_PASSWORD,
                            newPassword = "a-new-password",
                            confirmPassword = "a-typo",
                            fieldError = PasswordResetFieldError.CONFIRM_PASSWORD,
                        ),
                )
            }
        }

        composeTestRule.onAllNodesWithText(confirmLabel, useUnmergedTree = true).assertCountEquals(1)
        composeTestRule.onAllNodesWithText(mismatch, useUnmergedTree = true).assertCountEquals(1)
    }

    private fun render(uiState: PasswordResetUiState, onBackToSignIn: () -> Unit = {}) {
        composeTestRule.setContent { ServoraContent(uiState, onBackToSignIn) }
    }

    @Composable
    private fun ServoraContent(uiState: PasswordResetUiState, onBackToSignIn: () -> Unit = {}) {
        ServoraTheme {
            PasswordResetScreen(
                uiState = uiState,
                onEmailChange = {},
                onCodeChange = {},
                onNewPasswordChange = {},
                onConfirmPasswordChange = {},
                onTogglePasswordVisibility = {},
                onStepBack = {},
                onSubmit = {},
                onBackToSignIn = onBackToSignIn,
                imeVisible = false,
            )
        }
    }

    private fun frenchResourcesContext(): Context {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val configuration =
            Configuration(base.resources.configuration).apply { setLocale(Locale.CANADA_FRENCH) }
        return base.createConfigurationContext(configuration)
    }

    private fun string(resId: Int): String =
        ApplicationProvider.getApplicationContext<Context>().getString(resId)
}
