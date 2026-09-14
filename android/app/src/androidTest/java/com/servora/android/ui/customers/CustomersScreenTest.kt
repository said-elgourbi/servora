package com.servora.android.ui.customers

import android.content.Context
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.servora.android.R
import com.servora.android.domain.model.CustomerFilters
import com.servora.android.domain.model.CustomerJobFilter
import com.servora.android.domain.model.CustomerStatus
import com.servora.android.domain.model.CustomerStatusFilter
import com.servora.android.ui.theme.ServoraTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What the customers list renders: the floating New Customer action, the email stacked under the
 * phone number, the contact values (display-only, so a tap on one opens the customer) and the
 * derived property/job counts.
 *
 * Business behaviour is covered by `CustomersViewModelTest`; these tests cover rendering, the
 * `customers.create` gate and the callback wiring only.
 */
@RunWith(AndroidJUnit4::class)
class CustomersScreenTest {

    @get:Rule val composeTestRule = createComposeRule()

    @Test
    fun newCustomerIsOfferedAsAFloatingAction() {
        render(canCreateCustomer = true)

        composeTestRule.onNodeWithTag(AddCustomerActionTag).assertIsDisplayed()
    }

    @Test
    fun newCustomerActionIsHiddenWithoutTheCreatePermission() {
        render(canCreateCustomer = false)

        composeTestRule.onNodeWithTag(AddCustomerActionTag).assertDoesNotExist()
    }

    @Test
    fun tappingTheFloatingActionReportsTheCreateIntent() {
        var created = 0
        render(canCreateCustomer = true, onCreate = { created += 1 })

        composeTestRule.onNodeWithTag(AddCustomerActionTag).performClick()

        assertEquals(1, created)
    }

    @Test
    fun emailSitsUnderThePhoneNumber() {
        render(canCreateCustomer = true)

        val phoneTop =
            composeTestRule.onNodeWithText(PHONE, useUnmergedTree = true).getBoundsInRoot().top
        val emailTop =
            composeTestRule.onNodeWithText(EMAIL, useUnmergedTree = true).getBoundsInRoot().top

        assertTrue("the email should render below the phone number", emailTop > phoneTop)
    }

    @Test
    fun rowShowsTheDerivedPropertyAndJobCounts() {
        render(canCreateCustomer = true)

        composeTestRule
            .onNodeWithText(
                quantityString(R.plurals.customers_property_count, 3),
                useUnmergedTree = true,
            )
            .assertIsDisplayed()
        composeTestRule
            .onNodeWithText(
                quantityString(R.plurals.customers_job_count, 5),
                useUnmergedTree = true,
            )
            .assertIsDisplayed()
    }

    @Test
    fun contactValuesAreNotTapTargetsOfTheirOwn() {
        render(canCreateCustomer = true)

        composeTestRule
            .onNodeWithTag(customerPhoneTag("c1"), useUnmergedTree = true)
            .assertHasNoClickAction()
        composeTestRule
            .onNodeWithTag(customerEmailTag("c1"), useUnmergedTree = true)
            .assertHasNoClickAction()
    }

    @Test
    fun tappingAContactValueOpensTheCustomerLikeTheRestOfTheRow() {
        var opened: String? = null
        render(canCreateCustomer = true, onOpenCustomer = { opened = it })

        composeTestRule
            .onNodeWithTag(customerPhoneTag("c1"), useUnmergedTree = true)
            .performClick()
        assertEquals("the phone number should open the customer", "c1", opened)

        opened = null
        composeTestRule
            .onNodeWithTag(customerEmailTag("c1"), useUnmergedTree = true)
            .performClick()
        assertEquals("the email should open the customer", "c1", opened)
    }

    @Test
    fun statusSitsUnderTheCustomerName() {
        render(canCreateCustomer = true)

        val nameBottom =
            composeTestRule
                .onNodeWithText("ABC Property Management", useUnmergedTree = true)
                .getBoundsInRoot()
                .bottom
        // The row's status pill is found by tag: the applied-filter chip under the search field
        // carries the same "Active" label by default, so the text alone would be ambiguous.
        val statusTop =
            composeTestRule
                .onNodeWithTag(customerStatusTag("c1"), useUnmergedTree = true)
                .getBoundsInRoot()
                .top

        assertTrue("the status should render below the name", statusTop >= nameBottom)
    }

    @Test
    fun theSinceDateSitsBesideTheStatus() {
        render(canCreateCustomer = true)

        // The date itself is locale- and timezone-formatted, so assert through the tag and the
        // localized prefix rather than reproducing the formatting in the test.
        composeTestRule
            .onNodeWithTag(customerSinceTag("c1"), useUnmergedTree = true)
            .assertIsDisplayed()
            .assertTextContains(
                string(R.string.customers_since_format, ""),
                substring = true,
            )
    }

    @Test
    fun rowOffersAChevronToOpenTheCustomer() {
        render(canCreateCustomer = true)

        composeTestRule
            .onNodeWithTag(customerChevronTag("c1"), useUnmergedTree = true)
            .assertIsDisplayed()
    }

    @Test
    fun tappingTheRowReportsTheCustomerItOpens() {
        var opened: String? = null
        render(canCreateCustomer = true, onOpenCustomer = { opened = it })

        composeTestRule.onNodeWithTag(customerRowTag("c1")).performClick()

        assertEquals("c1", opened)
    }

    @Test
    fun theFilterButtonOpensTheFilterSheet() {
        render(canCreateCustomer = true)

        composeTestRule.onNodeWithTag(CustomersFilterButtonTag).performClick()

        composeTestRule.onNodeWithTag(CustomersFilterSheetTag).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.customers_filter_title)).assertIsDisplayed()
        // The sheet opens fully, so its commit action is on screen without dragging it up.
        composeTestRule.onNodeWithTag(CustomersFilterApplyTag).assertIsDisplayed()
    }

    @Test
    fun applyingAFilterReportsTheChosenStatusAndJobs() {
        var applied: CustomerFilters? = null
        render(canCreateCustomer = true, onApplyFilters = { applied = it })

        composeTestRule.onNodeWithTag(CustomersFilterButtonTag).performClick()
        composeTestRule
            .onNodeWithTag(customersFilterStatusTag(CustomerStatusFilter.INACTIVE))
            .performScrollTo()
            .performClick()
        composeTestRule
            .onNodeWithTag(customersFilterJobTag(CustomerJobFilter.HAS_OPEN_JOBS))
            .performScrollTo()
            .performClick()
        composeTestRule.onNodeWithTag(CustomersFilterApplyTag).performScrollTo().performClick()

        assertEquals(
            CustomerFilters(
                status = CustomerStatusFilter.INACTIVE,
                jobs = CustomerJobFilter.HAS_OPEN_JOBS,
            ),
            applied,
        )
    }

    @Test
    fun anEmptyFilteredListReportsNoMatchRatherThanNoCustomers() {
        render(
            canCreateCustomer = true,
            customers = emptyList(),
            filters = CustomerFilters(status = CustomerStatusFilter.ACTIVE),
        )

        composeTestRule
            .onNodeWithText(string(R.string.customers_no_match_title))
            .assertIsDisplayed()
    }

    @Test
    fun theFilterControlIsNumberedWhileTheDefaultActiveFilterApplies() {
        render(canCreateCustomer = true)

        // The list starts on active customers only, and that default counts as an applied filter.
        composeTestRule
            .onNodeWithTag(CustomersFilterCountTag, useUnmergedTree = true)
            .assertIsDisplayed()
            .assertTextContains("1")
    }

    @Test
    fun theFilterControlShowsThePlainLabelWithoutFilters() {
        render(canCreateCustomer = true, filters = CustomerFilters.Unconstrained)

        composeTestRule
            .onNodeWithTag(CustomersFilterCountTag, useUnmergedTree = true)
            .assertDoesNotExist()
        composeTestRule.onNodeWithText(string(R.string.customers_filter)).assertIsDisplayed()
    }

    @Test
    fun theDefaultActiveFilterIsShownAsAChipUnderTheSearchField() {
        render(canCreateCustomer = true)

        composeTestRule.onNodeWithTag(CustomersFilterChipsTag).assertIsDisplayed()
        composeTestRule
            .onNodeWithTag(CustomersFilterStatusChipTag)
            .assertIsDisplayed()
            .assertTextContains(string(R.string.customers_status_active))
    }

    @Test
    fun theAppliedFilterChipsAreAbsentWithoutFilters() {
        render(canCreateCustomer = true, filters = CustomerFilters.Unconstrained)

        composeTestRule.onNodeWithTag(CustomersFilterChipsTag).assertDoesNotExist()
    }

    @Test
    fun clearingTheStatusChipReportsTheUnconstrainedFilter() {
        var applied: CustomerFilters? = null
        render(canCreateCustomer = true, onApplyFilters = { applied = it })

        composeTestRule.onNodeWithTag(CustomersFilterStatusChipClearTag).performClick()

        assertEquals(CustomerFilters.Unconstrained, applied)
    }

    @Test
    fun clearingEveryFilterReportsTheUnconstrainedFilter() {
        var applied: CustomerFilters? = null
        render(
            canCreateCustomer = true,
            filters = CustomerFilters(
                status = CustomerStatusFilter.ACTIVE,
                jobs = CustomerJobFilter.HAS_OPEN_JOBS,
            ),
            onApplyFilters = { applied = it },
        )

        composeTestRule
            .onNodeWithTag(CustomersFilterClearAllTag)
            .performScrollTo()
            .performClick()

        assertEquals(CustomerFilters.Unconstrained, applied)
    }

    private fun render(
        canCreateCustomer: Boolean,
        customers: List<CustomerListItem> = listOf(customer()),
        filters: CustomerFilters = CustomerFilters(),
        onApplyFilters: (CustomerFilters) -> Unit = {},
        onCreate: () -> Unit = {},
        onOpenCustomer: (String) -> Unit = {},
    ) {
        composeTestRule.setContent {
            ServoraTheme {
                CustomersScreen(
                    permissions =
                        CustomerPermissionsUiState(
                            canOpenCustomers = true,
                            canCreateCustomer = canCreateCustomer,
                            canEditCustomer = false,
                            canArchiveCustomer = false,
                            canViewProperties = false,
                            canCreateProperty = false,
                        ),
                    state = CustomersUiState(customers = customers, filters = filters),
                    onCreate = onCreate,
                    onOpenCustomer = onOpenCustomer,
                    onRetry = {},
                    onApplyFilters = onApplyFilters,
                    modifier = Modifier,
                )
            }
        }
    }

    private fun customer(): CustomerListItem =
        CustomerListItem(
            id = "c1",
            displayName = "ABC Property Management",
            isCompany = true,
            email = EMAIL,
            phone = PHONE,
            status = CustomerStatus.ACTIVE,
            createdAt = "2025-01-12T10:30:00Z",
            propertyCount = 3,
            jobCount = 5,
        )

    private fun string(resId: Int): String =
        ApplicationProvider.getApplicationContext<Context>().getString(resId)

    private fun string(resId: Int, formatArg: Any?): String =
        ApplicationProvider.getApplicationContext<Context>().getString(resId, formatArg)

    /** Resolves a plural in the test's own locale, so the assertion matches what the row renders. */
    private fun quantityString(resId: Int, count: Int): String =
        ApplicationProvider.getApplicationContext<Context>()
            .resources
            .getQuantityString(resId, count, count)

    private companion object {
        const val PHONE = "555-123-4567"
        const val EMAIL = "john@example.com"
    }
}
