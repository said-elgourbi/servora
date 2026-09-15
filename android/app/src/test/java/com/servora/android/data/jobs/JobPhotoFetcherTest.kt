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
 * session the API refuses is renewed once exactly as every other read of evidence renews, and a photo
 * that cannot be read answers with nothing to draw rather than with an exception (`BR-042`).
 *
 * The cache is the library's own, built in a directory this test owns, because the entry the fetcher
 * writes and reads back is the behaviour under test (`qa.md` §6.1).
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
    fun `answers nothing for a photo the device no longer holds`() = runTest {
        val result = fetcher(image = JobPhotoImage.Local(PENDING_PATH)).fetch()

        assertNull(result)
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
    fun `answers nothing for evidence a refused session cannot read`() = runTest {
        val api = PhotoUploadApi()
        api.contentAnswer = { throw HttpException(Response.error<ResponseBody>(401, REFUSED_BODY)) }
        val authenticator =
            FakePhotoSessionAuthenticator(accessToken = "stale-token", renewal = SessionRenewal.Rejected)

        assertNull(fetcher(image = EVIDENCE, api = api, sessionAuthenticator = authenticator).fetch())
    }

    @Test
    fun `answers nothing when the backend cannot be reached`() = runTest {
        val api = PhotoUploadApi()
        api.contentAnswer = { throw IOException("offline") }

        assertNull(fetcher(image = EVIDENCE, api = api).fetch())
    }

    @Test
    fun `answers nothing, and does not renew, for a refusal that is not a 401`() = runTest {
        val api = PhotoUploadApi()
        api.contentAnswer = { throw HttpException(Response.error<ResponseBody>(403, REFUSED_BODY)) }
        val authenticator = FakePhotoSessionAuthenticator(
            accessToken = ACCESS_TOKEN,
            renewal = SessionRenewal.Renewed("fresh-token"),
        )

        assertNull(fetcher(image = EVIDENCE, api = api, sessionAuthenticator = authenticator).fetch())
        assertEquals(1, api.contentCalls)
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
        diskCacheKey = subjectId?.let { jobPhotoImageCacheKey(it, image) },
        diskCachePolicy = CachePolicy.ENABLED,
        fileSystem = FileSystem.SYSTEM,
        releaseCaches = releaseCaches,
        files = files,
        api = api,
        sessionAuthenticator = sessionAuthenticator,
        subject = FakeAuthenticatedSubject(subjectId),
        cacheScope = cacheScope,
    )

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
