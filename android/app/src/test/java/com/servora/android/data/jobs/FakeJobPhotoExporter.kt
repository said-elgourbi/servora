package com.servora.android.data.jobs

/**
 * A [JobPhotoExporter] a test decides (`qa.md` §6.1).
 *
 * What a save or a share answers is the only variable these tests need, and every request is recorded
 * so they can assert which photo was exported and from where — the device's own bytes or the API — and
 * that no export was attempted at all when the screen may not do it.
 */
class FakeJobPhotoExporter(
    var saveOutcome: JobPhotoExportOutcome = JobPhotoExportOutcome.SAVED,
    var shareOutcome: JobPhotoExportOutcome = JobPhotoExportOutcome.SHARED,
) : JobPhotoExporter {

    /** Every photo a save was asked for, in the order it was asked. */
    val saved = mutableListOf<JobPhotoExportSource>()

    /** Every photo a share was asked for, in the order it was asked. */
    val shared = mutableListOf<JobPhotoExportSource>()

    /** The title the last share handed to the platform's chooser. */
    var lastChooserTitle: String? = null

    override suspend fun saveToDevice(source: JobPhotoExportSource): JobPhotoExportOutcome {
        saved += source
        return saveOutcome
    }

    override suspend fun share(
        source: JobPhotoExportSource,
        chooserTitle: String,
    ): JobPhotoExportOutcome {
        shared += source
        lastChooserTitle = chooserTitle
        return shareOutcome
    }
}
