package com.servora.android.data.customers

import com.servora.android.data.offline.WorkingSetEntityTypes
import com.servora.android.data.offline.WorkingSetEntry
import com.servora.android.data.offline.WorkingSetStore
import com.servora.android.domain.model.CustomerDetail
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The one local answer the customer detail read keeps
 * (`docs/architecture/offline-first-architecture.md` §2).
 *
 * The screen composes four API reads — the header, the active Properties, the archived Properties
 * and the Jobs — so the working set holds those four answers as the backend gave them. Serving a read
 * offline therefore runs the same mapper the online read runs, and the local projection cannot
 * describe the customer differently (`BR-041`).
 */
@Serializable
internal data class CustomerDetailPayload(
    val detail: CustomerDetailDto,
    val properties: List<CustomerPropertyDto> = emptyList(),
    val archivedProperties: List<CustomerPropertyDto> = emptyList(),
    val jobs: List<CustomerJobDto> = emptyList(),
)

/** The customer detail's use of the working set. */
@Singleton
class CustomerDetailCache @Inject constructor(
    private val workingSet: WorkingSetStore,
    private val json: Json,
    private val clock: Clock,
) {

    /**
     * Records the answers the backend just gave, replacing whatever was held before (§2).
     *
     * Internal because the payload is the read's own wire shape: only the repository that composes
     * the customer detail writes it.
     */
    internal suspend fun remember(
        subjectId: String,
        customerId: String,
        payload: CustomerDetailPayload,
    ) {
        workingSet.put(
            WorkingSetEntry(
                subjectId = subjectId,
                entityType = WorkingSetEntityTypes.CUSTOMER_DETAIL,
                entityId = customerId,
                scopeId = null,
                version = null,
                payload = json.encodeToString(CustomerDetailPayload.serializer(), payload),
                reportedAt = clock.millis(),
            ),
        )
    }

    /**
     * The last detail the backend reported, or `null` when none is held.
     *
     * An answer this build cannot map is treated as no answer: the read then reports the honest
     * failure it would report without a local copy (`BR-042`).
     */
    suspend fun reported(subjectId: String, customerId: String): CustomerDetail? {
        val entry = workingSet.get(
            subjectId = subjectId,
            entityType = WorkingSetEntityTypes.CUSTOMER_DETAIL,
            entityId = customerId,
        ) ?: return null
        val payload = try {
            json.decodeFromString(CustomerDetailPayload.serializer(), entry.payload)
        } catch (failure: SerializationException) {
            return null
        }
        return payload.detail.toDetail(
            properties = payload.properties,
            archivedProperties = payload.archivedProperties,
            jobs = payload.jobs,
        )
    }
}
