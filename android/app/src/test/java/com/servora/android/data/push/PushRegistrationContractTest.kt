package com.servora.android.data.push

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** Exercises the production encoder defaults: required discriminator/false/zero cannot disappear. */
class PushRegistrationContractTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test fun `wire request includes token discriminator and independently disabled states`() {
        val request = PushRegistrationRequest(
            0, "com.servora.android.debug", PushProviderRegistration("FCM_TOKEN", "fake-target"),
            "fr-CA", false, "NOT_REQUESTED", false,
        )
        val encoded = json.parseToJsonElement(json.encodeToString(PushRegistrationRequest.serializer(), request)).jsonObject
        assertEquals("FCM_TOKEN", encoded.getValue("registration").jsonObject.getValue("kind").jsonPrimitive.content)
        assertEquals("0", encoded.getValue("expectedRevision").jsonPrimitive.content)
        assertEquals("false", encoded.getValue("alertsEnabled").jsonPrimitive.content)
        assertEquals("false", encoded.getValue("channelEnabled").jsonPrimitive.content)
        assertEquals("NOT_REQUESTED", encoded.getValue("notificationPermission").jsonPrimitive.content)
        assertFalse(encoded.containsKey("sessionId"))
        assertFalse(encoded.containsKey("organizationId"))
    }
}
