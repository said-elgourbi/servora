package com.servora.android.data.customers

import com.servora.android.domain.model.Customer
import com.servora.android.domain.model.CustomerDetail
import com.servora.android.domain.model.CustomerProperty

/** Outcome of a customer read. */
sealed interface CustomersResult {
    /** The backend returned the organization's customers. */
    data class Success(val customers: List<Customer>) : CustomersResult

    /** The read failed; [reason] decides what the UI reports. */
    data class Failure(val reason: CustomersFailureReason) : CustomersResult
}

/** Outcome of a customer detail read. */
sealed interface CustomerDetailResult {
    /** The backend returned the customer's detail and its section projections. */
    data class Success(val detail: CustomerDetail) : CustomerDetailResult

    /** The read failed; [reason] decides what the UI reports. */
    data class Failure(val reason: CustomersFailureReason) : CustomerDetailResult
}

/** Outcome of a Property create. */
sealed interface PropertyCreateResult {
    /** The backend created the Property and returned its row. */
    data class Success(val property: CustomerProperty) : PropertyCreateResult

    /** The create failed; [reason] decides what the form reports. */
    data class Failure(val reason: CustomersFailureReason) : PropertyCreateResult
}

/**
 * Stable reasons a customer read can fail, classified from the HTTP answer (`dev.md` §7).
 *
 * `401` and `403` are the authorization outcomes the customer endpoints document; a rejected payload
 * (`400`/`422`) is [VALIDATION], and anything this build cannot place stays [UNEXPECTED] instead of
 * being guessed at (`BR-042`).
 */
enum class CustomersFailureReason {
    /** No session is held, or the backend no longer accepts it. */
    UNAUTHENTICATED,

    /** The signed-in user does not hold `customers.view` (`BR-007`). */
    FORBIDDEN,

    /** The backend rejected the submitted values. */
    VALIDATION,

    /** The request never reached the backend. */
    NETWORK,

    /** The backend could not complete the request (5xx). */
    SERVER,

    /** A response this build cannot interpret, or an undeclared failure. */
    UNEXPECTED,
}
