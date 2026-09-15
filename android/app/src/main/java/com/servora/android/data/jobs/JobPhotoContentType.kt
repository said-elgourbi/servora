package com.servora.android.data.jobs

/**
 * The photo types Servora accepts as evidence, mirroring the API's own vocabulary
 * (`PHOTO_CONTENT_TYPES` in `api/src/storage/object-storage.ts`, `docs/api/job-photos.md` §3.3).
 *
 * The API owns this list: it sniffs the bytes of every upload, refuses a declared type that disagrees
 * with them, and stores the object under the type's extension. The client therefore mirrors the codes
 * rather than inventing its own (`BR-041`), and it is the **stable** [mimeType] — never [extension],
 * which is a local convenience for the app-private file name — that a request declares.
 */
enum class JobPhotoContentType(val mimeType: String, val extension: String) {
    JPEG("image/jpeg", "jpg"),
    PNG("image/png", "png"),
    WEBP("image/webp", "webp"),
    ;

    companion object {
        /**
         * The type [bytes] declare through their magic number, or `null` when Servora does not accept
         * them.
         *
         * The check is the API's, mirrored: a photo is never accepted because of its file name or a
         * header a caller claims (`api/src/jobs/job-photo.dto.ts` `sniffPhotoContentType`).
         */
        fun ofBytes(bytes: ByteArray): JobPhotoContentType? =
            when {
                bytes.startsWith(JPEG_MARKER) -> JPEG
                bytes.startsWith(PNG_SIGNATURE) -> PNG
                bytes.isWebp() -> WEBP
                else -> null
            }

        /** The type [mimeType] names, or `null` when this build does not know it. */
        fun ofMimeType(mimeType: String): JobPhotoContentType? =
            entries.firstOrNull { it.mimeType == mimeType }
    }
}

/** A JPEG file starts with the start-of-image marker the API's sniffer checks. */
private val JPEG_MARKER = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())

/** A PNG file starts with its 8-byte signature. */
private val PNG_SIGNATURE =
    byteArrayOf(
        0x89.toByte(),
        0x50,
        0x4E,
        0x47,
        0x0D,
        0x0A,
        0x1A,
        0x0A,
    )

private val RIFF_MARKER = "RIFF".toByteArray(Charsets.US_ASCII)
private val WEBP_MARKER = "WEBP".toByteArray(Charsets.US_ASCII)

/** The offset of the `WEBP` marker, which is what makes a RIFF container a WebP image. */
private const val WEBP_MARKER_OFFSET = 8

/**
 * Whether the bytes are a WebP image: a `RIFF` container whose form type is `WEBP`, exactly as the
 * API decides it.
 */
private fun ByteArray.isWebp(): Boolean =
    size >= WEBP_MARKER_OFFSET + WEBP_MARKER.size &&
        startsWith(RIFF_MARKER) &&
        WEBP_MARKER.indices.all { this[WEBP_MARKER_OFFSET + it] == WEBP_MARKER[it] }

/** Whether the bytes begin with [marker]. A shorter array simply does not. */
private fun ByteArray.startsWith(marker: ByteArray): Boolean =
    size >= marker.size && marker.indices.all { this[it] == marker[it] }
