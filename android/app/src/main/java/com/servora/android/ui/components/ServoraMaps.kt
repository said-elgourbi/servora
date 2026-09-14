package com.servora.android.ui.components

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.servora.android.domain.model.CustomerJobAddress

/**
 * Hands a Job's preserved address to the device, which opens it in the user's **preferred** map
 * application.
 *
 * Servora does not name a map provider (`BR-042`) and does not model navigation or location itself:
 * the `geo:` URI is the platform's own way of describing a location, and the system resolves it to
 * whichever application the user has chosen to handle one. Asking the *system* to resolve it is what
 * makes the choice the user's rather than the app's.
 *
 * A device that has no application for `geo:` still has a browser, so the address is offered to a maps
 * URL instead of the tap doing nothing. If neither exists the tap has no visible effect: Servora does
 * not invent a navigation screen to replace a missing application.
 */
internal fun openAddressInMaps(context: Context, address: CustomerJobAddress) {
    val query = Uri.encode(addressLine(address))
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=$query")))
    } catch (noMapApplication: ActivityNotFoundException) {
        context.startActivity(
            Intent(
                Intent.ACTION_VIEW,
                Uri.parse("https://www.google.com/maps/search/?api=1&query=$query"),
            ),
        )
    }
}
