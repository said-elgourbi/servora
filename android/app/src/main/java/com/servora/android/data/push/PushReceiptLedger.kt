package com.servora.android.data.push

import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** Storage implementations must replace the complete record atomically, or throw. */
interface PushReceiptStorage {
    fun read(): String?
    fun write(value: String)
}

/**
 * Durable presentation reservation, scoped by binding/event until the message expires.
 * Reserve only after session, binding, organization and presentation permission checks.
 * A reservation precedes display: a crash may lose one alert but cannot replay it on restart.
 */
@Singleton
class PushReceiptLedger @Inject constructor(private val storage: PushReceiptStorage) {
    private val json = Json

    @Synchronized
    fun reserve(message: AssignmentPushMessage, now: Instant): Boolean {
        if (message.expiresAt <= now) return false
        val receipts = try {
            storage.read()?.let { json.decodeFromString<List<Receipt>>(it) } ?: emptyList()
        } catch (_: SerializationException) {
            // Corruption cannot prove a message was never displayed. Do not erase that evidence.
            return false
        }
        val active = try {
            receipts.filter { Instant.parse(it.expiresAt) > now }
        } catch (_: java.time.format.DateTimeParseException) {
            return false
        }
        if (active.any { it.bindingId == message.bindingId && it.eventId == message.eventId }) return false
        storage.write(json.encodeToString(
            kotlinx.serialization.builtins.ListSerializer(Receipt.serializer()),
            active + Receipt(message.bindingId, message.eventId, message.expiresAt.toString()),
        ))
        return true
    }

    @Serializable
    private data class Receipt(val bindingId: String, val eventId: String, val expiresAt: String)
}
