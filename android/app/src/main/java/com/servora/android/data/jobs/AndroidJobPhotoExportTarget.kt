package com.servora.android.data.jobs

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Where an export writes on a device: the shared gallery, or the platform's share sheet (`D12`, `D13`).
 *
 * The device's media library is **written to** and never read: Servora still takes a photo it does not
 * hold from the device's own picker, and asks no media-read permission (`D10`). Saving puts the photo
 * the technician is looking at into the gallery album the platform shows beside its own, and sharing
 * hands a copy to whichever application they choose — and in both, the user decides and Servora names
 * no provider (`BR-042`).
 */
@Singleton
internal class AndroidJobPhotoExportTarget @Inject constructor(
    @ApplicationContext private val context: Context,
) : JobPhotoExportTarget {

    /**
     * Writes the photo into the device's own gallery.
     *
     * From API 29 an app may insert into the shared image collection without any permission, so the
     * photo lands in `Pictures/Servora`, and `IS_PENDING` keeps it out of the gallery until all of its
     * bytes are there. On API 26–28 the same write needs `WRITE_EXTERNAL_STORAGE`, so a save whose
     * permission has not been granted is reported as such and asked for, rather than failing quietly
     * (`D12`).
     */
    override fun saveToGallery(photo: JobPhotoExportBytes): JobPhotoExportTarget.SaveResult =
        try {
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> writeToSharedGallery(photo)
                hasLegacyGalleryPermission() -> writeToLegacyGallery(photo)
                else -> JobPhotoExportTarget.SaveResult.PERMISSION_REQUIRED
            }
        } catch (failure: IOException) {
            JobPhotoExportTarget.SaveResult.FAILED
        } catch (failure: SecurityException) {
            JobPhotoExportTarget.SaveResult.FAILED
        }

    /** The API 29+ write: an insert into the shared collection, with the bytes streamed into it. */
    private fun writeToSharedGallery(photo: JobPhotoExportBytes): JobPhotoExportTarget.SaveResult {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, photo.fileName)
            put(MediaStore.Images.Media.MIME_TYPE, photo.contentType.mimeType)
            put(MediaStore.Images.Media.RELATIVE_PATH, GALLERY_DIRECTORY)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values) ?: return JobPhotoExportTarget.SaveResult.FAILED
        return try {
            val output = resolver.openOutputStream(uri)
                ?: throw IOException("the inserted photo has no stream")
            output.use { stream -> stream.write(photo.bytes) }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            JobPhotoExportTarget.SaveResult.SAVED
        } catch (failure: IOException) {
            // A half-written photo must not be left in the gallery for the user to find.
            resolver.delete(uri, null, null)
            JobPhotoExportTarget.SaveResult.FAILED
        }
    }

    /**
     * The API 26–28 write: no `RELATIVE_PATH` exists, so the file is written where the platform keeps
     * photos and then registered, so the gallery sees it immediately.
     */
    @Suppress("DEPRECATION")
    private fun writeToLegacyGallery(photo: JobPhotoExportBytes): JobPhotoExportTarget.SaveResult {
        val directory = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            GALLERY_NAME,
        )
        if (!directory.exists() && !directory.mkdirs()) {
            return JobPhotoExportTarget.SaveResult.FAILED
        }
        val file = File(directory, photo.fileName)
        file.writeBytes(photo.bytes)
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, photo.fileName)
            put(MediaStore.Images.Media.MIME_TYPE, photo.contentType.mimeType)
            put(MediaStore.Images.Media.DATA, file.absolutePath)
        }
        context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        return JobPhotoExportTarget.SaveResult.SAVED
    }

    /** Whether the storage permission API 26–28 needs for a shared write has been granted (`D12`). */
    private fun hasLegacyGalleryPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Hands the photo to the application the technician chooses.
     *
     * The bytes are staged in the one directory the app's `FileProvider` exposes and nothing else is
     * (`res/xml/file_paths.xml`), and that directory is cleared before each export so what a previous
     * share left behind cannot accumulate. The receiving application is granted read access to that one
     * file and to nothing else (`D13`).
     */
    override fun share(
        photo: JobPhotoExportBytes,
        chooserTitle: String,
    ): JobPhotoExportTarget.ShareResult {
        val uri = try {
            shareableUri(photo)
        } catch (failure: IOException) {
            return JobPhotoExportTarget.ShareResult.FAILED
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = photo.contentType.mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        // The chooser is started from the application context, so it needs its own task.
        val chooser = Intent.createChooser(send, chooserTitle).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(chooser)
            JobPhotoExportTarget.ShareResult.SHARED
        } catch (failure: ActivityNotFoundException) {
            JobPhotoExportTarget.ShareResult.NO_TARGET
        }
    }

    /** Stages one photo for the share sheet and answers the content URI it is handed over with. */
    private fun shareableUri(photo: JobPhotoExportBytes): Uri {
        val directory = File(context.cacheDir, SHARE_DIRECTORY)
        directory.listFiles()?.forEach { stale -> stale.delete() }
        if (!directory.exists() && !directory.mkdirs()) {
            throw IOException("no directory to stage the shared photo in")
        }
        val file = File(directory, photo.fileName)
        file.writeBytes(photo.bytes)
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    private companion object {
        /** The album a saved photo appears in, beside the platform's own Pictures folders. */
        const val GALLERY_NAME = "Servora"

        /** The album's path inside the shared storage, as the API 29+ write names it. */
        val GALLERY_DIRECTORY = "${Environment.DIRECTORY_PICTURES}/$GALLERY_NAME"

        /** The app-private directory a shared photo is staged in before the chooser opens (`D13`). */
        const val SHARE_DIRECTORY = "evidence-share"
    }
}
