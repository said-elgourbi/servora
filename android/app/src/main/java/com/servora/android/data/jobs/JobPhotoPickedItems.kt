package com.servora.android.data.jobs

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The bytes of a photo the technician chose in the device's own photo picker
 * (`docs/tracker/029-photo-evidence-phases.md` D3).
 *
 * The picker hands over an item's address, not its bytes: a content URI belongs to whoever serves
 * it, so reading it is a device concern that sits behind this port, exactly as [JobPhotoFiles] and
 * [JobPhotoProcessing] do. What the pipeline receives is the same thing it receives from the camera —
 * bytes — so a picked photo is prepared, recorded and uploaded by the same code as a captured one
 * (`BR-015`, offline standard §9).
 *
 * It answers `null` when nothing can be read from the item: the caller then records **nothing** and
 * reports the item, rather than filing a draft whose bytes the device does not hold (`BR-014`).
 */
interface JobPhotoPickedItems {
    /** The bytes [uri] hands over, or `null` when they cannot be read. */
    suspend fun bytes(uri: String): ByteArray?
}

/**
 * The default [JobPhotoPickedItems], reading through the device's own content resolver.
 *
 * The read runs off the main thread because an item may be a large original that takes time to hand
 * over, and the picker may hand over several of them (`D3c`). Servora reads only what the user chose
 * in the picker — no storage permission is requested and nothing else on the device is read.
 */
@Singleton
class ContentResolverJobPhotoPickedItems @Inject constructor(
    @ApplicationContext private val context: Context,
) : JobPhotoPickedItems {

    override suspend fun bytes(uri: String): ByteArray? =
        withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openInputStream(Uri.parse(uri))?.use { it.readBytes() }
            }.getOrNull()
        }
}
