package com.servora.android.data.jobs

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When the image stack's cached bytes are released (`D4b`, `BR-007`).
 *
 * The stack is not told when a session ends, so the rule it uses instead is pinned here: the caches are
 * released once, on the first read under a session that is not the one they were cached for — not on
 * every read (which would empty the cache before it could ever be used) and never for the session that
 * cached them.
 */
class JobPhotoImageCacheScopeTest {

    @Test
    fun `releases on the first read of a session`() {
        val scope = JobPhotoImageCacheScope()

        assertTrue(scope.releaseRequiredFor("user-1"))
    }

    @Test
    fun `keeps the caches while the same session keeps reading`() {
        val scope = JobPhotoImageCacheScope()

        scope.releaseRequiredFor("user-1")

        assertFalse("A cache emptied on every read could never be used", scope.releaseRequiredFor("user-1"))
        assertFalse(scope.releaseRequiredFor("user-1"))
    }

    @Test
    fun `releases another session's bytes before it reads`() {
        val scope = JobPhotoImageCacheScope()

        scope.releaseRequiredFor("user-1")

        assertTrue(scope.releaseRequiredFor("user-2"))
    }

    @Test
    fun `releases again when a session returns, because its own bytes are gone`() {
        val scope = JobPhotoImageCacheScope()

        scope.releaseRequiredFor("user-1")
        scope.releaseRequiredFor("user-2")

        // user-1's entries were released when user-2 read, so nothing of theirs is cached any more.
        assertTrue(scope.releaseRequiredFor("user-1"))
    }
}
