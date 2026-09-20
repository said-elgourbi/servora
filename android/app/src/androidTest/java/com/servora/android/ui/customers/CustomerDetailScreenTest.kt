package com.servora.android.ui.customers

import android.content.Context
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
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
import com.servora.android.domain.model.PropertyStatus
import com.servora.android.ui.theme.ServoraTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    fun showsTheCustomerIdentityAndItsOwnContactDetails() {
        render(state = loaded())

        composeTestRule.onNodeWithText("ABC Property Management").assertIsDisplayed()
        // The customer header's own phone and email are the customer's general line, not a contact
        // person's, and they stay where they are (`BR-095`, `ADR-022` D2).
        composeTestRule.onNodeWithText(PHONE).assertIsDisplayed()
        composeTestRule.onNodeWithText(EMAIL).assertIsDisplayed()
        composeTestRule
            .onNodeWithText(string(R.string.customers_status_active), useUnmergedTree = true)
            .assertIsDisplayed()
    }

    @Test
    fun offersCreateJobAsAFloatingAction() {
        var created = 0
        render(state = loaded(), onCreateJob = { created += 1 })

        composeTestRule.onNodeWithTag(CustomerDetailCreateJobTag).assertIsDisplayed()
        composeTestRule.onNodeWithTag(CustomerDetailCreateJobTag).performClick()

        assertEquals(1, created)
    }

    @Test
    fun hidesCreateJobWithoutTheCreateCapability() {
        render(state = loaded(), canCreateJob = false)

        // Creating a Job is its own capability (`BR-008`, `BR-094`), so the affordance is drawn only
        // for a session the API would accept the create from (`BR-007`, `BR-011`).
        composeTestRule.onNodeWithTag(CustomerDetailCreateJobTag).assertDoesNotExist()
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
    fun keepsArchivedPropertiesBehindAnExplicitDisclosure() {
        render(state = loaded(archivedProperties = listOf(property(id = ARCHIVED_PROPERTY_ID, archived = true))))

        scrollTo(CustomerDetailPropertiesTag)

        // The section shows the active projection (`BR-081`), so the disclosure is the only thing
        // that reveals an archived Property exists — and the only way to reach its Restore action.
        composeTestRule.onNodeWithTag(CustomerDetailArchivedPropertiesTag).assertIsDisplayed()
        composeTestRule
            .onNodeWithText(quantityString(R.plurals.customers_detail_archived_properties, 1))
            .assertIsDisplayed()
        composeTestRule
            .onNodeWithTag(customerDetailPropertyTag(ARCHIVED_PROPERTY_ID))
            .assertDoesNotExist()
    }

    @Test
    fun revealsAnArchivedPropertyAndOpensIt() {
        var opened: String? = null
        render(
            state = loaded(archivedProperties = listOf(property(id = ARCHIVED_PROPERTY_ID, archived = true))),
            onOpenProperty = { opened = it },
        )

        scrollTo(CustomerDetailPropertiesTag)
        composeTestRule.onNodeWithTag(CustomerDetailArchivedPropertiesTag).performClick()
        scrollTo(customerDetailPropertyTag(ARCHIVED_PROPERTY_ID))

        composeTestRule
            .onNodeWithTag(customerDetailPropertyTag(ARCHIVED_PROPERTY_ID), useUnmergedTree = true)
            .assertIsDisplayed()
            .performClick()

        assertEquals(ARCHIVED_PROPERTY_ID, opened)
    }

    @Test
    fun hidesTheArchivedDisclosureWhenTheCustomerHasNone() {
        render(state = loaded())

        scrollTo(CustomerDetailPropertiesTag)

        composeTestRule.onNodeWithTag(CustomerDetailArchivedPropertiesTag).assertDoesNotExist()
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

    @Test
    fun rendersEachContactPersonWithTheirOwnPhoneAndEmail() {
        render(
            state = loaded(
                contacts = listOf(
                    contact(),
                    contact(
                        id = SECOND_CONTACT_ID,
                        firstName = "Alex",
                        lastName = "Nguyen",
                        isPrimary = false,
                        phone = null,
                        email = null,
                    ),
                ),
            ),
        )

        // The card draws every contact the API returned, in the API's order — the primary first —
        // and each row states the person's own details rather than the customer header's
        // (`BR-095`, `ADR-022` D6).
        composeTestRule.onNodeWithTag(CustomerDetailContactsTag).assertIsDisplayed()
        composeTestRule
            .onNodeWithTag(customerDetailContactTag(CONTACT_ID), useUnmergedTree = true)
            .assertIsDisplayed()
        composeTestRule
            .onNodeWithTag(customerDetailContactTag(SECOND_CONTACT_ID), useUnmergedTree = true)
            .assertIsDisplayed()
        composeTestRule.onNodeWithText(CONTACT_PHONE).assertIsDisplayed()
        composeTestRule.onNodeWithText(CONTACT_EMAIL).assertIsDisplayed()
        composeTestRule.onNodeWithText("Alex Nguyen").assertIsDisplayed()
        // At most one contact is primary, so exactly one row wears the badge (`BR-095`).
        composeTestRule
            .onAllNodesWithText(string(R.string.customers_contacts_primary))
            .assertCountEquals(1)
    }

    @Test
    fun marksTheCustomersOwnPhoneAsThePrimaryWhenNoContactPersonIsFlagged() {
        // Zero primary contacts is a legal state (`BR-095`): the customer is then its own primary, so its
        // own phone line wears the badge and no contact row does.
        render(state = loaded(contacts = listOf(contact(isPrimary = false))))

        composeTestRule.onNodeWithTag(CustomerDetailCustomerPhoneBadgeTag).assertIsDisplayed()
        composeTestRule
            .onAllNodesWithText(string(R.string.customers_contacts_primary))
            .assertCountEquals(1)
    }

    @Test
    fun leavesTheCustomersOwnPhoneUnmarkedWhileAContactPersonIsPrimary() {
        // The badge follows the effective primary and nothing else (`BR-095`): while a contact person
        // holds the flag, the customer's own general line carries no marker.
        render(state = loaded())

        composeTestRule.onNodeWithTag(CustomerDetailCustomerPhoneBadgeTag).assertDoesNotExist()
        composeTestRule
            .onAllNodesWithText(string(R.string.customers_contacts_primary))
            .assertCountEquals(1)
    }

    @Test
    fun showsAnEmptyStateRatherThanAnEmptyCard() {
        render(state = loaded(contacts = emptyList()))

        composeTestRule
            .onNodeWithText(string(R.string.customers_contacts_empty))
            .assertIsDisplayed()
    }

    @Test
    fun offersAddContactWhenTheSessionMayRecordOne() {
        var adds = 0
        render(state = loaded(), onAddContact = { adds += 1 })

        scrollTo(CustomerDetailAddContactTag)
        composeTestRule.onNodeWithTag(CustomerDetailAddContactTag).performClick()

        assertEquals(1, adds)
    }

    @Test
    fun hidesAddContactWithoutTheCreateCapability() {
        // Recording a contact is its own capability (`BR-095`), so the affordance is drawn only for a
        // session the API would accept the write from (`BR-007`, `BR-011`).
        render(state = loaded(), canCreateContact = false)

        scrollTo(CustomerDetailContactsTag)
        composeTestRule.onNodeWithTag(CustomerDetailAddContactTag).assertDoesNotExist()
    }

    @Test
    fun offersEditAndRemoveForTheCapabilitiesThatPerformThem() {
        var edited: String? = null
        var removed: CustomerContact? = null
        render(
            state = loaded(),
            onEditContact = { edited = it },
            onRemoveContact = { removed = it },
        )

        scrollTo(customerDetailContactEditTag(CONTACT_ID))
        composeTestRule.onNodeWithTag(customerDetailContactEditTag(CONTACT_ID)).performClick()
        assertEquals(CONTACT_ID, edited)

        // The removal is confirmed before it is taken, and the version the row was read with travels
        // with it so a contact that moved on is refused (`BR-032`, `BR-067`, `BR-095`).
        composeTestRule.onNodeWithTag(customerDetailContactRemoveTag(CONTACT_ID)).performClick()
        composeTestRule.onNodeWithTag(CustomerDetailContactRemoveDialogTag).assertIsDisplayed()
        assertNull(removed)
        composeTestRule.onNodeWithTag(CustomerDetailContactRemoveConfirmTag).performClick()

        assertEquals(CONTACT_ID, removed?.id)
        assertEquals(1, removed?.version)
    }

    @Test
    fun drawsNoRowActionWithoutItsCapability() {
        render(state = loaded(), canEditContact = false, canRemoveContact = false)

        scrollTo(CustomerDetailContactsTag)
        composeTestRule.onNodeWithTag(customerDetailContactEditTag(CONTACT_ID)).assertDoesNotExist()
        composeTestRule.onNodeWithTag(customerDetailContactRemoveTag(CONTACT_ID)).assertDoesNotExist()
    }

    @Test
    fun reportsARefusedRemovalAndClearsIt() {
        var dismissals = 0
        render(
            state = loaded(),
            contactRemoval = RemoveContactUiState(
                customerId = CUSTOMER_ID,
                failureReason = CustomersFailureReason.VERSION_CONFLICT,
            ),
            onDismissContactRemovalFailure = { dismissals += 1 },
        )

        scrollTo(CustomerDetailContactRemovalMessageTag)
        composeTestRule
            .onNodeWithText(string(R.string.contact_error_conflict), substring = true)
            .assertIsDisplayed()
        composeTestRule.onNodeWithTag(CustomerDetailContactRemovalMessageTag).performClick()

        assertEquals(1, dismissals)
    }

    @Test
    fun reportsTheRemovalOnceTheBackendAcceptedIt() {
        var removedSignals = 0
        render(
            state = loaded(),
            contactRemoval = RemoveContactUiState(customerId = CUSTOMER_ID, isRemoved = true),
            onContactRemoved = { removedSignals += 1 },
        )

        composeTestRule.waitForIdle()

        assertEquals(1, removedSignals)
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
        canCreateJob: Boolean = true,
        canCreateContact: Boolean = true,
        canEditContact: Boolean = true,
        canRemoveContact: Boolean = true,
        contactRemoval: RemoveContactUiState = RemoveContactUiState(),
        onAddProperty: () -> Unit = {},
        onCreateJob: () -> Unit = {},
        onRetry: () -> Unit = {},
        onSeeAllJobs: () -> Unit = {},
        onOpenProperty: (String) -> Unit = {},
        onAddContact: () -> Unit = {},
        onEditContact: (String) -> Unit = {},
        onRemoveContact: (CustomerContact) -> Unit = {},
        onDismissContactRemovalFailure: () -> Unit = {},
        onContactRemoved: () -> Unit = {},
    ) {
        composeTestRule.setContent {
            ServoraTheme {
                CustomerDetailScreen(
                    state = state,
                    canViewProperties = canViewProperties,
                    canAddProperty = canAddProperty,
                    canCreateJob = canCreateJob,
                    canCreateContact = canCreateContact,
                    canEditContact = canEditContact,
                    canRemoveContact = canRemoveContact,
                    contactRemoval = contactRemoval,
                    onAddProperty = onAddProperty,
                    onCreateJob = onCreateJob,
                    onSeeAllJobs = onSeeAllJobs,
                    onAddContact = onAddContact,
                    onEditContact = onEditContact,
                    onRemoveContact = onRemoveContact,
                    onDismissContactRemovalFailure = onDismissContactRemovalFailure,
                    onContactRemoved = onContactRemoved,
                    onRetry = onRetry,
                    onOpenProperty = onOpenProperty,
                    modifier = Modifier,
                )
            }
        }
    }

    private fun loaded(
        properties: List<CustomerProperty> = listOf(property()),
        archivedProperties: List<CustomerProperty> = emptyList(),
        jobs: List<CustomerJob> = listOf(job()),
        contacts: List<CustomerContact> = listOf(contact()),
    ) = CustomerDetailUiState(
        customerId = CUSTOMER_ID,
        detail = CustomerDetail(
            customer = customer(),
            contacts = contacts,
            properties = properties,
            jobs = jobs,
            archivedProperties = archivedProperties,
        ),
    )

    /**
     * One contact person as the backend reports it (`BR-095`).
     *
     * The person's own phone and email are deliberately different from the customer header's, because
     * they are separate facts: the header keeps the customer's general line (`ADR-022` D2).
     */
    private fun contact(
        id: String = CONTACT_ID,
        firstName: String = "John",
        lastName: String = "Smith",
        isPrimary: Boolean = true,
        phone: String? = CONTACT_PHONE,
        email: String? = CONTACT_EMAIL,
    ) = CustomerContact(
        id = id,
        customerId = CUSTOMER_ID,
        firstName = firstName,
        lastName = lastName,
        email = email,
        phone = phone,
        role = null,
        isPrimary = isPrimary,
        isBillingContact = false,
        isJobContact = false,
        version = 1,
        createdAt = "2025-01-12T10:30:00Z",
        updatedAt = "2025-01-12T10:30:00Z",
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

    private fun property(
        id: String = PROPERTY_ID,
        lastServiceAt: String? = "2026-08-28T13:00:00Z",
        archived: Boolean = false,
    ) = CustomerProperty(
        id = id,
        name = "Cedar Lane Building",
        addressLine1 = "987 Cedar Lane",
        addressLine2 = null,
        city = "Montreal",
        province = "QC",
        postalCode = "H3A 2T6",
        country = "Canada",
        jobCount = 4,
        lastServiceAt = lastServiceAt,
        status = if (archived) PropertyStatus.ARCHIVED else PropertyStatus.ACTIVE,
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
        const val ARCHIVED_PROPERTY_ID = "property-2"
        const val JOB_ID = "job-1"
        const val JOB_NUMBER = 1042
        const val CONTACT_ID = "contact-1"
        const val SECOND_CONTACT_ID = "contact-2"
        const val CONTACT_NAME = "John Smith"
        const val CONTACT_PHONE = "555-987-6543"
        const val CONTACT_EMAIL = "john.smith@example.com"
        const val PHONE = "555-123-4567"
        const val EMAIL = "john@example.com"
    }
}
