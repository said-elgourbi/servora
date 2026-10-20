package com.servora.android.data.jobs

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** Reads picked quote PDFs and stages downloaded quote PDFs for the device's own viewer. */
interface JobQuoteDocuments {
    fun readPicked(uri: String): JobQuoteDocumentPick
    fun open(jobId: String, fileName: String, bytes: ByteArray): JobQuoteDocumentOpen
}

data class PickedJobQuoteDocument(
    val fileName: String,
    val bytes: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is PickedJobQuoteDocument &&
            fileName == other.fileName &&
            bytes.contentEquals(other.bytes))

    override fun hashCode(): Int = 31 * fileName.hashCode() + bytes.contentHashCode()
}

sealed interface JobQuoteDocumentPick {
    data class Picked(val document: PickedJobQuoteDocument) : JobQuoteDocumentPick
    data object NotPdf : JobQuoteDocumentPick
    data object Unreadable : JobQuoteDocumentPick
}

sealed interface JobQuoteDocumentOpen {
    data object Opened : JobQuoteDocumentOpen
    data object Failed : JobQuoteDocumentOpen
    data object NoViewer : JobQuoteDocumentOpen
}

object UnavailableJobQuoteDocuments : JobQuoteDocuments {
    override fun readPicked(uri: String): JobQuoteDocumentPick = JobQuoteDocumentPick.Unreadable

    override fun open(jobId: String, fileName: String, bytes: ByteArray): JobQuoteDocumentOpen =
        JobQuoteDocumentOpen.Failed
}

@Singleton
internal class AndroidJobQuoteDocuments @Inject constructor(
    @ApplicationContext private val context: Context,
) : JobQuoteDocuments {
    override fun readPicked(uri: String): JobQuoteDocumentPick {
        val parsed = Uri.parse(uri)
        val resolver = context.contentResolver
        val mimeType = resolver.getType(parsed)
        if (mimeType != null && mimeType != PDF_MIME_TYPE) {
            return JobQuoteDocumentPick.NotPdf
        }
        return try {
            val bytes = resolver.openInputStream(parsed)?.use { stream -> stream.readBytes() }
                ?: return JobQuoteDocumentPick.Unreadable
            if (!bytes.isPdf()) {
                return JobQuoteDocumentPick.NotPdf
            }
            JobQuoteDocumentPick.Picked(
                PickedJobQuoteDocument(
                    fileName = parsed.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
                        ?: DEFAULT_FILE_NAME,
                    bytes = bytes,
                ),
            )
        } catch (failure: IOException) {
            JobQuoteDocumentPick.Unreadable
        } catch (failure: SecurityException) {
            JobQuoteDocumentPick.Unreadable
        }
    }

    override fun open(jobId: String, fileName: String, bytes: ByteArray): JobQuoteDocumentOpen {
        val uri = try {
            stagedUri(jobId, fileName, bytes)
        } catch (failure: IOException) {
            return JobQuoteDocumentOpen.Failed
        } catch (failure: IllegalArgumentException) {
            return JobQuoteDocumentOpen.Failed
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, PDF_MIME_TYPE)
            clipData = ClipData.newUri(context.contentResolver, fileName, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            JobQuoteDocumentOpen.Opened
        } catch (failure: ActivityNotFoundException) {
            JobQuoteDocumentOpen.NoViewer
        } catch (failure: SecurityException) {
            JobQuoteDocumentOpen.Failed
        } catch (failure: IllegalArgumentException) {
            JobQuoteDocumentOpen.Failed
        }
    }

    private fun stagedUri(jobId: String, fileName: String, bytes: ByteArray): Uri {
        val directory = File(context.cacheDir, "$DIRECTORY/${jobId.safePathPart()}")
        directory.listFiles()?.forEach { stale -> stale.delete() }
        if (!directory.exists() && !directory.mkdirs()) {
            throw IOException("no directory to stage the quote document")
        }
        val file = File(directory, fileName.safeFileName())
        file.writeBytes(bytes)
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    private companion object {
        const val DIRECTORY = "job-documents"
        const val DEFAULT_FILE_NAME = "quote.pdf"
    }
}

private const val PDF_MIME_TYPE = "application/pdf"

private fun ByteArray.isPdf(): Boolean =
    size >= 5 &&
        this[0] == 0x25.toByte() &&
        this[1] == 0x50.toByte() &&
        this[2] == 0x44.toByte() &&
        this[3] == 0x46.toByte() &&
        this[4] == 0x2d.toByte()

private fun String.safeFileName(): String {
    val cleaned = replace(Regex("[^A-Za-z0-9._-]"), "_").take(80).trim('.', '_')
    return cleaned.takeIf { it.isNotBlank() && it.endsWith(".pdf", ignoreCase = true) }
        ?: "quote.pdf"
}

private fun String.safePathPart(): String =
    replace(Regex("[^A-Za-z0-9._-]"), "_").take(80).ifBlank { "job" }
