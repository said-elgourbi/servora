package com.servora.android.data.push

import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

internal fun assignmentPayload() = mapOf(
    "version" to "1", "eventType" to "VISIT_ASSIGNED",
    "eventId" to "11111111-1111-4111-8111-111111111111",
    "bindingId" to "22222222-2222-4222-8222-222222222222",
    "organizationId" to "33333333-3333-4333-8333-333333333333",
    "jobId" to "44444444-4444-4444-8444-444444444444",
    "visitId" to "55555555-5555-4555-8555-555555555555",
    "expiresAt" to "2026-10-17T12:00:00Z",
)

class AssignmentPushMessageTest {
    private val now = Instant.parse("2026-10-16T12:00:00Z")

    @Test fun `valid opaque payload preserves routing and stable notification tag`() {
        val message = AssignmentPushMessage.parse(assignmentPayload(), now)!!
        assertEquals(assignmentPayload().getValue("jobId"), message.jobId)
        assertEquals(assignmentPayload().getValue("visitId"), message.visitId)
        assertEquals(assignmentPayload().getValue("organizationId"), message.organizationId)
        assertEquals("assignment:${message.bindingId}:${message.eventId}", message.notificationTag)
    }

    @Test fun `every field is required`() {
        assignmentPayload().keys.forEach {
            assertNull(it, AssignmentPushMessage.parse(assignmentPayload() - it, now))
        }
    }

    @Test fun `unknown versions event types and extra private fields are rejected`() {
        listOf("version" to "2", "eventType" to "VISIT_REMOVED", "customerName" to "private").forEach {
            assertNull(AssignmentPushMessage.parse(assignmentPayload() + it, now))
        }
    }

    @Test fun `identifiers require full UUID syntax`() {
        listOf("eventId", "bindingId", "organizationId", "jobId", "visitId").forEach { key ->
            listOf("", "1-1-1-1-1", "../job", " ${assignmentPayload().getValue(key)}").forEach { value ->
                assertNull(AssignmentPushMessage.parse(assignmentPayload() + (key to value), now))
            }
        }
    }

    @Test fun `UUID casing cannot evade deduplication`() {
        val id = "abcdefab-abcd-4abc-8abc-abcdefabcdef"
        val lower = AssignmentPushMessage.parse(assignmentPayload() + ("eventId" to id), now)!!
        val upper = AssignmentPushMessage.parse(assignmentPayload() + ("eventId" to id.uppercase()), now)!!
        assertEquals(lower, upper)
    }

    @Test fun `expiry is strict and must include a timezone`() {
        listOf("", "tomorrow", "2026-10-17T12:00:00", now.toString(), now.minusMillis(1).toString()).forEach {
            assertNull(AssignmentPushMessage.parse(assignmentPayload() + ("expiresAt" to it), now))
        }
        assertNotNull(AssignmentPushMessage.parse(assignmentPayload() + ("expiresAt" to now.plusMillis(1).toString()), now))
    }
}
