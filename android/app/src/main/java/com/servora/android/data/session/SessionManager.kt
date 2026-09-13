package com.servora.android.data.session

import com.servora.android.data.auth.AuthApi
import com.servora.android.domain.model.IssuedSession
import java.time.Clock
import java.time.Instant
import java.time.format.DateTimeParseException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * What the app knows about authentication at a given moment.
 *
 * The screen the app shows is a function of this state, not of a per-screen flag, so a restored
 * session and a fresh sign-in produce the same authenticated UI (`BR-010`, `BR-011`).
 */
sealed interface AuthState {
    /**
     * The stored session is being read and, when its access token has expired, renewed.
     *
     * This state exists so the app never shows sign-in while a valid session might still be
     * restored. The authenticated area may only be shown once this state has resolved.
     */
    data object Checking : AuthState

    /** No usable session is held; the authentication flow is shown. */
    data object SignedOut : AuthState

    /** A usable session is held; the authenticated area is shown. */
    data class SignedIn(val permissions: Set<String>) : AuthState
}

/**
 * Owns the application's authentication state across the process lifetime.
 *
 * Sign-in stores the session in [SessionStore]; this turns the stored session into the state the UI
 * renders and restores it after process death (`BR-014`). It never evaluates a credential — the
 * backend decides that (`BR-001`, `BR-007`); it only decides whether a credential the device already
 * holds is still worth presenting.
 */
interface SessionManager {
    /** The current authentication state; starts at [AuthState.Checking]. */
    val state: StateFlow<AuthState>

    /**
     * Restores the session across a process restart.
     *
     * Resolves [AuthState.Checking] by reading the persisted session: with no session the app is
     * signed out; with a valid access token it is signed in immediately; with an expired access
     * token the refresh flow runs. Idempotent, so a recreated process does not restore twice.
     */
    suspend fun restore()

    /** Records that [session] was just issued, so the UI moves to the authenticated area. */
    fun onAuthenticated(session: IssuedSession)

    /** Ends the session locally and asks the backend to revoke it. */
    suspend fun signOut()
}

/**
 * Default [SessionManager].
 *
 * The startup decision follows the session's own expiry rather than waiting for a request to fail:
 * a session whose access token is still valid is restored at once, and an expired one is renewed
 * through the existing [SessionAuthenticator] before the app opens (`BR-018`). A renewal that the
 * backend refuses ends the session; one that cannot reach the backend does not, because nothing
 * proves the session invalid and the app is offline-first (`BR-013`, `BR-031`).
 */
@Singleton
class DefaultSessionManager @Inject constructor(
    private val sessionStore: SessionStore,
    private val sessionAuthenticator: SessionAuthenticator,
    private val authApi: AuthApi,
    private val clock: Clock,
) : SessionManager {

    private val _state = MutableStateFlow<AuthState>(AuthState.Checking)
    override val state: StateFlow<AuthState> = _state.asStateFlow()

    private val restoration = Mutex()
    private var restored = false

    override suspend fun restore() = restoration.withLock {
        if (restored) {
            return@withLock
        }
        restored = true

        sessionStore.restore()
        val stored = sessionStore.current()
        if (stored == null) {
            _state.value = AuthState.SignedOut
            return@withLock
        }

        if (isAccessTokenUsable(stored)) {
            showSignedIn(stored)
            return@withLock
        }

        when (sessionAuthenticator.renew(stored.tokens.accessToken)) {
            is SessionRenewal.Renewed -> showSignedIn(sessionStore.current())
            SessionRenewal.Rejected -> {
                // The backend refused the refresh token: the session is over, so the credential is
                // cleared and the user signs in again.
                sessionStore.clear()
                _state.value = AuthState.SignedOut
            }

            SessionRenewal.Unavailable -> showSignedIn(stored)
        }
    }

    override fun onAuthenticated(session: IssuedSession) {
        restored = true
        showSignedIn(session)
    }

    override suspend fun signOut() {
        val accessToken = sessionStore.accessToken()
        // Local sign-out must succeed even offline, so the session is cleared before the
        // best-effort server revocation. The captured token still names the session to revoke.
        sessionStore.clear()
        _state.value = AuthState.SignedOut
        if (accessToken != null) {
            runCatching { authApi.signOut("Bearer $accessToken") }
        }
    }

    private fun showSignedIn(session: IssuedSession?) {
        _state.value = AuthState.SignedIn(session?.permissions.orEmpty())
    }

    /**
     * Whether the session's access token has not yet expired.
     *
     * The comparison is only a pre-emptive check: the backend remains the authority, so a device
     * clock that disagrees with the server costs at most one extra renewal or one refused call.
     */
    private fun isAccessTokenUsable(session: IssuedSession): Boolean {
        val expiresAt = parseInstant(session.tokens.accessTokenExpiresAt) ?: return false
        return expiresAt.isAfter(clock.instant())
    }

    private fun parseInstant(value: String): Instant? =
        try {
            Instant.parse(value)
        } catch (failure: DateTimeParseException) {
            null
        }
}
