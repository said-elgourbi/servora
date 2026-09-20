package com.servora.android.ui.customers

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.servora.android.R
import com.servora.android.data.customers.CustomersFailureReason
import com.servora.android.ui.theme.ServoraTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What the Add/Edit Contact form draws (`BR-095`).
 *
 * The fields are the ones a contact person owns, so these tests cover the form itself: its fields, the
 * primary flag's two states, the incomplete-form message and the two actions. Whether a contact may be
 * written is the backend's decision and is not asserted here.
 */
@RunWith(AndroidJUnit4::class)
class AddContactScreenTest {

    @get:Rule val composeTestRule = createComposeRule()

    @Test
    fun showsEveryFieldAContactPersonOwns() {
        render()

        composeTestRule
            .onNodeWithText(string(R.string.contact_section_person).uppercase())
            .assertIsDisplayed()
        composeTestRule.onNodeWithTag(AddContactFirstNameTag).assertExists()
        composeTestRule.onNodeWithTag(AddContactLastNameTag).assertExists()
        composeTestRule.onNodeWithTag(AddContactPhoneTag).assertExists()
        composeTestRule.onNodeWithTag(AddContactEmailTag).assertExists()
        composeTestRule.onNodeWithTag(AddContactPrimaryTag).assertExists()
    }

    @Test
    fun drawsNoFieldTheModelDoesNotHave() {
        render()

        // The free-text role and the billing/job-contact flags are modelled and returned by the API,
        // while no client captures them in this slice (`BR-042`, `ADR-022` D5).
        composeTestRule.onNodeWithText("Role", substring = true).assertDoesNotExist()
        composeTestRule.onNodeWithText("Billing", substring = true).assertDoesNotExist()
        composeTestRule.onNodeWithText("Job contact", substring = true).assertDoesNotExist()
    }

    @Test
    fun reportsWhatIsMissingAfterASaveAttempt() {
        var saves = 0
        render(
            state = AddContactUiState(customerId = CUSTOMER_ID, saveAttempted = true),
            onSave = { saves += 1 },
        )

        composeTestRule.onNodeWithTag(AddContactMessageTag).assertIsDisplayed()
        composeTestRule
            .onNodeWithText(string(R.string.contact_form_incomplete))
            .assertIsDisplayed()

        composeTestRule.onNodeWithTag(AddContactSaveTag).performClick()
        assertEquals(1, saves)
    }

    @Test
    fun reportsWhyTheBackendRefusedTheSave() {
        render(
            state = AddContactUiState(
                customerId = CUSTOMER_ID,
                failureReason = CustomersFailureReason.VERSION_CONFLICT,
            ),
        )

        // The reason is the API's own outcome, and it is explained in the terms of the record it
        // addresses (`BR-028`, `BR-095`).
        composeTestRule
            .onNodeWithText(string(R.string.contact_error_conflict))
            .assertIsDisplayed()
    }

    @Test
    fun offersTheDesignsTwoActions() {
        var saves = 0
        var cancels = 0
        render(onSave = { saves += 1 }, onCancel = { cancels += 1 })

        composeTestRule.onNodeWithTag(AddContactSaveTag).performClick()
        composeTestRule.onNodeWithTag(AddContactCancelTag).performClick()

        assertEquals(1, saves)
        assertEquals(1, cancels)
    }

    @Test
    fun returnsToTheCustomerOnceTheContactWasSaved() {
        var saved = 0
        render(
            state = AddContactUiState(customerId = CUSTOMER_ID, isSaved = true),
            onSaved = { saved += 1 },
        )

        composeTestRule.waitForIdle()

        assertEquals(1, saved)
    }

    @Test
    fun letsANewContactBeMadePrimary() {
        var stated: Boolean? = null
        render(
            state = AddContactUiState(customerId = CUSTOMER_ID),
            onPrimaryChange = { stated = it },
        )

        composeTestRule.onNodeWithTag(AddContactPrimaryTag).assertIsEnabled()
        composeTestRule.onNodeWithTag(AddContactPrimaryTag).performClick()

        assertEquals(true, stated)
    }

    @Test
    fun locksThePrimaryFlagForAContactThatAlreadyHoldsIt() {
        render(
            state = AddContactUiState(
                customerId = CUSTOMER_ID,
                contactId = CONTACT_ID,
                firstName = "John",
                lastName = "Smith",
                isPrimary = true,
                storedIsPrimary = true,
            ),
        )

        // The API documents `isPrimary: true` — which promotes a contact and clears the customer's
        // previous primary in one transaction — and defines no un-promotion, so there is no change
        // for the form to state for a contact that already holds the flag (`BR-042`, `BR-095`).
        composeTestRule.onNodeWithTag(AddContactPrimaryTag).assertIsNotEnabled()
    }

    private fun render(
        state: AddContactUiState = AddContactUiState(customerId = CUSTOMER_ID),
        onPrimaryChange: (Boolean) -> Unit = {},
        onSave: () -> Unit = {},
        onCancel: () -> Unit = {},
        onSaved: () -> Unit = {},
    ) {
        composeTestRule.setContent {
            ServoraTheme {
                AddContactScreen(
                    state = state,
                    onFirstNameChange = {},
                    onLastNameChange = {},
                    onPhoneChange = {},
                    onEmailChange = {},
                    onPrimaryChange = onPrimaryChange,
                    onSave = onSave,
                    onCancel = onCancel,
                    onSaved = onSaved,
                )
            }
        }
    }

    private fun string(resId: Int): String =
        ApplicationProvider.getApplicationContext<Context>().getString(resId)

    private companion object {
        const val CUSTOMER_ID = "customer-1"
        const val CONTACT_ID = "contact-1"
    }
}
