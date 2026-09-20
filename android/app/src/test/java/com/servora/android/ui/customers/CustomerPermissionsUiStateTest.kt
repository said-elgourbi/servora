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
    fun `maps the audio capability on its own`() {
        // Recording an audio note is its own capability, because the catalogue is per kind (`ADR-015`
        // D2, `ADR-018` A7): a session may be allowed a voice note and not a photo, or the reverse.
        val state = customerPermissionsUiState(
            setOf("evidence.audio.add").asPermissionChecker(),
        )

        assertTrue(state.canAddEvidenceAudio)
        assertFalse(state.canAddEvidencePhoto)
        assertFalse(state.canRemoveEvidence)
    }

    @Test
    fun `maps the audio removal capability on its own`() {
        // Removing an accepted recording is the audio kind's own Manager-level capability (`BR-089`,
        // `ADR-018` A7): the photo removal does not grant it, and it does not grant the photo's.
        val state = customerPermissionsUiState(
            setOf("evidence.audio.remove", "evidence.audio.add").asPermissionChecker(),
        )

        assertTrue(state.canRemoveAudioEvidence)
        assertFalse(state.canRemoveEvidence)
        assertTrue(state.canAddEvidenceAudio)
    }

    @Test
    fun `does not infer the audio removal from the photo removal`() {
        val state = customerPermissionsUiState(
            setOf("evidence.photo.remove").asPermissionChecker(),
        )

        // The two kinds are withdrawn separately, so one kind's removal never stands for the other's.
        assertTrue(state.canRemoveEvidence)
        assertFalse(state.canRemoveAudioEvidence)
    }

    @Test
    fun `maps the contact capabilities to their own UI capabilities`() {
        // A Customer's contact persons are maintained with their own capability set (`BR-095`), read from
        // its own codes: a session told it may add a contact is not thereby told it may edit or remove
        // one.
        val state = customerPermissionsUiState(
            setOf("customers.contacts.create").asPermissionChecker(),
        )

        assertTrue(state.canCreateContact)
        assertFalse(state.canEditContact)
        assertFalse(state.canRemoveContact)
    }

    @Test
    fun `does not infer contact capabilities from the customer ones`() {
        // `customers.edit` authorizes no contact write (`BR-095`): a session holding every customer
        // capability is offered no contact action.
        val state = customerPermissionsUiState(
            setOf(
                "customers.view",
                "customers.create",
                "customers.edit",
                "customers.archive",
            ).asPermissionChecker(),
        )

        assertFalse(state.canCreateContact)
        assertFalse(state.canEditContact)
        assertFalse(state.canRemoveContact)
    }

    @Test
    fun `does not infer customer capabilities from the contact ones`() {
        val state = customerPermissionsUiState(
            setOf(
                "customers.contacts.create",
                "customers.contacts.edit",
                "customers.contacts.remove",
            ).asPermissionChecker(),
        )

        assertFalse(state.canOpenCustomers)
        assertFalse(state.canCreateCustomer)
        assertFalse(state.canEditCustomer)
        assertFalse(state.canArchiveCustomer)
        assertFalse(state.canViewProperties)
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
