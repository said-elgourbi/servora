package com.servora.android.data.customers

import com.servora.android.data.offline.WorkingSetEntityTypes
import com.servora.android.data.offline.WorkingSetEntry
import com.servora.android.data.offline.WorkingSetStore
import com.servora.android.domain.model.Customer
import com.servora.android.domain.model.CustomerFilters
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The list answer the backend last reported for one filter
 * (`docs/architecture/offline-first-architecture.md` §2).
 *
 * The payload is the list endpoint's own wire shape, as received: serving a read offline therefore
 * runs the same mapper the online read runs, and the local list cannot describe a customer
 * differently (`BR-041`).
 */
@Serializable
internal data class CustomerListPayload(val rows: List<CustomerDto>)

/**
 * The customer list's use of the working set.
 *
 * One row is kept **per filter**, because the backend filters the list and the filter is what makes
 * one answer this answer: a list read for another filter shows rows the current one excludes, so it
 * is never served in its place (`BR-001`, §10).
 */
@Singleton
class CustomerListCache @Inject constructor(
    private val workingSet: WorkingSetStore,
    private val json: Json,
    private val clock: Clock,
) {

    /**
     * Records the rows the backend just returned for [filters], replacing that filter's earlier
     * answer (§10).
     *
     * Internal because the payload is the read's own wire shape: only the repository that performs
     * the list read writes it.
     */
    internal suspend fun remember(
        subjectId: String,
        filters: CustomerFilters,
        rows: List<CustomerDto>,
    ) {
        workingSet.put(
            WorkingSetEntry(
                subjectId = subjectId,
                entityType = WorkingSetEntityTypes.CUSTOMER_LIST,
                entityId = filters.cacheKey(),
                scopeId = null,
                version = null,
                payload = json.encodeToString(
                    CustomerListPayload.serializer(),
                    CustomerListPayload(rows),
                ),
                reportedAt = clock.millis(),
            ),
        )
    }

    /**
     * The last list the backend reported for [filters], or `null` when none is held.
     *
     * An answer this build cannot map is treated as no answer: the read then reports the honest
     * failure it would report without a local copy (`BR-042`).
     */
    suspend fun reported(subjectId: String, filters: CustomerFilters): List<Customer>? {
        val entry = workingSet.get(
            subjectId = subjectId,
            entityType = WorkingSetEntityTypes.CUSTOMER_LIST,
            entityId = filters.cacheKey(),
        ) ?: return null
        val payload = try {
            json.decodeFromString(CustomerListPayload.serializer(), entry.payload)
        } catch (failure: SerializationException) {
            return null
        }
        return payload.rows.toCustomers()
    }
}

/**
 * The local key one filter's answer is held under.
 *
 * This is a local storage key rather than a wire value: the two codes are already the stable filter
 * codes the API documents and validates (`BR-041`), and the pair is what identifies the answer.
 */
internal fun CustomerFilters.cacheKey(): String = "${status.name}:${jobs.name}"
