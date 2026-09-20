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
import com.servora.android.data.customers.ContactCreateResult
import com.servora.android.data.customers.ContactRemoveResult
import com.servora.android.data.customers.ContactUpdateResult
import com.servora.android.data.customers.CreateCustomerContactRequest
import com.servora.android.data.customers.CreateCustomerRequest
import com.servora.android.data.customers.CreatePropertyRequest
import com.servora.android.data.customers.CustomerCreateResult
import com.servora.android.data.customers.CustomerDetailResult
import com.servora.android.data.customers.CustomersFailureReason
import com.servora.android.data.customers.CustomersRepository
import com.servora.android.data.customers.CustomersResult
import com.servora.android.data.customers.CustomerUpdateResult
import com.servora.android.data.customers.PropertyCreateResult
import com.servora.android.data.customers.PropertyDeleteResult
import com.servora.android.data.customers.PropertyLifecycleRequest
import com.servora.android.data.customers.PropertyRepository
import com.servora.android.data.customers.PropertyResult
import com.servora.android.data.customers.QueuedPropertyOperation
import com.servora.android.data.customers.RemoveCustomerContactRequest
import com.servora.android.data.customers.UpdateCustomerContactRequest
import com.servora.android.data.customers.UpdateCustomerRequest
import com.servora.android.data.customers.UpdatePropertyRequest
import com.servora.android.data.home.ManagerHomeRepository
import com.servora.android.data.home.ManagerHomeResult
import com.servora.android.data.home.TechnicianHomeRepository
import com.servora.android.data.home.TechnicianHomeResult
import com.servora.android.data.schedule.ScheduleRepository
import com.servora.android.data.schedule.ScheduleResult
import com.servora.android.data.schedule.VisitRequestReviewResult
import com.servora.android.data.schedule.VisitRequestsRepository
import com.servora.android.data.schedule.VisitRequestsResult
import com.servora.android.domain.model.TechnicianHome
import com.servora.android.domain.model.TechnicianHomeVisit
import com.servora.android.domain.model.VisitStatus
import com.servora.android.data.jobs.AssignableTechniciansResult
import com.servora.android.data.jobs.CreateJobRequest
import com.servora.android.data.jobs.JobActionResult
import com.servora.android.data.jobs.JobActivityResult
import com.servora.android.data.jobs.JobCreateFailure
import com.servora.android.data.jobs.JobCreateResult
import com.servora.android.data.jobs.JobDetailsRepository
import com.servora.android.data.jobs.JobDetailsResult
import com.servora.android.data.jobs.ActivityWriteResult
import java.time.Instant
import com.servora.android.domain.model.Customer
import com.servora.android.domain.model.CustomerCompany
import com.servora.android.domain.model.CustomerContact
import com.servora.android.domain.model.CustomerDetail
import com.servora.android.domain.model.CustomerFilters
import com.servora.android.domain.model.CustomerJob
import com.servora.android.domain.model.CustomerJobAddress
import com.servora.android.domain.model.CustomerJobTechnician
import com.servora.android.domain.model.CustomerProperty
import com.servora.android.domain.model.CustomerStatus
import com.servora.android.domain.model.CustomerType
import com.servora.android.domain.model.FollowUpVisitRequest
import com.servora.android.domain.model.JobStatus
import com.servora.android.domain.model.ManagerHome
import com.servora.android.domain.model.ManagerHomeTodaySummary
import com.servora.android.domain.model.TechnicianAssignment
import com.servora.android.ui.components.ServoraTopBarBackTag
import com.servora.android.ui.components.ServoraTopBarSubtitleTag
import com.servora.android.ui.components.ServoraTopBarTitleTag
import com.servora.android.ui.customers.AddCustomerActionTag
import com.servora.android.ui.customers.AddCustomerCancelTag
import com.servora.android.ui.customers.AddCustomerTag
import com.servora.android.ui.customers.AddCustomerViewModel
import com.servora.android.ui.customers.AddPropertyTag
import com.servora.android.ui.customers.AddPropertyViewModel
import com.servora.android.ui.customers.AddContactTag
import com.servora.android.ui.customers.AddContactViewModel
import com.servora.android.ui.customers.CustomerDetailAddContactTag
import com.servora.android.ui.customers.CustomerDetailAddPropertyTag
import com.servora.android.ui.customers.CustomerDetailAllJobsTag
import com.servora.android.ui.customers.CustomerDetailContentTag
import com.servora.android.ui.customers.CustomerDetailCreateJobTag
import com.servora.android.ui.customers.CustomerDetailContactsTag
import com.servora.android.ui.customers.CustomerDetailPropertiesTag
import com.servora.android.ui.customers.CustomerDetailSeeAllJobsTag
import com.servora.android.ui.customers.CustomerPermissionsUiState
import com.servora.android.ui.jobs.CreateJobCustomerValueTag
import com.servora.android.ui.jobs.CreateJobTag
import com.servora.android.ui.jobs.CreateJobViewModel
import com.servora.android.ui.customers.CustomersNavTag
import com.servora.android.ui.customers.CustomersViewModel
import com.servora.android.ui.customers.EditContactViewModel
import com.servora.android.ui.customers.EditCustomerActionTag
import com.servora.android.ui.customers.EditCustomerViewModel
import com.servora.android.ui.customers.EditPropertyViewModel
import com.servora.android.ui.customers.PropertyDetailViewModel
import com.servora.android.ui.customers.RemoveContactViewModel
import com.servora.android.ui.customers.ServoraHomeScreen
import com.servora.android.ui.customers.customerDetailContactEditTag
import com.servora.android.ui.customers.customerDetailContactTag
import com.servora.android.ui.customers.customerDetailJobTag
import com.servora.android.ui.customers.customerRowTag
import com.servora.android.ui.home.ManagerHomeViewModel
import com.servora.android.ui.home.TechnicianHomeContentTag
import com.servora.android.ui.home.TechnicianHomeNextVisitTag
import com.servora.android.ui.home.TechnicianHomeViewModel
import com.servora.android.data.jobs.JobPhotoImages
import com.servora.android.data.jobs.QueuedVisitFieldAction
import com.servora.android.data.jobs.QueuedVisitNote
import com.servora.android.data.jobs.VisitNote
import com.servora.android.data.jobs.VisitStatusChange
import com.servora.android.data.jobs.inertJobAudioEvidenceCache
import com.servora.android.data.jobs.inertJobAudioPlayer
import com.servora.android.data.jobs.inertJobAudioSession
import com.servora.android.data.jobs.inertJobPhotoExporter
import com.servora.android.data.jobs.inertJobPhotoPickedItems
import com.servora.android.data.jobs.inertJobPhotoSession
import com.servora.android.ui.jobs.JobDetailsViewModel
import com.servora.android.ui.schedule.ScheduleViewModel
import com.servora.android.ui.schedule.TechnicianScheduleFailureTag
import com.servora.android.ui.schedule.TechnicianScheduleViewModel
import com.servora.android.ui.theme.ServoraTheme
import java.time.Clock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
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
    fun newCustomerOpensItsOwnScreenFromTheList() {
        render()

        composeTestRule.onNodeWithTag(AddCustomerActionTag).performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(AddCustomerTag).assertIsDisplayed()
        composeTestRule
            .onNodeWithTag(ServoraTopBarTitleTag)
            .assertTextEquals(string(R.string.customers_create_title))
        // A pushed screen offers a way back to what it was opened from.
        composeTestRule.onNodeWithTag(ServoraTopBarBackTag).assertIsDisplayed()
    }

    @Test
    fun cancellingTheNewCustomerFormReturnsToTheList() {
        render()
        composeTestRule.onNodeWithTag(AddCustomerActionTag).performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(AddCustomerCancelTag).performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(customerRowTag(CUSTOMER_ID)).assertIsDisplayed()
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
    fun createJobOpensTheFormWithTheCustomerAsContext() {
        render()
        openTheCustomer()

        composeTestRule.onNodeWithTag(CustomerDetailCreateJobTag).performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(CreateJobTag).assertIsDisplayed()
        composeTestRule
            .onNodeWithTag(ServoraTopBarTitleTag)
            .assertTextEquals(string(R.string.job_create_title))
        // The customer the form was opened for is shown as fixed context rather than asked for again,
        // and no Create Job affordance floats over the form (`BR-012`).
        composeTestRule
            .onNodeWithTag(CreateJobCustomerValueTag)
            .assertTextEquals("ABC Property Management")
        composeTestRule.onNodeWithTag(CustomerDetailCreateJobTag).assertDoesNotExist()
    }

    @Test
    fun createJobIsHiddenWithoutTheCreateCapability() {
        render(canCreateJob = false)
        openTheCustomer()

        // Creating a Job is its own capability (`BR-008`, `BR-094`), so a session that does not hold it
        // is offered no affordance the API would refuse (`BR-007`, `BR-011`).
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

    @Test
    fun aFieldSessionLandsOnItsOwnDay() {
        render(canOpenCustomers = false, canViewAssignedWork = true)

        // A session holding only the field capability opens its own day rather than the
        // operation’s, and the office destinations are not offered to it (`BR-009`, `BR-010`,
        // `ADR-019` D6).
        composeTestRule.onNodeWithTag(TechnicianHomeContentTag).assertIsDisplayed()
        composeTestRule.onNodeWithTag(TechnicianHomeNextVisitTag).assertIsDisplayed()
        composeTestRule.onNodeWithTag(CustomersNavTag).assertDoesNotExist()
    }

    @Test
    fun aFieldSessionReachesItsOwnSchedule() {
        render(canOpenCustomers = false, canViewAssignedWork = true)

        // The field capability reads the caller's own schedule, so the destination is offered to a
        // session that holds it and it names itself "My Schedule" rather than the office's
        // (`BR-009`, `BR-010`, `BR-012`).
        composeTestRule.onNodeWithText(string(R.string.nav_schedule)).performClick()
        composeTestRule.onNodeWithText(string(R.string.nav_my_schedule)).assertIsDisplayed()
        // The fake answers that the read failed, so the technician schedule's own failure state is
        // what the destination shows rather than another screen's (`BR-042`).
        composeTestRule.onNodeWithTag(TechnicianScheduleFailureTag).assertIsDisplayed()
    }

    private fun render(
        jobCount: Int = 1,
        contacts: List<CustomerContact> = emptyList(),
        canEditCustomer: Boolean = true,
        canViewProperties: Boolean = true,
        canCreateProperty: Boolean = true,
        canCreateJob: Boolean = true,
        canCreateContact: Boolean = true,
        canEditContact: Boolean = true,
        canRemoveContact: Boolean = true,
        canOpenCustomers: Boolean = true,
        canViewAssignedWork: Boolean = false,
    ) {
        val repository = FakeCustomersRepository(jobCount = jobCount, contacts = contacts)
        viewModel = CustomersViewModel(repository)
        val addCustomerViewModel = AddCustomerViewModel(repository)
        val editCustomerViewModel = EditCustomerViewModel(repository)
        val addPropertyViewModel = AddPropertyViewModel(repository)
        // The contact destinations are wired by the shell too: the form writes through the same
        // repository, and the removal is scoped to the customer detail destination (`BR-095`).
        val addContactViewModel = AddContactViewModel(repository)
        val editContactViewModel = EditContactViewModel(repository)
        val removeContactViewModel = RemoveContactViewModel(repository)
        // The Property lifecycle destinations are wired by the shell too; the fake answers "not
        // found", because these tests cover navigation rather than Property behaviour.
        val propertyRepository = FakePropertyRepository()
        val propertyDetailViewModel =
            PropertyDetailViewModel(propertyRepository, Clock.systemUTC())
        val editPropertyViewModel = EditPropertyViewModel(propertyRepository)
        // The manager home is wired by the shell too. These tests cover navigation, so it answers an
        // empty day rather than a scripted one.
        val managerHomeViewModel = ManagerHomeViewModel(FakeManagerHomeRepository())
        // The technician home is wired by the shell too; the fake answers the caller's own day,
        // because a session holding only the field capability lands on it (`ADR-019` D6).
        val technicianHomeViewModel =
            TechnicianHomeViewModel(FakeTechnicianHomeRepository())
        // The schedule is wired by the shell too. These tests cover navigation and never open it,
        // so the fake reports that the read failed rather than answering a day.
        val scheduleViewModel = ScheduleViewModel(FakeScheduleRepository(), FakeVisitRequestsRepository())
        // The Job Details destination is wired by the shell too; the fake answers "not found",
        // because these tests cover navigation rather than Job behaviour.
        val jobDetailsViewModel = JobDetailsViewModel(
            repository = FakeJobDetailsRepository(),
            photos = inertJobPhotoSession(),
            audio = inertJobAudioSession(),
            jobPhotoImages = JobPhotoImages.None,
            pickedItems = inertJobPhotoPickedItems(),
            exporter = inertJobPhotoExporter(),
            // Playback needs a device, so the navigation tests get a player that plays nothing and a
            // cache that holds no bytes (`ADR-018` A9, `qa.md` §6.2).
            audioEvidence = inertJobAudioEvidenceCache(),
            audioPlayer = inertJobAudioPlayer(),
            clock = Clock.systemUTC(),
        )
        // The Create Job destination is wired by the shell too. Its own tests cover the form; here it is
        // only opened, so the fake answers that a Job cannot be created.
        val createJobViewModel =
            CreateJobViewModel(repository, FakeJobDetailsRepository())
        // Hoisted out of the composable like the other destinations: a ViewModel is constructed by its
        // owner, not by a composable's body.
        val technicianScheduleViewModel =
            TechnicianScheduleViewModel(FakeScheduleRepository())
        composeTestRule.setContent {
            ServoraTheme {
                ServoraHomeScreen(
                    permissions =
                        CustomerPermissionsUiState(
                            canOpenCustomers = canOpenCustomers,
                            canCreateCustomer = canOpenCustomers,
                            canEditCustomer = canEditCustomer,
                            canArchiveCustomer = canOpenCustomers,
                            canViewProperties = canViewProperties,
                            canCreateProperty = canCreateProperty,
                            canCreateJob = canCreateJob,
                            canCreateContact = canCreateContact,
                            canEditContact = canEditContact,
                            canRemoveContact = canRemoveContact,
                            canViewAssignedWork = canViewAssignedWork,
                        ),
                    customersViewModel = viewModel,
                    addCustomerViewModel = addCustomerViewModel,
                    editCustomerViewModel = editCustomerViewModel,
                    addPropertyViewModel = addPropertyViewModel,
                    propertyDetailViewModel = propertyDetailViewModel,
                    editPropertyViewModel = editPropertyViewModel,
                    addContactViewModel = addContactViewModel,
                    editContactViewModel = editContactViewModel,
                    removeContactViewModel = removeContactViewModel,
                    managerHomeViewModel = managerHomeViewModel,
                    technicianHomeViewModel = technicianHomeViewModel,
                    jobDetailsViewModel = jobDetailsViewModel,
                    createJobViewModel = createJobViewModel,
                    scheduleViewModel = scheduleViewModel,
                    technicianScheduleViewModel = technicianScheduleViewModel,
                    onSignOut = {},
                )
            }
        }
        composeTestRule.waitUntil(timeoutMillis = WAIT_TIMEOUT) {
            // Which home a session lands on is decided by its capabilities (`BR-011`).
            if (canOpenCustomers) {
                viewModel.uiState.value.customers.isNotEmpty()
            } else {
                technicianHomeViewModel.uiState.value.hasContent
            }
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

    @Test
    fun theAddContactDestinationOpensFromTheCustomer() {
        render()

        openTheCustomer()
        openAddContact()

        // The destination is the contact form, headed by its own title, over the customer it belongs
        // to (`BR-095`, `docs/decisions/011-android-contextual-top-bar.md`).
        composeTestRule.onNodeWithTag(AddContactTag).assertIsDisplayed()
        composeTestRule
            .onNodeWithTag(ServoraTopBarTitleTag)
            .assertTextEquals(string(R.string.contact_add_title))
        composeTestRule
            .onNodeWithTag(ServoraTopBarSubtitleTag)
            .assertTextEquals("ABC Property Management")
    }

    @Test
    fun theEditContactDestinationOpensFromAContactRow() {
        render(contacts = listOf(contact()))

        openTheCustomer()
        openEditContact()

        composeTestRule.onNodeWithTag(AddContactTag).assertIsDisplayed()
        composeTestRule
            .onNodeWithTag(ServoraTopBarTitleTag)
            .assertTextEquals(string(R.string.contact_edit_title))
    }

    @Test
    fun theAddContactActionIsHiddenWithoutTheContactCreatePermission() {
        render(canCreateContact = false)

        openTheCustomer()

        // Reading a customer's contacts adds no capability (`ADR-022` D4), so the card is still
        // drawn; adding one is its own capability and is not offered without it (`BR-007`, `BR-011`).
        composeTestRule
            .onNodeWithTag(CustomerDetailContentTag)
            .performScrollToNode(hasTestTag(CustomerDetailContactsTag))
        composeTestRule.onNodeWithTag(CustomerDetailAddContactTag).assertDoesNotExist()
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

    /** Opens the customer's contacts card and presses its Add action. */
    private fun openAddContact() {
        composeTestRule
            .onNodeWithTag(CustomerDetailContentTag)
            .performScrollToNode(hasTestTag(CustomerDetailAddContactTag))
        composeTestRule.onNodeWithTag(CustomerDetailAddContactTag).performClick()
        composeTestRule.waitForIdle()
    }

    /** Opens the contact row's Edit action on the customer detail. */
    private fun openEditContact() {
        composeTestRule
            .onNodeWithTag(CustomerDetailContentTag)
            .performScrollToNode(hasTestTag(customerDetailContactTag(CONTACT_ID)))
        composeTestRule.onNodeWithTag(customerDetailContactEditTag(CONTACT_ID)).performClick()
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
        private val contacts: List<CustomerContact> = emptyList(),
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

        override suspend fun createCustomer(
            request: CreateCustomerRequest,
        ): CustomerCreateResult =
            CustomerCreateResult.Failure(CustomersFailureReason.UNEXPECTED)

        override suspend fun createContact(
            customerId: String,
            request: CreateCustomerContactRequest,
        ): ContactCreateResult = ContactCreateResult.Failure(CustomersFailureReason.UNEXPECTED)

        // The contact edit and removal are not what this fake scripts; they answer the same outcome an
        // unrepresentable reply would.
        override suspend fun updateContact(
            customerId: String,
            contactId: String,
            request: UpdateCustomerContactRequest,
        ): ContactUpdateResult = ContactUpdateResult.Failure(CustomersFailureReason.UNEXPECTED)

        override suspend fun removeContact(
            customerId: String,
            contactId: String,
            request: RemoveCustomerContactRequest,
        ): ContactRemoveResult = ContactRemoveResult.Failure(CustomersFailureReason.UNEXPECTED)

        override suspend fun updateCustomer(
            customerId: String,
            request: UpdateCustomerRequest,
        ): CustomerUpdateResult = CustomerUpdateResult.Success

        private fun detail(jobs: List<CustomerJob>) = CustomerDetail(
            customer = customer(),
            company = CustomerCompany(
                customerId = CUSTOMER_ID,
                legalName = "ABC Property Management Ltd.",
                businessName = null,
                taxNumber = null,
            ),
            contacts = contacts,
            properties = listOf(property()),
            jobs = jobs,
        )
    }

    private companion object {
        const val CUSTOMER_ID = "customer-1"
        const val CONTACT_ID = "contact-1"
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

/**
 * One contact person as the backend reports it (`BR-095`).
 *
 * Its phone and email are its own, which is why they differ from the customer header's own details.
 */
private fun contact() = CustomerContact(
    id = "contact-1",
    customerId = "customer-1",
    firstName = "John",
    lastName = "Smith",
    email = "john.smith@example.com",
    phone = "555-987-6543",
    role = null,
    isPrimary = true,
    isBillingContact = false,
    isJobContact = false,
    version = 1,
    createdAt = "2025-01-12T10:31:00Z",
    updatedAt = "2025-01-12T10:31:00Z",
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

    override suspend fun queuedOperation(propertyId: String): QueuedPropertyOperation? = null

    override val appliedOperations: Flow<Unit> = emptyFlow()
}

/** A [ManagerHomeRepository] that answers an empty day, so the shell always has one to render. */
private class FakeManagerHomeRepository : ManagerHomeRepository {
    override suspend fun loadManagerHome(timeZone: String): ManagerHomeResult =
        ManagerHomeResult.Success(
            ManagerHome(
                displayName = "Sarah Tremblay",
                attention = emptyList(),
                attentionTotal = 0,
                today = ManagerHomeTodaySummary(
                    total = 0,
                    completed = 0,
                    inProgress = 0,
                    upcoming = 0,
                ),
                visits = emptyList(),
            ),
        )
}

/**
 * A [JobDetailsRepository] that answers "not found".
 *
 * The navigation tests exercise the signed-in back stack, not Job Details behaviour, so the Job
 * Details destination is wired to a repository that reports nothing rather than to scripted Job data.
 */
private class FakeJobDetailsRepository : JobDetailsRepository {
    override suspend fun loadJobDetails(jobId: String): JobDetailsResult =
        JobDetailsResult.Failure(CustomersFailureReason.NOT_FOUND)

    // The navigation tests never create a Job: the Create Job destination is opened, and creating one
    // is covered by the form's own tests.
    override suspend fun createJob(request: CreateJobRequest): JobCreateResult =
        JobCreateResult.Failure(JobCreateFailure.UNEXPECTED)

    override suspend fun loadJobActivity(jobId: String): JobActivityResult =
        JobActivityResult.Failure(CustomersFailureReason.NOT_FOUND)

    override suspend fun addVisitNoteRequest(note: VisitNote): ActivityWriteResult = unreachable()

    override suspend fun changeVisitStatus(action: VisitStatusChange): JobActionResult =
        unreachable()

    override suspend fun queuedVisitAction(jobId: String): QueuedVisitFieldAction? = null

    override suspend fun queuedVisitNotes(jobId: String): List<QueuedVisitNote> = emptyList()

    override suspend fun discardQueuedVisitAction(jobId: String, operationId: String): Boolean =
        unreachable()

    override suspend fun discardQueuedVisitNote(jobId: String, operationId: String): Boolean =
        unreachable()

    override val appliedOperations: Flow<Unit> = emptyFlow()

    override suspend fun removeJobPhoto(
        jobId: String,
        photoId: String,
        reason: String,
    ): ActivityWriteResult = unreachable()

    override suspend fun removeJobAudioNote(
        jobId: String,
        audioNoteId: String,
        reason: String,
    ): ActivityWriteResult = unreachable()

    // The navigation tests never reach a Job or Visit action: the destination reports that no Job is
    // readable, so its action row is never drawn.
    override suspend fun changeJobStatus(
        jobId: String,
        status: JobStatus,
        note: String?,
        expectedVersion: Int,
    ): JobActionResult = unreachable()

    override suspend fun rescheduleVisit(
        jobId: String,
        visitId: String,
        scheduledStart: Instant,
        scheduledEnd: Instant,
        reason: String?,
        confirmConflicts: Boolean,
        expectedVersion: Int,
    ): JobActionResult = unreachable()

    override suspend fun assignVisitTechnicians(
        jobId: String,
        visitId: String,
        assignments: List<TechnicianAssignment>,
        confirmConflicts: Boolean,
        expectedVersion: Int,
    ): JobActionResult = unreachable()

    override suspend fun loadAssignableTechnicians(): AssignableTechniciansResult =
        unreachable()

    private fun unreachable(): Nothing =
        throw AssertionError("the navigation tests do not act on a Job")
}

/**
 * A [ScheduleRepository] that reports the read failed.
 *
 * These tests cover navigation and never open the Schedule destination, so nothing here needs a day
 * to render.
 */
private class FakeScheduleRepository : ScheduleRepository {

    override suspend fun loadSchedule(
        localDate: String,
        timeZone: String,
        membershipIds: List<String>,
    ): ScheduleResult = ScheduleResult.Failure(CustomersFailureReason.NETWORK)
}

private class FakeVisitRequestsRepository : VisitRequestsRepository {
    override suspend fun loadRequests(): VisitRequestsResult =
        VisitRequestsResult.Success(emptyList())

    override suspend fun askForClarification(
        request: FollowUpVisitRequest,
    ): VisitRequestReviewResult =
        VisitRequestReviewResult.Success(request)

    override suspend fun reject(request: FollowUpVisitRequest): VisitRequestReviewResult =
        VisitRequestReviewResult.Success(request)
}

/**
 * A [TechnicianHomeRepository] that answers the caller's own day with one Visit to do next.
 *
 * These tests cover navigation, so the day is the smallest one that renders the field home.
 */
private class FakeTechnicianHomeRepository : TechnicianHomeRepository {

    override suspend fun loadTechnicianHome(timeZone: String): TechnicianHomeResult =
        TechnicianHomeResult.Success(
            TechnicianHome(
                displayName = "Mike Johnson",
                nextVisit = TechnicianHomeVisit(
                    visitId = "visit-1",
                    visitStatus = VisitStatus.SCHEDULED,
                    scheduledStart = "2026-09-17T13:00:00.000Z",
                    scheduledEnd = "2026-09-17T14:00:00.000Z",
                    jobId = "job-1",
                    jobNumber = 1042,
                    jobTitle = "Furnace repair",
                    jobStatus = JobStatus.SCHEDULED,
                    customerId = "customer-1",
                    customerName = "ABC Property Management",
                    address = null,
                    technicians = emptyList(),
                    isOverdue = false,
                ),
                visits = emptyList(),
                upcoming = emptyList(),
                upcomingTotal = 0,
                attention = emptyList(),
                attentionTotal = 0,
            ),
        )
}
