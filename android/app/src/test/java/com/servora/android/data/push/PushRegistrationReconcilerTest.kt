package com.servora.android.data.push

import com.servora.android.data.session.SessionAuthenticator
import com.servora.android.data.session.SessionRenewal
import com.servora.android.data.session.inMemorySessionStore
import com.servora.android.domain.model.AuthTokens
import com.servora.android.domain.model.IssuedSession
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

class PushRegistrationReconcilerTest {
    private val sessions = inMemorySessionStore()
    private val storage = MemoryInstallationStorage()
    private val tokens = FakeTokens()
    private val api = FakeApi()
    private var renewal: SessionRenewal = SessionRenewal.Rejected
    private var renewCalls = 0
    private val authenticator = object : SessionAuthenticator {
        override fun accessToken() = sessions.accessToken()
        override suspend fun renew(rejectedToken: String): SessionRenewal {
            renewCalls++
            return renewal
        }
    }
    private fun engine() = PushRegistrationReconciler(api, storage, tokens, sessions, authenticator)
    private suspend fun register(
        session: String = "session-a",
        enabled: Boolean = true,
        device: PushDeviceState = PushDeviceState("fr-CA", "DENIED", false),
    ) = engine().reconcile(session, "com.servora.android.debug", enabled, device)

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun `concurrent triggers serialize on the acknowledged revision`() = runTest {
        sessions.store(session())
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val reconciler = engine()
        api.putAction = {
            if (api.requests.size == 1) { entered.complete(Unit); release.await() }
            api.revision++
            api.response()
        }
        val first = async { reconciler.reconcile("session-a", "com.servora.android.debug", true, PushDeviceState()) }
        entered.await()
        val second = async { reconciler.reconcile("session-a", "com.servora.android.debug", false, PushDeviceState()) }
        runCurrent()
        assertEquals(1, api.requests.size)
        release.complete(Unit)
        assertEquals(PushReconciliationResult.REGISTERED, first.await())
        assertEquals(PushReconciliationResult.REGISTERED, second.await())
        assertEquals(listOf(0, 1), api.requests.map { it.expectedRevision })
        assertFalse(api.requests.last().alertsEnabled)
    }

    @Test fun `first registration persists proof before target reset and sends observed state`() = runTest {
        sessions.store(session())
        tokens.beforeDelete = { assertTrue(storage.value!!.needsTargetReset) }
        assertEquals(PushReconciliationResult.REGISTERED, register(enabled = false))
        val request = api.requests.single()
        assertEquals(0, request.expectedRevision)
        assertEquals("FCM_TOKEN", request.registration.kind)
        assertEquals("target-1", request.registration.value)
        assertEquals("fr-CA", request.locale)
        assertEquals("DENIED", request.notificationPermission)
        assertFalse(request.channelEnabled)
        assertFalse(request.alertsEnabled)
        assertEquals("session-a", storage.value!!.sessionId)
        assertEquals(1, storage.value!!.revision)
        assertNotNull(storage.value!!.bindingId)
        assertEquals(43, storage.value!!.secret.length)
        assertEquals(1, tokens.deletes)
    }

    @Test fun `restart reuses proof and revision without rotating a valid target`() = runTest {
        sessions.store(session())
        register()
        val previous = storage.value!!
        register()
        assertEquals(previous.id, storage.value!!.id)
        assertEquals(previous.secret, storage.value!!.secret)
        assertEquals(1, api.requests.last().expectedRevision)
        assertEquals(1, tokens.deletes)
    }

    @Test fun `token rotation uses latest SDK target`() = runTest {
        sessions.store(session())
        register()
        tokens.value = "target-2"
        register()
        assertEquals("target-2", api.requests.last().registration.value)
        assertEquals(1, api.requests.last().expectedRevision)
    }

    @Test fun `lost response recovers current session revision using GET`() = runTest {
        sessions.store(session())
        api.putAction = { request ->
            api.revision++
            if (api.requests.size == 1) throw IOException("lost response")
            if (request.expectedRevision != api.revision - 1) { api.revision--; throw http(409) }
            api.response()
        }
        assertEquals(PushReconciliationResult.RETRY, register())
        assertEquals(0, storage.value!!.revision)
        assertEquals(PushReconciliationResult.REGISTERED, register())
        assertEquals(listOf(0, 0, 1), api.requests.map { it.expectedRevision })
        assertEquals(1, api.reads)
        assertEquals(1, tokens.deletes)
        assertEquals(2, storage.value!!.revision)
    }

    @Test fun `new session rebinds with last acknowledged revision`() = runTest {
        sessions.store(session())
        register()
        val id = storage.value!!.id
        sessions.store(session("session-b"))
        assertEquals(PushReconciliationResult.REGISTERED, register("session-b"))
        assertEquals(1, api.requests.last().expectedRevision)
        assertEquals(id, storage.value!!.id)
        assertEquals("session-b", storage.value!!.sessionId)
    }

    @Test fun `irreconcilable revision rotates identity and target instead of blind takeover`() = runTest {
        sessions.store(session())
        register()
        val old = storage.value!!
        sessions.store(session("session-b"))
        api.putAction = { if (api.ids.last() == old.id) throw http(409) else api.response() }
        api.getAction = { throw http(404) }
        assertEquals(PushReconciliationResult.REGISTERED, register("session-b"))
        assertNotEquals(old.id, storage.value!!.id)
        assertNotEquals(old.secret, storage.value!!.secret)
        assertEquals(0, api.requests.last().expectedRevision)
        assertEquals(2, tokens.deletes)
    }

    @Test fun `target conflict cannot loop forever or transfer another installation`() = runTest {
        sessions.store(session())
        api.putAction = { throw http(409) }
        api.getAction = { throw http(404) }
        assertEquals(PushReconciliationResult.STOPPED, register())
        assertEquals(2, api.requests.size)
        assertEquals(2, tokens.deletes)
    }

    @Test fun `account switch while PUT is in flight discards old response`() = runTest {
        sessions.store(session())
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        api.putAction = { entered.complete(Unit); release.await(); api.response() }
        val pending = async { register() }
        entered.await()
        sessions.store(session("session-b"))
        release.complete(Unit)
        assertEquals(PushReconciliationResult.SESSION_CHANGED, pending.await())
        assertNull(storage.value!!.sessionId)
        assertEquals(0, storage.value!!.revision)
    }

    @Test fun `sign-out during target acquisition prevents PUT`() = runTest {
        sessions.store(session())
        tokens.beforeToken = { sessions.clear() }
        assertEquals(PushReconciliationResult.SESSION_CHANGED, register())
        assertTrue(api.requests.isEmpty())
    }

    @Test fun `old session cannot register with new credentials`() = runTest {
        sessions.store(session("session-b"))
        assertEquals(PushReconciliationResult.SESSION_CHANGED, register())
        assertTrue(api.requests.isEmpty())
    }

    @Test fun `missing Firebase variant remains usable without registration`() = runTest {
        sessions.store(session())
        tokens.configured = false
        assertEquals(PushReconciliationResult.UNCONFIGURED, register())
        assertTrue(api.requests.isEmpty())
        assertNull(storage.value)
    }

    @Test fun `network server and rate limit failures retry while client failures stop`() = runTest {
        sessions.store(session())
        for (code in listOf(429, 500, 503)) {
            api.putAction = { throw http(code) }
            assertEquals(PushReconciliationResult.RETRY, register())
        }
        for (code in listOf(400, 403)) {
            api.putAction = { throw http(code) }
            assertEquals(PushReconciliationResult.STOPPED, register())
        }
    }

    @Test fun `expired access token renews once and retries under same session`() = runTest {
        sessions.store(session())
        renewal = SessionRenewal.Renewed("fresh-token")
        api.putAction = { if (api.authorizations.last() != "Bearer fresh-token") throw http(401) else api.response() }
        assertEquals(PushReconciliationResult.REGISTERED, register())
        assertEquals(1, renewCalls)
        assertEquals(2, api.requests.size)
    }

    @Test fun `refresh outage retries and refused refresh stops`() = runTest {
        sessions.store(session())
        api.putAction = { throw http(401) }
        renewal = SessionRenewal.Unavailable
        assertEquals(PushReconciliationResult.RETRY, register())
        renewal = SessionRenewal.Rejected
        assertEquals(PushReconciliationResult.SESSION_CHANGED, register())
    }

    @Test fun `cleanup of old session never unregisters newer local binding`() = runTest {
        sessions.store(session())
        register()
        sessions.store(session("session-b"))
        register("session-b")
        engine().unregister("session-a", "old-access")
        assertEquals(0, api.deletes)
        engine().unregister("session-b", "captured-access")
        assertEquals(1, api.deletes)
        assertEquals("Bearer captured-access", api.deletedAuthorization)
    }

    @Test fun `offline unregister preserves identity for reconciliation`() = runTest {
        sessions.store(session())
        register()
        val identity = storage.value
        api.deleteAction = { throw IOException("offline") }
        engine().unregister("session-a", "old-access")
        assertEquals(identity, storage.value)
    }

    @Test fun `lost proof rotates SDK target before associating fresh identity`() = runTest {
        sessions.store(session())
        register()
        val oldId = storage.value!!.id
        storage.value = null
        register()
        assertNotEquals(oldId, storage.value!!.id)
        assertEquals(2, tokens.deletes)
    }

    @Test fun `interrupted reset is persisted and retried before any register`() = runTest {
        sessions.store(session())
        tokens.beforeDelete = { throw IOException("offline") }
        assertEquals(PushReconciliationResult.RETRY, register())
        assertTrue(storage.value!!.needsTargetReset)
        assertTrue(api.requests.isEmpty())
        tokens.beforeDelete = {}
        assertEquals(PushReconciliationResult.REGISTERED, register())
        assertFalse(storage.value!!.needsTargetReset)
    }

    private class MemoryInstallationStorage : PushInstallationStorage {
        var value: PushInstallation? = null
        override fun read() = value
        override fun write(installation: PushInstallation) { value = installation }
    }
    private class FakeTokens : PushTokenProvider {
        override var configured = true
        var value = "target-1"
        var deletes = 0
        var beforeDelete: suspend () -> Unit = {}
        var beforeToken: suspend () -> Unit = {}
        override suspend fun token(): String { beforeToken(); return value }
        override suspend fun deleteToken() { deletes++; beforeDelete() }
    }
    private class FakeApi : PushInstallationsApi {
        val requests = mutableListOf<PushRegistrationRequest>()
        val ids = mutableListOf<String>()
        val authorizations = mutableListOf<String>()
        var revision = 0
        var reads = 0
        var deletes = 0
        var deletedAuthorization: String? = null
        var putAction: suspend (PushRegistrationRequest) -> PushRegistrationResponse = { revision++; response() }
        var getAction: suspend () -> PushRegistrationResponse = { response() }
        var deleteAction: suspend () -> Unit = {}
        fun response() = PushRegistrationResponse(ids.last(), "binding-$revision", revision.coerceAtLeast(1), true, false, "2026-10-16T16:00:00Z")
        override suspend fun put(authorization: String, secret: String, id: String, request: PushRegistrationRequest): PushRegistrationResponse {
            assertEquals(43, secret.length)
            requests += request; ids += id; authorizations += authorization
            return putAction(request)
        }
        override suspend fun get(authorization: String, secret: String, id: String): PushRegistrationResponse { reads++; return getAction() }
        override suspend fun delete(authorization: String, secret: String, id: String) {
            deletes++; deletedAuthorization = authorization; deleteAction()
        }
    }
    private fun session(id: String = "session-a") = IssuedSession(
        sessionId = id, tokens = AuthTokens("access-$id", "2026-10-17T12:00:00Z", "refresh-$id"), permissions = emptySet(),
    )
    private fun http(code: Int) = HttpException(Response.error<Unit>(code, "{}".toResponseBody()))
}
