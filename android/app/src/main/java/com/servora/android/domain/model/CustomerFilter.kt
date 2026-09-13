package com.servora.android.domain.model

/**
 * The customer's own status dimension of the customer-list filter.
 *
 * [ALL] applies no constraint. The other values are the stable customer status codes the backend
 * already returns (`BR-023`); they are never localized display text (`BR-041`).
 */
enum class CustomerStatusFilter {
    ALL,
    ACTIVE,
    INACTIVE,
}

/**
 * The jobs dimension of the customer-list filter.
 *
 * The values are the stable wire codes of `GET /customers?jobs=` (`BR-041`). Their meaning is a
 * product decision recorded by the backend slice:
 *
 * - [HAS_OPEN_JOBS] / [NO_OPEN_JOBS] — the customer has, or does not have, at least one Job that
 *   is not `COMPLETED` or `CANCELED` (`BR-058`). A customer with no Jobs has no open Jobs.
 * - [HAS_OVERDUE_VISITS] — the customer has at least one Visit still `SCHEDULED` (`BR-074`) whose
 *   scheduled end has passed.
 * - [NO_JOBS] — the customer has never had a Job (`BR-048`).
 */
enum class CustomerJobFilter {
    ALL,
    HAS_OPEN_JOBS,
    NO_OPEN_JOBS,
    HAS_OVERDUE_VISITS,
    NO_JOBS,
}

/**
 * The customer-list filter the backend is asked to apply.
 *
 * The list starts from **active customers only**: that is the product's default view of a customer
 * list (`BR-023`). The default selection is a real applied filter, so the screen marks the filter
 * control and shows the selection as a removable chip rather than hiding it. [jobs] constrains
 * nothing by default.
 */
data class CustomerFilters(
    val status: CustomerStatusFilter = CustomerStatusFilter.ACTIVE,
    val jobs: CustomerJobFilter = CustomerJobFilter.ALL,
) {
    /** How many dimensions constrain the list; the filter control displays this number. */
    val appliedCount: Int
        get() = (if (status != CustomerStatusFilter.ALL) 1 else 0) +
            (if (jobs != CustomerJobFilter.ALL) 1 else 0)

    /**
     * Whether any dimension constrains the list.
     *
     * An empty list is only "no match" when a constraint is active; an unconstrained empty list
     * means the account genuinely has no customers, which the list reports as its empty state.
     */
    val isActive: Boolean
        get() = appliedCount > 0

    companion object {
        /**
         * No dimension constrains the list, so the backend returns every customer it holds.
         *
         * Clearing the filters produces this, which is deliberately different from the list's
         * starting state.
         */
        val Unconstrained = CustomerFilters(
            status = CustomerStatusFilter.ALL,
            jobs = CustomerJobFilter.ALL,
        )
    }
}
