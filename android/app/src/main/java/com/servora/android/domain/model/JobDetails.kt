package com.servora.android.domain.model

/**
 * The role a technician holds on a Visit (`BR-068`).
 *
 * A Visit with technicians has exactly one `LEAD`; every other assigned technician is a
 * `TECHNICIAN`. The codes are the backend's vocabulary (`BR-041`) and the client resolves a label
 * for one rather than inventing another. Servora has no third assignment role and does not use the
 * term "Helper".
 */
enum class AssignmentRole {
    LEAD,
    TECHNICIAN,
}

/**
 * One technician assigned to the Visit this Job Details screen represents (`BR-068`).
 *
 * [name] is absent when the member has no profile yet; the screen still shows the assignment, because
 * the technician really is assigned to the work (`BR-020`).
 */
data class JobDetailsTechnician(
    val membershipId: String,
    val name: String?,
    val role: AssignmentRole,
) {
    /** Whether this technician is the Visit's Lead, which is what the Lead badge marks (`BR-068`). */
    val isLead: Boolean get() = role == AssignmentRole.LEAD
}

/**
 * The single Visit a Job Details screen represents (`BR-081`).
 *
 * A Job may have several Visits over time (`BR-047`), so the screen shows the one the backend
 * selected — the same choice the customer-detail Job row presents, so both surfaces agree.
 */
data class JobDetailsVisit(
    val id: String,
    val status: VisitStatus,
    /** ISO-8601 UTC instant the Visit is scheduled to start. */
    val scheduledStart: String,
    val scheduledEnd: String,
    /** The Visit's version, echoed back when it is rescheduled or its crew changes (`BR-086`). */
    val version: Int,
    /**
     * Whether this Visit may be rescheduled (`BR-073`).
     *
     * The API answers it, so the screen draws its Reschedule action from the server's own answer
     * rather than deciding the lifecycle itself (`BR-041`, `BR-007`).
     */
    val reschedulable: Boolean,
)

/**
 * One technician who may be assigned to a Visit (`BR-024`, `BR-068`).
 *
 * It is what the assign sheet offers: the membership an assignment names and the name it presents.
 * Nothing else about a technician is modelled, because the Technician domain is not defined
 * (`BR-024`).
 */
data class AssignableTechnician(
    val membershipId: String,
    val name: String?,
)

/** One technician the user wants on a Visit, with the role they hold there (`BR-068`). */
data class TechnicianAssignment(
    val membershipId: String,
    val role: AssignmentRole,
)

/**
 * One technician's overlapping Visit, as `BR-070` requires it to be presented.
 *
 * The conflict identifies the Visit, the technician and the time window, because the user has to
 * decide whether to accept the overlap rather than being told that one exists.
 */
data class ScheduleConflict(
    val visitId: String,
    val jobNumber: Int,
    val technicianName: String?,
    /** ISO-8601 UTC instant the conflicting Visit starts. */
    val scheduledStart: String,
    val scheduledEnd: String,
)

/**
 * One Job as the backend described it (`BR-021`, `BR-058`, `BR-068`, `BR-081`).
 *
 * Everything here is a projection of authoritative records: the Job, its Customer, the address it
 * preserves and the Visit that represents it (`BR-001`, `BR-056`). The client holds no Job business
 * state of its own and never decides which Visit or which technicians the screen shows.
 *
 * [address] is the Job's preserved address snapshot (`BR-056`), the same snapshot shape the customer's
 * Job row carries.
 */
data class JobDetails(
    val id: String,
    /** The organization-scoped, human-readable Job number (`BR-052`). */
    val jobNumber: Int,
    val title: String,
    val description: String?,
    val status: JobStatus,
    /**
     * The statuses this Job may move to (`BR-058`).
     *
     * They come from the backend, which owns the lifecycle, so the screen draws its status actions
     * without holding a second copy of the rule (`BR-041`). `CANCELED` is absent while `BR-064`'s
     * cancellation-reason catalogue is an open question.
     */
    val allowedStatusTransitions: List<JobStatus>,
    /** The Job's version, echoed back when its status changes (`BR-086`). */
    val version: Int,
    val customerId: String,
    val customerName: String,
    val address: CustomerJobAddress?,
    /** The represented Visit, or `null` when the Job has no Visit with a schedule (`BR-051`). */
    val selectedVisit: JobDetailsVisit?,
    /** Assigned technicians, Lead first (`BR-068`); empty when the represented Visit has none. */
    val technicians: List<JobDetailsTechnician>,
)
