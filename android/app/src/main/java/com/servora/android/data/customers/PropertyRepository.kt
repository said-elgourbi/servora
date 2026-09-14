package com.servora.android.data.customers

import com.servora.android.data.session.SessionAuthenticator
import com.servora.android.data.session.SessionRenewal
import com.servora.android.domain.model.PropertyArchiveImpact
import com.servora.android.domain.model.PropertyDetail
import com.servora.android.domain.model.PropertyStatus
import java.io.IOException
import javax.inject.Inject
import kotlinx.serialization.SerializationException
import retrofit2.HttpException

/** Outcome of a Property read or mutation. */
sealed interface PropertyResult {
    /** The backend answered with the Property's current values. */
    data class Success(val property: PropertyDetail) : PropertyResult

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
}

/**
 * Default [PropertyRepository].
 *
 * DefaultPropertyRepository keeps the same session contract as [DefaultCustomersRepository]: the
 * access token is read from [SessionAuthenticator], and a `401` is renewed once and retried before
 * the outcome is reported.
 */
class DefaultPropertyRepository @Inject constructor(
    private val api: PropertiesApi,
    private val sessionAuthenticator: SessionAuthenticator,
) : PropertyRepository {

    override suspend fun loadProperty(
        customerId: String,
        propertyId: String,
    ): PropertyResult {
        val accessToken = sessionAuthenticator.accessToken()
            ?: return PropertyResult.Failure(CustomersFailureReason.UNAUTHENTICATED)
        return read(accessToken, allowRenewal = true, customerId, propertyId)
    }

    override suspend fun updateProperty(
        customerId: String,
        propertyId: String,
        request: UpdatePropertyRequest,
    ): PropertyResult {
        val accessToken = sessionAuthenticator.accessToken()
            ?: return PropertyResult.Failure(CustomersFailureReason.UNAUTHENTICATED)
        return mutate(accessToken, allowRenewal = true) { token ->
            api.update(token, customerId, propertyId, request)
        }
    }

    override suspend fun archiveProperty(
        customerId: String,
        propertyId: String,
        request: PropertyLifecycleRequest,
    ): PropertyResult {
        val accessToken = sessionAuthenticator.accessToken()
            ?: return PropertyResult.Failure(CustomersFailureReason.UNAUTHENTICATED)
        return mutate(accessToken, allowRenewal = true) { token ->
            api.archive(token, customerId, propertyId, request)
        }
    }

    override suspend fun restoreProperty(
        customerId: String,
        propertyId: String,
        request: PropertyLifecycleRequest,
    ): PropertyResult {
        val accessToken = sessionAuthenticator.accessToken()
            ?: return PropertyResult.Failure(CustomersFailureReason.UNAUTHENTICATED)
        return mutate(accessToken, allowRenewal = true) { token ->
            api.restore(token, customerId, propertyId, request)
        }
    }

    override suspend fun deleteProperty(
        customerId: String,
        propertyId: String,
    ): PropertyDeleteResult {
        val accessToken = sessionAuthenticator.accessToken()
            ?: return PropertyDeleteResult.Failure(
                CustomersFailureReason.UNAUTHENTICATED,
            )
        return delete(accessToken, allowRenewal = true, customerId, propertyId)
    }

    /** Reads the Property once with [accessToken], renewing the session and retrying when allowed. */
    private suspend fun read(
        accessToken: String,
        allowRenewal: Boolean,
        customerId: String,
        propertyId: String,
    ): PropertyResult =
        try {
            answer(api.detail("Bearer $accessToken", customerId, propertyId))
        } catch (failure: HttpException) {
            if (allowRenewal && failure.code() == HTTP_UNAUTHORIZED) {
                renewAndRetryRead(accessToken, customerId, propertyId)
            } else {
                PropertyResult.Failure(failure.code().toFailureReason())
            }
        } catch (failure: IOException) {
            PropertyResult.Failure(CustomersFailureReason.NETWORK)
        } catch (failure: SerializationException) {
            PropertyResult.Failure(CustomersFailureReason.UNEXPECTED)
        }

    /** Retries the read once with a renewed session, or reports why it could not renew. */
    private suspend fun renewAndRetryRead(
        rejectedToken: String,
        customerId: String,
        propertyId: String,
    ): PropertyResult =
        when (val renewal = sessionAuthenticator.renew(rejectedToken)) {
            is SessionRenewal.Renewed ->
                read(
                    renewal.accessToken,
                    allowRenewal = false,
                    customerId,
                    propertyId,
                )

            SessionRenewal.Rejected ->
                PropertyResult.Failure(CustomersFailureReason.UNAUTHENTICATED)

            SessionRenewal.Unavailable ->
                PropertyResult.Failure(CustomersFailureReason.NETWORK)
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
    ): PropertyResult =
        try {
            answer(call("Bearer $accessToken"))
        } catch (failure: HttpException) {
            if (allowRenewal && failure.code() == HTTP_UNAUTHORIZED) {
                when (val renewal = sessionAuthenticator.renew(accessToken)) {
                    is SessionRenewal.Renewed ->
                        mutate(renewal.accessToken, allowRenewal = false, call)

                    SessionRenewal.Rejected ->
                        PropertyResult.Failure(
                            CustomersFailureReason.UNAUTHENTICATED,
                        )

                    SessionRenewal.Unavailable ->
                        PropertyResult.Failure(CustomersFailureReason.NETWORK)
                }
            } else {
                PropertyResult.Failure(failure.code().toFailureReason())
            }
        } catch (failure: IOException) {
            PropertyResult.Failure(CustomersFailureReason.NETWORK)
        } catch (failure: SerializationException) {
            PropertyResult.Failure(CustomersFailureReason.UNEXPECTED)
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
                    response.code().toFailureReason(),
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

    private fun Int.toFailureReason(): CustomersFailureReason =
        when {
            this == HTTP_UNAUTHORIZED -> CustomersFailureReason.UNAUTHENTICATED
            this == HTTP_FORBIDDEN -> CustomersFailureReason.FORBIDDEN
            this == HTTP_NOT_FOUND -> CustomersFailureReason.NOT_FOUND
            this == HTTP_CONFLICT -> CustomersFailureReason.VERSION_CONFLICT
            this == HTTP_UNPROCESSABLE -> CustomersFailureReason.VALIDATION
            this == HTTP_BAD_REQUEST -> CustomersFailureReason.VALIDATION
            this >= HTTP_SERVER_ERROR -> CustomersFailureReason.SERVER
            else -> CustomersFailureReason.UNEXPECTED
        }

    private companion object {
        const val HTTP_BAD_REQUEST = 400
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_FORBIDDEN = 403
        const val HTTP_NOT_FOUND = 404
        const val HTTP_CONFLICT = 409
        const val HTTP_UNPROCESSABLE = 422
        const val HTTP_SERVER_ERROR = 500
    }
}

/**
 * Maps a wire row onto the domain, or `null` when this build cannot represent a value it carries.
 *
 * An unknown lifecycle state fails the read rather than being guessed at, which is the same
 * contract-mismatch rule the customer read follows (`BR-042`).
 */
private fun PropertyDetailDto.toPropertyDetail(): PropertyDetail? {
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

