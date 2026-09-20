package com.servora.android.data.jobs

import com.servora.android.data.offline.OfflineSync
import com.servora.android.data.offline.OutboxFailureReason
import com.servora.android.data.offline.OutboxOperation
import com.servora.android.data.offline.OutboxStore
import com.servora.android.data.session.AuthenticatedSubject
import com.servora.android.domain.model.EvidencePhase
import com.servora.android.domain.model.PendingJobPhoto
import java.io.File
import java.time.Clock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * A photo session that holds nothing, for instrumented tests about something else.
 *
 * The Job Details destination is wired by the application shell, so a navigation test has to build the
 * ViewModel that destination uses. These tests are about where navigation goes, not about photo
 * evidence, so every collaborator here answers the smallest honest thing: no pending photos, no bytes
 * and no uploads (`qa.md` §6.2).
 */
fun inertJobPhotoSession(): JobPhotoSession = JobPhotoSession(
    pending = InertPendingJobPhotoStore,
    files = InertJobPhotoFiles,
    processing = InertJobPhotoProcessing,
    outbox = InertOutboxStore,
    subject = InertSubject,
    offlineSync = InertOfflineSync,
    clock = Clock.systemUTC(),
)

private object InertPendingJobPhotoStore : PendingJobPhotoStore {
    override fun pending(subjectId: String, jobId: String): Flow<List<PendingJobPhoto>> =
        flowOf(emptyList())

    override suspend fun record(photo: PendingJobPhoto) = Unit

    override suspend fun updateReview(photoId: String, phase: EvidencePhase?, note: String?) = Unit

    override suspend fun submit(photo: PendingJobPhoto): Boolean = false

    override suspend fun find(photoId: String): PendingJobPhoto? = null

    override suspend fun remove(photoId: String) = Unit
}

private object InertJobPhotoFiles : JobPhotoFiles {
    override fun fileFor(
        subjectId: String,
        photoId: String,
        contentType: JobPhotoContentType,
    ): File = File("app-private/job-photos/$subjectId/$photoId.${contentType.extension}")

    override fun exists(path: String): Boolean = false

    override fun readBytes(path: String): ByteArray? = null

    override fun write(path: String, bytes: ByteArray): Boolean = false

    override fun delete(path: String) = Unit
}

private object InertJobPhotoProcessing : JobPhotoProcessing {
    override suspend fun convertToJpeg(bytes: ByteArray): ByteArray? = null

    override suspend fun fitToUploadLimit(bytes: ByteArray): ByteArray? = null
}

internal object InertOutboxStore : OutboxStore {
    override suspend fun record(operation: OutboxOperation) = Unit

    override suspend fun head(subjectId: String): OutboxOperation? = null

    override suspend fun markInFlight(operationId: String) = Unit

    override suspend fun markApplied(operationId: String) = Unit

    override suspend fun markRetryable(
        operationId: String,
        reason: OutboxFailureReason,
        at: Long,
    ) = Unit

    override suspend fun markRejected(
        operationId: String,
        reason: OutboxFailureReason,
        at: Long,
    ) = Unit

    override suspend fun discardRefused(operationId: String) = Unit

    override suspend fun recoverInFlight() = Unit

    override suspend fun awaitingCount(subjectId: String): Int = 0

    override suspend fun rejected(subjectId: String): List<OutboxOperation> = emptyList()

    override suspend fun queuedFor(subjectId: String, targetId: String): List<OutboxOperation> =
        emptyList()
}

internal object InertSubject : AuthenticatedSubject {
    override fun current(): String? = "inert-subject"
}

internal object InertOfflineSync : OfflineSync {
    override fun requestSync() = Unit
}

/**
 * An exporter that writes nothing, for instrumented tests about something else.
 *
 * The Job Details destination is wired with one, so a test about where navigation goes has nothing to
 * export: every call answers the smallest honest thing — the photo's bytes could not be read, which is
 * what a device with no evidence and no session would reach (`qa.md` §6.2).
 */
fun inertJobPhotoExporter(): JobPhotoExporter = object : JobPhotoExporter {
    override suspend fun saveToDevice(source: JobPhotoExportSource): JobPhotoExportOutcome =
        JobPhotoExportOutcome.UNREADABLE

    override suspend fun share(
        source: JobPhotoExportSource,
        chooserTitle: String,
    ): JobPhotoExportOutcome = JobPhotoExportOutcome.UNREADABLE
}
