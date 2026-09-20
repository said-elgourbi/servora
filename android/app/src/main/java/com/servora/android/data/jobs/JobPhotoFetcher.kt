package com.servora.android.data.jobs

import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.disk.DiskCache
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.request.CachePolicy
import coil3.request.Options
import com.servora.android.data.session.AuthenticatedSubject
import javax.inject.Inject
import javax.inject.Singleton
import okio.Buffer
import okio.FileSystem
import okio.Path.Companion.toPath

/**
 * Reads one photo's bytes for the image stack (`D4b`, `BR-015`, `BR-027`).
 *
 * The stack was told *what* to load by [JobPhotoImages]; this is *how* it is read, and it is the only
 * thing in the app that reads a photo for drawing:
 *
 * - a photo whose bytes are still on this device is read from the file that holds them, and nothing is
 *   cached — the bytes already are the cache (§9);
 * - evidence the backend holds is read from this session's own disk-cache entry when it is there, and
 *   through the API when it is not, keeping the `401 → renew once` path every other read of evidence
 *   has, so a session the API refuses is refused here exactly as it was on a tile (`BR-007`, `BR-018`).
 *
 * Before anything is read, the first read of a session releases the bytes cached for the session before
 * it (`JobPhotoImageCacheScope`), so what one member's session cached is never served to another's.
 *
 * A photo that cannot be read is answered by a failure that says **why** ([JobPhotoBytesUnavailable]):
 * the stack reports it to the surface that asked, which then says the photo is not readable without
 * connectivity rather than showing a broken control, and never a different picture (`D5`, `BR-042`).
 */
@Singleton
class JobPhotoFetcherFactory @Inject constructor(
    private val files: JobPhotoFiles,
    private val reader: JobPhotoContentReader,
    private val subject: AuthenticatedSubject,
    private val cacheScope: JobPhotoImageCacheScope,
) : Fetcher.Factory<JobPhotoImage> {

    override fun create(data: JobPhotoImage, options: Options, imageLoader: ImageLoader): Fetcher =
        JobPhotoFetcher(
            image = data,
            diskCache = imageLoader.diskCache,
            diskCacheKey = options.diskCacheKey,
            diskCachePolicy = options.diskCachePolicy,
            fileSystem = options.fileSystem,
            releaseCaches = {
                imageLoader.memoryCache?.clear()
                imageLoader.diskCache?.clear()
            },
            files = files,
            reader = reader,
            subject = subject,
            cacheScope = cacheScope,
        )
}

/**
 * The reader [JobPhotoFetcherFactory] builds for one request.
 *
 * It is handed the few things a read needs rather than the whole [Options] — and the release of the
 * stack's caches as an action rather than as the stack itself — so what it does with them (the cache
 * entry it may read and write, the file it reads a pending photo from, the session it renews, and when
 * the cached bytes of another session are let go) is verifiable on the JVM, where neither a Coil
 * `Options` nor an `ImageLoader` can be built (`qa.md` §6.1).
 */
internal class JobPhotoFetcher(
    private val image: JobPhotoImage,
    private val diskCache: DiskCache?,
    private val diskCacheKey: String?,
    private val diskCachePolicy: CachePolicy,
    private val fileSystem: FileSystem,
    private val releaseCaches: () -> Unit,
    private val files: JobPhotoFiles,
    private val reader: JobPhotoContentReader,
    private val subject: AuthenticatedSubject,
    private val cacheScope: JobPhotoImageCacheScope,
) : Fetcher {

    override suspend fun fetch(): FetchResult? {
        val subjectId = subject.current() ?: return null
        // The bytes another session cached are released before this one reads anything of its own
        // (`D4b`, `BR-007`).
        if (cacheScope.releaseRequiredFor(subjectId)) {
            releaseCaches()
        }
        return when (image) {
            is JobPhotoImage.Local -> localPhoto(image)
            is JobPhotoImage.Backend -> evidence(image)
        }
    }

    /** A photo the device still holds: the file itself, read where it is (`§9`). */
    private fun localPhoto(photo: JobPhotoImage.Local): FetchResult? =
        if (files.exists(photo.path)) {
            SourceFetchResult(
                source = ImageSource(file = photo.path.toPath(), fileSystem = fileSystem),
                mimeType = null,
                dataSource = DataSource.DISK,
            )
        } else {
            // A pending photo whose bytes are gone: a `Local` photo is never cache, so nothing evicted
            // it — it is reported as unreadable rather than as an offline photo, which would name the
            // wrong cause (`BR-014`, `BR-042`).
            throw JobPhotoBytesUnavailableException(JobPhotoBytesUnavailable.UNAVAILABLE)
        }

    /**
     * Evidence the backend holds: this session's cached copy when it has one, the API when it does not
     * (`BR-015`, `BR-031`).
     *
     * The cached copy is what makes a photo the technician has already opened readable offline; a photo
     * this device holds neither way is reported as such, with the reason the read gave (`D5`).
     */
    private suspend fun evidence(photo: JobPhotoImage.Backend): FetchResult? {
        val cache = diskCache
        val cacheKey = diskCacheKey
        if (cache != null && cacheKey != null && diskCachePolicy.readEnabled) {
            cache.openSnapshot(cacheKey)?.let { snapshot ->
                return SourceFetchResult(
                    source = ImageSource(
                        file = snapshot.data,
                        fileSystem = cache.fileSystem,
                        diskCacheKey = cacheKey,
                        closeable = snapshot,
                    ),
                    mimeType = null,
                    dataSource = DataSource.DISK,
                )
            }
        }

        val read = reader.read(photo.jobId, photo.photoId)
        val bytes = when (read) {
            is JobPhotoContentRead.Bytes -> read.bytes
            // The bytes are not here and the backend did not deliver them: which of the two it was is
            // what the technician is told, so it is carried out of the read rather than flattened
            // (`D5`, `BR-042`).
            JobPhotoContentRead.Unreachable ->
                throw JobPhotoBytesUnavailableException(JobPhotoBytesUnavailable.OFFLINE)

            JobPhotoContentRead.Unavailable ->
                throw JobPhotoBytesUnavailableException(JobPhotoBytesUnavailable.UNAVAILABLE)
        }
        if (cache != null && cacheKey != null && diskCachePolicy.writeEnabled) {
            writeToCache(cache, cacheKey, bytes)
        }
        return SourceFetchResult(
            source = ImageSource(
                source = Buffer().apply { write(bytes) },
                fileSystem = fileSystem,
            ),
            mimeType = null,
            dataSource = DataSource.NETWORK,
        )
    }

    /**
     * Stores the bytes this session read under its own key, so the next cold start — or the next tile
     * of the same Job — reads them from the device instead of downloading them again (`D4b`).
     *
     * That entry is what changes the cold-start measurement `D4` asked for: full-resolution bytes are
     * downloaded per photo **once**, rather than once per cold start. A cache write that fails is not an
     * error — the photo has already been read, and the cache is allowed to be evictable (`D4b`).
     */
    private fun writeToCache(cache: DiskCache, cacheKey: String, bytes: ByteArray) {
        runCatching {
            cache.openEditor(cacheKey)?.let { editor ->
                fileSystem.write(editor.data) { write(bytes) }
                editor.commit()
            }
        }
    }
}
