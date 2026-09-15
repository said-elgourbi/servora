package com.servora.android.data.jobs

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * The clock the Jobs feature's tests stamp local rows with.
 *
 * The device clock is used only for pacing and for a photo's `capturedAt` provenance, never as
 * business time (`BR-031`), so a test pins one rather than asserting against the wall clock.
 */
internal val TEST_CLOCK: Clock =
    Clock.fixed(Instant.parse("2026-09-15T13:05:00Z"), ZoneOffset.UTC)
