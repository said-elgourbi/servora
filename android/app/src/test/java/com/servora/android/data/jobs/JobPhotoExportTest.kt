package com.servora.android.data.jobs

import com.servora.android.data.session.SessionAuthenticator
import com.servora.android.data.session.SessionRenewal
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * How a photo leaves Servora: its bytes are read from wherever they are, and then written where the
 * technician asked (`D12`, `D13`).
 *
 * The reads are asserted where they happen — a pending photo from the file the device holds and never
 * from the API, evidence through the API — and so is the rule that decides what a copy is called and
 * what it refuses to write: an export must never produce a file Servora cannot account for, and a
 * device that cannot read the bytes must write nothing at all (`BR-042`).
 */
class JobPhotoExportTest {

    @Test
    fun `reads a photo this device holds from the file it holds, not from the API`() = runTest {
        val files = FakeJobPhotoFiles().apply {
            writeCapture(PENDING_PATH, FakeJobPhotoFiles.JPEG_BYTES)
        }
        val api = PhotoUploadApi()

        val photo = content(files = files, api = api)
            .read(JobPhotoExportSource.Local(photoId = "photo-1", path = PENDING_PATH))

        assertEquals(FakeJobPhotoFiles.JPEG_BYTES.toList(), photo?.bytes?.toList())
        assertEquals(JobPhotoContentType.JPEG, photo?.contentType)
        assertEquals("Servora-photo-1.jpg", photo?.fileName)
        assertEquals("a photo this device holds is never asked of the API", 0, api.contentCalls)
    }

    @Test
    fun `reads evidence through the API and proves its type from the bytes`() = runTest {
        val api = PhotoUploadApi()

        val photo = content(api = api)
            .read(JobPhotoExportSource.Evidence(photoId = "photo-2", jobId = JOB_ID))

        assertEquals(JobPhotoContentType.JPEG, photo?.contentType)
        assertEquals("Servora-photo-2.jpg", photo?.fileName)
        assertEquals("Bearer $ACCESS_TOKEN", api.lastAuthorization)
        assertEquals(1, api.contentCalls)
    }

    @Test
    fun `names the copy after the type the bytes were proven to be`() = runTest {
        val files = FakeJobPhotoFiles().apply {
            writeCapture(PENDING_PATH, FakeJobPhotoFiles.PNG_BYTES)
        }

        val photo = content(files = files)
            .read(JobPhotoExportSource.Local(photoId = "photo-3", path = PENDING_PATH))

        assertEquals(JobPhotoContentType.PNG, photo?.contentType)
        assertEquals("Servora-photo-3.png", photo?.fileName)
    }

    @Test
    fun `writes nothing for bytes Servora does not accept`() = runTest {
        val files = FakeJobPhotoFiles().apply {
            writeCapture(PENDING_PATH, FakeJobPhotoFiles.UNACCEPTED_BYTES)
        }

        assertNull(
            content(files = files)
                .read(JobPhotoExportSource.Local(photoId = "photo-1", path = PENDING_PATH)),
        )
    }

    @Test
    fun `writes nothing for a photo the device no longer holds`() = runTest {
        assertNull(
            content().read(JobPhotoExportSource.Local(photoId = "photo-1", path = PENDING_PATH)),
        )
    }

    @Test
    fun `writes nothing for evidence a refused session cannot read`() = runTest {
        val api = PhotoUploadApi()
        api.contentAnswer = { throw photoUnreachable() }

        assertNull(content(api = api).read(JobPhotoExportSource.Evidence("photo-2", JOB_ID)))
    }

    @Test
    fun `saves what the device wrote, and reports what it would not`() = runTest {
        val target = FakeExportTarget()
        val exporter = exporter(target = target)

        assertEquals(JobPhotoExportOutcome.SAVED, exporter.saveToDevice(localSource()))
        assertEquals("Servora-photo-1.jpg", target.lastFileName)

        target.saveResult = JobPhotoExportTarget.SaveResult.PERMISSION_REQUIRED
        assertEquals(JobPhotoExportOutcome.PERMISSION_REQUIRED, exporter.saveToDevice(localSource()))

        target.saveResult = JobPhotoExportTarget.SaveResult.FAILED
        assertEquals(JobPhotoExportOutcome.FAILED, exporter.saveToDevice(localSource()))
    }

    @Test
    fun `shares through the platform's chooser, and reports a device that cannot`() = runTest {
        val target = FakeExportTarget()
        val exporter = exporter(target = target)

        assertEquals(JobPhotoExportOutcome.SHARED, exporter.share(localSource(), CHOOSER_TITLE))
        assertEquals(CHOOSER_TITLE, target.lastChooserTitle)

        target.shareResult = JobPhotoExportTarget.ShareResult.NO_TARGET
        assertEquals(
            JobPhotoExportOutcome.NO_SHARE_TARGET,
            exporter.share(localSource(), CHOOSER_TITLE),
        )

        target.shareResult = JobPhotoExportTarget.ShareResult.FAILED
        assertEquals(JobPhotoExportOutcome.FAILED, exporter.share(localSource(), CHOOSER_TITLE))
    }

    @Test
    fun `never reaches the device when the bytes cannot be read`() = runTest {
        val target = FakeExportTarget()
        val exporter = exporter(files = FakeJobPhotoFiles(), target = target)

        assertEquals(JobPhotoExportOutcome.UNREADABLE, exporter.saveToDevice(localSource()))
        assertEquals(JobPhotoExportOutcome.UNREADABLE, exporter.share(localSource(), CHOOSER_TITLE))
        assertNull("nothing was written anywhere", target.lastFileName)
    }

    /** The reader the exports under test read through, over a device and an API a test decides. */
    private fun content(
        // Nothing on the device by default: a test that exports a photo the device holds says so by
        // passing the files it wrote (`qa.md` §6.1).
        files: JobPhotoFiles = FakeJobPhotoFiles(),
        api: JobDetailsApi = PhotoUploadApi(),
    ): JobPhotoExportContent = JobPhotoExportContent(
        files = files,
        reader = JobPhotoContentReader(api, FakeExportSessionAuthenticator()),
    )

    /** The exporter under test, with a target a test decides. */
    private fun exporter(
        files: JobPhotoFiles = FakeJobPhotoFiles().apply { writeCapture(PENDING_PATH, JPEG_BYTES) },
        target: JobPhotoExportTarget,
    ): JobPhotoExporter = DefaultJobPhotoExporter(content = content(files = files), target = target)

    /** One photo the device holds, as the viewer hands it to an export. */
    private fun localSource(): JobPhotoExportSource =
        JobPhotoExportSource.Local(photoId = "photo-1", path = PENDING_PATH)

    private companion object {
        const val JOB_ID = "job-1"
        const val ACCESS_TOKEN = "access-token"
        const val PENDING_PATH = "app-private/job-photos/user-1/photo-1.jpg"
        const val CHOOSER_TITLE = "Share photo"

        val JPEG_BYTES: ByteArray get() = FakeJobPhotoFiles.JPEG_BYTES
    }
}

/** A [SessionAuthenticator] with a session a test decides (`BR-018`). */
private class FakeExportSessionAuthenticator(
    private val accessToken: String? = "access-token",
    private val renewal: SessionRenewal = SessionRenewal.Rejected,
) : SessionAuthenticator {

    override fun accessToken(): String? = accessToken

    override suspend fun renew(rejectedToken: String): SessionRenewal = renewal
}

/** Where an export wrote, in memory, and what the device answers with (`qa.md` §6.1). */
private class FakeExportTarget(
    var saveResult: JobPhotoExportTarget.SaveResult = JobPhotoExportTarget.SaveResult.SAVED,
    var shareResult: JobPhotoExportTarget.ShareResult = JobPhotoExportTarget.ShareResult.SHARED,
) : JobPhotoExportTarget {

    var lastFileName: String? = null
    var lastBytes: ByteArray? = null
    var lastChooserTitle: String? = null

    override fun saveToGallery(photo: JobPhotoExportBytes): JobPhotoExportTarget.SaveResult {
        lastFileName = photo.fileName
        lastBytes = photo.bytes
        return saveResult
    }

    override fun share(
        photo: JobPhotoExportBytes,
        chooserTitle: String,
    ): JobPhotoExportTarget.ShareResult {
        lastFileName = photo.fileName
        lastBytes = photo.bytes
        lastChooserTitle = chooserTitle
        return shareResult
    }
}
