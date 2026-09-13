package com.servora.android.data.session

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.servora.android.domain.model.AuthTokens
import com.servora.android.domain.model.IssuedSession
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [EncryptedSessionStorage] on a real device: the Android Keystore is only exercised there, which is
 * why this cannot be a JVM unit test.
 *
 * It covers the property the store depends on — a session written by one process is read back by
 * the next — and the at-rest guarantee, that the refresh token is not readable in the preferences
 * file.
 */
@RunWith(AndroidJUnit4::class)
class EncryptedSessionStorageTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val storage = EncryptedSessionStorage(context, Json { ignoreUnknownKeys = true })

    @Before
    fun clearStoredSession() {
        storage.clear()
    }

    @Test
    fun roundTripsASession() {
        storage.write(SESSION)

        assertEquals(SESSION, storage.read())
    }

    @Test
    fun returnsNullWhenNothingIsStored() {
        assertNull(storage.read())
    }

    @Test
    fun clearsTheStoredSession() {
        storage.write(SESSION)

        storage.clear()

        assertNull(storage.read())
    }

    @Test
    fun keepsTheRefreshTokenOutOfThePreferencesFile() {
        storage.write(SESSION)

        val ciphertext = preferences().getString(EncryptedSessionStorage.KEY_CIPHERTEXT, null)

        assertNotNull(ciphertext)
        assertFalse(ciphertext!!.contains(SESSION.tokens.refreshToken))
    }

    private fun preferences() =
        context.getSharedPreferences(EncryptedSessionStorage.PREFERENCES_NAME, Context.MODE_PRIVATE)

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
