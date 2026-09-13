package com.servora.android.ui.customers

import androidx.compose.runtime.Immutable
import com.servora.android.data.customers.CustomersFailureReason
import com.servora.android.domain.model.Customer
import com.servora.android.domain.model.CustomerDetail
import com.servora.android.domain.model.CustomerFilters
import com.servora.android.domain.model.CustomerStatus
import com.servora.android.domain.model.CustomerType

/** One row of the customer list, as the screens render it. */
data class CustomerListItem(
    val id: String,
    val displayName: String,
    val isCompany: Boolean,
    val email: String?,
    val phone: String?,
    val status: CustomerStatus,
    /** ISO-8601 UTC instant the customer record was created; the row's "since" date. */
    val createdAt: String,
    val propertyCount: Int,
    val jobCount: Int,
)

/**
 * Everything the customer screens render.
 *
 * The list is the backend's (`BR-001`): [customers] stays empty until a read succeeds, and
 * [failureReason] reports why the last read did not.
 *
 * [filters] is the filter the backend was last asked to apply, so the screen can tell an empty
 * list apart from an empty *filtered* list (`BR-042`).
 *
 * [customerDetail] is the open customer's detail read, or `null` while the list is shown.
 */
@Immutable
data class CustomersUiState(
    val isLoading: Boolean = false,
    val customers: List<CustomerListItem> = emptyList(),
    val failureReason: CustomersFailureReason? = null,
    val filters: CustomerFilters = CustomerFilters(),
    val customerDetail: CustomerDetailUiState? = null,
)

/**
 * The open customer's detail, and whether it is still being read.
 *
 * The Property and Job rows are the backend's projections (`BR-081`), so [detail] stays `null`
 * until a read succeeds and [failureReason] reports why the last read did not.
 */
@Immutable
data class CustomerDetailUiState(
    val customerId: String,
    val isLoading: Boolean = false,
    val detail: CustomerDetail? = null,
    val failureReason: CustomersFailureReason? = null,
)

/** Maps the domain customer onto the row the list renders. */
fun Customer.toListItem(): CustomerListItem =
    CustomerListItem(
        id = id,
        displayName = displayName,
        isCompany = type == CustomerType.COMPANY,
        email = email,
        phone = phone,
        status = status,
        createdAt = createdAt,
        propertyCount = propertyCount,
        jobCount = jobCount,
    )
