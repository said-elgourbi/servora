package com.servora.android.data.jobs

import com.servora.android.data.offline.OfflineSync

/**
 * [OfflineSync] a test decides.
 *
 * A queued photo asks for a replay rather than performing one (`§6`), so the feature's tests assert
 * that the request was made — not that a network happened.
 */
class FakeOfflineSync : OfflineSync {

    var requests = 0
        private set

    override fun requestSync() {
        requests += 1
    }
}
