package com.servora.android.data.customers

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/**
 * The contact routes' wire contract, as Retrofit and the shared JSON configuration really produce it.
 *
 * The repository tests script a `CustomersApi` double, so they never touch Retrofit itself. This test
 * builds the interface the way `NetworkModule` does, which is what makes it able to catch a declaration
 * Retrofit refuses. Retrofit parses a method's annotations when the method is **first invoked** (this app
 * does not enable `validateEagerly`), and its own `@DELETE` is a body-less method that rejects a `@Body`
 * there — so the mistake would have surfaced on the first removal in the app rather than at compile time.
 * It also pins what the two new bodies actually contain: the edit is partial, so a member the caller does
 * not state must not travel (`BR-095`, `ADR-022` D9).
 */
class CustomersApiContractTest {

    @Test
    fun `creates the interface through Retrofit with every method parsed`() = runTest {
        // This app does not enable `validateEagerly`, so Retrofit parses a method when it is first called
        // — which for the removal is in the field. `api()` enables it, so `create` parses every method of
        // the interface here and a declaration Retrofit refuses fails this test rather than a removal.
        val api = api(CapturingInterceptor())

        api.removeContact(
            authorization = "Bearer access-token",
            id = "c1",
            contactId = "ct1",
            request = RemoveCustomerContactRequest(expectedVersion = 1),
        )
    }

    @Test
    fun `sends the contact edit as a partial patch that states the version it read`() = runTest {
        val interceptor = CapturingInterceptor(answerBody = contactBody(version = 2))
        val api = api(interceptor)

        api.updateContact(
            authorization = "Bearer access-token",
            id = "c1",
            contactId = "ct1",
            request = UpdateCustomerContactRequest(
                phone = "+15559999999",
                isPrimary = true,
                expectedVersion = 1,
            ),
        )

        val request = requireNotNull(interceptor.lastRequest)
        assertEquals("PATCH", request.method)
        assertEquals("/customers/c1/contacts/ct1", request.url.encodedPath)
        assertEquals("Bearer access-token", request.header("Authorization"))
        // Only what the caller stated travels: the members left at their default are absent, which is
        // what leaves the contact's `role` — a value no client edits — as it is (`ADR-022` D9).
        assertEquals(setOf("phone", "isPrimary", "expectedVersion"), request.jsonBody().keys)
        assertEquals("+15559999999", request.jsonBody()["phone"]?.jsonPrimitive?.content)
        assertEquals("true", request.jsonBody()["isPrimary"]?.jsonPrimitive?.content)
        assertEquals("1", request.jsonBody()["expectedVersion"]?.jsonPrimitive?.content)
    }

    @Test
    fun `sends the contact removal as a delete whose body carries the version it read`() = runTest {
        val interceptor = CapturingInterceptor(answerCode = 204)
        val api = api(interceptor)

        val response = api.removeContact(
            authorization = "Bearer access-token",
            id = "c1",
            contactId = "ct1",
            request = RemoveCustomerContactRequest(expectedVersion = 3),
        )

        val request = requireNotNull(interceptor.lastRequest)
        assertEquals("DELETE", request.method)
        assertEquals("/customers/c1/contacts/ct1", request.url.encodedPath)
        assertEquals("Bearer access-token", request.header("Authorization"))
        assertEquals(setOf("expectedVersion"), request.jsonBody().keys)
        assertEquals("3", request.jsonBody()["expectedVersion"]?.jsonPrimitive?.content)
        // The route answers `204` with no body, which is what the repository reads as success.
        assertEquals(204, response.code())
    }

    /**
     * The interface as the app builds it: the same `Json` configuration (`NetworkModule`) and the same
     * kotlinx-serialization converter, over a client whose only job is to record the request.
     *
     * `validateEagerly` is enabled here and not in the app so that creating the interface parses every
     * method, which is what turns a declaration Retrofit refuses into a failure of this test rather than
     * of the app's first call to that method.
     */
    private fun api(interceptor: CapturingInterceptor): CustomersApi =
        Retrofit.Builder()
            .baseUrl("https://api.servora.test/")
            .client(OkHttpClient.Builder().addInterceptor(interceptor).build())
            .addConverterFactory(
                Json { ignoreUnknownKeys = true }
                    .asConverterFactory("application/json".toMediaType()),
            )
            .validateEagerly(true)
            .build()
            .create(CustomersApi::class.java)

    /** The contact an edit answers with; its values are not this test's subject. */
    private fun contactBody(version: Int): String =
        """
        {
          "id": "ct1",
          "customerId": "c1",
          "firstName": "John",
          "lastName": "Smith",
          "email": null,
          "phone": "+15559999999",
          "role": null,
          "isPrimary": true,
          "isBillingContact": false,
          "isJobContact": false,
          "version": $version,
          "createdAt": "2026-01-01T00:00:00.000Z",
          "updatedAt": "2026-01-02T00:00:00.000Z"
        }
        """.trimIndent()

    /** The body the request carried, parsed so a member's absence can be asserted. */
    private fun Request.jsonBody() = Json.parseToJsonElement(body.orEmpty().readUtf8()).jsonObject

    /** The body a request carries, read through a buffer rather than off the wire. */
    private fun RequestBody.readUtf8(): String {
        val buffer = Buffer()
        writeTo(buffer)
        return buffer.readUtf8()
    }

    /** The body a request carries, or an empty one when it carries none. */
    private fun RequestBody?.orEmpty(): RequestBody =
        this ?: "".toRequestBody("application/json".toMediaType())
}

/** An interceptor that records the request it was given and answers with a scripted reply. */
private class CapturingInterceptor(
    private val answerBody: String = "",
    private val answerCode: Int = 200,
) : Interceptor {

    var lastRequest: Request? = null

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        lastRequest = request
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(answerCode)
            .message("scripted")
            .body(answerBody.toResponseBody("application/json".toMediaType()))
            .build()
    }
}
