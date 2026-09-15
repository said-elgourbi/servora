package com.servora.android.data.jobs

/**
 * The rotation and mirror a photo's own EXIF orientation says its pixels need before it is presented
 * (`BR-015`).
 *
 * The device's camera writes a portrait capture in the sensor's own pixel layout — landscape — and
 * records the rotation that makes it upright in the file's EXIF `Orientation` tag. `BitmapFactory`
 * does not apply that tag, so a decode that ignores it draws a portrait capture on its side: the
 * camera's JPEG is portrait only once the tag is honoured.
 *
 * The vocabulary is the standard EXIF one: `1` is the already-upright photo and `2`–`8` describe a
 * quarter-turn and/or a mirror (`android.media.ExifInterface`'s `ORIENTATION_*` constants have
 * exactly these values). The mapping follows the same convention Android samples and image loaders
 * use: a clockwise rotation first, then — where the code calls for one — a mirror across the vertical
 * axis. Orienting a stored 4032×3024 capture by 90° or 270° therefore presents it as 3024×4032.
 *
 * The rule is arithmetic over a plain [Int] rather than an `ExifInterface` read, so it is verifiable
 * on the JVM (`qa.md` §6.1) and so nothing here needs the Android runtime: the code the file declares
 * is passed in. A code this build does not know — `0`, a value outside `1`..`8`, or a file that
 * declares no orientation at all — is [Normal]. An unknown orientation is never guessed into a
 * rotation, because presenting a photo with an invented turn is exactly the wrong image `BR-042`
 * forbids.
 */
internal data class JobPhotoOrientation(
    /** Clockwise rotation in degrees, applied before [mirror]. */
    val rotationDegrees: Int,
    /** Whether the rotated result is then mirrored across its vertical axis. */
    val mirror: Boolean,
) {
    /**
     * Whether the photo is presented exactly as its pixels are stored.
     *
     * A decode that is already upright is answered by the bitmap it decoded, so the common case costs
     * no second copy of the image.
     */
    val isIdentity: Boolean get() = rotationDegrees == 0 && !mirror

    companion object {
        /** EXIF `Orientation` `1`: the stored pixels are already upright. */
        val Normal: JobPhotoOrientation = JobPhotoOrientation(rotationDegrees = 0, mirror = false)

        /**
         * The transformation the EXIF orientation [exifOrientation] describes.
         *
         * `1` normal · `2` flip horizontally · `3` rotate 180° · `4` flip vertically · `5` transpose
         * (main diagonal) · `6` rotate 90° CW · `7` transverse (anti-diagonal) · `8` rotate 270° CW.
         * The mirrored codes are expressed as a rotation plus a horizontal mirror, which is the same
         * pixel mapping: a vertical flip is a 180° rotation followed by a horizontal mirror, and the
         * two diagonal flips are a quarter-turn followed by one.
         */
        fun of(exifOrientation: Int): JobPhotoOrientation =
            when (exifOrientation) {
                1 -> Normal
                2 -> JobPhotoOrientation(rotationDegrees = 0, mirror = true)
                3 -> JobPhotoOrientation(rotationDegrees = 180, mirror = false)
                4 -> JobPhotoOrientation(rotationDegrees = 180, mirror = true)
                5 -> JobPhotoOrientation(rotationDegrees = 90, mirror = true)
                6 -> JobPhotoOrientation(rotationDegrees = 90, mirror = false)
                7 -> JobPhotoOrientation(rotationDegrees = 270, mirror = true)
                8 -> JobPhotoOrientation(rotationDegrees = 270, mirror = false)
                else -> Normal
            }
    }
}
