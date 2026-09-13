package com.servora.android.data.session

import com.servora.android.domain.model.IssuedSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A [SessionManager] that records what it was asked and never touches storage or the network.
 *
 * Used by tests that care that a sign-in notified the session manager, not how the manager renews.
 */
internal class FakeSessionManager(
    initial: AuthState = AuthState.SignedOut,
) : SessionManager {

    private val _state = MutableStateFlow(initial)
    override val state: StateFlow<AuthState> = _state.asStateFlow()

    val authenticated = mutableListOf<IssuedSession>()
    var restoreCalls = 0
    var signOutCalls = 0

    override suspend fun restore() {
        restoreCalls += 1
    }

    override fun onAuthenticated(session: IssuedSession) {
        authenticated += session
        _state.value = AuthState.SignedIn(session.permissions)
    }

    override suspend fun signOut() {
        signOutCalls += 1
        _state.value = AuthState.SignedOut
    }
}
