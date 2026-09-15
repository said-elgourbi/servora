package com.servora.android.data.offline

import com.servora.android.data.session.AuthenticatedSubject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The session lifecycle as the offline layer needs to see it
 * (`docs/architecture/offline-first-architecture.md` §6, §10).
 *
 * The session owner reports when a session becomes available and when it ends; the offline layer
 * decides what that means for the queue and the working set. Keeping the two apart is what stops
 * session handling from growing its own copy of the offline rules (`dev.md` §1).
 */
interface OfflineSessionLifecycle {
    /** A session is available: the queue may be replayed, and its subject is now known (§6). */
    fun onSessionAvailable()

    /**
     * A session is ending, while its credential is still readable.
     *
     * Local state scoped to that session is dropped so the next user cannot read it. Pending work is
     * **not** dropped: `BR-014` forbids losing it, and its disposition at sign-out is an open product
     * question that is recorded rather than decided here (§10).
     */
    suspend fun onSessionEnding()
}

/** Default [OfflineSessionLifecycle]. */
@Singleton
class SessionScopedOfflineState @Inject constructor(
    private val sync: OfflineSync,
    private val subject: AuthenticatedSubject,
    private val workingSet: WorkingSetStore,
) : OfflineSessionLifecycle {

    override fun onSessionAvailable() {
        sync.requestSync()
    }

    override suspend fun onSessionEnding() {
        val subjectId = subject.current() ?: return
        workingSet.clear(subjectId)
    }
}
