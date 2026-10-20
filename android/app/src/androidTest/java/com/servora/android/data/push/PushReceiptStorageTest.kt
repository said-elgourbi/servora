package com.servora.android.data.push

import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated test-APK storage, independent of the signed-in application's state. */
@RunWith(AndroidJUnit4::class)
class PushReceiptStorageTest {
    private val base = InstrumentationRegistry.getInstrumentation().context
    private val directory = File(base.noBackupFilesDir, "receipts-test-${UUID.randomUUID()}").apply { mkdirs() }
    private val context = object : ContextWrapper(base) {
        override fun getNoBackupFilesDir() = directory
    }

    @After fun cleanUp() { directory.deleteRecursively() }

    @Test fun receiptRecordSurvivesReopening() {
        assertNull(FilePushReceiptStorage(context).read())
        FilePushReceiptStorage(context).write("[]")
        assertEquals("[]", FilePushReceiptStorage(context).read())
    }

    @Test fun failedAtomicReplacementRestoresPreviousRecord() {
        val storage = FilePushReceiptStorage(context)
        storage.write("[]")
        // Simulate process death during the next write: AtomicFile must preserve the committed file.
        val atomic = android.util.AtomicFile(File(directory, "push-receipts.json"))
        atomic.startWrite().use { it.write("{incomplete".toByteArray()) }
        assertEquals("[]", FilePushReceiptStorage(context).read())
    }
}
