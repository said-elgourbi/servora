package com.servora.android.data.jobs

import com.servora.android.data.offline.OutboxFailureReason
import kotlinx.serialization.json.Json
import retrofit2.HttpException

/**
 * Classifies an upload the API refused into the reason the technician is shown (§6, `dev.md` §7).
 *
 * It is stated once because both evidence kinds upload the same way and are answered by the same error
 * envelope: a status that means "you are no longer allowed to record here" cannot mean one thing for a
 * photo and another for a recording (`BR-041`). The mapping is what makes a refusal safe to keep rather
 * than retry — everything this does not name is reported as unexpected rather than assumed retryable
 * (`BR-032`).
 *
 * The API's **stable code** decides where the status alone cannot: `409` is a version conflict for a
 * versioned write and a **closed Job** for a field write, and those two answers have to be worded
 * differently (`BR-041`, `BR-062`, `BR-079`).
 */
internal fun uploadFailureReason(
    status: Int,
    errorCode: String? = null,
): OutboxFailureReason =
    if (errorCode == CODE_JOB_CLOSED_FOR_FIELD_WORK) {
        OutboxFailureReason.JOB_CLOSED
    } else {
        when (status) {
            HTTP_BAD_REQUEST, HTTP_TOO_LARGE, HTTP_UNPROCESSABLE -> OutboxFailureReason.INVALID
            HTTP_FORBIDDEN -> OutboxFailureReason.NOT_AUTHORIZED
            HTTP_NOT_FOUND -> OutboxFailureReason.NOT_FOUND
            HTTP_CONFLICT -> OutboxFailureReason.STALE
            in HTTP_SERVER_ERROR..HTTP_SERVER_ERROR_MAX -> OutboxFailureReason.SERVER
            else -> OutboxFailureReason.UNEXPECTED
        }
    }

/**
 * Reads the API's stable error code from a refused response, best-effort.
 *
 * The classification is already known from the HTTP status, so a body this build cannot read means the
 * refusal is reported without its code — never that it is reported as something it was not (`BR-032`).
 */
internal fun HttpException.apiErrorCode(json: Json): String? =
    runCatching {
        val body = response()?.errorBody()?.string()
        if (body.isNullOrBlank()) null
        else json.decodeFromString<ApiErrorDto>(body).code
    }.getOrNull()

/** The API's stable code for a field write under a Job that is `COMPLETED` or `CANCELED` (`BR-079`). */
internal const val CODE_JOB_CLOSED_FOR_FIELD_WORK = "JOB_CLOSED_FOR_FIELD_WORK"

private const val HTTP_BAD_REQUEST = 400
private const val HTTP_FORBIDDEN = 403
private const val HTTP_NOT_FOUND = 404
private const val HTTP_CONFLICT = 409
private const val HTTP_TOO_LARGE = 413
private const val HTTP_UNPROCESSABLE = 422
private const val HTTP_SERVER_ERROR = 500
private const val HTTP_SERVER_ERROR_MAX = 599
