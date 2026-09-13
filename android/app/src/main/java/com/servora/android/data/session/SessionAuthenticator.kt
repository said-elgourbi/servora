package com.servora.android.data.session

import com.servora.android.data.auth.AuthApi
import com.servora.android.data.auth.RefreshRequestDto
import com.servora.android.data.auth.toIssuedSession
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException
import retrofit2.HttpException

/** Outcome of trying to renew a session the backend refused. */
sealed interface SessionRenewal {
    /** The backend issued a new pair; [accessToken] is the credential the caller retries with. */
    data class Renewed(val accessToken: String) : SessionRenewal

    /** The refresh token was refused or the session is gone: the caller is unauthenticated. */
    data object Rejected : SessionRenewal

    /** The renewal did not reach the backend, which may still accept the session later. */
    data object Unavailable : SessionRenewal
}

/**
 * Supplies the access token authenticated calls carry and keeps the session alive (`BR-018`).
 *
 * An access token is short-lived (`ACCESS_TOKEN_LIFETIME`, default 15m). When the backend refuses
 * the one a call used (`401`), the caller asks to [renew] it and retries once. The backend stays
 * the authority for whether the new token works (`BR-001`, `BR-007`).
 */
interface SessionAuthenticator {
    /** The token to present, or `null` when no session is held. */
    fun accessToken(): String?

    /** Renews the session after the backend refused [rejectedToken]. */
    suspend fun renew(rejectedToken: String): SessionRenewal
}

/**
 * Default [SessionAuthenticator]: rotates the stored refresh token through `POST /auth/refresh`
 * (`docs/api/authentication.md` §3.2).
 *
 * Refresh rotates the refresh token, so two callers refreshing at once would spend one token and
 * get each other rejected. A single [Mutex] therefore owns the renewal, and a caller that waited
 * for it re-reads the session: if another caller already produced a different access token, that
 * token is the fresh one and no second rotation happens.
 */
@Singleton
class DefaultSessionAuthenticator @Inject constructor(
    private val api: AuthApi,
    private val sessionStore: SessionStore,
) : SessionAuthenticator {

    private val renewal = Mutex()

    override fun accessToken(): String? = sessionStore.accessToken()

    override suspend fun renew(rejectedToken: String): SessionRenewal = renewal.withLock {
        val current = sessionStore.accessToken()
        if (current != null && current != rejectedToken) {
            return@withLock SessionRenewal.Renewed(current)
        }
        val refreshToken = sessionStore.refreshToken() ?: return@withLock SessionRenewal.Rejected

        try {
            val renewed = api.refresh(RefreshRequestDto(refreshToken = refreshToken))
                .toIssuedSession()
            sessionStore.store(renewed)
            SessionRenewal.Renewed(renewed.tokens.accessToken)
        } catch (failure: HttpException) {
            // A refused refresh token ends the session; a backend failure does not.
            if (failure.code() == HTTP_UNAUTHORIZED || failure.code() == HTTP_BAD_REQUEST) {
                sessionStore.clear()
                SessionRenewal.Rejected
            } else {
                SessionRenewal.Unavailable
            }
        } catch (failure: IOException) {
            SessionRenewal.Unavailable
        } catch (failure: SerializationException) {
            SessionRenewal.Unavailable
        }
    }

    private companion object {
        const val HTTP_BAD_REQUEST = 400
        const val HTTP_UNAUTHORIZED = 401
    }
}
