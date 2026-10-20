package com.servora.android.data.push

import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated storage in the test APK; never touches the signed-in app's installation proof. */
@RunWith(AndroidJUnit4::class)
class PushInstallationStorageTest {
    private val base = InstrumentationRegistry.getInstrumentation().context
    private val directory = File(base.noBackupFilesDir, "push-test-${UUID.randomUUID()}").apply { mkdirs() }
    private val context = object : ContextWrapper(base) {
        override fun getNoBackupFilesDir() = directory
    }
    private fun storage() = FilePushInstallationStorage(context, Json { ignoreUnknownKeys = true })

    @After fun cleanUp() { directory.deleteRecursively() }

    @Test fun identityRevisionAndBindingSurviveReopening() {
        val expected = PushInstallation.fresh().copy(
            revision = 7, bindingId = UUID.randomUUID().toString(),
            sessionId = UUID.randomUUID().toString(), needsTargetReset = false,
        )
        storage().write(expected)
        assertEquals(expected, storage().read())
        assertEquals(43, expected.secret.length)
    }

    @Test fun corruptStateRequestsFreshIdentityInsteadOfPartialRecovery() {
        File(directory, "push-installation.json").writeText("{incomplete")
        assertNull(storage().read())
        storage().write(PushInstallation.fresh())
        assertTrue(storage().read()!!.needsTargetReset)
    }

    @Test fun pendingTargetResetSurvivesProcessRestart() {
        val pending = PushInstallation.fresh()
        storage().write(pending)
        assertEquals(pending, storage().read())
        assertTrue(storage().read()!!.needsTargetReset)
    }
}
