package com.servora.android.data.push

import java.time.Instant

/** Opaque routing only. Receiving this message never grants access to the Job. */
@ConsistentCopyVisibility
data class AssignmentPushMessage private constructor(
    val eventId: String,
    val bindingId: String,
    val organizationId: String,
    val jobId: String,
    val visitId: String,
    val expiresAt: Instant,
) {
    /** Stable Android notification tag; a retry updates the same notification. */
    val notificationTag: String get() = "assignment:$bindingId:$eventId"

    companion object {
        private val uuid = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
        private val keys = setOf(
            "version", "eventType", "eventId", "bindingId", "organizationId", "jobId", "visitId", "expiresAt",
        )

        /** Fail closed on unsupported schemas, malformed identifiers, private fields or expiry. */
        fun parse(data: Map<String, String>, now: Instant): AssignmentPushMessage? {
            if (data.keys != keys || data["version"] != "1" || data["eventType"] != "VISIT_ASSIGNED") return null
            val identifiers = listOf("eventId", "bindingId", "organizationId", "jobId", "visitId")
                .map { key -> data.getValue(key).takeIf(uuid::matches)?.lowercase() ?: return null }
            val expiry = try {
                Instant.parse(data.getValue("expiresAt"))
            } catch (_: java.time.format.DateTimeParseException) {
                return null
            }
            if (expiry <= now) return null
            return AssignmentPushMessage(
                identifiers[0], identifiers[1], identifiers[2], identifiers[3], identifiers[4], expiry,
            )
        }
    }
}
