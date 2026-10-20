package com.servora.android.ui.schedule

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.servora.android.R
import com.servora.android.data.schedule.AdHocWorkReportDto
import com.servora.android.data.schedule.ResolveAdHocLocationRequestDto
import com.servora.android.data.schedule.toReport
import com.servora.android.domain.model.AdHocReportCustomerOption
import com.servora.android.domain.model.AdHocReportPropertyOption
import com.servora.android.ui.customers.CustomerPermissionsUiState
import com.servora.android.ui.customers.addPropertyProvinceOptionTag
import com.servora.android.ui.theme.ServoraTheme
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AdHocLocationDialogTest {
    @get:Rule val compose = createComposeRule()
    private val permissions = CustomerPermissionsUiState(true, true, false, false, true, true)
    private fun text(id: Int) = ApplicationProvider.getApplicationContext<Context>().getString(id)
    private fun report() = AdHocWorkReportDto(
        id = "report", workStartedAt = "2026-10-01T13:00:00Z", workEndedAt = "2026-10-01T15:00:00Z",
        outcomeCode = "RESOLVED", summary = "Repair", status = "PENDING", version = 7,
        createdAt = "2026-10-01T15:00:00Z", reportedCustomerName = "Maple Leaf Bakery",
        reportedCustomerPhone = "6135550123", reportedCustomerAddress = "210 Sparks Street",
    ).toReport()!!

    @Test
    fun createsFromEditableReportedDetails() {
        var submitted: ResolveAdHocLocationRequestDto? = null
        compose.setContent {
            ServoraTheme {
                AdHocLocationDialog(report(), ScheduleUiState(), permissions, {}, {}, { submitted = it }, {})
            }
        }
        compose.onNodeWithText(text(R.string.ad_hoc_company)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.ad_hoc_customer_name)).performScrollTo().performTextReplacement("Edited Bakery")
        compose.onNodeWithText(text(R.string.ad_hoc_report_reported_phone_label)).performScrollTo().performTextReplacement("6135550999")
        compose.onNodeWithText(text(R.string.ad_hoc_street)).performScrollTo().performTextReplacement("212 Sparks Street")
        compose.onNodeWithText(text(R.string.ad_hoc_city)).performScrollTo().performTextReplacement("Ottawa")
        compose.onNodeWithTag("add-property-province").performScrollTo().performClick()
        compose.onNodeWithTag(addPropertyProvinceOptionTag("ON")).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.ad_hoc_postal_code)).performScrollTo().performTextReplacement("K1P 5B4")
        compose.onNodeWithTag("ad-hoc-location-confirm").performClick()
        compose.runOnIdle {
            assertEquals(7, submitted!!.expectedVersion)
            assertEquals("\"Edited Bakery\"", submitted!!.newCustomer!!["displayName"].toString())
            assertEquals("\"6135550999\"", submitted!!.newCustomer!!["phone"].toString())
            assertEquals("212 Sparks Street", submitted!!.newProperty!!.addressLine1)
            assertEquals("Maple Leaf Bakery", report().reportedCustomerName)
        }
    }

    @Test
    fun selectsExistingCustomerAndPropertyWithoutCreatingRecords() {
        var submitted: ResolveAdHocLocationRequestDto? = null
        val state = mutableStateOf(ScheduleUiState())
        compose.setContent {
            ServoraTheme {
                AdHocLocationDialog(report(), state.value, permissions,
                    onSearch = { state.value = state.value.copy(adHocLocationCustomers = listOf(AdHocReportCustomerOption("match", "Matched Bakery", "6135550123"))) },
                    onProperties = { state.value = state.value.copy(adHocLocationProperties = listOf(AdHocReportPropertyOption("property", null, "210 Sparks Street", null, "Ottawa", "ON", "K1P 5B4", "Canada"))) },
                    onConfirm = { submitted = it }, onDismiss = {})
            }
        }
        compose.onNodeWithText(text(R.string.ad_hoc_existing_customer), useUnmergedTree = true).performScrollTo().performClick()
        compose.onNodeWithText("Matched Bakery\n6135550123").performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.ad_hoc_existing_property)).performScrollTo().performClick()
        compose.onNodeWithText("210 Sparks Street, Ottawa, K1P 5B4").performScrollTo().performClick()
        compose.onNodeWithTag("ad-hoc-location-confirm").performClick()
        compose.runOnIdle {
            assertEquals("match", submitted!!.existingCustomerId)
            assertEquals("property", submitted!!.existingPropertyId)
            assertNull(submitted!!.newCustomer)
            assertNull(submitted!!.newProperty)
        }
    }

    @Test
    fun refusesIncompleteAddressAndDisablesControlsWhileSending() {
        val state = mutableStateOf(ScheduleUiState())
        compose.setContent { ServoraTheme { AdHocLocationDialog(report(), state.value, permissions, {}, {}, {}, {}) } }
        compose.onNodeWithTag("ad-hoc-location-confirm").assertIsNotEnabled()
        compose.runOnIdle { state.value = state.value.copy(reviewingAdHocReportId = "report") }
        compose.onNodeWithTag("ad-hoc-location-confirm").assertIsNotEnabled()
        compose.onNodeWithText(text(R.string.ad_hoc_location_cancel)).assertIsNotEnabled()
    }
}
