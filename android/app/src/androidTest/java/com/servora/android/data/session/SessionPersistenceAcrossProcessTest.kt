package com.servora.android.data.session

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.servora.android.domain.model.AuthTokens
import com.servora.android.domain.model.IssuedSession
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

/**
 * The exact reported scenario, on a real Android runtime: a session written by one process must be
 * readable by the next.
 *
 * The two methods are run as **separate instrumentation invocations** with an `am force-stop`
 * between them (see the QA runbook), so the read genuinely happens in a new process and re-opens
 * the Android Keystore and the preferences file. `EncryptedSessionStorageTest` cannot prove this:
 * it writes and reads inside one process.
 *
 * The methods are ordered so a single `connectedDebugAndroidTest` pass also works (write then read
 * in one process); the cross-process guarantee comes from the two-invocation run.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class SessionPersistenceAcrossProcessTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun step1WriteSessionForTheNextProcess() {
        storage().write(SESSION)
    }

    @Test
    fun step2ReadSessionWrittenByThePreviousProcess() {
        assertEquals(SESSION, storage().read())
    }

    private fun storage() = EncryptedSessionStorage(context, Json { ignoreUnknownKeys = true })

    private companion object {
        val SESSION = IssuedSession(
            sessionId = "session-1",
            tokens = AuthTokens(
                accessToken = "access-token",
                accessTokenExpiresAt = "2026-01-01T00:10:00Z",
                refreshToken = "refresh-token",
            ),
            permissions = setOf("customers.view"),
        )
    }
}
