package com.servora.android.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.servora.android.data.session.AuthState
import com.servora.android.data.session.SessionManager
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Drives the authentication gate the whole app sits behind.
 *
 * Restoration is a data concern (`SessionManager`), so this only runs it once when the process
 * starts and exposes the resulting [AuthState] to the UI. Because the state starts at
 * [AuthState.Checking] and is only resolved after the persisted session has been read, no
 * authenticated-then-sign-in flash can occur (`BR-014`).
 */
@HiltViewModel
class SessionViewModel @Inject constructor(
    private val sessionManager: SessionManager,
) : ViewModel() {

    val state: StateFlow<AuthState> = sessionManager.state

    init {
        viewModelScope.launch { sessionManager.restore() }
    }

    /** Ends the session and returns the app to the authentication flow. */
    fun signOut() {
        viewModelScope.launch { sessionManager.signOut() }
    }
}
