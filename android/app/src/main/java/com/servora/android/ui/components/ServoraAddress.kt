package com.servora.android.ui.components

import com.servora.android.domain.model.CustomerJobAddress

/**
 * A preserved address snapshot as one line (`BR-056`).
 *
 * Only the parts the snapshot actually carries are joined, so a partly known address is presented as
 * what is known rather than padded with empty separators. It lives here because more than one screen
 * presents a Job's preserved snapshot — the customer's Job row and the Job Details screen — and two
 * formatings of the same record would let them disagree (`BR-041`).
 */
internal fun addressLine(address: CustomerJobAddress): String {
    val locality = listOfNotNull(
        address.city?.takeIf { it.isNotBlank() },
        listOfNotNull(
            address.province?.takeIf { it.isNotBlank() },
            address.postalCode?.takeIf { it.isNotBlank() },
        ).joinToString(" ").takeIf { it.isNotBlank() },
    ).joinToString(", ")
    return listOfNotNull(
        address.addressLine1?.takeIf { it.isNotBlank() },
        address.addressLine2?.takeIf { it.isNotBlank() },
        locality.takeIf { it.isNotBlank() },
    ).joinToString(", ")
}
