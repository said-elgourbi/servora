package com.servora.android.ui.jobs

import androidx.compose.runtime.Immutable
import com.servora.android.data.customers.CustomersFailureReason
import com.servora.android.data.jobs.JobCreateFailure
import com.servora.android.domain.model.CustomerProperty
import com.servora.android.ui.customers.CustomerListItem

/**
 * The Create Job form (`BR-094`; `docs/api/job-details.md` §6).
 *
 * The form states exactly what the API accepts: the Customer the Job is for, the Property the work is
 * performed at, and the title, with an optional description. Nothing else — no priority, no owner, no
 * category, no schedule, no crew, no Visit (`BR-053`, `BR-054`, `BR-055`, `BR-047`) — and no value the
 * backend owns: the Job number, the status, the version and the address snapshot are never sent
 * (`BR-001`, `BR-052`, `BR-056`, `BR-058`).
 *
 * [fixedCustomerId] is set when the form was opened for a Customer — from that Customer's detail, for
 * instance — so the Customer is shown as fixed context rather than being searched for again (`BR-012`).
 * The Customer selector is then not drawn at all.
 *
 * [showsValidationError] only appears after a submit was attempted, so an untouched form is never marked
 * incomplete before the user has done anything.
 */
@Immutable
data class CreateJobUiState(
    /** The Customer the form is fixed to, or `null` when the caller chooses one. */
    val fixedCustomerId: String? = null,
    /** The fixed Customer's display name, as the destination already read it. */
    val fixedCustomerName: String? = null,
    val selectedCustomerId: String? = null,
    val selectedCustomerName: String? = null,
    /** What the user typed into the Customer search field; it narrows the list locally. */
    val customerQuery: String = "",
    val customers: List<CustomerListItem> = emptyList(),
    val customersLoading: Boolean = false,
    val customersFailure: CustomersFailureReason? = null,
    /** The selected Customer's `ACTIVE` Properties (`BR-081`, `BR-083`). */
    val properties: List<CustomerProperty> = emptyList(),
    val propertiesLoading: Boolean = false,
    val propertiesFailure: CustomersFailureReason? = null,
    val selectedPropertyId: String? = null,
    val title: String = "",
    val description: String = "",
    val isSubmitting: Boolean = false,
    val submitAttempted: Boolean = false,
    /** The Job the backend created, so the destination can open it (`BR-001`). */
    val createdJobId: String? = null,
    val failureReason: JobCreateFailure? = null,
) {
    /** Whether the Customer is fixed context rather than a choice. */
    val hasFixedCustomer: Boolean get() = fixedCustomerId != null

    /** The Property the user chose, when they chose one. */
    val selectedProperty: CustomerProperty?
        get() = properties.firstOrNull { it.id == selectedPropertyId }

    /**
     * The rows the Customer selector draws: the read answer narrowed by [customerQuery].
     *
     * The narrowing is local because the API exposes no text query on `GET /customers` — it filters by
     * status and jobs only — so there is no server search to ask (`BR-001`). The fields searched are the
     * ones a customer can be recognised by, exactly as the customer list screen narrows its rows.
     */
    val visibleCustomers: List<CustomerListItem>
        get() = customers.filter { customer ->
            customerQuery.isBlank() ||
                customer.displayName.contains(customerQuery, ignoreCase = true) ||
                customer.email?.contains(customerQuery, ignoreCase = true) == true ||
                customer.phone?.contains(customerQuery, ignoreCase = true) == true
        }

    /**
     * Whether the form may be submitted.
     *
     * `BR-094` requires a Customer and a Property, and `BR-053` requires a title. A submission already in
     * flight is not submitted again.
     */
    val canSubmit: Boolean
        get() = selectedCustomerId != null &&
            selectedPropertyId != null &&
            title.isNotBlank() &&
            !isSubmitting

    /** Whether the form reports what is still missing, which only happens after a submit attempt. */
    val showsValidationError: Boolean
        get() = submitAttempted && !canSubmit

    /** Whether a Customer is chosen, so the Property selector can be drawn enabled. */
    val canChooseProperty: Boolean
        get() = selectedCustomerId != null

    /** Whether the chosen Customer has been read and holds no `ACTIVE` Property. */
    val showsNoProperties: Boolean
        get() = selectedCustomerId != null &&
            properties.isEmpty() &&
            !propertiesLoading &&
            propertiesFailure == null
}
