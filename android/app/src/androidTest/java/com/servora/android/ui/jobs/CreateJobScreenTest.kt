package com.servora.android.ui.jobs

import android.content.Context
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.servora.android.R
import com.servora.android.data.jobs.JobCreateFailure
import com.servora.android.domain.model.CustomerProperty
import com.servora.android.domain.model.CustomerStatus
import com.servora.android.ui.customers.CustomerListItem
import com.servora.android.ui.theme.ServoraTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What the Create Job form draws (`BR-094`).
 *
 * The form's fields are the values the API accepts, so these tests cover the form itself: the two
 * selectors, the fixed-customer context, the no-properties state, the validation message, the create
 * action and the failure copy. Whether a Job may be created is the backend's decision and is not
 * asserted here (`BR-001`, `BR-007`).
 */
@RunWith(AndroidJUnit4::class)
class CreateJobScreenTest {

    @get:Rule val composeTestRule = createComposeRule()

    @Test
    fun drawsTheThreeValuesAJobIsCreatedWith() {
        render()

        composeTestRule.onNodeWithText(string(R.string.job_create_section_customer).uppercase())
            .assertIsDisplayed()
        composeTestRule.onNodeWithTag(CreateJobCustomerTag).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.job_create_section_property).uppercase())
            .assertIsDisplayed()
        composeTestRule.onNodeWithTag(CreateJobPropertyTag).assertIsDisplayed()
        composeTestRule.onNodeWithTag(CreateJobTitleTag).assertExists()
        composeTestRule.onNodeWithTag(CreateJobDescriptionTag).assertExists()
        composeTestRule.onNodeWithTag(CreateJobSubmitTag).assertExists()
    }

    @Test
    fun keepsThePropertySelectorUnusableUntilACustomerIsChosen() {
        render()

        // `BR-094` requires a Property owned by the Customer, so there is nothing to choose before one
        // is known.
        composeTestRule.onNodeWithTag(CreateJobPropertyTag).assertIsNotEnabled()
        composeTestRule
            .onNodeWithText(string(R.string.job_create_property_needs_customer))
            .assertIsDisplayed()
    }

    @Test
    fun enablesThePropertySelectorOnceACustomerIsChosen() {
        render(state = CreateJobUiState(selectedCustomerId = "c1", selectedCustomerName = "Martha"))

        composeTestRule.onNodeWithTag(CreateJobPropertyTag).assertIsEnabled()
    }

    @Test
    fun showsTheCustomerTheFormWasOpenedForAsFixedContext() {
        render(
            state = CreateJobUiState(
                fixedCustomerId = "c1",
                fixedCustomerName = "ABC Property Management",
                selectedCustomerId = "c1",
                selectedCustomerName = "ABC Property Management",
            ),
        )

        // The customer is context, so it is drawn as a value rather than a search control and the
        // picker is not offered (`BR-012`).
        composeTestRule
            .onNodeWithTag(CreateJobCustomerValueTag)
            .assertTextEquals("ABC Property Management")
        composeTestRule.onNodeWithTag(CreateJobCustomerTag).assertDoesNotExist()
    }

    @Test
    fun letsTheCustomerBeSearchedAndChosen() {
        var selected: CustomerListItem? = null
        render(
            state = CreateJobUiState(
                customers = listOf(customer(id = "c1", name = "Martha Reynolds")),
            ),
            onSelectCustomer = { selected = it },
        )

        composeTestRule.onNodeWithTag(CreateJobCustomerTag).performClick()
        composeTestRule.onNodeWithTag(CreateJobCustomerSheetTag).assertIsDisplayed()
        composeTestRule.onNodeWithTag(CreateJobCustomerSearchTag).assertExists()
        composeTestRule.onNodeWithTag(createJobCustomerOptionTag("c1")).performClick()

        assertEquals("c1", selected?.id)
    }

    @Test
    fun showsThePropertiesOfTheChosenCustomerAndChoosesOne() {
        var selectedProperty: String? = null
        render(
            state = CreateJobUiState(
                selectedCustomerId = "c1",
                selectedCustomerName = "Martha Reynolds",
                properties = listOf(property(id = "p1", name = "Cedar Lane Building")),
            ),
            onSelectProperty = { selectedProperty = it },
        )

        composeTestRule.onNodeWithTag(CreateJobPropertyTag).performClick()
        composeTestRule.onNodeWithTag(CreateJobPropertySheetTag).assertIsDisplayed()
        // The row states the Property and the address it is, so two Properties of one customer are told
        // apart by where they are.
        composeTestRule
            .onNodeWithText("987 Cedar Lane, Montreal, QC H3A 2T6")
            .assertIsDisplayed()
        composeTestRule.onNodeWithTag(createJobPropertyOptionTag("p1")).performClick()

        assertEquals("p1", selectedProperty)
    }

    @Test
    fun explainsThatAPropertyIsRequiredWhenTheCustomerHasNone() {
        render(
            state = CreateJobUiState(
                selectedCustomerId = "c1",
                selectedCustomerName = "Martha Reynolds",
                properties = emptyList(),
            ),
        )

        composeTestRule.onNodeWithTag(CreateJobNoPropertiesTag).assertIsDisplayed()
        composeTestRule
            .onNodeWithText(string(R.string.job_create_no_properties))
            .assertIsDisplayed()
    }

    @Test
    fun reportsWhatIsMissingAfterASubmitAttempt() {
        var submits = 0
        render(
            state = CreateJobUiState(selectedCustomerId = "c1", submitAttempted = true),
            onSubmit = { submits += 1 },
        )

        composeTestRule.onNodeWithTag(CreateJobMessageTag).assertIsDisplayed()
        composeTestRule
            .onNodeWithText(string(R.string.job_create_form_incomplete))
            .assertIsDisplayed()
        composeTestRule.onNodeWithTag(CreateJobSubmitTag).performClick()

        assertEquals(1, submits)
    }

    @Test
    fun reportsTheApiFailureWithoutClearingTheForm() {
        render(
            state = CreateJobUiState(
                selectedCustomerId = "c1",
                selectedCustomerName = "Martha Reynolds",
                selectedPropertyId = "p1",
                title = "Furnace repair",
                failureReason = JobCreateFailure.PROPERTY_UNAVAILABLE,
            ),
        )

        composeTestRule.onNodeWithTag(CreateJobMessageTag).assertIsDisplayed()
        composeTestRule
            .onNodeWithText(string(R.string.job_create_error_property_unavailable))
            .assertIsDisplayed()
        // The entered title is still drawn: a refusal does not discard the form (`BR-042`).
        composeTestRule.onNodeWithText("Furnace repair").assertIsDisplayed()
    }

    @Test
    fun disablesTheActionWhileTheJobIsBeingCreated() {
        render(
            state = CreateJobUiState(
                selectedCustomerId = "c1",
                selectedPropertyId = "p1",
                title = "Furnace repair",
                isSubmitting = true,
            ),
        )

        composeTestRule.onNodeWithTag(CreateJobSubmitTag).assertIsNotEnabled()
    }

    @Test
    fun opensTheCreatedJobOnceTheBackendAcceptedIt() {
        var created: String? = null
        render(
            state = CreateJobUiState(
                selectedCustomerId = "c1",
                selectedPropertyId = "p1",
                title = "Furnace repair",
                createdJobId = "job-9",
            ),
            onCreated = { created = it },
        )

        composeTestRule.waitForIdle()

        assertEquals("job-9", created)
    }

    @Test
    fun cancelsOutOfTheForm() {
        var cancels = 0
        render(onCancel = { cancels += 1 })

        composeTestRule.onNodeWithTag(CreateJobCancelTag).performClick()

        assertEquals(1, cancels)
    }

    private fun render(
        state: CreateJobUiState = CreateJobUiState(),
        onSelectCustomer: (CustomerListItem) -> Unit = {},
        onSelectProperty: (String) -> Unit = {},
        onSubmit: () -> Unit = {},
        onCancel: () -> Unit = {},
        onCreated: (String) -> Unit = {},
    ) {
        composeTestRule.setContent {
            ServoraTheme {
                CreateJobScreen(
                    state = state,
                    onCustomerQueryChange = {},
                    onCustomerPickerOpen = {},
                    onSelectCustomer = onSelectCustomer,
                    onRetryCustomers = {},
                    onSelectProperty = onSelectProperty,
                    onRetryProperties = {},
                    onTitleChange = {},
                    onDescriptionChange = {},
                    onSubmit = onSubmit,
                    onCancel = onCancel,
                    onCreated = onCreated,
                    modifier = Modifier,
                )
            }
        }
    }

    private fun customer(id: String, name: String) = CustomerListItem(
        id = id,
        displayName = name,
        isCompany = true,
        email = "dispatch@example.com",
        phone = "+15145550142",
        status = CustomerStatus.ACTIVE,
        createdAt = "2026-01-01T10:00:00Z",
        propertyCount = 1,
        jobCount = 0,
    )

    private fun property(id: String, name: String?) = CustomerProperty(
        id = id,
        name = name,
        addressLine1 = "987 Cedar Lane",
        addressLine2 = null,
        city = "Montreal",
        province = "QC",
        postalCode = "H3A 2T6",
        country = "Canada",
        jobCount = 0,
        lastServiceAt = null,
    )

    private fun string(resId: Int): String =
        ApplicationProvider.getApplicationContext<Context>().getString(resId)
}
