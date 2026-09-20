package com.servora.android.ui.auth

import android.content.Context
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.servora.android.R
import com.servora.android.ui.signin.DevSignInAccount
import com.servora.android.ui.signin.DevSignInRole
import com.servora.android.ui.signin.SignInScreen
import com.servora.android.ui.signin.SignInUiState
import com.servora.android.ui.theme.ServoraTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The temporary development sign-in shortcut on the sign-in screen
 * (`docs/tracker/049-android-dev-sign-in-buttons.md`).
 *
 * The accounts are supplied through the screen's `devAccounts` parameter rather than read from the
 * build, so the rendering rules are driven directly: an account list draws the section, an empty
 * one draws nothing, and a running attempt disables the buttons. Which build carries which list is
 * the source set's business (`DevSignInAccounts`), asserted by the JVM test of the same name.
 *
 * On-device execution belongs to the product owner (`qa.md` §7.3).
 */
@RunWith(AndroidJUnit4::class)
class DevSignInButtonsTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun theDevelopmentAccountsAreOfferedAsOneTapButtons() {
        var submitted: Pair<String, String>? = null
        showSignIn(accounts = ACCOUNTS, onDevSignIn = { email, password ->
            submitted = email to password
        })

        composeTestRule.onNodeWithText(string(R.string.dev_sign_in_label)).assertExists()
        composeTestRule.onNodeWithText(managerLabel()).performClick()

        assertEquals(MANAGER_EMAIL to MANAGER_PASSWORD, submitted)
    }

    @Test
    fun eachDevelopmentAccountShowsTheAccountItSignsInAs() {
        showSignIn(accounts = ACCOUNTS)

        // The address is the only credential detail a screen may show: it tells the accounts apart.
        composeTestRule.onNodeWithText(MANAGER_EMAIL).assertExists()
        composeTestRule.onNodeWithText(TECHNICIAN_EMAIL).assertExists()
        composeTestRule.onNodeWithText(technicianLabel()).assertExists()
    }

    @Test
    fun aBuildWithoutAccountsShowsNoDevelopmentSection() {
        showSignIn(accounts = emptyList())

        composeTestRule.onNodeWithText(string(R.string.dev_sign_in_label)).assertDoesNotExist()
        composeTestRule.onNodeWithText(managerLabel()).assertDoesNotExist()
    }

    @Test
    fun theDevelopmentButtonsAreDisabledWhileAnAttemptIsRunning() {
        var submitted: Pair<String, String>? = null
        showSignIn(
            accounts = ACCOUNTS,
            uiState = SignInUiState(isSubmitting = true),
            onDevSignIn = { email, password -> submitted = email to password },
        )

        composeTestRule.onNodeWithText(managerLabel()).assertIsNotEnabled()
        composeTestRule.onNodeWithText(managerLabel()).performClick()

        assertNull("a disabled shortcut must not start a second attempt", submitted)
        composeTestRule.onNodeWithText(managerLabel()).assertIsNotEnabled()
    }

    @Test
    fun theDevelopmentButtonsAreEnabledWhileTheFormIsIdle() {
        showSignIn(accounts = ACCOUNTS)

        composeTestRule.onNodeWithText(managerLabel()).assertIsEnabled()
    }

    private fun showSignIn(
        accounts: List<DevSignInAccount>,
        uiState: SignInUiState = SignInUiState(),
        onDevSignIn: (String, String) -> Unit = { _, _ -> },
    ) {
        composeTestRule.setContent {
            ServoraTheme {
                SignInScreen(
                    uiState = uiState,
                    onEmailChange = {},
                    onPasswordChange = {},
                    onTogglePasswordVisibility = {},
                    onSubmit = {},
                    devAccounts = accounts,
                    onDevSignIn = onDevSignIn,
                )
            }
        }
    }

    private fun string(resId: Int): String =
        ApplicationProvider.getApplicationContext<Context>().getString(resId)

    // Resolved from resources rather than written as English literals, so the assertions hold in
    // whichever language the device runs (`BR-028`).
    private fun managerLabel(): String = string(R.string.dev_sign_in_manager)

    private fun technicianLabel(): String = string(R.string.dev_sign_in_technician)

    private companion object {
        const val MANAGER_EMAIL = "manager@servora.test"
        const val TECHNICIAN_EMAIL = "technician@servora.test"
        const val MANAGER_PASSWORD = "manager-password"
        const val TECHNICIAN_PASSWORD = "technician-password"

        val ACCOUNTS = listOf(
            DevSignInAccount(DevSignInRole.MANAGER, MANAGER_EMAIL, MANAGER_PASSWORD),
            DevSignInAccount(DevSignInRole.TECHNICIAN, TECHNICIAN_EMAIL, TECHNICIAN_PASSWORD),
        )
    }
}
