package com.servora.android.ui.jobs

import com.servora.android.R
import com.servora.android.data.customers.CustomersFailureReason
import com.servora.android.data.jobs.JobCreateFailure

/**
 * The message a Job create failure is reported with (`BR-028`).
 *
 * The reason stays a stable code (`BR-041`); only its presentation is localized here. The create's own
 * reasons are not the Property form's: this route answers a Customer that is not active and a Property
 * that is archived, and saying "this property changed since you opened it" for either would be a
 * different reason than the API gave.
 */
internal fun JobCreateFailure.messageRes(): Int =
    when (this) {
        JobCreateFailure.UNAUTHENTICATED -> R.string.job_create_error_unauthenticated
        JobCreateFailure.FORBIDDEN -> R.string.job_create_error_forbidden
        JobCreateFailure.VALIDATION -> R.string.job_create_error_validation
        JobCreateFailure.CUSTOMER_NOT_FOUND -> R.string.job_create_error_customer_not_found
        JobCreateFailure.CUSTOMER_INACTIVE -> R.string.job_create_error_customer_inactive
        JobCreateFailure.PROPERTY_NOT_FOUND -> R.string.job_create_error_property_not_found
        JobCreateFailure.PROPERTY_UNAVAILABLE -> R.string.job_create_error_property_unavailable
        JobCreateFailure.NOT_FOUND -> R.string.job_create_error_not_found
        JobCreateFailure.CONFLICT -> R.string.job_create_error_conflict
        JobCreateFailure.NETWORK -> R.string.job_create_error_network
        JobCreateFailure.SERVER -> R.string.job_create_error_server
        JobCreateFailure.UNEXPECTED -> R.string.job_create_error_server
    }

/**
 * The message a failed read inside the form is reported with.
 *
 * The Customer list and the Property list are reads, so their failures are the read reasons the rest of
 * the app already classifies (`offline-first-architecture.md` §2); only the copy is this form's.
 */
internal fun CustomersFailureReason.readMessageRes(): Int =
    when (this) {
        CustomersFailureReason.UNAUTHENTICATED -> R.string.job_create_error_unauthenticated
        CustomersFailureReason.FORBIDDEN -> R.string.job_create_error_forbidden
        CustomersFailureReason.VALIDATION -> R.string.job_create_error_validation
        CustomersFailureReason.VERSION_CONFLICT -> R.string.job_create_error_conflict
        CustomersFailureReason.NOT_FOUND -> R.string.job_create_error_not_found
        CustomersFailureReason.NETWORK -> R.string.job_create_error_network
        CustomersFailureReason.SERVER -> R.string.job_create_error_server
        CustomersFailureReason.UNEXPECTED -> R.string.job_create_error_server
    }
