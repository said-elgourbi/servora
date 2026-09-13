package com.servora.android.ui.customers

import android.content.Context
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.servora.android.R
import com.servora.android.data.customers.CustomersFailureReason
import com.servora.android.domain.model.Customer
import com.servora.android.domain.model.CustomerContact
import com.servora.android.domain.model.CustomerDetail
import com.servora.android.domain.model.CustomerJob
import com.servora.android.domain.model.CustomerJobAddress
import com.servora.android.domain.model.CustomerJobTechnician
import com.servora.android.domain.model.CustomerProperty
import com.servora.android.domain.model.CustomerStatus
import com.servora.android.domain.model.CustomerType
import com.servora.android.domain.model.JobStatus
import com.servora.android.ui.theme.ServoraTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What the customer detail renders (`BR-081`).
 *
 * The Property and Job values are the backend's projections, so these tests cover formatting and
 * the affordances the screen owns: the links, the Create Job action, the localized "never
 * serviced"/"unassigned" states and the Job history.
 */
@RunWith(AndroidJUnit4::class)
class CustomerDetailScreenTest {

    @get:Rule val composeTestRule = createComposeRule()

    @Test
    fun showsTheCustomerIdentityAndItsContacts() {
        render(state = loaded())

        composeTestRule.onNodeWithText("ABC Property Management").assertIsDisplayed()
        composeTestRule.onNodeWithText(CONTACT_NAME).assertIsDisplayed()
        composeTestRule.onNodeWithText(PHONE).assertIsDisplayed()
        composeTestRule.onNodeWithText(EMAIL).assertIsDisplayed()
        composeTestRule
            .onNodeWithText(string(R.string.customers_status_active), useUnmergedTree = true)
            .assertIsDisplayed()
    }

    @Test
    fun offersCreateJobAsAFloatingAction() {
        render(state = loaded())

        composeTestRule.onNodeWithTag(CustomerDetailCreateJobTag).assertIsDisplayed()
    }

    @Test
    fun offersAddPropertyUnderThePropertiesWhenTheUserMayWrite() {
        var adds = 0
        render(state = loaded(), canAddProperty = true, onAddProperty = { adds += 1 })

        scrollTo(CustomerDetailPropertiesTag)
        composeTestRule.onNodeWithTag(CustomerDetailAddPropertyTag).assertIsDisplayed().performClick()

        assertEquals(1, adds)
    }

    @Test
    fun hidesAddPropertyWithoutTheWritePermission() {
        render(state = loaded(), canAddProperty = false)

        scrollTo(CustomerDetailPropertiesTag)
        composeTestRule.onNodeWithTag(CustomerDetailAddPropertyTag).assertDoesNotExist()
    }

    @Test
    fun omitsThePropertiesSectionWithoutThePropertyViewPermission() {
        // Properties are their own capability set (`BR-085`), so the section is absent rather than
        // shown as empty for a caller who may not read Properties.
        render(state = loaded(), canViewProperties = false)

        composeTestRule.onNodeWithTag(CustomerDetailPropertiesTag).assertDoesNotExist()
        composeTestRule.onNodeWithTag(CustomerDetailAddPropertyTag).assertDoesNotExist()
    }

    @Test
    fun rendersAPropertyWithItsDerivedJobCountAndLastService() {
        render(state = loaded())

        scrollTo(CustomerDetailPropertiesTag)

        composeTestRule
            .onNodeWithTag(customerDetailPropertyTag(PROPERTY_ID), useUnmergedTree = true)
            .assertIsDisplayed()
        composeTestRule
            .onNodeWithText(quantityString(R.plurals.customers_job_count, 4), substring = true)
            .assertIsDisplayed()
        composeTestRule
            .onNodeWithText(
                string(R.string.customers_detail_last_service_format, ""),
                substring = true,
            )
            .assertIsDisplayed()
    }

    @Test
    fun reportsAPropertyThatHasNeverBeenServiced() {
        render(state = loaded(properties = listOf(property(lastServiceAt = null))))

        scrollTo(CustomerDetailPropertiesTag)

        composeTestRule
            .onNodeWithText(string(R.string.customers_detail_never_serviced), substring = true)
            .assertIsDisplayed()
    }

    @Test
    fun rendersAJobWithItsNumberStatusAndTechnicians() {
        render(state = loaded())

        scrollTo(CustomerDetailJobsTag)

        composeTestRule
            .onNodeWithTag(customerDetailJobTag(JOB_ID), useUnmergedTree = true)
            .assertIsDisplayed()
        composeTestRule
            .onNodeWithText(string(R.string.customers_job_number_format, JOB_NUMBER))
            .assertIsDisplayed()
        composeTestRule
            .onNodeWithText("Mike Lead, Sarah Tech", substring = true)
            .assertIsDisplayed()
        composeTestRule
            .onNodeWithText(string(R.string.customers_job_status_completed))
            .assertIsDisplayed()
    }

    @Test
    fun reportsAJobWhoseSelectedVisitHasNoTechnicians() {
        render(state = loaded(jobs = listOf(job(technicians = emptyList()))))

        scrollTo(CustomerDetailJobsTag)

        composeTestRule
            .onNodeWithText(string(R.string.customers_job_unassigned), substring = true)
            .assertIsDisplayed()
    }

    @Test
    fun reportsSeeAllWhenMoreJobsExistThanItPreviews() {
        var seeAll = 0
        val jobs = (1..4).map { index -> job(id = "job-$index", jobNumber = index) }
        render(state = loaded(jobs = jobs), onSeeAllJobs = { seeAll += 1 })

        scrollTo(CustomerDetailSeeAllJobsTag)
        composeTestRule.onNodeWithTag(CustomerDetailSeeAllJobsTag).performClick()

        assertEquals(1, seeAll)
    }

    @Test
    fun keepsTheJobHistoryBehindSeeAllWhenEveryJobAlreadyFits() {
        render(state = loaded())

        scrollTo(CustomerDetailJobsTag)

        composeTestRule.onNodeWithTag(CustomerDetailSeeAllJobsTag).assertDoesNotExist()
    }

    @Test
    fun reportsAFailedReadAndOffersToRetry() {
        var retried = 0
        render(
            state = CustomerDetailUiState(
                customerId = CUSTOMER_ID,
                failureReason = CustomersFailureReason.NETWORK,
            ),
            onRetry = { retried += 1 },
        )

        composeTestRule.onNodeWithText(string(R.string.customers_error_title)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.customers_retry)).performClick()

        assertEquals(1, retried)
    }

    /**
     * Scrolls the detail's list until [tag] is composed, so a section below the fold can be
     * asserted on a phone-sized screen.
     */
    private fun scrollTo(tag: String) {
        composeTestRule
            .onNodeWithTag(CustomerDetailContentTag)
            .performScrollToNode(hasTestTag(tag))
    }

    private fun render(
        state: CustomerDetailUiState,
        canViewProperties: Boolean = true,
        canAddProperty: Boolean = true,
        onAddProperty: () -> Unit = {},
        onRetry: () -> Unit = {},
        onSeeAllJobs: () -> Unit = {},
    ) {
        composeTestRule.setContent {
            ServoraTheme {
                CustomerDetailScreen(
                    state = state,
                    canViewProperties = canViewProperties,
                    canAddProperty = canAddProperty,
                    onAddProperty = onAddProperty,
                    onSeeAllJobs = onSeeAllJobs,
                    onRetry = onRetry,
                    modifier = Modifier,
                )
            }
        }
    }

    private fun loaded(
        properties: List<CustomerProperty> = listOf(property()),
        jobs: List<CustomerJob> = listOf(job()),
    ) = CustomerDetailUiState(
        customerId = CUSTOMER_ID,
        detail = CustomerDetail(
            customer = customer(),
            contacts = listOf(
                CustomerContact(
                    id = "contact-1",
                    customerId = CUSTOMER_ID,
                    firstName = "John",
                    lastName = "Smith",
                    email = EMAIL,
                    phone = PHONE,
                    role = null,
                    isPrimary = true,
                    isBillingContact = false,
                    isJobContact = false,
                    createdAt = "2025-01-12T10:30:00Z",
                    updatedAt = "2025-01-12T10:30:00Z",
                ),
            ),
            properties = properties,
            jobs = jobs,
        ),
    )

    private fun customer() = Customer(
        id = CUSTOMER_ID,
        organizationId = "org-1",
        type = CustomerType.COMPANY,
        displayName = "ABC Property Management",
        email = EMAIL,
        phone = PHONE,
        billingEmail = null,
        billingPhone = null,
        notes = "Badge required after 6 PM.",
        status = CustomerStatus.ACTIVE,
        createdAt = "2025-01-12T10:30:00Z",
        updatedAt = "2025-01-12T10:30:00Z",
        propertyCount = 1,
        jobCount = 1,
    )

    private fun property(lastServiceAt: String? = "2026-08-28T13:00:00Z") = CustomerProperty(
        id = PROPERTY_ID,
        name = "Cedar Lane Building",
        addressLine1 = "987 Cedar Lane",
        addressLine2 = null,
        city = "Montreal",
        province = "QC",
        postalCode = "H3A 2T6",
        country = "Canada",
        jobCount = 4,
        lastServiceAt = lastServiceAt,
    )

    private fun job(
        id: String = JOB_ID,
        jobNumber: Int = JOB_NUMBER,
        technicians: List<CustomerJobTechnician> = listOf(
            CustomerJobTechnician("lead-1", "Mike Lead", "LEAD"),
            CustomerJobTechnician("tech-1", "Sarah Tech", "TECHNICIAN"),
        ),
    ) = CustomerJob(
        id = id,
        jobNumber = jobNumber,
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
        technicians = technicians,
    )

    private fun string(resId: Int): String =
        ApplicationProvider.getApplicationContext<Context>().getString(resId)

    private fun string(resId: Int, formatArg: Any?): String =
        ApplicationProvider.getApplicationContext<Context>().getString(resId, formatArg)

    private fun quantityString(resId: Int, count: Int): String =
        ApplicationProvider.getApplicationContext<Context>()
            .resources
            .getQuantityString(resId, count, count)

    private companion object {
        const val CUSTOMER_ID = "customer-1"
        const val PROPERTY_ID = "property-1"
        const val JOB_ID = "job-1"
        const val JOB_NUMBER = 1042
        const val CONTACT_NAME = "John Smith"
        const val PHONE = "555-123-4567"
        const val EMAIL = "john@example.com"
    }
}
