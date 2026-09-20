package com.servora.android.ui.customers

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.servora.android.R
import com.servora.android.data.customers.CustomersFailureReason
import com.servora.android.domain.model.CustomerContact
import com.servora.android.domain.model.CustomerStatus
import com.servora.android.domain.model.CustomerType
import com.servora.android.ui.theme.ServoraTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What the Edit Customer form draws (`BR-023`, `BR-087`).
 *
 * The form states the customer's type, so these tests cover what it draws for each stated type, the
 * notice a conversion shows before it is applied, the incomplete-form message and the two actions.
 * Whether the edit — or a conversion — may be applied is the backend's decision and is not asserted
 * here.
 */
@RunWith(AndroidJUnit4::class)
class EditCustomerScreenTest {

    @get:Rule val composeTestRule = createComposeRule()

    @Test
    fun showsNothingEditableUntilTheCustomerHasBeenRead() {
        render(state = EditCustomerUiState(customerId = CUSTOMER_ID, isLoading = true))

        composeTestRule.onNodeWithTag(EditCustomerIndividualTag).assertDoesNotExist()
        composeTestRule.onNodeWithTag(EditCustomerCompanyNameTag).assertDoesNotExist()
        composeTestRule.onNodeWithTag(EditCustomerPropertiesHintTag).assertDoesNotExist()
        composeTestRule.onNodeWithTag(EditCustomerSaveTag).assertDoesNotExist()
    }

    @Test
    fun reportsAFailedReadWithARetry() {
        var retries = 0
        render(
            state = EditCustomerUiState(
                customerId = CUSTOMER_ID,
                isLoading = false,
                failureReason = CustomersFailureReason.NETWORK,
            ),
            onRetry = { retries += 1 },
        )

        composeTestRule
            .onNodeWithText(string(R.string.customers_error_title))
            .assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.customers_retry)).performClick()

        assertEquals(1, retries)
    }

    @Test
    fun drawsTheFieldsOfACompanyCustomer() {
        render(state = loadedState(type = CustomerType.COMPANY))

        composeTestRule.onNodeWithTag(EditCustomerBusinessTag).assertIsDisplayed()
        composeTestRule.onNodeWithTag(EditCustomerCompanyNameTag).assertExists()
        composeTestRule.onNodeWithTag(EditCustomerPhoneTag).assertExists()
        composeTestRule.onNodeWithTag(EditCustomerEmailTag).assertExists()
        composeTestRule.onNodeWithTag(EditCustomerNotesTag).assertExists()
        composeTestRule.onNodeWithTag(EditCustomerStatusActiveTag).assertExists()
        composeTestRule.onNodeWithTag(EditCustomerStatusInactiveTag).assertExists()
        // A company stores a legal name, not a person's names.
        composeTestRule.onNodeWithTag(EditCustomerFirstNameTag).assertDoesNotExist()
        composeTestRule.onNodeWithTag(EditCustomerLastNameTag).assertDoesNotExist()
    }

    @Test
    fun saysWherePropertiesAreManaged() {
        render(state = loadedState())

        composeTestRule
            .onNodeWithTag(EditCustomerPropertiesHintTag)
            .assertExists()
        composeTestRule
            .onNodeWithText(string(R.string.customers_edit_properties_hint))
            .assertExists()
    }

    @Test
    fun drawsTheFieldsOfAnIndividualCustomer() {
        render(
            state = loadedState(
                type = CustomerType.INDIVIDUAL,
                storedType = CustomerType.INDIVIDUAL,
            ),
        )

        composeTestRule.onNodeWithTag(EditCustomerIndividualTag).assertIsDisplayed()
        composeTestRule.onNodeWithTag(EditCustomerFirstNameTag).assertExists()
        composeTestRule.onNodeWithTag(EditCustomerLastNameTag).assertExists()
        composeTestRule.onNodeWithTag(EditCustomerCompanyNameTag).assertDoesNotExist()
    }

    @Test
    fun saysWhatAConversionReplacesOnlyWhenTheTypeChanges() {
        render(state = loadedState(type = CustomerType.INDIVIDUAL))

        // The form states the individual kind while the customer is a company, which is a
        // conversion (`BR-087`).
        composeTestRule
            .onNodeWithTag(EditCustomerConversionNoticeTag)
            .assertIsDisplayed()
        composeTestRule
            .onNodeWithText(string(R.string.customers_edit_conversion_notice))
            .assertIsDisplayed()
    }


    @Test
    fun saysNothingAboutConversionWhileTheTypeIsUnchanged() {
        render(
            state = loadedState(
                type = CustomerType.COMPANY,
                storedType = CustomerType.COMPANY,
            ),
        )

        composeTestRule.onNodeWithTag(EditCustomerConversionNoticeTag).assertDoesNotExist()
    }

    @Test
    fun reportsWhatIsMissingAfterASaveAttempt() {
        var saves = 0
        render(
            state = loadedState(
                type = CustomerType.COMPANY,
                companyName = "",
                saveAttempted = true,
            ),
            onSave = { saves += 1 },
        )

        composeTestRule.onNodeWithTag(EditCustomerMessageTag).assertIsDisplayed()
        composeTestRule
            .onNodeWithText(string(R.string.customers_edit_incomplete))
            .assertIsDisplayed()

        composeTestRule.onNodeWithTag(EditCustomerSaveTag).performClick()
        assertEquals(1, saves)
    }

    @Test
    fun offersTheDesignsTwoActions() {
        var saves = 0
        var cancels = 0
        render(
            state = loadedState(),
            onSave = { saves += 1 },
            onCancel = { cancels += 1 },
        )

        composeTestRule.onNodeWithTag(EditCustomerSaveTag).performClick()
        composeTestRule.onNodeWithTag(EditCustomerCancelTag).performClick()

        assertEquals(1, saves)
        assertEquals(1, cancels)
    }

    @Test
    fun returnsToTheCustomerOnceTheEditWasAccepted() {
        var saved = 0
        render(
            state = loadedState().copy(isSaved = true),
            onSaved = { saved += 1 },
        )

        composeTestRule.waitForIdle()

        assertEquals(1, saved)
    }

    @Test
    fun showsTheCustomersContactsReadOnlyWithTheHintThatSaysWhereTheyAreManaged() {
        render(state = loadedState(contacts = listOf(contact())))

        // The edit form writes the customer's own fields and nothing else, so its contacts are shown
        // as the backend reported them and the hint says where they are maintained (`ADR-022` D5).
        composeTestRule.onNodeWithTag(EditCustomerContactsTag).assertExists()
        composeTestRule.onNodeWithText("John Smith").assertExists()
        composeTestRule
            .onNodeWithText(string(R.string.customers_contacts_primary))
            .assertExists()
        composeTestRule
            .onNodeWithText(string(R.string.customers_edit_contacts_hint))
            .assertExists()
    }

    @Test
    fun omitsTheContactsBlockWhenTheCustomerHasNoneButKeepsTheHint() {
        render(state = loadedState())

        composeTestRule.onNodeWithTag(EditCustomerContactsTag).assertDoesNotExist()
        composeTestRule.onNodeWithTag(EditCustomerContactsHintTag).assertExists()
    }

    private fun render(
        state: EditCustomerUiState = loadedState(),
        onTypeChange: (CustomerType) -> Unit = {},
        onStatusChange: (CustomerStatus) -> Unit = {},
        onSave: () -> Unit = {},
        onCancel: () -> Unit = {},
        onRetry: () -> Unit = {},
        onSaved: () -> Unit = {},
    ) {
        composeTestRule.setContent {
            ServoraTheme {
                EditCustomerScreen(
                    state = state,
                    onTypeChange = onTypeChange,
                    onCompanyNameChange = {},
                    onFirstNameChange = {},
                    onLastNameChange = {},
                    onPhoneChange = {},
                    onEmailChange = {},
                    onNotesChange = {},
                    onStatusChange = onStatusChange,
                    onSave = onSave,
                    onCancel = onCancel,
                    onRetry = onRetry,
                    onSaved = onSaved,
                )
            }
        }
    }

    private fun string(resId: Int): String =
        ApplicationProvider.getApplicationContext<Context>().getString(resId)

    private companion object {
        const val CUSTOMER_ID = "customer-1"
    }
}

/** A form whose customer has been read: the state every drawn case starts from. */
private fun loadedState(
    type: CustomerType = CustomerType.COMPANY,
    storedType: CustomerType = CustomerType.COMPANY,
    companyName: String = "Cedar Property Management Ltd.",
    saveAttempted: Boolean = false,
    contacts: List<CustomerContact> = emptyList(),
) = EditCustomerUiState(
    customerId = "customer-1",
    isLoading = false,
    isLoaded = true,
    type = type,
    storedType = storedType,
    companyName = companyName,
    firstName = "Jordan",
    lastName = "Lee",
    phone = "+15551234567",
    email = "hello@abc.example",
    notes = "Gate code 4412.",
    status = CustomerStatus.ACTIVE,
    saveAttempted = saveAttempted,
    contacts = contacts,
)

/** One contact person as the backend reports it (`BR-095`). */
private fun contact(
    id: String = "contact-1",
    firstName: String = "John",
    lastName: String = "Smith",
    isPrimary: Boolean = true,
) = CustomerContact(
    id = id,
    customerId = "customer-1",
    firstName = firstName,
    lastName = lastName,
    email = "john@example.com",
    phone = "+15559876543",
    role = null,
    isPrimary = isPrimary,
    isBillingContact = false,
    isJobContact = false,
    version = 1,
    createdAt = "2025-01-12T10:31:00Z",
    updatedAt = "2025-01-12T10:31:00Z",
)
