package com.servora.android.data.push

import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class AssignmentPushEligibilityTest {
    private val now = Instant.parse("2026-10-16T12:00:00Z")
    private val message = AssignmentPushMessage.parse(assignmentPayload(), now)!!
    private val installation = PushInstallation(
        id = "local", secret = "proof", revision = 1,
        bindingId = message.bindingId, sessionId = "session", needsTargetReset = false,
        organizationId = message.organizationId,
    )
    @Test fun `restored matching session binding and organization are required`() {
        assertTrue(assignmentBindingMatches(message, installation, "session", now))
        assertFalse(assignmentBindingMatches(message, installation, null, now))
        assertFalse(assignmentBindingMatches(message, null, "session", now))
        assertFalse(assignmentBindingMatches(message, installation, "another-session", now))
        assertFalse(assignmentBindingMatches(message, installation.copy(bindingId = "old"), "session", now))
        assertFalse(assignmentBindingMatches(message, installation.copy(organizationId = "other"), "session", now))
    }
    @Test fun `legacy registration without trusted organization fails closed`() {
        assertFalse(assignmentBindingMatches(message, installation.copy(organizationId = null), "session", now))
    }
    @Test fun `reset in progress and expiry reject both receipt and taps`() {
        assertFalse(assignmentBindingMatches(message, installation.copy(needsTargetReset = true), "session", now))
        assertFalse(assignmentBindingMatches(message, installation, "session", message.expiresAt))
    }
}
