package com.servora.android.data.customers

import com.servora.android.data.offline.OutboxFailureReason
import com.servora.android.data.offline.ReplayOutcome

/**
 * How one call to the API ended, before the caller decides what to report or what to do with a
 * queued row.
 *
 * The distinction that matters is between a call the API **refused** — an answer the user has to act
 * on — and one that never applied, which is what makes an operation safe to queue and retry
 * (`BR-031`, `BR-032`, `docs/architecture/offline-first-architecture.md` §6).
 */
internal sealed interface Attempt {
    /** The API answered with the record. */
    data class Answered(val row: PropertyDetailDto) : Attempt

    /** The API refused the call; [reason] says why, and the reason does not change by retrying. */
    data class Refused(val reason: CustomersFailureReason) : Attempt

    /**
     * The request never applied and may later: no connectivity, a `5xx`, or a renewal that did not
     * reach the backend.
     */
    data object Undelivered : Attempt

    /** The session is over; the user signs in again before anything can be applied. */
    data object Unauthenticated : Attempt
}

/**
 * The classification of a customer endpoint's HTTP answer (`dev.md` §7).
 *
 * `401` and `403` are the authorization outcomes the endpoints document, a rejected payload is
 * [CustomersFailureReason.VALIDATION], a `409` is the version guard `BR-086` requires, and anything
 * this build cannot place stays [CustomersFailureReason.UNEXPECTED] rather than being guessed at
 * (`BR-042`).
 */
internal fun httpFailureReason(code: Int): CustomersFailureReason =
    when {
        code == HTTP_BAD_REQUEST -> CustomersFailureReason.VALIDATION
        code == HTTP_UNAUTHORIZED -> CustomersFailureReason.UNAUTHENTICATED
        code == HTTP_FORBIDDEN -> CustomersFailureReason.FORBIDDEN
        code == HTTP_NOT_FOUND -> CustomersFailureReason.NOT_FOUND
        code == HTTP_CONFLICT -> CustomersFailureReason.VERSION_CONFLICT
        code == HTTP_UNPROCESSABLE -> CustomersFailureReason.VALIDATION
        code >= HTTP_SERVER_ERROR -> CustomersFailureReason.SERVER
        else -> CustomersFailureReason.UNEXPECTED
    }

/**
 * What a refusal means for a queued operation's replay (§6).
 *
 * A refusal is terminal for the replay: the reason will not change by trying again, so the row is
 * kept and shown rather than retried (`BR-032`). Only a failure that could not reach the backend may
 * be retried.
 */
internal fun CustomersFailureReason.toReplayOutcome(): ReplayOutcome =
    when (this) {
        CustomersFailureReason.UNAUTHENTICATED -> ReplayOutcome.Unauthenticated
        CustomersFailureReason.NETWORK -> ReplayOutcome.Retryable(OutboxFailureReason.NETWORK)
        CustomersFailureReason.SERVER -> ReplayOutcome.Retryable(OutboxFailureReason.SERVER)

        CustomersFailureReason.FORBIDDEN ->
            ReplayOutcome.Rejected(OutboxFailureReason.NOT_AUTHORIZED)

        CustomersFailureReason.VERSION_CONFLICT -> ReplayOutcome.Rejected(OutboxFailureReason.STALE)
        CustomersFailureReason.VALIDATION -> ReplayOutcome.Rejected(OutboxFailureReason.INVALID)
        CustomersFailureReason.NOT_FOUND -> ReplayOutcome.Rejected(OutboxFailureReason.NOT_FOUND)
        CustomersFailureReason.UNEXPECTED -> ReplayOutcome.Rejected(OutboxFailureReason.UNEXPECTED)
    }

/**
 * What an attempt means for the row the engine is holding (§6).
 *
 * An answered call is applied; a refusal is terminal and kept; anything that never reached the
 * backend may be retried; and a refused session waits for the next sign-in.
 */
internal fun Attempt.toReplayOutcome(): ReplayOutcome =
    when (this) {
        is Attempt.Answered -> ReplayOutcome.Applied
        is Attempt.Refused -> reason.toReplayOutcome()
        Attempt.Undelivered -> ReplayOutcome.Retryable(OutboxFailureReason.NETWORK)
        Attempt.Unauthenticated -> ReplayOutcome.Unauthenticated
    }

private const val HTTP_BAD_REQUEST = 400
private const val HTTP_UNAUTHORIZED = 401
private const val HTTP_FORBIDDEN = 403
private const val HTTP_NOT_FOUND = 404
private const val HTTP_CONFLICT = 409
private const val HTTP_UNPROCESSABLE = 422
private const val HTTP_SERVER_ERROR = 500
