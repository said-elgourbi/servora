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
import com.servora.android.domain.model.PropertyProvince
import com.servora.android.ui.theme.ServoraTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What the Add Property form draws (`BR-049`, `BR-050`).
 *
 * The fields are the authoritative Property fields, so these tests cover the form itself: the two
 * sections, the province picker, the incomplete-form message and the two actions. Whether a Property
 * may be created is the backend's decision and is not asserted here.
 */
@RunWith(AndroidJUnit4::class)
class AddPropertyScreenTest {

    @get:Rule val composeTestRule = createComposeRule()

    @Test
    fun showsTheTwoSectionsAndEveryAuthoritativeField() {
        render()

        composeTestRule
            .onNodeWithText(string(R.string.property_section_service_address).uppercase())
            .assertIsDisplayed()
        composeTestRule.onNodeWithTag(AddPropertyStreetTag).assertExists()
        composeTestRule.onNodeWithTag(AddPropertyUnitTag).assertExists()
        composeTestRule.onNodeWithTag(AddPropertyProvinceTag).assertExists()
        composeTestRule.onNodeWithTag(AddPropertyPostalCodeTag).assertExists()
        composeTestRule
            .onNodeWithText(string(R.string.property_section_details).uppercase())
            .assertExists()
        composeTestRule.onNodeWithTag(AddPropertyNameTag).assertExists()
        composeTestRule.onNodeWithTag(AddPropertyNotesTag).assertExists()
    }

    @Test
    fun drawsNoFieldTheModelDoesNotHave() {
        render()

        // The design's property type, access instructions and country are not part of the Property
        // model, so the form must not ask for them (`BR-042`).
        composeTestRule.onNodeWithText("Property type", substring = true).assertDoesNotExist()
        composeTestRule.onNodeWithText("Access instructions", substring = true).assertDoesNotExist()
        composeTestRule.onNodeWithText("Country", substring = true).assertDoesNotExist()
    }

    @Test
    fun carriesNoCreateJobActionOverTheForm() {
        render()

        composeTestRule.onNodeWithText(string(R.string.customers_create_job)).assertDoesNotExist()
    }

    @Test
    fun reportsWhatIsMissingAfterASaveAttempt() {
        var saves = 0
        render(
            state = AddPropertyUiState(customerId = CUSTOMER_ID, saveAttempted = true),
            onSave = { saves += 1 },
        )

        composeTestRule.onNodeWithTag(AddPropertyMessageTag).assertIsDisplayed()
        composeTestRule
            .onNodeWithText(string(R.string.property_form_incomplete))
            .assertIsDisplayed()

        composeTestRule.onNodeWithTag(AddPropertySaveTag).performClick()
        assertEquals(1, saves)
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
    fun showsTheChosenProvinceWithItsStableCodeAndLocalizedName() {
        render(state = AddPropertyUiState(customerId = CUSTOMER_ID, province = PropertyProvince.QC))

        composeTestRule
            .onNodeWithText(
                string(
                    R.string.property_province_value_format,
                    "QC",
                    string(R.string.property_province_qc),
                ),
            )
            .assertIsDisplayed()
    }

    @Test
    fun offersTheDesignsTwoActions() {
        var saves = 0
        var cancels = 0
        render(onSave = { saves += 1 }, onCancel = { cancels += 1 })

        composeTestRule.onNodeWithTag(AddPropertySaveTag).performClick()
        composeTestRule.onNodeWithTag(AddPropertyCancelTag).performClick()

        assertEquals(1, saves)
        assertEquals(1, cancels)
    }

    @Test
    fun returnsToTheCustomerOnceThePropertyWasCreated() {
        var saved = 0
        render(
            state = AddPropertyUiState(customerId = CUSTOMER_ID, isSaved = true),
            onSaved = { saved += 1 },
        )

        composeTestRule.waitForIdle()

        assertEquals(1, saved)
    }

    private fun render(
        state: AddPropertyUiState = AddPropertyUiState(customerId = CUSTOMER_ID),
        onProvinceChange: (PropertyProvince) -> Unit = {},
        onSave: () -> Unit = {},
        onCancel: () -> Unit = {},
        onSaved: () -> Unit = {},
    ) {
        composeTestRule.setContent {
            ServoraTheme {
                AddPropertyScreen(
                    state = state,
                    onStreetAddressChange = {},
                    onUnitChange = {},
                    onCityChange = {},
                    onProvinceChange = onProvinceChange,
                    onPostalCodeChange = {},
                    onNameChange = {},
                    onNotesChange = {},
                    onSave = onSave,
                    onCancel = onCancel,
                    onSaved = onSaved,
                )
            }
        }
    }

    private fun string(resId: Int): String =
        ApplicationProvider.getApplicationContext<Context>().getString(resId)

    private fun string(resId: Int, vararg args: Any): String =
        ApplicationProvider.getApplicationContext<Context>().getString(resId, *args)

    private companion object {
        const val CUSTOMER_ID = "customer-1"
    }
}
