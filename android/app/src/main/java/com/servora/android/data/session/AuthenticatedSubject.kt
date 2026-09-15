package com.servora.android.data.session

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Base64

/**
 * The authenticated subject the held session belongs to.
 *
 * Local business state is scoped to the session that produced it, so a queue of pending work or a
 * cached projection must be attributed to *someone*. The API decides the tenant of every request from
 * the session itself and never names an organization to the client, so the identity the device can
 * read is the access token's subject (`sub`) — the user the session was issued to.
 *
 * This is a local partition key only. It is never an authorization input: the backend authorizes
 * every replay and every read (`BR-001`, `BR-007`). The signature is deliberately not verified — the
 * token is read from the device's own encrypted storage, and nothing here grants access to anything.
 */
interface AuthenticatedSubject {
    /**
     * The subject of the held session, or `null` when there is no session or the credential cannot
     * be read.
     *
     * Callers treat `null` as "cannot attribute this to a subject", and therefore do not queue:
     * pending work must never be filed under an identity it did not come from (`BR-014`).
     */
    fun current(): String?
}

/** Default [AuthenticatedSubject], reading the subject claim of the stored access token. */
@Singleton
class AccessTokenSubject @Inject constructor(
    private val sessionStore: SessionStore,
) : AuthenticatedSubject {

    override fun current(): String? = sessionStore.accessToken()?.let(::accessTokenSubject)
}

/**
 * Reads the `sub` claim of an access token.
 *
 * The token is a compact JWS: three base64url segments, the second of which is the claims set. Only
 * that segment is read, and only its `sub` member; anything unparsable yields `null` rather than a
 * guess (`BR-042`).
 */
internal fun accessTokenSubject(accessToken: String): String? {
    val segments = accessToken.split('.')
    if (segments.size < 2 || segments[1].isEmpty()) {
        return null
    }
    return try {
        val claims = String(base64UrlDecode(segments[1]), Charsets.UTF_8)
        Json.parseToJsonElement(claims)
            .jsonObject["sub"]
            ?.jsonPrimitive
            ?.contentOrNull
            ?.takeIf { it.isNotBlank() }
    } catch (failure: IllegalArgumentException) {
        null
    } catch (failure: SerializationException) {
        null
    }
}

/**
 * Decodes one base64url segment.
 *
 * A JWT segment is written without padding, which `java.util.Base64`'s URL decoder does not always
 * accept, so the padding is restored first.
 */
private fun base64UrlDecode(segment: String): ByteArray {
    val padding = (BASE64_QUANTUM - segment.length % BASE64_QUANTUM) % BASE64_QUANTUM
    return Base64.getUrlDecoder().decode(segment + "=".repeat(padding))
}

private const val BASE64_QUANTUM = 4
