package com.servora.android.ui.customers

import com.servora.android.R
import com.servora.android.data.customers.CustomersFailureReason

/**
 * The message a Property operation failure is reported with.
 *
 * The reason stays a stable code (`BR-041`); only its presentation is localized here (`BR-028`).
 * Both the Add/Edit Property form and the Property lifecycle actions share this mapping, so one
 * operation's failure cannot be explained differently from another's.
 */
internal fun CustomersFailureReason.messageRes(): Int =
    when (this) {
        CustomersFailureReason.UNAUTHENTICATED -> R.string.property_error_unauthenticated
        CustomersFailureReason.FORBIDDEN -> R.string.property_error_forbidden
        CustomersFailureReason.VALIDATION -> R.string.property_error_validation
        CustomersFailureReason.VERSION_CONFLICT -> R.string.property_error_conflict
        CustomersFailureReason.NOT_FOUND -> R.string.property_error_not_found
        CustomersFailureReason.NETWORK -> R.string.property_error_network
        CustomersFailureReason.SERVER -> R.string.property_error_server
        CustomersFailureReason.UNEXPECTED -> R.string.property_error_server
    }
