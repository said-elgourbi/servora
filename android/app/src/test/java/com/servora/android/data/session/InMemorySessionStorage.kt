package com.servora.android.data.session

import com.servora.android.domain.model.IssuedSession

/**
 * An in-memory [SessionStorage] for tests.
 *
 * It also stands in for persistence across a "restart": a second [SessionStore] over the same
 * instance sees what the first stored, which is exactly the behaviour the real encrypted storage
 * provides on disk.
 */
internal class InMemorySessionStorage : SessionStorage {

    var stored: IssuedSession? = null
        private set

    override fun read(): IssuedSession? = stored

    override fun write(session: IssuedSession) {
        stored = session
    }

    override fun clear() {
        stored = null
    }
}

/** A [SessionStore] backed by [InMemorySessionStorage], for tests outside this package. */
internal fun inMemorySessionStore(): SessionStore = SessionStore(InMemorySessionStorage())
