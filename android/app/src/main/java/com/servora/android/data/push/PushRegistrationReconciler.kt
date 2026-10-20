package com.servora.android.data.push

import com.servora.android.data.session.SessionAuthenticator
import com.servora.android.data.session.SessionRenewal
import com.servora.android.data.session.SessionStore
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException
import retrofit2.HttpException

interface PushTokenProvider {
    val configured: Boolean
    suspend fun token(): String
    suspend fun deleteToken()
}

enum class PushReconciliationResult { REGISTERED, RETRY, STOPPED, SESSION_CHANGED, UNCONFIGURED }

/** Serializes registration and fences credentials/responses by the captured session identity. */
@Singleton
class PushRegistrationReconciler @Inject constructor(
    private val api: PushInstallationsApi,
    private val storage: PushInstallationStorage,
    private val provider: PushTokenProvider,
    private val sessions: SessionStore,
    private val authenticator: SessionAuthenticator,
) {
    private val mutex = Mutex()

    suspend fun reconcile(
        sessionId: String,
        applicationId: String,
        alertsEnabled: Boolean,
        device: PushDeviceState,
    ): PushReconciliationResult = mutex.withLock {
        if (!provider.configured) return@withLock PushReconciliationResult.UNCONFIGURED
        try {
            var installation = storage.read() ?: PushInstallation.fresh().also(storage::write)
            // At most one recovery rotation per trigger; never blindly repeat a takeover request.
            repeat(2) { recovery ->
                if (!isCurrent(sessionId)) return@withLock PushReconciliationResult.SESSION_CHANGED
                if (installation.needsTargetReset) {
                    provider.deleteToken()
                    installation = installation.copy(needsTargetReset = false)
                    storage.write(installation)
                }
                val token = provider.token()
                if (!isCurrent(sessionId)) return@withLock PushReconciliationResult.SESSION_CHANGED
                val request = PushRegistrationRequest(
                    installation.revision, applicationId, PushProviderRegistration(kind = "FCM_TOKEN", value = token),
                    device.locale, alertsEnabled, device.notificationPermission, device.channelEnabled,
                )
                try {
                    val response = authenticated(sessionId) { authorization ->
                        api.put(authorization, installation.secret, installation.id, request)
                    }
                    if (!isCurrent(sessionId)) return@withLock PushReconciliationResult.SESSION_CHANGED
                    require(response.installationId == installation.id && response.revision > 0)
                    storage.write(installation.copy(
                        revision = response.revision,
                        bindingId = response.bindingId,
                        organizationId = response.organizationId,
                        sessionId = sessionId,
                    ))
                    return@withLock PushReconciliationResult.REGISTERED
                } catch (failure: HttpException) {
                    if (failure.code() != 409 && failure.code() != 404) throw failure
                    if (recovery != 0) return@withLock PushReconciliationResult.STOPPED
                    val recovered = try {
                        authenticated(sessionId) { authorization ->
                            api.get(authorization, installation.secret, installation.id)
                        }
                    } catch (readFailure: HttpException) {
                        if (readFailure.code() != 404) throw readFailure
                        null
                    }
                    if (!isCurrent(sessionId)) return@withLock PushReconciliationResult.SESSION_CHANGED
                    installation = if (recovered != null && recovered.revision != installation.revision) {
                        installation.copy(
                            revision = recovered.revision, bindingId = recovered.bindingId,
                            organizationId = recovered.organizationId,
                            sessionId = sessionId,
                        )
                    } else {
                        // Missing proof/binding, unrecoverable new-session revision, or target conflict.
                        PushInstallation.fresh()
                    }
                    storage.write(installation)
                }
            }
            PushReconciliationResult.STOPPED
        } catch (_: SessionChanged) {
            PushReconciliationResult.SESSION_CHANGED
        } catch (failure: HttpException) {
            if (failure.code() >= 500 || failure.code() == 429) PushReconciliationResult.RETRY
            else PushReconciliationResult.STOPPED
        } catch (_: IOException) {
            PushReconciliationResult.RETRY
        } catch (_: SerializationException) {
            PushReconciliationResult.STOPPED
        } catch (_: IllegalArgumentException) {
            PushReconciliationResult.STOPPED
        }
    }

    /** Captured credentials can only unregister their own binding. Server sign-out also cleans it. */
    suspend fun unregister(sessionId: String, accessToken: String) = mutex.withLock {
        try {
            val installation = storage.read() ?: return@withLock
            if (installation.sessionId != sessionId) return@withLock
            api.delete("Bearer $accessToken", installation.secret, installation.id)
            // DELETE increments revision; GET/409 recovers it at the next sign-in.
        } catch (_: IOException) {
            // Best effort; backend revocation and send-time eligibility are authoritative.
        } catch (_: HttpException) {
            // An expired/revoked/obsolete session cannot clean a newer binding.
        }
    }

    private fun isCurrent(sessionId: String) = sessions.current()?.sessionId == sessionId

    private suspend fun <T> authenticated(sessionId: String, request: suspend (String) -> T): T {
        if (!isCurrent(sessionId)) throw SessionChanged()
        val accessToken = sessions.accessToken() ?: throw SessionChanged()
        return try {
            request("Bearer $accessToken")
        } catch (failure: HttpException) {
            if (failure.code() != 401 || !isCurrent(sessionId)) throw failure
            when (val renewed = authenticator.renew(accessToken)) {
                is SessionRenewal.Renewed -> {
                    if (!isCurrent(sessionId)) throw SessionChanged()
                    request("Bearer ${renewed.accessToken}")
                }
                SessionRenewal.Rejected -> throw SessionChanged()
                SessionRenewal.Unavailable -> throw IOException("Push authentication unavailable")
            }
        }
    }

    private class SessionChanged : Exception()
}
