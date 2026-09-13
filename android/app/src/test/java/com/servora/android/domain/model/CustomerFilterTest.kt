package com.servora.android.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The customer-list filter's product default and the "is anything applied" signal the screen uses
 * to mark the filter control and to tell an empty filtered list from an empty account.
 */
class CustomerFilterTest {

    @Test
    fun `starts from the active customers only`() {
        val filters = CustomerFilters()

        assertEquals(CustomerStatusFilter.ACTIVE, filters.status)
        assertEquals(CustomerJobFilter.ALL, filters.jobs)
    }

    @Test
    fun `counts the default active selection as an applied filter`() {
        assertTrue(CustomerFilters().isActive)
        assertEquals(1, CustomerFilters().appliedCount)
    }

    @Test
    fun `counts every constrained dimension`() {
        val filters = CustomerFilters(
            status = CustomerStatusFilter.INACTIVE,
            jobs = CustomerJobFilter.HAS_OPEN_JOBS,
        )

        assertTrue(filters.isActive)
        assertEquals(2, filters.appliedCount)
    }

    @Test
    fun `the unconstrained filter constrains nothing`() {
        assertEquals(CustomerStatusFilter.ALL, CustomerFilters.Unconstrained.status)
        assertEquals(CustomerJobFilter.ALL, CustomerFilters.Unconstrained.jobs)
        assertFalse(CustomerFilters.Unconstrained.isActive)
        assertEquals(0, CustomerFilters.Unconstrained.appliedCount)
    }

    @Test
    fun `the default and the unconstrained filter are different states`() {
        assertFalse(CustomerFilters() == CustomerFilters.Unconstrained)
    }
}
