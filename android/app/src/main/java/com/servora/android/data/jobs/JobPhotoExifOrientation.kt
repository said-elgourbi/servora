package com.servora.android.data.jobs

import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.ExifInterface
import java.io.ByteArrayInputStream

/**
 * What a photo's own bytes say about the way its pixels must be turned, and the turn itself
 * (`BR-015`, `D7b`).
 *
 * There is **one** read of the EXIF `Orientation` tag and **one** turn of decoded pixels, because two
 * paths need them and must not drift: the **display** path turns what it decodes so a portrait capture
 * is drawn upright instead of on its side (`JobPhotoImages`), and the **preparation** path turns what
 * it re-encodes so the evidence Servora stores is itself upright and does not depend on a tag the
 * re-encode discards (`DefaultJobPhotoProcessing`, `D7b`).
 *
 * The rule that maps the tag's code to a rotation — [JobPhotoOrientation] — stays pure arithmetic over
 * a plain `Int`, so it is still verifiable on the JVM. These two functions are the part that needs the
 * Android runtime: the platform `android.media.ExifInterface` to read the tag, and `Bitmap` with
 * `Matrix` to turn the pixels. Keeping them in their own file rather than inside the rule is what lets
 * the rule keep its JVM coverage (`qa.md` §6.1), and it adds **no** dependency — the platform reader is
 * the one the display fix already chose.
 *
 * The orientation [bytes] declare, or [JobPhotoOrientation.Normal] when the file states none.
 *
 * `BitmapFactory` does not apply a file's EXIF orientation, which is why the tag has to be read before
 * the pixels are used. A file that carries no readable tag — a photo that was stripped of its metadata,
 * a format the device cannot parse EXIF from, or bytes that are not an image — answers `Normal`: the
 * photo is presented, or stored, exactly as its pixels are rather than guessed into a rotation
 * (`BR-042`). The read is wrapped because a malformed tag is a property of the file, not a failure of
 * the caller: a photo whose metadata cannot be read is still a photo.
 */
internal fun jobPhotoExifOrientation(bytes: ByteArray): JobPhotoOrientation {
    val code = runCatching {
        ExifInterface(ByteArrayInputStream(bytes))
            .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
    return JobPhotoOrientation.of(code)
}

/**
 * The photo turned so its pixels are upright, or `null` when it cannot be turned.
 *
 * It copies nothing when the photo is already upright, which is what an ordinary photo is, so the
 * common case of both callers costs no second copy of the image. The turned copy is what the caller
 * keeps: the unturned original is released here so a photo is never held twice. A turn the device
 * cannot perform is answered `null` — the same answer as a photo that cannot be decoded — because
 * handing back the unturned pixels would present, or store, exactly the wrong image the caller must
 * not use (`BR-042`).
 */
internal fun Bitmap.turnedBy(orientation: JobPhotoOrientation): Bitmap? {
    if (orientation.isIdentity) {
        return this
    }
    return runCatching {
        val matrix = Matrix().apply {
            postRotate(orientation.rotationDegrees.toFloat())
            if (orientation.mirror) {
                postScale(-1f, 1f)
            }
        }
        val turned = Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
        recycle()
        turned
    }.getOrNull()
}
