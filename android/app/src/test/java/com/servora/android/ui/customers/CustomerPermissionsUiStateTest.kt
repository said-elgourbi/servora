package com.servora.android.ui.customers

import com.servora.android.domain.auth.asPermissionChecker
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomerPermissionsUiStateTest {
    @Test
    fun `maps customer permissions to UI capabilities`() {
        val state = customerPermissionsUiState(
            setOf(
                "customers.view",
                "customers.create",
                "customers.edit",
                "customers.archive",
            ).asPermissionChecker(),
        )

        assertTrue(state.canOpenCustomers)
        assertTrue(state.canCreateCustomer)
        assertTrue(state.canEditCustomer)
        assertTrue(state.canArchiveCustomer)
    }

    @Test
    fun `maps property permissions to their own UI capabilities`() {
        val state = customerPermissionsUiState(
            setOf(
                "properties.view",
                "properties.create",
            ).asPermissionChecker(),
        )

        assertTrue(state.canViewProperties)
        assertTrue(state.canCreateProperty)
    }

    @Test
    fun `does not infer property capabilities from customer permissions`() {
        // Property capabilities are independent of `customers.*` (`BR-085`): holding every customer
        // permission grants no Property capability.
        val state = customerPermissionsUiState(
            setOf(
                "customers.view",
                "customers.create",
                "customers.edit",
                "customers.archive",
            ).asPermissionChecker(),
        )

        assertFalse(state.canViewProperties)
        assertFalse(state.canCreateProperty)
    }

    @Test
    fun `does not infer customer capabilities from property permissions`() {
        val state = customerPermissionsUiState(
            setOf(
                "properties.view",
                "properties.create",
            ).asPermissionChecker(),
        )

        assertFalse(state.canOpenCustomers)
        assertFalse(state.canCreateCustomer)
        assertFalse(state.canEditCustomer)
        assertFalse(state.canArchiveCustomer)
    }

    @Test
    fun `maps the evidence capabilities to their own UI capabilities`() {
        // Recording, reading and removing evidence are three separate capabilities (`BR-006`, `BR-015`,
        // `BR-089`): a session is told which of them it holds, and no one implies another.
        val state = customerPermissionsUiState(
            setOf("evidence.photo.add", "evidence.view").asPermissionChecker(),
        )

        assertTrue(state.canAddEvidencePhoto)
        assertTrue(state.canViewEvidence)
        assertFalse(state.canRemoveEvidence)
    }

    @Test
    fun `maps the removal capability on its own`() {
        val state = customerPermissionsUiState(
            setOf("evidence.photo.remove").asPermissionChecker(),
        )

        assertTrue(state.canRemoveEvidence)
        // Removing accepted evidence is not adding or reading it: the capability is its own.
        assertFalse(state.canAddEvidencePhoto)
        assertFalse(state.canViewEvidence)
    }

    @Test
    fun `does not infer capabilities from missing permissions`() {
        val state = customerPermissionsUiState(emptySet<String>().asPermissionChecker())

        assertFalse(state.canOpenCustomers)
        assertFalse(state.canCreateCustomer)
        assertFalse(state.canEditCustomer)
        assertFalse(state.canArchiveCustomer)
        assertFalse(state.canViewProperties)
        assertFalse(state.canCreateProperty)
    }

    @Test
    fun `accepts legacy customer permission aliases from older sessions`() {
        val state = customerPermissionsUiState(
            setOf(
                "view.customers",
                "create.customers",
                "edit.customers",
                "archive.customers",
            ).asPermissionChecker(),
        )

        assertTrue(state.canOpenCustomers)
        assertTrue(state.canCreateCustomer)
        assertTrue(state.canEditCustomer)
        assertTrue(state.canArchiveCustomer)
        // The aliases are customer-only; they must not stand in for a Property capability.
        assertFalse(state.canViewProperties)
        assertFalse(state.canCreateProperty)
    }
}
