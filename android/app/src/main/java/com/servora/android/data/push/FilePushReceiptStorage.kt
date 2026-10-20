package com.servora.android.data.push

import android.content.Context
import android.util.AtomicFile
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileNotFoundException
import javax.inject.Inject
import javax.inject.Singleton

/** No backup: receipt history must never cross installations. Contains no credentials or payload. */
@Singleton
class FilePushReceiptStorage @Inject constructor(
    @ApplicationContext context: Context,
) : PushReceiptStorage {
    private val file = AtomicFile(File(context.noBackupFilesDir, "push-receipts.json"))

    override fun read(): String? = try {
        file.openRead().use { it.readBytes().decodeToString() }
    } catch (failure: FileNotFoundException) {
        // An unreadable existing record is not an empty history.
        if (file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()) throw failure
        null
    }

    override fun write(value: String) {
        val stream = file.startWrite()
        try {
            stream.write(value.encodeToByteArray())
            file.finishWrite(stream)
        } catch (failure: Exception) {
            file.failWrite(stream)
            throw failure
        }
    }
}
