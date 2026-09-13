package com.servora.android.data.session

import com.servora.android.domain.model.IssuedSession

/**
 * Durable storage for the session the backend most recently issued to this installation.
 *
 * The backend stays the system of record (`BR-001`); this only keeps the credential the client was
 * given so a killed process does not force the user to sign in again (`BR-014`). A refresh token is
 * returned to the client exactly once and only its hash exists server-side (`BR-046`), so the copy
 * this port stores is the only copy and must be encrypted at rest.
 *
 * The impl is deliberately synchronous and free of coroutine machinery: [SessionStore] owns the
 * dispatcher, so this contract stays a plain key-value read/write that a test can double.
 */
interface SessionStorage {
    /** The stored session, or `null` when none is stored or it cannot be read. */
    fun read(): IssuedSession?

    /** Persists [session], replacing the previously stored one. */
    fun write(session: IssuedSession)

    /** Removes the stored session. */
    fun clear()
}
