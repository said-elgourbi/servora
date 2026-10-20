package com.servora.android.ui.schedule

import com.servora.android.domain.model.AdHocReportCustomerOption
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdHocReportFormUiStateTest {
    private val form = AdHocReportFormState(
        workStartedAt = Instant.parse("2026-10-01T13:00:00Z"),
        workEndedAt = Instant.parse("2026-10-01T15:00:00Z"),
        summary = "Replaced the leaking valve.",
    )

    @Test
    fun `search text does not replace selecting a customer`() {
        val state = form.copy(customerQuery = "Maple Leaf Bakery")
        assertFalse(state.canSubmit)
        assertEquals(AdHocReportProblem.NO_CUSTOMER, state.problem)
    }

    @Test
    fun `selected customer needs no reported contact details`() {
        assertTrue(form.copy(
            selectedCustomer = AdHocReportCustomerOption("customer-1", "Maple Leaf Bakery"),
        ).canSubmit)
    }

    @Test
    fun `unknown customer requires all three nonblank fields`() {
        val complete = form.copy(
            unknownCustomer = true,
            reportedCustomerName = "Maple Leaf Bakery",
            reportedCustomerPhone = "+16135550123",
            reportedCustomerAddress = "210 Sparks Street, Ottawa",
        )
        assertTrue(complete.canSubmit)
        listOf("", "   ", "\t\n").forEach { blank ->
            listOf(
                complete.copy(reportedCustomerName = blank),
                complete.copy(reportedCustomerPhone = blank),
                complete.copy(reportedCustomerAddress = blank),
            ).forEach { incomplete ->
                assertFalse(incomplete.canSubmit)
                assertEquals(AdHocReportProblem.INCOMPLETE_CUSTOMER_DETAILS, incomplete.problem)
            }
        }
    }

    @Test
    fun `switching back to search requires a selected customer`() {
        assertFalse(form.copy(
            reportedCustomerName = "Maple Leaf Bakery",
            reportedCustomerPhone = "+16135550123",
            reportedCustomerAddress = "210 Sparks Street, Ottawa",
        ).canSubmit)
    }
}
