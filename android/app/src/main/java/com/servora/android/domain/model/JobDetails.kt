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
 * One of the Customer's contact persons (`BR-092`, `BR-095`).
 *
 * A name, a phone number, an email address and whether the person is the Customer's flagged primary and
 * nothing else: `BR-092` is a view-only read of the people a technician may need to speak to before or
 * during a Visit, so the billing-contact flag, the free-text `role` and the contact's write token are
 * deliberately absent (`BR-041`). A `null` phone or email is a detail the office never recorded, which the
 * screen leaves out rather than drawing empty.
 *
 * [isPrimary] is the contact row's own flag (`BR-095`): it is `false` on every contact of a Customer whose
 * primary contact is not recorded, and the Customer itself is then the effective primary.
 */
data class JobContactPerson(
    val firstName: String,
    val lastName: String,
    val phone: String?,
    val email: String?,
    val isPrimary: Boolean = false,
)

/**
 * The Customer's own contact details and contact persons, when the API included them (`BR-092`).
 *
 * The block is the Customer's own phone, email and notes together with its contact persons
 * (`BR-095`). The billing fields, the preferred contact method, the language, the Customer's addresses
 * and its Properties are not part of the read (`ADR-021` D3). A `null` value is a field the office never
 * recorded, which is a different thing from the whole block being absent — absence means the API did not
 * admit this session to the Customer, and the screen draws nothing rather than inventing a reason.
 */
data class JobCustomerContact(
    val phone: String?,
    val email: String?,
    val notes: String?,
    /**
     * The Customer's contact persons, primary first (`BR-095`).
     *
     * Empty for a Customer with none — an individual Customer is their own primary and holds no contact
     * person row — and for a payload that predates the field, which is what a working-set row written by
     * an earlier build holds (`BR-042`, `offline-first-architecture.md` §10).
     */
    val contacts: List<JobContactPerson> = emptyList(),
)

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
    /**
     * The statuses **this caller** may move this Visit to, as the API answered it (`BR-074`, `BR-093`).
     *
     * They come from the backend, which owns the lifecycle, so the screen draws its field action from
     * the server's own answer rather than holding a second copy of the rule (`BR-041`, `BR-022`). The
     * list is already narrowed to what the caller may execute: it is empty when neither the caller's crew
     * membership nor the office capability authorizes them, and `COMPLETED` is absent unless the session
     * holds `VISIT_RECORD_OUTCOME`, which a completion requires of every caller (`BR-077`, `BR-093`).
     * Whether the session holds the capability that reaches the route at all stays the client's own gate
     * and the API decides again at the route (`BR-007`, `BR-011`). `CANCELED` and `NO_SHOW` are absent
     * because they are dispatch actions no capability authorizes today (`BR-066`, `BR-076`).
     */
    val allowedStatusTransitions: List<VisitStatus> = emptyList(),
    /**
     * Whether **this caller** is authorized to drive this Visit, as the API answered it (`BR-093`).
     *
     * The other half of the question `allowedStatusTransitions` leaves open, and the half no client can
     * decide: the API evaluates the caller's current assignment when the action is performed and refuses
     * a Visit that crew does not include (`ADR-019` D3), **or** admits the caller through the office
     * capability that needs no crew membership at all (`ADR-019` D7). Without it a screen that shows a
     * Visit the caller may read — a colleague's on the same Job, or an office session's — would offer an
     * action whose only answer is a refused one (`BR-007`, `BR-041`).
     */
    val fieldActionable: Boolean,
)

/**
 * One Visit of the Job, as the Job Details read lists the Job's Visits (`BR-047`, `BR-071`).
 *
 * The screen represents one Visit ([JobDetails.selectedVisit]) and presents the Job's other Visits
 * beside it, because a Job may have several field attempts over time (`BR-051`) and the two are
 * different things (`BR-047`). [scheduledStart] and [scheduledEnd] are both `null` for a Visit that has
 * not been scheduled yet (`BR-072`).
 */
data class JobDetailsVisitSummary(
    val id: String,
    /** The Visit's stable, human-readable sequence within the Job: `1`, `2`, … (`BR-052`). */
    val sequence: Int,
    val status: VisitStatus,
    /**
     * What the Visit's completion recorded, or `null` when the Visit holds no outcome (`BR-077`,
     * `BR-078`).
     *
     * It is the Visit's **current** outcome (`BR-079`): a Visit that was completed and then reopened
     * holds none until it is completed again, so the field states what resulted from a field attempt
     * rather than what once did. The client derives nothing here — the API reports the Visit's own
     * outcome, and an outcome code this build cannot name is reported as no outcome rather than as a
     * different one (`BR-001`, `BR-042`).
     */
    val outcome: VisitOutcome?,
    /** ISO-8601 UTC instant the Visit is scheduled to start, or `null` when it has no schedule. */
    val scheduledStart: String?,
    /** ISO-8601 UTC instant the Visit is scheduled to end, or `null` when it has no schedule. */
    val scheduledEnd: String?,
    val version: Int,
    /** The technicians currently assigned to **this** Visit, Lead first (`BR-068`). */
    val technicians: List<JobDetailsTechnician>,
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
    /**
     * The Customer's own contact details, or `null` when the API did not include them (`BR-092`).
     *
     * Whether this session may read the Customer is the API's answer, never the client's: a caller with
     * no customer capability receives the same Job without the block, and the screen draws exactly what
     * it was given (`BR-001`, `BR-007`). A technician assigned to the Job therefore still sees the Job
     * even when they were not given the Customer.
     */
    val customerContactDetails: JobCustomerContact? = null,
    val address: CustomerJobAddress?,
    /** The represented Visit, or `null` when the Job has no Visit with a schedule (`BR-051`). */
    val selectedVisit: JobDetailsVisit?,
    /** Assigned technicians, Lead first (`BR-068`); empty when the represented Visit has none. */
    val technicians: List<JobDetailsTechnician>,
    /**
     * Every Visit of the Job, in the Job's own visit sequence (`BR-047`, `BR-051`, `BR-071`).
     *
     * The represented Visit is one entry of this list — the one [selectedVisit] names — and the list is
     * what lets a screen present the Job rather than a single field attempt. It is empty when the read
     * carried no visits, which an answer predating the field does (`BR-042`).
     */
    val visits: List<JobDetailsVisitSummary> = emptyList(),
)
