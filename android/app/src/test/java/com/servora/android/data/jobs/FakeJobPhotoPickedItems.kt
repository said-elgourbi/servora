package com.servora.android.data.jobs

/**
 * [JobPhotoPickedItems] in memory.
 *
 * A test decides what the device's photo picker handed over and what the device can read back from
 * each item, so the behaviour a pick is built around — every item recorded on its own, an item the
 * device cannot read reported while the others are taken — is asserted without a device
 * (`qa.md` §6.1).
 */
class FakeJobPhotoPickedItems(
    /** What each address hands over; `null`, or an address that is absent, is an item it cannot read. */
    private val items: Map<String, ByteArray?>,
) : JobPhotoPickedItems {

    /** The addresses read so far, in the order they were read, so the pass's order is assertable. */
    val readUris: MutableList<String> = mutableListOf()

    override suspend fun bytes(uri: String): ByteArray? {
        readUris += uri
        return items[uri]
    }

    companion object {
        /** A pick of [uris] where every item hands over the same recognizable bytes. */
        fun ofJpegs(vararg uris: String): FakeJobPhotoPickedItems =
            FakeJobPhotoPickedItems(uris.associateWith { FakeJobPhotoFiles.JPEG_BYTES })
    }
}
