package com.servora.android.data.offline

/**
 * How long a queued operation waits before its next replay attempt (§6).
 *
 * Backoff exists so a replay triggered by connectivity, app start or an explicit retry does not
 * hammer an API that is already failing. It is deliberately modest and bounded: the operation is
 * never dropped, and a user who wants it to go now can ask for a replay.
 */
object ReplayBackoff {

    /** The wait after the first failed attempt. */
    private const val FIRST_DELAY_MILLIS = 30_000L

    /** The longest wait between attempts. */
    private const val MAX_DELAY_MILLIS = 60 * 60 * 1000L

    private const val FACTOR = 2

    /** The wait required after [attemptCount] failed attempts; `0` before the first one. */
    fun delayMillis(attemptCount: Int): Long {
        if (attemptCount <= 0) {
            return 0
        }
        var delay = FIRST_DELAY_MILLIS
        repeat(attemptCount - 1) {
            delay = minOf(delay * FACTOR, MAX_DELAY_MILLIS)
        }
        return delay
    }
}
