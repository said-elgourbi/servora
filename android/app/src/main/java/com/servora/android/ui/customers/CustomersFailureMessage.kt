package com.servora.android.ui.customers

import com.servora.android.R
import com.servora.android.data.customers.CustomersFailureReason
import com.servora.android.data.offline.OutboxFailureReason

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

/**
 * The message a contact-person operation failure is reported with (`BR-095`).
 *
 * A contact write is authorized by its own capability set, so its refusals are explained in the
 * terms of the record it addresses — the `customers.*` copy names Properties and would name the
 * wrong record (`BR-028`, `BR-041`). The reason stays a stable code; only its presentation is
 * localized here.
 */
internal fun CustomersFailureReason.contactMessageRes(): Int =
    when (this) {
        CustomersFailureReason.UNAUTHENTICATED -> R.string.contact_error_unauthenticated
        CustomersFailureReason.FORBIDDEN -> R.string.contact_error_forbidden
        CustomersFailureReason.VALIDATION -> R.string.contact_error_validation
        CustomersFailureReason.VERSION_CONFLICT -> R.string.contact_error_conflict
        CustomersFailureReason.NOT_FOUND -> R.string.contact_error_not_found
        CustomersFailureReason.NETWORK -> R.string.contact_error_network
        CustomersFailureReason.SERVER -> R.string.contact_error_server
        CustomersFailureReason.UNEXPECTED -> R.string.contact_error_server
    }

/**
 * The explanation of why a queued operation was refused.
 *
 * The codes are the replay engine's (`offline-first-architecture.md` §6). Their explanations reuse
 * the failure copy above, so the same kind of refusal is never described two ways (`BR-028`).
 */
internal fun OutboxFailureReason.messageRes(): Int =
    when (this) {
        OutboxFailureReason.UNAUTHENTICATED -> R.string.property_error_unauthenticated
        OutboxFailureReason.NOT_AUTHORIZED -> R.string.property_error_forbidden
        OutboxFailureReason.STALE -> R.string.property_error_conflict
        OutboxFailureReason.INVALID -> R.string.property_error_validation
        OutboxFailureReason.NOT_FOUND -> R.string.property_error_not_found
        OutboxFailureReason.SERVER -> R.string.property_error_server
        OutboxFailureReason.NETWORK -> R.string.property_error_network
        OutboxFailureReason.UNEXPECTED -> R.string.property_error_server
    }
