package com.servora.android.data.push

import java.io.IOException
import java.time.Instant
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import org.junit.Assert.*
import org.junit.Test

class PushReceiptLedgerTest {
    private val now = Instant.parse("2026-10-16T12:00:00Z")
    private val message = AssignmentPushMessage.parse(assignmentPayload(), now)!!
    private val storage = MemoryStorage()

    @Test fun `duplicate remains rejected after reopening ledger`() {
        assertTrue(PushReceiptLedger(storage).reserve(message, now))
        assertFalse(PushReceiptLedger(storage).reserve(message, now))
    }

    @Test fun `distinct events and bindings each reserve once`() {
        val ledger = PushReceiptLedger(storage)
        assertTrue(ledger.reserve(message, now))
        listOf("bindingId", "eventId").forEach { key ->
            val changed = AssignmentPushMessage.parse(assignmentPayload() + (key to "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"), now)!!
            assertTrue(ledger.reserve(changed, now))
            assertFalse(ledger.reserve(changed, now))
        }
    }

    @Test fun `concurrent callbacks reserve only once`() {
        val ledger = PushReceiptLedger(storage)
        val executor = Executors.newFixedThreadPool(4)
        try {
            val results = executor.invokeAll(List(20) { Callable { ledger.reserve(message, now) } })
            assertEquals(1, results.count { it.get() })
        } finally {
            executor.shutdownNow()
        }
    }

    @Test fun `expired message is not reserved or written`() {
        assertFalse(PushReceiptLedger(storage).reserve(message, message.expiresAt))
        assertNull(storage.value)
    }

    @Test fun `pruning keeps every unexpired receipt`() {
        val ledger = PushReceiptLedger(storage)
        val earlier = AssignmentPushMessage.parse(assignmentPayload() + ("expiresAt" to now.plusSeconds(1).toString()), now)!!
        assertTrue(ledger.reserve(earlier, now))
        val later = AssignmentPushMessage.parse(assignmentPayload() + ("eventId" to "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"), now)!!
        assertTrue(ledger.reserve(later, now.plusSeconds(1)))
        assertFalse(storage.value!!.contains(message.eventId))
        assertFalse(PushReceiptLedger(storage).reserve(later, now.plusSeconds(2)))
    }

    @Test fun `corrupt history fails closed without overwriting evidence`() {
        listOf("{broken", "{}", "[{\"bindingId\":\"binding\",\"eventId\":\"event\",\"expiresAt\":\"bad\"}]").forEach {
            storage.value = it
            assertFalse(PushReceiptLedger(storage).reserve(message, now))
            assertEquals(it, storage.value)
        }
    }

    @Test fun `write failure never reports reservation success`() {
        val failing = object : PushReceiptStorage {
            override fun read(): String? = null
            override fun write(value: String) { throw IOException("Disk unavailable") }
        }
        assertThrows(IOException::class.java) { PushReceiptLedger(failing).reserve(message, now) }
    }

    private class MemoryStorage : PushReceiptStorage {
        var value: String? = null
        override fun read() = value
        override fun write(value: String) { this.value = value }
    }
}
