package com.servora.android.data.jobs

/**
 * One photo the image stack is asked to load, and where its bytes are (`D4b`, `BR-015`, `BR-027`).
 *
 * The stack draws every preview and the full-size photo, so a photo is identified here by **where it
 * can be read** rather than by bytes someone already decoded: the feature's fetcher
 * ([JobPhotoFetcher]) reads them, and the library samples, turns and caches them. Nothing in this type
 * writes, copies or holds evidence — a preview is a read of what already exists on the device or in
 * the backend (`BR-001`).
 */
sealed interface JobPhotoImage {
    /** A photo whose bytes are still on this device, before the backend accepted it (§9). */
    data class Local(val path: String) : JobPhotoImage

    /** A photo the backend holds, read through the API (`BR-015`). */
    data class Backend(val jobId: String, val photoId: String) : JobPhotoImage
}

/**
 * The key the image stack caches [image] under, for the session that reads it (`D4b`, `BR-007`).
 *
 * The session's subject is part of every key, exactly as it is part of the app-private directory a
 * pending photo is stored in (`filesDir/job-photos/<subjectId>/…`): bytes cached for one member's
 * session are never served to another member's, not even on a device both have signed in on. That
 * partition is what keeps the cache a read of evidence the API already authorized rather than a way
 * around the authorization (`BR-007`).
 *
 * The same key names the in-memory entry and the on-disk one, so a cold start finds exactly what the
 * previous run cached for this session and nothing it did not.
 */
internal fun jobPhotoImageCacheKey(subjectId: String, image: JobPhotoImage): String =
    when (image) {
        is JobPhotoImage.Local -> "$subjectId/local/${image.path}"
        is JobPhotoImage.Backend -> "$subjectId/evidence/${image.jobId}/${image.photoId}"
    }
