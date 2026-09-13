package com.servora.android.ui.navigation

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.servora.android.R
import com.servora.android.data.customers.CreatePropertyRequest
import com.servora.android.data.customers.CustomerDetailResult
import com.servora.android.data.customers.CustomersFailureReason
import com.servora.android.data.customers.CustomersRepository
import com.servora.android.data.customers.CustomersResult
import com.servora.android.data.customers.PropertyCreateResult
import com.servora.android.data.customers.PropertyDeleteResult
import com.servora.android.data.customers.PropertyLifecycleRequest
import com.servora.android.data.customers.PropertyRepository
import com.servora.android.data.customers.PropertyResult
import com.servora.android.data.customers.UpdatePropertyRequest
import com.servora.android.domain.model.Customer
import com.servora.android.domain.model.CustomerDetail
import com.servora.android.domain.model.CustomerFilters
import com.servora.android.domain.model.CustomerJob
import com.servora.android.domain.model.CustomerJobAddress
import com.servora.android.domain.model.CustomerJobTechnician
import com.servora.android.domain.model.CustomerProperty
import com.servora.android.domain.model.CustomerStatus
import com.servora.android.domain.model.CustomerType
import com.servora.android.domain.model.JobStatus
import com.servora.android.ui.components.ServoraTopBarBackTag
import com.servora.android.ui.components.ServoraTopBarSubtitleTag
import com.servora.android.ui.components.ServoraTopBarTitleTag
import com.servora.android.ui.customers.AddPropertyTag
import com.servora.android.ui.customers.AddPropertyViewModel
import com.servora.android.ui.customers.CustomerDetailAddPropertyTag
import com.servora.android.ui.customers.CustomerDetailAllJobsTag
import com.servora.android.ui.customers.CustomerDetailContentTag
import com.servora.android.ui.customers.CustomerDetailCreateJobTag
import com.servora.android.ui.customers.CustomerDetailPropertiesTag
import com.servora.android.ui.customers.CustomerDetailSeeAllJobsTag
import com.servora.android.ui.customers.CustomerPermissionsUiState
import com.servora.android.ui.customers.CustomersViewModel
import com.servora.android.ui.customers.EditCustomerActionTag
import com.servora.android.ui.customers.EditPropertyViewModel
import com.servora.android.ui.customers.PropertyDetailViewModel
import com.servora.android.ui.customers.ServoraHomeScreen
import com.servora.android.ui.customers.customerDetailJobTag
import com.servora.android.ui.customers.customerRowTag
import com.servora.android.ui.theme.ServoraTheme
import java.time.Clock
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The signed-in back stack (`docs/decisions/010-android-navigation.md`).
 *
 * These tests drive the real shell, so they verify the wiring the requirement is about: a customer
 * opened from the list is *pushed* on top of the list, and both the header arrow and the system Back
 * button pop exactly one screen.
 */
@RunWith(AndroidJUnit4::class)
class ServoraHomeNavigationTest {

    @get:Rule val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var viewModel: CustomersViewModel

    @Test
    fun openingACustomerPushesTheViewCustomerScreen() {
        render()

        openTheCustomer()

        composeTestRule.onNodeWithText(string(R.string.customers_view_title)).assertIsDisplayed()
    }

    @Test
    fun aRootDestinationShowsItsTitleWithoutABackControl() {
        render()

        // The Customers tab is the root the client starts on, so the top bar names it and offers
        // nothing to go back to.
        composeTestRule
            .onNodeWithTag(ServoraTopBarTitleTag)
            .assertTextEquals(string(R.string.nav_customers))
        composeTestRule.onNodeWithTag(ServoraTopBarBackTag).assertDoesNotExist()
    }

    @Test
    fun theViewCustomerScreenShowsItsOwnTitleWithABackControl() {
        render()

        openTheCustomer()

        composeTestRule
            .onNodeWithTag(ServoraTopBarTitleTag)
            .assertTextEquals(string(R.string.customers_view_title))
        composeTestRule.onNodeWithTag(ServoraTopBarBackTag).assertIsDisplayed()
    }

    @Test
    fun theEditActionIsShownWithTheCustomerUpdatePermission() {
        render(canEditCustomer = true)

        openTheCustomer()

        composeTestRule.onNodeWithTag(EditCustomerActionTag).assertIsDisplayed()
    }

    @Test
    fun theEditActionIsHiddenWithoutTheCustomerUpdatePermission() {
        render(canEditCustomer = false)

        openTheCustomer()

        composeTestRule.onNodeWithTag(EditCustomerActionTag).assertDoesNotExist()
    }

    @Test
    fun theHeaderArrowReturnsToTheCustomersList() {
        render()
        openTheCustomer()

        composeTestRule.onNodeWithTag(ServoraTopBarBackTag).performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(customerRowTag(CUSTOMER_ID)).assertIsDisplayed()
    }

    @Test
    fun theSystemBackButtonReturnsToTheCustomersList() {
        render()
        openTheCustomer()

        pressSystemBack()

        composeTestRule.onNodeWithTag(customerRowTag(CUSTOMER_ID)).assertIsDisplayed()
    }

    @Test
    fun editingPushesTheEditScreenAndBackReturnsToTheViewCustomerScreen() {
        render()
        openTheCustomer()

        composeTestRule.onNodeWithTag(EditCustomerActionTag).performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText(string(R.string.customers_save)).assertIsDisplayed()

        pressSystemBack()

        composeTestRule.onNodeWithText(string(R.string.customers_view_title)).assertIsDisplayed()
    }

    @Test
    fun seeAllPushesTheJobHistoryOnTopOfTheDetail() {
        render(jobCount = 4)
        openTheCustomer()

        composeTestRule
            .onNodeWithTag(CustomerDetailContentTag)
            .performScrollToNode(hasTestTag(CustomerDetailSeeAllJobsTag))
        composeTestRule.onNodeWithTag(CustomerDetailSeeAllJobsTag).performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(CustomerDetailAllJobsTag).assertIsDisplayed()
        composeTestRule.onNodeWithTag(customerDetailJobTag("job-4")).assertIsDisplayed()

        pressSystemBack()

        composeTestRule.onNodeWithText(string(R.string.customers_view_title)).assertIsDisplayed()
    }

    @Test
    fun reopeningACustomerDoesNotStackADuplicateDestination() {
        render()
        openTheCustomer()
        composeTestRule.onNodeWithTag(ServoraTopBarBackTag).performClick()
        composeTestRule.waitForIdle()

        openTheCustomer()

        // A second destination for the same customer would match several nodes and fail these
        // assertions.
        composeTestRule.onNodeWithText(string(R.string.customers_view_title)).assertIsDisplayed()
        composeTestRule.onNodeWithTag(ServoraTopBarBackTag).assertIsDisplayed()
    }

    @Test
    fun theSystemBackButtonLeavesTheAppFromARootDestination() {
        render()

        // Dispatched directly rather than through `pressSystemBack`: the Activity is finishing, so
        // there is nothing left to wait for idle on.
        composeTestRule.runOnUiThread {
            composeTestRule.activity.onBackPressedDispatcher.onBackPressed()
        }

        assertTrue(
            "Back from a root destination should leave the app",
            composeTestRule.activity.isFinishing,
        )
    }

    @Test
    fun addPropertyOpensItsOwnScreenWithTheCustomerAsContext() {
        render()
        openTheCustomer()
        openAddProperty()

        composeTestRule.onNodeWithTag(AddPropertyTag).assertIsDisplayed()
        composeTestRule
            .onNodeWithTag(ServoraTopBarTitleTag)
            .assertTextEquals(string(R.string.property_add_title))
        // The context line names the customer the Property is being added to.
        composeTestRule
            .onNodeWithTag(ServoraTopBarSubtitleTag)
            .assertTextEquals("ABC Property Management")
        // A create form must not carry an unrelated primary action floating over it.
        composeTestRule.onNodeWithTag(CustomerDetailCreateJobTag).assertDoesNotExist()
    }

    @Test
    fun bothBackControlsLeaveAddPropertyForTheCustomer() {
        render()
        openTheCustomer()
        openAddProperty()

        composeTestRule.onNodeWithTag(ServoraTopBarBackTag).performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText(string(R.string.customers_view_title)).assertIsDisplayed()

        openAddProperty()
        pressSystemBack()

        composeTestRule.onNodeWithText(string(R.string.customers_view_title)).assertIsDisplayed()
    }

    private fun render(
        jobCount: Int = 1,
        canEditCustomer: Boolean = true,
        canViewProperties: Boolean = true,
        canCreateProperty: Boolean = true,
    ) {
        val repository = FakeCustomersRepository(jobCount = jobCount)
        viewModel = CustomersViewModel(repository)
        val addPropertyViewModel = AddPropertyViewModel(repository)
        // The Property lifecycle destinations are wired by the shell too; the fake answers "not
        // found", because these tests cover navigation rather than Property behaviour.
        val propertyRepository = FakePropertyRepository()
        val propertyDetailViewModel =
            PropertyDetailViewModel(propertyRepository, Clock.systemUTC())
        val editPropertyViewModel = EditPropertyViewModel(propertyRepository)
        composeTestRule.setContent {
            ServoraTheme {
                ServoraHomeScreen(
                    permissions =
                        CustomerPermissionsUiState(
                            canOpenCustomers = true,
                            canCreateCustomer = true,
                            canEditCustomer = canEditCustomer,
                            canArchiveCustomer = true,
                            canViewProperties = canViewProperties,
                            canCreateProperty = canCreateProperty,
                        ),
                    customersViewModel = viewModel,
                    addPropertyViewModel = addPropertyViewModel,
                    propertyDetailViewModel = propertyDetailViewModel,
                    editPropertyViewModel = editPropertyViewModel,
                    onSignOut = {},
                )
            }
        }
        composeTestRule.waitUntil(timeoutMillis = WAIT_TIMEOUT) {
            viewModel.uiState.value.customers.isNotEmpty()
        }
    }

    @Test
    fun addPropertyIsHiddenWithoutThePropertyCreatePermission() {
        render(canCreateProperty = false)

        openTheCustomer()

        // The Properties section is still readable, but the create affordance belongs to
        // `properties.create` (`BR-085`), not to the customer permission.
        composeTestRule.onNodeWithTag(CustomerDetailAddPropertyTag).assertDoesNotExist()
    }

    @Test
    fun thePropertiesSectionIsAbsentWithoutThePropertyViewPermission() {
        render(canViewProperties = false)

        openTheCustomer()

        composeTestRule.onNodeWithTag(CustomerDetailPropertiesTag).assertDoesNotExist()
        composeTestRule.onNodeWithTag(CustomerDetailAddPropertyTag).assertDoesNotExist()
    }

    private fun openTheCustomer() {
        composeTestRule.onNodeWithTag(customerRowTag(CUSTOMER_ID)).performClick()
        composeTestRule.waitUntil(timeoutMillis = WAIT_TIMEOUT) {
            viewModel.uiState.value.customerDetail?.detail != null
        }
        composeTestRule.waitForIdle()
    }

    private fun openAddProperty() {
        composeTestRule
            .onNodeWithTag(CustomerDetailContentTag)
            .performScrollToNode(hasTestTag(CustomerDetailAddPropertyTag))
        composeTestRule.onNodeWithTag(CustomerDetailAddPropertyTag).performClick()
        composeTestRule.waitForIdle()
    }

    private fun pressSystemBack() {
        composeTestRule.runOnUiThread {
            composeTestRule.activity.onBackPressedDispatcher.onBackPressed()
        }
        composeTestRule.waitForIdle()
    }

    private fun string(resId: Int): String =
        ApplicationProvider.getApplicationContext<Context>().getString(resId)

    private class FakeCustomersRepository(
        private val jobCount: Int = 1,
    ) : CustomersRepository {

        private val jobs = (1..jobCount).map { index -> job(index) }

        override suspend fun listCustomers(filters: CustomerFilters): CustomersResult =
            CustomersResult.Success(listOf(customer()))

        override suspend fun loadCustomerDetail(customerId: String): CustomerDetailResult =
            if (customerId == CUSTOMER_ID) {
                CustomerDetailResult.Success(detail(jobs))
            } else {
                CustomerDetailResult.Failure(CustomersFailureReason.UNEXPECTED)
            }

        override suspend fun createProperty(
            customerId: String,
            request: CreatePropertyRequest,
        ): PropertyCreateResult = PropertyCreateResult.Success(property())

        private fun detail(jobs: List<CustomerJob>) = CustomerDetail(
            customer = customer(),
            contacts = emptyList(),
            properties = listOf(property()),
            jobs = jobs,
        )
    }

    private companion object {
        const val CUSTOMER_ID = "customer-1"
        const val WAIT_TIMEOUT = 5_000L
    }
}

private fun customer() = Customer(
    id = "customer-1",
    organizationId = "org-1",
    type = CustomerType.COMPANY,
    displayName = "ABC Property Management",
    email = "john@example.com",
    phone = "555-123-4567",
    billingEmail = null,
    billingPhone = null,
    notes = null,
    status = CustomerStatus.ACTIVE,
    createdAt = "2025-01-12T10:30:00Z",
    updatedAt = "2025-01-12T10:30:00Z",
    propertyCount = 1,
    jobCount = 1,
)

private fun property() = CustomerProperty(
    id = "property-1",
    name = "Cedar Lane Building",
    addressLine1 = "987 Cedar Lane",
    addressLine2 = null,
    city = "Montreal",
    province = "QC",
    postalCode = "H3A 2T6",
    country = "Canada",
    jobCount = 4,
    lastServiceAt = "2026-08-28T13:00:00Z",
)

private fun job(index: Int) = CustomerJob(
    id = "job-$index",
    jobNumber = index,
    title = "HVAC Maintenance",
    status = JobStatus.COMPLETED,
    address = CustomerJobAddress(
        propertyName = "Cedar Lane Building",
        addressLine1 = "987 Cedar Lane",
        addressLine2 = null,
        city = "Montreal",
        province = "QC",
        postalCode = "H3A 2T6",
        country = "Canada",
    ),
    scheduledStart = "2026-09-08T13:00:00Z",
    technicians = listOf(CustomerJobTechnician("lead-1", "Mike Lead", "LEAD")),
)

/**
 * A [PropertyRepository] that answers "not found".
 *
 * The navigation tests exercise the signed-in back stack, not Property behaviour, so the Property
 * destinations are wired to a repository that reports nothing rather than to scripted Property data.
 */
private class FakePropertyRepository : PropertyRepository {
    override suspend fun loadProperty(
        customerId: String,
        propertyId: String,
    ): PropertyResult = PropertyResult.Failure(CustomersFailureReason.NOT_FOUND)

    override suspend fun updateProperty(
        customerId: String,
        propertyId: String,
        request: UpdatePropertyRequest,
    ): PropertyResult = PropertyResult.Failure(CustomersFailureReason.NOT_FOUND)

    override suspend fun archiveProperty(
        customerId: String,
        propertyId: String,
        request: PropertyLifecycleRequest,
    ): PropertyResult = PropertyResult.Failure(CustomersFailureReason.NOT_FOUND)

    override suspend fun restoreProperty(
        customerId: String,
        propertyId: String,
        request: PropertyLifecycleRequest,
    ): PropertyResult = PropertyResult.Failure(CustomersFailureReason.NOT_FOUND)

    override suspend fun deleteProperty(
        customerId: String,
        propertyId: String,
    ): PropertyDeleteResult =
        PropertyDeleteResult.Failure(CustomersFailureReason.NOT_FOUND)
}

