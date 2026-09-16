package com.servora.android.data.jobs

import coil3.decode.DataSource
import coil3.disk.DiskCache
import coil3.fetch.SourceFetchResult
import coil3.request.CachePolicy
import com.servora.android.data.session.AuthenticatedSubject
import com.servora.android.data.session.FakeAuthenticatedSubject
import com.servora.android.data.session.SessionAuthenticator
import com.servora.android.data.session.SessionRenewal
import java.io.File
import java.io.IOException
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import retrofit2.HttpException
import retrofit2.Response

/**
 * How the image stack reads a photo for drawing (`D4b`, `BR-015`, `BR-031`).
 *
 * The reads are asserted where they happen: a pending photo comes from the file the device holds and
 * never from the API, evidence comes from this session's own cached copy once it has one (which is
 * `D4`'s measurement — full-resolution bytes per photo *once*, rather than once per cold start), a
 * session the API refuses is renewed once exactly as every other read of evidence renews, and a read
 * that cannot deliver the bytes reports **why** — which is what lets a surface say a photo is not
 * available offline rather than that it failed (`D5`, `BR-042`).
 *
 * The cache is the library's own, built in a directory this test owns, because the entry the fetcher
 * writes and reads back is the behaviour under test (`qa.md` §6.1), and the key the fetcher is handed
 * is built by the same rule the port builds it with ([jobPhotoImageDiskCacheKey]) — so the policy that
 * a photo this device holds is never a cache entry is asserted here rather than assumed (`D5`, `§9`).
 */
class JobPhotoFetcherTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `reads a pending photo from the file this device holds`() = runTest {
        val photo = File(temporaryFolder.root, "photo-1.jpg")
        photo.writeBytes(FakeJobPhotoFiles.JPEG_BYTES)
        val files = FakeJobPhotoFiles().apply { writeCapture(photo.path, FakeJobPhotoFiles.JPEG_BYTES) }
        val api = PhotoUploadApi()

        val result = fetcher(image = JobPhotoImage.Local(photo.path), files = files, api = api).fetch()

        val source = result as SourceFetchResult
        assertEquals(DataSource.DISK, source.dataSource)
        assertEquals(
            FakeJobPhotoFiles.JPEG_BYTES.toList(),
            source.source.source().readByteArray().toList(),
        )
        assertEquals("A photo this device holds is never asked of the API", 0, api.contentCalls)
    }

    @Test
    fun `reports a photo this device no longer holds as unreadable`() = runTest {
        val failure = failureOf { fetcher(image = JobPhotoImage.Local(PENDING_PATH)).fetch() }

        // It is not an offline photo: a `Local` photo is never cache, so nothing evicted it (`§9`).
        assertEquals(JobPhotoBytesUnavailable.UNAVAILABLE, failure.reason)
    }

    @Test
    fun `reads evidence from the API when this session has not cached it`() = runTest {
        val api = PhotoUploadApi()

        val result = fetcher(image = EVIDENCE, api = api).fetch()

        assertEquals(DataSource.NETWORK, (result as SourceFetchResult).dataSource)
        assertEquals("Bearer $ACCESS_TOKEN", api.lastAuthorization)
        assertEquals(1, api.contentCalls)
    }

    @Test
    fun `reads this session's cached copy rather than downloading the photo again`() = runTest {
        val api = PhotoUploadApi()
        val cache = diskCache()

        val first = fetcher(image = EVIDENCE, api = api, diskCache = cache).fetch()

        assertNotNull(first)
        assertEquals(DataSource.NETWORK, (first as SourceFetchResult).dataSource)

        val second = fetcher(image = EVIDENCE, api = api, diskCache = cache).fetch()

        assertEquals(DataSource.DISK, (second as SourceFetchResult).dataSource)
        assertEquals("A cached photo must not be downloaded again (`D4b`)", 1, api.contentCalls)
    }

    @Test
    fun `keys the cache per session, so one member's evidence is not another's`() = runTest {
        val api = PhotoUploadApi()
        val cache = diskCache()

        fetcher(image = EVIDENCE, api = api, diskCache = cache, subjectId = "user-2").fetch()
        val mine = fetcher(image = EVIDENCE, api = api, diskCache = cache, subjectId = "user-1").fetch()

        assertEquals(DataSource.NETWORK, (mine as SourceFetchResult).dataSource)
        assertEquals("Another session's entry must not be read (`BR-007`)", 2, api.contentCalls)
    }

    @Test
    fun `releases what a previous session cached before it reads`() = runTest {
        var releases = 0
        val scope = JobPhotoImageCacheScope()
        val release = { releases += 1 }

        fetcher(image = EVIDENCE, cacheScope = scope, releaseCaches = release, subjectId = "user-1").fetch()
        fetcher(image = EVIDENCE, cacheScope = scope, releaseCaches = release, subjectId = "user-1").fetch()
        fetcher(image = EVIDENCE, cacheScope = scope, releaseCaches = release, subjectId = "user-2").fetch()

        assertEquals("Once per session, not once per read", 2, releases)
    }

    @Test
    fun `answers nothing when there is no session to attribute the photo to`() = runTest {
        val result = fetcher(image = EVIDENCE, subjectId = null).fetch()

        assertNull(result)
    }

    @Test
    fun `renews the session once when the API refuses the photo`() = runTest {
        val api = PhotoUploadApi()
        var reads = 0
        api.contentAnswer = {
            reads += 1
            if (reads == 1) {
                throw HttpException(Response.error<ResponseBody>(401, REFUSED_BODY))
            }
            FakeJobPhotoFiles.JPEG_BYTES.toResponseBody("image/jpeg".toMediaType())
        }
        val authenticator = FakePhotoSessionAuthenticator(
            accessToken = "stale-token",
            renewal = SessionRenewal.Renewed("fresh-token"),
        )

        val result = fetcher(image = EVIDENCE, api = api, sessionAuthenticator = authenticator).fetch()

        assertNotNull(result)
        assertEquals("The refused read is retried once", 2, api.contentCalls)
        assertEquals("Bearer fresh-token", api.lastAuthorization)
    }

    @Test
    fun `reports evidence a refused session cannot read as unreadable`() = runTest {
        val api = PhotoUploadApi()
        api.contentAnswer = { throw HttpException(Response.error<ResponseBody>(401, REFUSED_BODY)) }
        val authenticator =
            FakePhotoSessionAuthenticator(accessToken = "stale-token", renewal = SessionRenewal.Rejected)

        val failure = failureOf {
            fetcher(image = EVIDENCE, api = api, sessionAuthenticator = authenticator).fetch()
        }

        // The backend answered — it refused — so this is not a connectivity problem (`BR-007`).
        assertEquals(JobPhotoBytesUnavailable.UNAVAILABLE, failure.reason)
    }

    @Test
    fun `reports evidence it cannot get offline as unavailable offline`() = runTest {
        val api = PhotoUploadApi()
        api.contentAnswer = { throw IOException("offline") }

        val failure = failureOf { fetcher(image = EVIDENCE, api = api).fetch() }

        // This is the state `D5` names: the bytes are neither on this device nor in the cache, and the
        // backend could not be reached, so the technician is told to reconnect (`BR-013`).
        assertEquals(JobPhotoBytesUnavailable.OFFLINE, failure.reason)
    }

    @Test
    fun `reports a backend that answered with a server failure as offline too`() = runTest {
        val api = PhotoUploadApi()
        api.contentAnswer = { throw HttpException(Response.error<ResponseBody>(503, REFUSED_BODY)) }

        val failure = failureOf { fetcher(image = EVIDENCE, api = api).fetch() }

        assertEquals(JobPhotoBytesUnavailable.OFFLINE, failure.reason)
    }

    @Test
    fun `reports a refusal that is not a 401 as unreadable, and does not renew`() = runTest {
        val api = PhotoUploadApi()
        api.contentAnswer = { throw HttpException(Response.error<ResponseBody>(403, REFUSED_BODY)) }
        val authenticator = FakePhotoSessionAuthenticator(
            accessToken = ACCESS_TOKEN,
            renewal = SessionRenewal.Renewed("fresh-token"),
        )

        val failure = failureOf {
            fetcher(image = EVIDENCE, api = api, sessionAuthenticator = authenticator).fetch()
        }

        assertEquals(JobPhotoBytesUnavailable.UNAVAILABLE, failure.reason)
        assertEquals(1, api.contentCalls)
    }

    @Test
    fun `writes nothing to the cache when the read did not deliver the photo`() = runTest {
        val api = PhotoUploadApi()
        api.contentAnswer = { throw IOException("offline") }
        val cache = diskCache()

        failureOf { fetcher(image = EVIDENCE, api = api, diskCache = cache).fetch() }

        // A failure must not leave anything behind: the cache only ever holds bytes a session read, so a
        // failed read cannot evict a photo that is in it (`D5`, `§9`).
        assertEquals(0L, cache.size)
    }

    @Test
    fun `never puts a photo this device holds into the cache`() = runTest {
        val photo = File(temporaryFolder.root, "photo-2.jpg")
        photo.writeBytes(FakeJobPhotoFiles.JPEG_BYTES)
        val files = FakeJobPhotoFiles().apply { writeCapture(photo.path, FakeJobPhotoFiles.JPEG_BYTES) }
        val cache = diskCache()

        val result = fetcher(
            image = JobPhotoImage.Local(photo.path),
            files = files,
            diskCache = cache,
        ).fetch()

        assertEquals(DataSource.DISK, (result as SourceFetchResult).dataSource)
        // The file *is* the copy: a pending upload's bytes are app-private and no size policy evicts
        // them, which is what `BR-014` requires (`jobPhotoImageDiskCacheKey`).
        assertEquals(0L, cache.size)
    }

    /** The fetcher one read is asserted through, with everything a device would supply replaced. */
    private fun fetcher(
        image: JobPhotoImage,
        files: JobPhotoFiles = FakeJobPhotoFiles(),
        api: JobDetailsApi = PhotoUploadApi(),
        diskCache: DiskCache? = null,
        subjectId: String? = SUBJECT_ID,
        sessionAuthenticator: SessionAuthenticator = FakePhotoSessionAuthenticator(),
        releaseCaches: () -> Unit = {},
        cacheScope: JobPhotoImageCacheScope = JobPhotoImageCacheScope(),
    ): JobPhotoFetcher = JobPhotoFetcher(
        image = image,
        diskCache = diskCache,
        // The port's own rule, so a photo this device holds is handed no disk key here either.
        diskCacheKey = subjectId?.let { jobPhotoImageDiskCacheKey(it, image) },
        diskCachePolicy = CachePolicy.ENABLED,
        fileSystem = FileSystem.SYSTEM,
        releaseCaches = releaseCaches,
        files = files,
        reader = JobPhotoContentReader(api, sessionAuthenticator),
        subject = FakeAuthenticatedSubject(subjectId),
        cacheScope = cacheScope,
    )

    /** The failure a read reported, so the reason it carried out of the read can be asserted. */
    private suspend fun failureOf(
        read: suspend () -> Any?,
    ): JobPhotoBytesUnavailableException {
        val failure = runCatching { read() }.exceptionOrNull()
        assertTrue("expected a photo failure, got $failure", failure is JobPhotoBytesUnavailableException)
        return failure as JobPhotoBytesUnavailableException
    }

    /** The library's disk cache, in a directory this test owns and removes (`D4b`). */
    private fun diskCache(): DiskCache =
        DiskCache.Builder().directory(temporaryFolder.newFolder("image-cache").toOkioPath()).build()

    private companion object {
        const val SUBJECT_ID = "user-1"
        const val ACCESS_TOKEN = "access-token"
        const val PENDING_PATH = "app-private/job-photos/user-1/photo-1.jpg"

        val EVIDENCE = JobPhotoImage.Backend(jobId = "job-1", photoId = "photo-1")

        /** The body a refusal carries; nothing reads it, but an error response needs one. */
        val REFUSED_BODY: ResponseBody = "".toResponseBody("application/json".toMediaType())
    }
}

/** A [SessionAuthenticator] a test decides, so a refusal and its renewal are the only variable. */
private class FakePhotoSessionAuthenticator(
    private val accessToken: String? = "access-token",
    private val renewal: SessionRenewal = SessionRenewal.Rejected,
) : SessionAuthenticator {

    override fun accessToken(): String? = accessToken

    override suspend fun renew(rejectedToken: String): SessionRenewal = renewal
}
