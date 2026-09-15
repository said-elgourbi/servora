package com.servora.android.data.jobs

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Which session's photo bytes the image stack is holding (`D4b`, `BR-007`).
 *
 * A cache is not a store: it exists so the same photo is not downloaded and decoded twice, and it must
 * not outlive the session whose evidence it holds. What may never happen is the reverse — a member
 * signing in on the same device being served another member's evidence without the API answering — and
 * that is prevented by the keys themselves ([jobPhotoImageCacheKey]); this decides when the bytes are
 * actually let go.
 *
 * The rule is a comparison rather than a plain release because the stack is not told when a session
 * ends: the session owner does not depend on this feature's display cache, and the offline layer's own
 * session seam releases offline state (`OfflineSessionLifecycle`). So the stack releases on the **first
 * read under a session that is not the one it cached** — the last moment before those bytes could be
 * served to someone else. Nothing the previous session cached can then be read by this one, and its
 * bytes are dropped as soon as this session reads a photo.
 *
 * It holds no cache of its own and knows nothing about the stack's caches: the read that asks it
 * releases them (`JobPhotoFetcher`), which is what keeps the choice of cache the app's and the rule
 * verifiable on the JVM (`qa.md` §6.1).
 */
@Singleton
class JobPhotoImageCacheScope @Inject constructor() {

    /** The subject whose bytes the stack holds, or `null` before it has read anything. */
    private var lastReadSubjectId: String? = null

    /**
     * Records that [subjectId] is the session reading now, and answers whether what the stack cached
     * before has to be released first.
     *
     * It answers `true` exactly once per session, on that session's first read: releasing on every read
     * would empty the cache before it could ever be used, and never releasing would let one member's
     * bytes outlive their session.
     */
    fun releaseRequiredFor(subjectId: String): Boolean {
        if (subjectId == lastReadSubjectId) {
            return false
        }
        lastReadSubjectId = subjectId
        return true
    }
}
