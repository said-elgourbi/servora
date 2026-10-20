package com.servora.android.data.push

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PushRegistrationCoordinatorTest {
    private val sessions = MutableStateFlow<String?>(null)
    private val enabled = MutableStateFlow(true)
    private val device = MutableStateFlow(PushDeviceState())
    private val triggers = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val calls = mutableListOf<Triple<String, Boolean, PushDeviceState>>()
    private var answer: suspend () -> PushReconciliationResult = { PushReconciliationResult.REGISTERED }

    private suspend fun runLoop() = reconcileLatestPushState(
        sessions, enabled, device, triggers.onStart { emit(Unit) },
    ) { session, preference, observed ->
        calls += Triple(session, preference, observed)
        answer()
    }

    @Test fun `waits for session restoration and reconciles startup and state changes`() = runTest {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { runLoop() }
        runCurrent()
        assertTrue(calls.isEmpty())
        sessions.value = "a"
        runCurrent()
        assertEquals("a", calls.single().first)
        enabled.value = false
        runCurrent()
        assertFalse(calls.last().second)
        device.value = PushDeviceState("fr-CA", "GRANTED", true)
        runCurrent()
        assertEquals(device.value, calls.last().third)
        assertEquals(3, calls.size)
    }

    @Test fun `token connectivity and resume signals refresh current state`() = runTest {
        sessions.value = "a"
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { runLoop() }
        runCurrent()
        repeat(3) { triggers.emit(Unit); runCurrent() }
        assertEquals(4, calls.size)
    }

    @Test fun `network failure backs off and reconnect interrupts the delay`() = runTest {
        sessions.value = "a"
        answer = { PushReconciliationResult.RETRY }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { runLoop() }
        runCurrent()
        assertEquals(1, calls.size)
        advanceTimeBy(999); runCurrent()
        assertEquals(1, calls.size)
        advanceTimeBy(1); runCurrent()
        assertEquals(2, calls.size)
        triggers.emit(Unit); runCurrent()
        assertEquals(3, calls.size)
        answer = { PushReconciliationResult.REGISTERED }
        advanceTimeBy(1_000); runCurrent()
        assertEquals(4, calls.size)
        advanceTimeBy(60_000); runCurrent()
        assertEquals(4, calls.size)
    }

    @Test fun `session change cancels obsolete request and sign-out cancels retries`() = runTest {
        sessions.value = "a"
        var cancelled = false
        answer = { try { awaitCancellation() } finally { cancelled = true } }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { runLoop() }
        runCurrent()
        answer = { PushReconciliationResult.RETRY }
        sessions.value = "b"; runCurrent()
        assertTrue(cancelled)
        assertEquals(listOf("a", "b"), calls.map { it.first })
        sessions.value = null; runCurrent()
        advanceTimeBy(60_000); runCurrent()
        assertEquals(2, calls.size)
    }

    @Test fun `permanent failures wait for a fresh trigger`() = runTest {
        sessions.value = "a"
        answer = { PushReconciliationResult.STOPPED }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { runLoop() }
        runCurrent()
        advanceTimeBy(60_000); runCurrent()
        assertEquals(1, calls.size)
        triggers.emit(Unit); runCurrent()
        assertEquals(2, calls.size)
    }

    @Test fun `request deadline retries instead of wedging the coordinator`() = runTest {
        sessions.value = "a"
        answer = { awaitCancellation() }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { runLoop() }
        runCurrent()
        advanceTimeBy(30_000); runCurrent()
        assertEquals(1, calls.size)
        advanceTimeBy(1_000); runCurrent()
        assertEquals(2, calls.size)
    }
}
