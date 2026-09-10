package com.servora.android.data.auth

import com.servora.android.domain.model.IssuedSession

/** Outcome of a sign-in attempt. */
sealed interface SignInResult {
    /** The backend authenticated the user and issued [session]. */
    data class Success(val session: IssuedSession) : SignInResult

    /** The attempt failed; [reason] decides what the UI reports. */
    data class Failure(val reason: AuthFailureReason) : SignInResult
}
