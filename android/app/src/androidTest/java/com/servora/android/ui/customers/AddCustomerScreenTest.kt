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
import com.servora.android.domain.model.CustomerType
import com.servora.android.domain.model.PropertyProvince
import com.servora.android.ui.theme.ServoraTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What the New Customer form draws (`BR-023`, `BR-049`, `BR-050`).
 *
 * The fields are the customer's own plus the two optional sections, so these tests cover the form
 * itself: the type control, the fields of each type, the optional sections and their permission
 * gating, the incomplete-form message, and the two actions. Whether a customer may be created is the
 * backend's decision and is not asserted here.
 */
@RunWith(AndroidJUnit4::class)
class AddCustomerScreenTest {

    @get:Rule val composeTestRule = createComposeRule()

    @Test
    fun showsTheTypeControlAndEveryAuthoritativeCustomerField() {
        render()

        composeTestRule.onNodeWithTag(AddCustomerIndividualTag).assertIsDisplayed()
        composeTestRule.onNodeWithTag(AddCustomerBusinessTag).assertIsDisplayed()
        composeTestRule
            .onNodeWithText(string(R.string.customers_create_section_info).uppercase())
            .assertIsDisplayed()
        composeTestRule.onNodeWithTag(AddCustomerFirstNameTag).assertExists()
        composeTestRule.onNodeWithTag(AddCustomerLastNameTag).assertExists()
        composeTestRule.onNodeWithTag(AddCustomerPhoneTag).assertExists()
        composeTestRule.onNodeWithTag(AddCustomerEmailTag).assertExists()
        composeTestRule.onNodeWithTag(AddCustomerNotesTag).assertExists()
    }

    @Test
    fun drawsTheOptionalFirstServicePropertyWithItsOwnFields() {
        render()

        composeTestRule
            .onNodeWithText(string(R.string.customers_create_section_property))
            .assertIsDisplayed()
        composeTestRule.onNodeWithTag(AddCustomerPropertySectionTag).assertExists()
        composeTestRule.onNodeWithTag(AddCustomerPropertyStreetTag).assertExists()
        composeTestRule.onNodeWithTag(AddCustomerPropertyUnitTag).assertExists()
        composeTestRule.onNodeWithTag(AddCustomerPropertyCityTag).assertExists()
        composeTestRule.onNodeWithTag(AddCustomerPropertyPostalCodeTag).assertExists()
        composeTestRule.onNodeWithTag(AddCustomerPropertyNameTag).assertExists()
        composeTestRule.onNodeWithTag(AddCustomerPropertyNotesTag).assertExists()
    }

    @Test
    fun hidesThePropertySectionWithoutThePropertyCreatePermission() {
        render(canCreateProperty = false)

        // Creating a Property is its own capability (`BR-085`), so the section is not drawn at all
        // for a caller who cannot write one.
        composeTestRule.onNodeWithTag(AddCustomerPropertySectionTag).assertDoesNotExist()
    }

    @Test
    fun showsThePrimaryContactOnlyForABusinessCustomerWithThePermission() {
        render(state = AddCustomerUiState(type = CustomerType.COMPANY))

        composeTestRule.onNodeWithTag(AddCustomerCompanyNameTag).assertExists()
        composeTestRule.onNodeWithTag(AddCustomerContactFirstNameTag).assertExists()
        composeTestRule.onNodeWithTag(AddCustomerContactLastNameTag).assertExists()
    }

    @Test
    fun anIndividualCustomerHasNoCompanyOrContactFields() {
        render()

        composeTestRule.onNodeWithTag(AddCustomerCompanyNameTag).assertDoesNotExist()
        composeTestRule.onNodeWithTag(AddCustomerContactFirstNameTag).assertDoesNotExist()
    }

    @Test
    fun hidesThePrimaryContactWithoutThePermissionThatWritesIt() {
        render(
            state = AddCustomerUiState(type = CustomerType.COMPANY),
            canCreateContact = false,
        )

        composeTestRule.onNodeWithTag(AddCustomerCompanyNameTag).assertExists()
        composeTestRule.onNodeWithTag(AddCustomerContactFirstNameTag).assertDoesNotExist()
    }

    @Test
    fun drawsNoFieldTheModelDoesNotHave() {
        render()

        // The form has no customer status choice, the Property model has no type or access
        // instructions, and the country is the backend's, so the form must not ask for them
        // (`BR-042`).
        composeTestRule.onNodeWithText("Customer status", substring = true).assertDoesNotExist()
        composeTestRule.onNodeWithText("Property type", substring = true).assertDoesNotExist()
        composeTestRule
            .onNodeWithText("Access instructions", substring = true)
            .assertDoesNotExist()
        composeTestRule.onNodeWithText("Country", substring = true).assertDoesNotExist()
    }

    @Test
    fun reportsWhatIsMissingAfterASaveAttempt() {
        var saves = 0
        render(state = AddCustomerUiState(saveAttempted = true), onSave = { saves += 1 })

        composeTestRule.onNodeWithTag(AddCustomerMessageTag).assertIsDisplayed()
        composeTestRule
            .onNodeWithText(string(R.string.customers_create_incomplete))
            .assertIsDisplayed()

        composeTestRule.onNodeWithTag(AddCustomerSaveTag).performClick()
        assertEquals(1, saves)
    }

    @Test
    fun offersTheDesignsTwoActions() {
        var saves = 0
        var cancels = 0
        render(onSave = { saves += 1 }, onCancel = { cancels += 1 })

        composeTestRule.onNodeWithTag(AddCustomerSaveTag).performClick()
        composeTestRule.onNodeWithTag(AddCustomerCancelTag).performClick()

        assertEquals(1, saves)
        assertEquals(1, cancels)
    }

    @Test
    fun picksAProvinceFromThePicker() {
        var picked: PropertyProvince? = null
        render(onProvinceChange = { picked = it })

        composeTestRule.onNodeWithTag(AddPropertyProvinceTag).performClick()
        composeTestRule.onNodeWithTag(AddPropertyProvinceSheetTag).assertIsDisplayed()
        composeTestRule.onNodeWithTag(addPropertyProvinceOptionTag("QC")).performClick()

        assertEquals(PropertyProvince.QC, picked)
    }

    @Test
    fun offersToOpenTheCustomerOnceItExistsButThePropertyDidNotSave() {
        var opened: String? = null
        var saves = 0
        render(
            state = AddCustomerUiState(
                createdCustomerId = CREATED_CUSTOMER_ID,
                unfinishedStep = CustomerSetupStep.FIRST_PROPERTY,
            ),
            onSave = { saves += 1 },
            onOpenCreatedCustomer = { opened = it },
        )

        composeTestRule.onNodeWithTag(AddCustomerCreatedTag).assertIsDisplayed()
        // The form cannot create the customer again, so the primary action opens it instead.
        composeTestRule.onNodeWithTag(AddCustomerSaveTag).performClick()

        assertEquals(0, saves)
        assertEquals(CREATED_CUSTOMER_ID, opened)
    }

    @Test
    fun returnsTheCreatedCustomerOnceEveryPartWasAccepted() {
        var saved: String? = null
        render(
            state = AddCustomerUiState(
                isSaved = true,
                createdCustomerId = CREATED_CUSTOMER_ID,
            ),
            onSaved = { saved = it },
        )

        composeTestRule.waitForIdle()

        assertEquals(CREATED_CUSTOMER_ID, saved)
    }

    private fun render(
        state: AddCustomerUiState = AddCustomerUiState(),
        canCreateProperty: Boolean = true,
        canCreateContact: Boolean = true,
        onProvinceChange: (PropertyProvince) -> Unit = {},
        onSave: () -> Unit = {},
        onCancel: () -> Unit = {},
        onSaved: (String) -> Unit = {},
        onOpenCreatedCustomer: (String) -> Unit = {},
    ) {
        composeTestRule.setContent {
            ServoraTheme {
                AddCustomerScreen(
                    state = state,
                    canCreateProperty = canCreateProperty,
                    canCreateContact = canCreateContact,
                    onTypeChange = {},
                    onCompanyNameChange = {},
                    onFirstNameChange = {},
                    onLastNameChange = {},
                    onPhoneChange = {},
                    onEmailChange = {},
                    onNotesChange = {},
                    onStreetAddressChange = {},
                    onUnitChange = {},
                    onCityChange = {},
                    onProvinceChange = onProvinceChange,
                    onPostalCodeChange = {},
                    onPropertyNameChange = {},
                    onPropertyNotesChange = {},
                    onContactFirstNameChange = {},
                    onContactLastNameChange = {},
                    onSave = onSave,
                    onCancel = onCancel,
                    onSaved = onSaved,
                    onOpenCreatedCustomer = onOpenCreatedCustomer,
                )
            }
        }
    }

    private fun string(resId: Int): String =
        ApplicationProvider.getApplicationContext<Context>().getString(resId)

    private companion object {
        const val CREATED_CUSTOMER_ID = "created-1"
    }
}
