package com.servora.android.data.session

import com.servora.android.domain.model.AuthTokens
import com.servora.android.domain.model.IssuedSession
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What [SessionStore] keeps in memory and what it leaves behind for the next process.
 *
 * This is the storage half of the restore bug: the session must survive a process restart, so the
 * tests assert that a *new* store over the same storage reads the session back, and that clearing
 * reaches the storage rather than only the in-memory copy.
 */
class SessionStoreTest {

    @Test
    fun `late session replacement cannot undo sign-out or a new account`() = runTest {
        val store = inMemorySessionStore()
        val old = session("old-access", "old-refresh")
        store.store(old)
        store.clear()
        assertFalse(store.replaceIfCurrent(old, old))
        assertNull(store.current())
        val newer = old.copy(sessionId = "new-session")
        store.store(newer)
        assertFalse(store.replaceIfCurrent(old, null))
        assertEquals(newer, store.current())
    }

    @Test
    fun `conditional refresh persists the new pair and emits observed state`() = runTest {
        val storage = InMemorySessionStorage()
        val store = SessionStore(storage)
        val old = session("old-access", "old-refresh")
        val newer = session("new-access", "new-refresh")
        store.store(old)
        assertTrue(store.replaceIfCurrent(old, newer))
        assertEquals(newer, storage.stored)
        assertEquals(newer, store.changes.value)
        assertFalse(store.replaceIfCurrent(old, null))
        assertTrue(store.replaceIfCurrent(newer, null))
        assertNull(storage.stored)
        assertNull(store.changes.value)
    }

    @Test
    fun `restored session is observable by registration`() = runTest {
        val storage = InMemorySessionStorage()
        val old = session("old-access", "old-refresh")
        storage.write(old)
        val store = SessionStore(storage)
        assertNull(store.changes.value)
        store.restore()
        assertEquals(old, store.changes.value)
    }

    @Test
    fun `holds nothing before a session is stored`() = runTest {
        val store = inMemorySessionStore()

        assertNull(store.current())
        assertNull(store.accessToken())
        assertNull(store.refreshToken())
    }

    @Test
    fun `persists the session it stores`() = runTest {
        val storage = InMemorySessionStorage()
        val store = SessionStore(storage)

        store.store(session("access-1", "refresh-1"))

        assertEquals(session("access-1", "refresh-1"), storage.stored)
    }

    @Test
    fun `restores a session that a previous process persisted`() = runTest {
        val storage = InMemorySessionStorage()
        SessionStore(storage).store(session("access-1", "refresh-1"))

        val reopened = SessionStore(storage)
        reopened.restore()

        assertEquals("access-1", reopened.accessToken())
        assertEquals("refresh-1", reopened.refreshToken())
        assertEquals(setOf("customers.view"), reopened.current()?.permissions)
    }

    @Test
    fun `does not overwrite an in-memory session with a later restore`() = runTest {
        val storage = InMemorySessionStorage()
        val store = SessionStore(storage)
        store.store(session("access-1", "refresh-1"))
        // Written behind the store's back, as another process could.
        storage.write(session("access-2", "refresh-2"))

        store.restore()

        assertEquals("access-1", store.accessToken())
    }

    @Test
    fun `clears the session in memory and on disk`() = runTest {
        val storage = InMemorySessionStorage()
        val store = SessionStore(storage)
        store.store(session("access-1", "refresh-1"))

        store.clear()

        assertNull(store.current())
        assertNull(storage.stored)
    }

    private fun session(accessToken: String, refreshToken: String) =
        IssuedSession(
            sessionId = "session-1",
            tokens = AuthTokens(
                accessToken = accessToken,
                accessTokenExpiresAt = "2026-01-01T00:10:00Z",
                refreshToken = refreshToken,
            ),
            permissions = setOf("customers.view"),
        )
}
