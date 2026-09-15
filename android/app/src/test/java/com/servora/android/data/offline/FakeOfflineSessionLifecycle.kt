package com.servora.android.data.offline

/**
 * [OfflineSessionLifecycle] a test drives and reads back.
 *
 * The mode is injectable so a test can trigger a sync at a chosen moment instead of relying on the
 * default implementation's own queue.
 */
class FakeOfflineSessionLifecycle(
    private val onAvailable: () -> Unit = {},
    private val onEnding: suspend () -> Unit = {},
) : OfflineSessionLifecycle {

    var availableCalls = 0
        private set

    var endingCalls = 0
        private set

    override fun onSessionAvailable() {
        availableCalls += 1
        onAvailable()
    }

    override suspend fun onSessionEnding() {
        endingCalls += 1
        onEnding()
    }
}
