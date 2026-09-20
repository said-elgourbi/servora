package com.servora.android.data.schedule

import com.servora.android.data.customers.CustomersFailureReason
import com.servora.android.data.customers.couldNotReachBackend
import com.servora.android.data.offline.ReadSource
import com.servora.android.data.session.AuthenticatedSubject
import com.servora.android.data.session.SessionAuthenticator
import com.servora.android.data.session.SessionRenewal
import com.servora.android.domain.model.Schedule
import com.servora.android.domain.model.ScheduleAddress
import com.servora.android.domain.model.ScheduleDay
import com.servora.android.domain.model.ScheduleScope
import com.servora.android.domain.model.ScheduleScopeKind
import com.servora.android.domain.model.ScheduleTechnician
import com.servora.android.domain.model.ScheduleVisit
import com.servora.android.domain.model.VisitStatus
import java.io.IOException
import javax.inject.Inject
import kotlinx.serialization.SerializationException
import retrofit2.HttpException

/**
 * Reads one local day's schedule from the backend.
 *
 * The backend authorizes the read **and its scope**, owns the tenant boundary, resolves the day and
 * decides what the unassigned lane holds, so this layer never names an organization, never decides
 * which technician a caller may read and never re-derives a scheduling rule of its own (`BR-001`,
 * `BR-007`, `BR-042`). One read serves the office's day and a technician's own work; which of the two a
 * caller gets is the API's answer, reported as [Schedule.scope].
 */
interface ScheduleRepository {
    /**
     * Returns the schedule for [localDate] exactly as it writes a day (`YYYY-MM-DD`), or why it could
     * not be read.
     *
     * [timeZone] is the device's own zone and [membershipIds] the technicians the day is narrowed to
     * (`emptyList` for the whole organization, and always empty for a field caller, who reads their
     * own work), so the day the backend resolves is the day the caller is working in and the filter
     * uses the assignment vocabulary (`BR-068`).
     */
    suspend fun loadSchedule(
        localDate: String,
        timeZone: String,
        membershipIds: List<String>,
    ): ScheduleResult
}

/**
 * Default [ScheduleRepository].
 *
 * It keeps the same session contract as the other repositories: the access token is read from
 * [SessionAuthenticator], and a read the backend refuses with `401` is retried once with a renewed
 * session before the outcome is reported.
 *
 * **Offline posture** (`docs/architecture/offline-first-architecture.md` §13). `BR-013` names
 * “viewing assigned work” as field work that must survive lost connectivity, so a read that answered
 * for the caller's **own** work is remembered day by day in the working set ([ScheduleCache]) and
 * served from there only when the API cannot be reached. An **organization-scoped** answer is not
 * kept: the office board is a dispatcher's read over the operation's own work and stays online-only
 * (`ADR-020` D6). A refusal is never replaced by a local copy — an answer, `403` included, is the
 * backend's own (`BR-007`, `BR-042`).
 */
class DefaultScheduleRepository @Inject constructor(
    private val api: ScheduleApi,
    private val sessionAuthenticator: SessionAuthenticator,
    /** The days the backend answered for the caller's own work, for a read that cannot reach it. */
    private val cache: ScheduleCache,
    /** The subject the working set is partitioned by; local answers never cross sessions. */
    private val subject: AuthenticatedSubject,
) : ScheduleRepository {

    override suspend fun loadSchedule(
        localDate: String,
        timeZone: String,
        membershipIds: List<String>,
    ): ScheduleResult {
        // A read whose subject cannot be read is refused rather than answered without local state
        // (§10) — the working set is scoped to the session that produced it.
        val subjectId = subject.current()
            ?: return ScheduleResult.Failure(CustomersFailureReason.UNAUTHENTICATED)
        val accessToken = sessionAuthenticator.accessToken()
            ?: return ScheduleResult.Failure(CustomersFailureReason.UNAUTHENTICATED)

        return when (
            val read = read(
                accessToken = accessToken,
                allowRenewal = true,
                localDate = localDate,
                timeZone = timeZone,
                membershipIds = membershipIds,
            )
        ) {
            is ScheduleRead.Answered -> {
                // Only the caller's own day is kept: it is the work `BR-013` makes readable without
                // connectivity, and the office board is deliberately not written to the working set.
                if (read.schedule.scope.kind == ScheduleScopeKind.SELF) {
                    cache.rememberDay(
                        subjectId = subjectId,
                        localDate = localDate,
                        schedule = read.payload,
                    )
                }
                ScheduleResult.Success(
                    schedule = read.schedule,
                    source = ReadSource.BACKEND,
                )
            }

            is ScheduleRead.Failed -> {
                if (!read.reason.couldNotReachBackend()) {
                    // The backend answered — a refusal included — so its answer stands (§2).
                    ScheduleResult.Failure(read.reason)
                } else {
                    val reported = cache.reportedDay(
                        subjectId = subjectId,
                        localDate = localDate,
                    )?.toSchedule()
                    if (reported == null) {
                        ScheduleResult.Failure(read.reason)
                    } else {
                        ScheduleResult.Success(
                            schedule = reported,
                            source = ReadSource.WORKING_SET,
                        )
                    }
                }
            }
        }
    }

    private suspend fun read(
        accessToken: String,
        allowRenewal: Boolean,
        localDate: String,
        timeZone: String,
        membershipIds: List<String>,
    ): ScheduleRead =
        try {
            val payload = api.schedule(
                authorization = "Bearer $accessToken",
                date = localDate,
                timeZone = timeZone,
                membershipIds = membershipIds,
            )
            val schedule = payload.toSchedule()
            if (schedule == null) {
                ScheduleRead.Failed(CustomersFailureReason.UNEXPECTED)
            } else {
                ScheduleRead.Answered(schedule = schedule, payload = payload)
            }
        } catch (failure: HttpException) {
            if (allowRenewal && failure.code() == HTTP_UNAUTHORIZED) {
                renewAndRetry(accessToken, localDate, timeZone, membershipIds)
            } else {
                ScheduleRead.Failed(failure.toFailureReason())
            }
        } catch (failure: IOException) {
            ScheduleRead.Failed(CustomersFailureReason.NETWORK)
        } catch (failure: SerializationException) {
            ScheduleRead.Failed(CustomersFailureReason.UNEXPECTED)
        }

    /** Retries the read once with a renewed session, or reports why it could not renew. */
    private suspend fun renewAndRetry(
        rejectedToken: String,
        localDate: String,
        timeZone: String,
        membershipIds: List<String>,
    ): ScheduleRead =
        when (val renewal = sessionAuthenticator.renew(rejectedToken)) {
            is SessionRenewal.Renewed ->
                read(
                    accessToken = renewal.accessToken,
                    allowRenewal = false,
                    localDate = localDate,
                    timeZone = timeZone,
                    membershipIds = membershipIds,
                )

            SessionRenewal.Rejected ->
                ScheduleRead.Failed(CustomersFailureReason.UNAUTHENTICATED)

            SessionRenewal.Unavailable ->
                ScheduleRead.Failed(CustomersFailureReason.NETWORK)
        }
}

/** One attempt at the read: the backend's answer, or why it did not arrive. */
private sealed interface ScheduleRead {
    data class Answered(
        val schedule: Schedule,
        /** The wire response as received, so the working set keeps what the backend reported. */
        val payload: ScheduleDto,
    ) : ScheduleRead

    data class Failed(val reason: CustomersFailureReason) : ScheduleRead
}


private const val HTTP_UNAUTHORIZED = 401

/** Classifies an HTTP answer into the stable reason the screen reports (`dev.md` 7). */
private fun HttpException.toFailureReason(): CustomersFailureReason =
    when (code()) {
        400, 422 -> CustomersFailureReason.VALIDATION
        401 -> CustomersFailureReason.UNAUTHENTICATED
        403 -> CustomersFailureReason.FORBIDDEN
        404 -> CustomersFailureReason.NOT_FOUND
        409 -> CustomersFailureReason.VERSION_CONFLICT
        in 500..599 -> CustomersFailureReason.SERVER
        else -> CustomersFailureReason.UNEXPECTED
    }

/**
 * Maps the wire payload onto the domain model, or reports a contract mismatch.
 *
 * A Visit status or a scope this build does not know fails the whole read rather than being dropped
 * silently, which is the contract-mismatch rule the other reads follow (`BR-042`, `BR-041`).
 */
internal fun ScheduleDto.toSchedule(): Schedule? {
    val scope = scope.toScope() ?: return null
    val visits = visits.map { it.toVisit() ?: return null }
    // An absent lane is a scope that has none; a present one is mapped row by row, so a lane the
    // read was not given is never presented as an empty one (`BR-042`).
    val lane = unassigned
    val unassigned = lane?.items?.map { it.toVisit() ?: return null } ?: emptyList()
    return Schedule(
        day = ScheduleDay(localDate = day.localDate, timeZone = day.timeZone),
        scope = scope,
        technicians = technicians.map { it.toTechnician() },
        visits = visits,
        unassigned = unassigned,
        unassignedTotal = lane?.total ?: 0,
        hasUnassignedLane = lane != null,
    )
}

/** The scope the read was resolved for, or `null` when this build does not know the code. */
private fun ScheduleScopeDto.toScope(): ScheduleScope? {
    val kind = ScheduleScopeKind.entries.firstOrNull { it.name == this.kind } ?: return null
    return ScheduleScope(kind = kind, membershipId = membershipId)
}

private fun ScheduleVisitDto.toVisit(): ScheduleVisit? {
    val status = VisitStatus.entries.firstOrNull { it.name == visitStatus } ?: return null
    return ScheduleVisit(
        visitId = visitId,
        visitStatus = status,
        scheduledStart = scheduledStart,
        scheduledEnd = scheduledEnd,
        jobId = jobId,
        jobNumber = jobNumber,
        jobTitle = jobTitle,
        customerId = customerId,
        customerName = customerName,
        address = address?.toAddress(),
        technicians = technicians.map { it.toTechnician() },
        isOverdue = overdue,
    )
}

/**
 * One technician: a Visit's crew member (`BR-068`) or one choice of the screen's filter.
 *
 * [roleCode] is the stable assignment role the API exchanges, or blank for a filter option, which is
 * not a crew row and carries no role.
 */
private fun ScheduleTechnicianDto.toTechnician(): ScheduleTechnician =
    ScheduleTechnician(
        membershipId = membershipId,
        name = name,
        roleCode = roleCode,
    )

private fun ScheduleAddressDto.toAddress(): ScheduleAddress =
    ScheduleAddress(
        propertyName = propertyName,
        addressLine1 = addressLine1,
        addressLine2 = addressLine2,
        city = city,
        province = province,
        postalCode = postalCode,
        country = country,
    )
