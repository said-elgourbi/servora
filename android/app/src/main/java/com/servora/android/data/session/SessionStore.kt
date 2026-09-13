package com.servora.android.data.session

import com.servora.android.domain.model.IssuedSession
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Holds the session the backend most recently issued to this installation.
 *
 * Authenticated calls need the access token that sign-in produced, but nothing downstream of the
 * sign-in flow receives it. Keeping it here gives the credential one owner and one place to forget
 * when a session ends. The backend remains the authority for what the token may do (`BR-001`,
 * `BR-007`); this only carries it.
 *
 * The session is backed by [SessionStorage] so it survives process death (`BR-014`, `BR-031`): a
 * restart must not cost the user their session. The in-memory copy serves reads on any thread;
 * [restore], [store] and [clear] touch storage and so move off the main thread themselves, which
 * keeps every caller from having to remember to. [restore] is the only entry point that reads
 * storage back; a session written before that call is not overwritten by it, because a stored
 * session is only loaded once per process.
 */
@Singleton
class SessionStore @Inject constructor(
    private val storage: SessionStorage,
) {

    @Volatile
    private var session: IssuedSession? = null

    @Volatile
    private var loaded = false

    /**
     * Loads the persisted session into memory, once per process.
     *
     * Called at application start by `SessionManager`, before the authenticated UI can appear, so the
     * read happens on a background dispatcher and no later read is a disk access.
     */
    suspend fun restore() {
        if (loaded) {
            return
        }
        val stored = withContext(Dispatchers.IO) { storage.read() }
        synchronized(this) {
            if (!loaded) {
                session = stored
                loaded = true
            }
        }
    }

    /** The current session, or `null` when none is held. */
    fun current(): IssuedSession? = session

    /** The access token of the current session, or `null` when no session is held. */
    fun accessToken(): String? = session?.tokens?.accessToken

    /**
     * The refresh token of the current session, or `null` when no session is held.
     *
     * The raw token is the only copy that exists (`BR-046`): the backend stores a hash. It is used
     * to renew an access token the backend has refused, and is never logged.
     */
    fun refreshToken(): String? = session?.tokens?.refreshToken

    /** Remembers [session] as the current one and persists it. */
    suspend fun store(session: IssuedSession) {
        synchronized(this) {
            this.session = session
            loaded = true
        }
        withContext(Dispatchers.IO) { storage.write(session) }
    }

    /** Forgets the current session, in memory and on disk. */
    suspend fun clear() {
        synchronized(this) {
            session = null
            loaded = true
        }
        withContext(Dispatchers.IO) { storage.clear() }
    }
}
