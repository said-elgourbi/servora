package com.servora.android.data.customers

import com.servora.android.data.offline.OutboxReplayEngine
import com.servora.android.data.offline.ReadSource
import com.servora.android.data.session.AuthenticatedSubject
import com.servora.android.data.session.SessionAuthenticator
import com.servora.android.data.session.SessionRenewal
import com.servora.android.domain.model.PropertyArchiveImpact
import com.servora.android.domain.model.PropertyDetail
import com.servora.android.domain.model.PropertyStatus
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.SerializationException
import retrofit2.HttpException

/** Outcome of a Property read or mutation. */
sealed interface PropertyResult {
    /** The Property's values, from the backend or from the last answer it reported. */
    data class Success(
        val property: PropertyDetail,
        val source: ReadSource = ReadSource.BACKEND,
    ) : PropertyResult

    /**
     * The API could not be reached, so the action is queued and will be applied when it can
     * (`BR-086`).
     *
     * [property] is the last state the backend reported, or `null` when none is held: nothing has
     * been applied, and the screen presents the queued action as pending rather than as done.
     */
    data class Queued(val property: PropertyDetail?) : PropertyResult

    /** The operation failed; [reason] decides what the screen reports. */
    data class Failure(val reason: CustomersFailureReason) : PropertyResult
}

/** Outcome of a permanent deletion. */
sealed interface PropertyDeleteResult {
    /** The Property was removed, or was already gone. */
    data object Success : PropertyDeleteResult

    /**
     * The API refused the deletion because a business record references the Property (`BR-082`).
     *
     * This is not a failure: it is the answer that permanent deletion is not the removal this
     * Property has, and that archiving is.
     */
    data object HasReferences : PropertyDeleteResult

    /** The deletion failed; [reason] decides what the screen reports. */
    data class Failure(val reason: CustomersFailureReason) : PropertyDeleteResult
}

/**
 * Reads and mutates one organization-owned Property (`BR-082` – `BR-086`).
 *
 * The backend authorizes every call and owns the tenant boundary, the Customer context and the
 * lifecycle rules, so this layer never names an organization and never decides whether an operation
 * is allowed (`BR-001`, `BR-007`).
 */
interface PropertyRepository {
    /** Reads one Property, `ACTIVE` or `ARCHIVED`, with its derived values. */
    suspend fun loadProperty(
        customerId: String,
        propertyId: String,
    ): PropertyResult

    /** Edits a Property whether it is `ACTIVE` or `ARCHIVED` (`BR-084`). */
    suspend fun updateProperty(
        customerId: String,
        propertyId: String,
        request: UpdatePropertyRequest,
    ): PropertyResult

    /** Archives a Property; the API answers idempotently when it is already archived. */
    suspend fun archiveProperty(
        customerId: String,
        propertyId: String,
        request: PropertyLifecycleRequest,
    ): PropertyResult

    /** Restores an archived Property; the API answers idempotently when it is already active. */
    suspend fun restoreProperty(
        customerId: String,
        propertyId: String,
        request: PropertyLifecycleRequest,
    ): PropertyResult

    /** Permanently deletes an unreferenced Property, or reports that it has history. */
    suspend fun deleteProperty(
        customerId: String,
        propertyId: String,
    ): PropertyDeleteResult

    /**
     * The lifecycle action the backend has not accepted yet for one Property, or `null` (`§7`).
     *
     * A screen shows this next to the Property rather than presenting the queued change as applied
     * (`BR-086`).
     */
    suspend fun queuedOperation(propertyId: String): QueuedPropertyOperation?

    /**
     * Emits when a queued operation was accepted by the backend, so an open screen re-reads.
     *
     * The re-read is what replaces the local view with the state the API now reports (`§7`, §10).
     */
    val appliedOperations: Flow<Unit>
}

/**
 * Default [PropertyRepository].
 *
 * DefaultPropertyRepository keeps the same session contract as [DefaultCustomersRepository]: the
 * access token is read from [SessionAuthenticator], and a `401` is renewed once and retried before
 * the outcome is reported.
 *
 * It is also where the Property feature meets the offline standard: a read the API could not answer
 * is served from the last answer it reported (`§2`), and an archive or restore the API could not be
 * reached for is queued and replayed rather than reported as a failure (`BR-086`, §4). An edit is
 * **online-only**, because the API accepts no idempotency key for it, so §5 forbids queueing it.
 */
class DefaultPropertyRepository @Inject constructor(
    private val api: PropertiesApi,
    private val sessionAuthenticator: SessionAuthenticator,
    private val offline: PropertyOfflineStore,
    private val subject: AuthenticatedSubject,
    engine: OutboxReplayEngine,
) : PropertyRepository {

    override val appliedOperations: Flow<Unit> = engine.applied

    override suspend fun loadProperty(
        customerId: String,
        propertyId: String,
    ): PropertyResult {
        val subjectId = subject.current()
            ?: return PropertyResult.Failure(CustomersFailureReason.UNAUTHENTICATED)
        val accessToken = sessionAuthenticator.accessToken()
            ?: return PropertyResult.Failure(CustomersFailureReason.UNAUTHENTICATED)

        return when (val attempt = read(accessToken, allowRenewal = true, customerId, propertyId)) {
            is Attempt.Answered -> {
                // A successful read replaces the local copy rather than being merged into it (§10).
                offline.remember(subjectId, customerId, attempt.row)
                answer(attempt.row)
            }

            Attempt.Undelivered -> reported(subjectId, propertyId)
            is Attempt.Refused -> PropertyResult.Failure(attempt.reason)
            Attempt.Unauthenticated -> PropertyResult.Failure(CustomersFailureReason.UNAUTHENTICATED)
        }
    }

    override suspend fun queuedOperation(propertyId: String): QueuedPropertyOperation? {
        val subjectId = subject.current() ?: return null
        return offline.queuedAction(subjectId, propertyId)
    }

    override suspend fun updateProperty(
        customerId: String,
        propertyId: String,
        request: UpdatePropertyRequest,
    ): PropertyResult {
        val subjectId = subject.current()
            ?: return PropertyResult.Failure(CustomersFailureReason.UNAUTHENTICATED)
        val accessToken = sessionAuthenticator.accessToken()
            ?: return PropertyResult.Failure(CustomersFailureReason.UNAUTHENTICATED)
        val call: suspend (token: String) -> PropertyDetailDto = { token ->
            api.update(token, customerId, propertyId, request)
        }
        return when (val attempt = mutate(accessToken, allowRenewal = true, call)) {
            is Attempt.Answered -> {
                offline.remember(subjectId, customerId, attempt.row)
                answer(attempt.row)
            }

            is Attempt.Refused -> PropertyResult.Failure(attempt.reason)
            Attempt.Undelivered -> PropertyResult.Failure(CustomersFailureReason.NETWORK)
            Attempt.Unauthenticated -> PropertyResult.Failure(CustomersFailureReason.UNAUTHENTICATED)
        }
    }

    override suspend fun archiveProperty(
        customerId: String,
        propertyId: String,
        request: PropertyLifecycleRequest,
    ): PropertyResult = lifecycle(
        customerId = customerId,
        propertyId = propertyId,
        request = request,
        action = PropertyLifecycleAction.ARCHIVE,
    ) { token -> api.archive(token, customerId, propertyId, request) }

    override suspend fun restoreProperty(
        customerId: String,
        propertyId: String,
        request: PropertyLifecycleRequest,
    ): PropertyResult = lifecycle(
        customerId = customerId,
        propertyId = propertyId,
        request = request,
        action = PropertyLifecycleAction.RESTORE,
    ) { token -> api.restore(token, customerId, propertyId, request) }

    /**
     * Runs one lifecycle action, queueing it when the API could not be reached (`BR-086`).
     *
     * A refusal is reported as a failure rather than queued: the API answered, and its answer is the
     * one the user has to act on (`BR-032`). A refused session is not queued either, because the
     * operation would then replay under a session that has already ended.
     */
    private suspend fun lifecycle(
        customerId: String,
        propertyId: String,
        request: PropertyLifecycleRequest,
        action: PropertyLifecycleAction,
        call: suspend (token: String) -> PropertyDetailDto,
    ): PropertyResult {
        val subjectId = subject.current()
            ?: return PropertyResult.Failure(CustomersFailureReason.UNAUTHENTICATED)
        val accessToken = sessionAuthenticator.accessToken()
            ?: return PropertyResult.Failure(CustomersFailureReason.UNAUTHENTICATED)

        return when (val attempt = mutate(accessToken, allowRenewal = true, call)) {
            is Attempt.Answered -> {
                offline.remember(subjectId, customerId, attempt.row)
                answer(attempt.row)
            }

            Attempt.Undelivered -> queue(action, subjectId, customerId, propertyId, request)
            is Attempt.Refused -> PropertyResult.Failure(attempt.reason)
            Attempt.Unauthenticated -> PropertyResult.Failure(CustomersFailureReason.UNAUTHENTICATED)
        }
    }

    /**
     * Queues the action the API could not be reached for (`§4`).
     *
     * A request that carries no idempotency key cannot be replayed at most once, so it is reported as
     * the network failure it is rather than queued (§5).
     */
    private suspend fun queue(
        action: PropertyLifecycleAction,
        subjectId: String,
        customerId: String,
        propertyId: String,
        request: PropertyLifecycleRequest,
    ): PropertyResult {
        val queued = offline.queueLifecycle(action, subjectId, customerId, propertyId, request)
        if (!queued) {
            return PropertyResult.Failure(CustomersFailureReason.NETWORK)
        }
        // Nothing has been applied, so a queued action is never presented as done: the screen shows
        // the last state the backend reported plus the action that is waiting (`BR-086`, §7).
        return PropertyResult.Queued(offline.reportedDetail(subjectId, propertyId))
    }

    /** The last answer the backend reported, or the honest failure when none is held (`§2`). */
    private suspend fun reported(subjectId: String, propertyId: String): PropertyResult =
        offline.reportedDetail(subjectId, propertyId)?.let { property ->
            PropertyResult.Success(property, ReadSource.WORKING_SET)
        } ?: PropertyResult.Failure(CustomersFailureReason.NETWORK)

    override suspend fun deleteProperty(
        customerId: String,
        propertyId: String,
    ): PropertyDeleteResult {
        val subjectId = subject.current() ?: return PropertyDeleteResult.Failure(
            CustomersFailureReason.UNAUTHENTICATED,
        )
        val accessToken = sessionAuthenticator.accessToken()
            ?: return PropertyDeleteResult.Failure(
                CustomersFailureReason.UNAUTHENTICATED,
            )
        val result = delete(accessToken, allowRenewal = true, customerId, propertyId)
        if (result is PropertyDeleteResult.Success) {
            // The Property no longer exists, so the local copy of it must not outlive it (`§2`).
            offline.forget(subjectId, propertyId)
        }
        return result
    }

    /** Reads the Property once with [accessToken], renewing the session and retrying when allowed. */
    private suspend fun read(
        accessToken: String,
        allowRenewal: Boolean,
        customerId: String,
        propertyId: String,
    ): Attempt =
        try {
            Attempt.Answered(api.detail("Bearer $accessToken", customerId, propertyId))
        } catch (failure: HttpException) {
            if (allowRenewal && failure.code() == HTTP_UNAUTHORIZED) {
                renewAndRetryRead(accessToken, customerId, propertyId)
            } else {
                Attempt.Refused(httpFailureReason(failure.code()))
            }
        } catch (failure: IOException) {
            Attempt.Undelivered
        } catch (failure: SerializationException) {
            Attempt.Refused(CustomersFailureReason.UNEXPECTED)
        }

    /** Retries the read once with a renewed session, or reports why it could not renew. */
    private suspend fun renewAndRetryRead(
        rejectedToken: String,
        customerId: String,
        propertyId: String,
    ): Attempt =
        when (val renewal = sessionAuthenticator.renew(rejectedToken)) {
            is SessionRenewal.Renewed ->
                read(
                    renewal.accessToken,
                    allowRenewal = false,
                    customerId,
                    propertyId,
                )

            SessionRenewal.Rejected -> Attempt.Unauthenticated
            SessionRenewal.Unavailable -> Attempt.Undelivered
        }

    /**
     * Runs one Property mutation once, renewing the session and retrying when the backend refuses
     * the access token.
     *
     * A retry after a `401` is safe: the request was refused before it was applied. The mutation
     * still carries its own guard — the idempotency key for archive and restore, the expected version
     * for an edit — so a replay could not apply twice even if it reached the handler.
     */
    private suspend fun mutate(
        accessToken: String,
        allowRenewal: Boolean,
        call: suspend (token: String) -> PropertyDetailDto,
    ): Attempt =
        try {
            Attempt.Answered(call("Bearer $accessToken"))
        } catch (failure: HttpException) {
            if (allowRenewal && failure.code() == HTTP_UNAUTHORIZED) {
                when (val renewal = sessionAuthenticator.renew(accessToken)) {
                    is SessionRenewal.Renewed ->
                        mutate(renewal.accessToken, allowRenewal = false, call)

                    SessionRenewal.Rejected -> Attempt.Unauthenticated
                    SessionRenewal.Unavailable -> Attempt.Undelivered
                }
            } else {
                Attempt.Refused(httpFailureReason(failure.code()))
            }
        } catch (failure: IOException) {
            Attempt.Undelivered
        } catch (failure: SerializationException) {
            Attempt.Refused(CustomersFailureReason.UNEXPECTED)
        }

    /**
     * Deletes the Property once, renewing the session and retrying when the backend refuses the
     * access token.
     *
     * A `404` is reported as success: the record is already gone, which is the outcome the caller
     * asked for. That keeps a retried deletion — for example after connectivity dropped once the
     * backend had already applied it — from reporting a failure for work that is done.
     */
    private suspend fun delete(
        accessToken: String,
        allowRenewal: Boolean,
        customerId: String,
        propertyId: String,
    ): PropertyDeleteResult =
        try {
            val response = api.delete("Bearer $accessToken", customerId, propertyId)
            when {
                response.isSuccessful -> PropertyDeleteResult.Success
                response.code() == HTTP_NOT_FOUND -> PropertyDeleteResult.Success
                response.code() == HTTP_CONFLICT ->
                    PropertyDeleteResult.HasReferences

                response.code() == HTTP_UNAUTHORIZED && allowRenewal ->
                    renewAndRetryDelete(accessToken, customerId, propertyId)

                else -> PropertyDeleteResult.Failure(
                    httpFailureReason(response.code()),
                )
            }
        } catch (failure: IOException) {
            PropertyDeleteResult.Failure(CustomersFailureReason.NETWORK)
        } catch (failure: SerializationException) {
            PropertyDeleteResult.Failure(CustomersFailureReason.UNEXPECTED)
        }

    private suspend fun renewAndRetryDelete(
        rejectedToken: String,
        customerId: String,
        propertyId: String,
    ): PropertyDeleteResult =
        when (val renewal = sessionAuthenticator.renew(rejectedToken)) {
            is SessionRenewal.Renewed ->
                delete(
                    renewal.accessToken,
                    allowRenewal = false,
                    customerId,
                    propertyId,
                )

            SessionRenewal.Rejected ->
                PropertyDeleteResult.Failure(
                    CustomersFailureReason.UNAUTHENTICATED,
                )

            SessionRenewal.Unavailable ->
                PropertyDeleteResult.Failure(CustomersFailureReason.NETWORK)
        }

    /** Maps a wire row onto the domain Property, or reports a contract mismatch. */
    private fun answer(row: PropertyDetailDto): PropertyResult {
        val property = row.toPropertyDetail()
        return if (property == null) {
            PropertyResult.Failure(CustomersFailureReason.UNEXPECTED)
        } else {
            PropertyResult.Success(property)
        }
    }

    private companion object {
        /** The delete route's own answers, which are not failures (`BR-082`). */
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_NOT_FOUND = 404
        const val HTTP_CONFLICT = 409
    }
}

/**
 * Maps a wire row onto the domain, or `null` when this build cannot represent a value it carries.
 *
 * An unknown lifecycle state fails the read rather than being guessed at, which is the same
 * contract-mismatch rule the customer read follows (`BR-042`). The working set stores this row's
 * payload and maps it with this same function, so an offline read cannot describe the Property
 * differently from an online one (`BR-041`).
 */
internal fun PropertyDetailDto.toPropertyDetail(): PropertyDetail? {
    val propertyStatus =
        PropertyStatus.entries.firstOrNull { it.name == status } ?: return null
    return PropertyDetail(
        id = id,
        name = name,
        addressLine1 = addressLine1,
        addressLine2 = addressLine2,
        city = city,
        province = province,
        postalCode = postalCode,
        country = country,
        notes = notes,
        status = propertyStatus,
        version = version,
        archivedAt = archivedAt,
        jobCount = jobCount,
        lastServiceAt = lastServiceAt,
        archiveImpact = PropertyArchiveImpact(
            activeJobCount = archiveImpact.activeJobCount,
            activeVisitCount = archiveImpact.activeVisitCount,
        ),
        canBePermanentlyDeleted = canBePermanentlyDeleted,
    )
}

