package com.servora.android.data.offline

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * A [Clock] a test moves by hand, so retry backoff can be exercised without waiting.
 *
 * The engine reads the device clock only to pace retries; business time is always the backend's
 * (`docs/architecture/offline-first-architecture.md` §4).
 */
class AdjustableClock(private var now: Instant) : Clock() {

    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock = this

    override fun instant(): Instant = now

    /** Moves the clock forward by [duration]. */
    fun advance(duration: Duration) {
        now = now.plus(duration)
    }
}
