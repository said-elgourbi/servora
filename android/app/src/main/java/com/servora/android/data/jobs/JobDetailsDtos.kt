package com.servora.android.data.jobs

import kotlinx.serialization.Serializable

/*
 * Wire contract for `GET /jobs/:id` (`docs/api/job-details.md`).
 *
 * These types mirror the JSON exactly (camelCase, UUID identifiers, ISO-8601 UTC timestamps) and stay
 * in the data layer: the rest of the app exchanges `com.servora.android.domain.model.JobDetails`
 * instead.
 */

/** Response body of the Job Details read. */
@Serializable
data class JobDetailsDto(
    val id: String,
    val jobNumber: Int,
    val title: String,
    val description: String? = null,
    val typeCode: String? = null,
    val status: String,
    val allowedStatusTransitions: List<String> = emptyList(),
    val version: Int = 0,
    val customerId: String,
    val customerName: String,
    /**
     * The Customer's own contact details, present only when the API included them (`BR-092`).
     *
     * Its absence is the API's answer to the read's second authorization question, so the client parses
     * what it was given rather than deciding whether the session may read the Customer (`BR-001`,
     * `BR-007`).
     */
    val customerContactDetails: JobDetailsCustomerContactDto? = null,
    val propertyId: String? = null,
    val address: JobDetailsAddressDto? = null,
    val selectedVisit: JobDetailsVisitDto? = null,
    val technicians: List<JobDetailsTechnicianDto> = emptyList(),
    /**
     * Every Visit of the Job, in the Job's own visit sequence (`BR-047`, `BR-051`, `BR-071`).
     *
     * It defaults to an empty list so a Job the API answered before this field existed — or a working-set
     * row written by an earlier build — is still readable rather than failing the whole read
     * (`BR-042`, `offline-first-architecture.md` §10).
     */
    val visits: List<JobDetailsVisitSummaryDto> = emptyList(),
)

/** The Visit the Job is represented by, with the schedule it carries (`BR-072`). */
@Serializable
data class JobDetailsVisitDto(
    val id: String,
    val status: String,
    val scheduledStart: String,
    val scheduledEnd: String,
    val version: Int = 0,
    val reschedulable: Boolean = false,
    /**
     * The statuses **this caller** may move the Visit to (`BR-074`, `BR-093`).
     *
     * The API reports the table `PATCH /jobs/{jobId}/visits/{visitId}/status` validates against,
     * already narrowed to what the caller is authorized to execute, so the screen draws its Visit
     * actions from the server's own answer rather than holding a second copy of the lifecycle
     * (`BR-022`, `BR-041`). An unknown code is dropped rather than offered. `CANCELED` and `NO_SHOW`
     * are absent because they are dispatch actions no capability authorizes today (`BR-066`).
     */
    val allowedStatusTransitions: List<String> = emptyList(),
    /**
     * Whether **this session** is authorized to drive the Visit's field lifecycle, as the API answered
     * it (`BR-074`, `BR-066`, `BR-093`; `ADR-019` D3, D7).
     *
     * It answers the part of the question the client cannot answer for itself: the caller's own
     * membership is on *this* Visit's current crew, or the caller holds the office capability that
     * admits them without crew membership, and the API evaluates that when the action is performed. The
     * capability the session holds stays the client's own gate beside it, so the two are never
     * conflated. It defaults to `false` so a response that predates the field — a cached one
     * included — never offers an action the API would refuse (`BR-042`, `BR-007`).
     */
    val fieldActionable: Boolean = false,
)

/**
 * One Visit of the Job, as the Job Details read lists the Job's Visits (`BR-047`, `BR-071`).
 *
 * The Job Details screen represents one Visit (`BR-081`); a Job may have several Visits over time and
 * each one is a field attempt of its own (`BR-051`), so the read also reports the Visits the Job holds.
 * `scheduledStart` and `scheduledEnd` are absent together for a Visit that has not been scheduled yet
 * (`BR-072`).
 */
@Serializable
data class JobDetailsVisitSummaryDto(
    val id: String,
    /** The Visit's stable, human-readable sequence within the Job: `1`, `2`, … (`BR-052`). */
    val sequence: Int,
    val status: String,
    /**
     * The Visit's own **current** outcome (`BR-077`, `BR-078`), or absent when it holds none.
     *
     * It carries no outcome history: a Visit that was reopened holds no current outcome until it is
     * completed again, which is exactly what the field reports (`BR-079`). It defaults to `null` so a
     * Job the API answered before the field existed — or a working-set row written by an earlier build
     * — is still readable rather than failing the whole read (`BR-042`,
     * `offline-first-architecture.md` §10).
     */
    val outcomeCode: String? = null,
    val scheduledStart: String? = null,
    val scheduledEnd: String? = null,
    val version: Int = 0,
    /** The technicians currently assigned to **this** Visit, Lead first (`BR-068`). */
    val technicians: List<JobDetailsTechnicianDto> = emptyList(),
)

/** One technician assigned to the represented Visit. [name] is absent without a member profile. */
@Serializable
data class JobDetailsTechnicianDto(
    val membershipId: String,
    val name: String? = null,
    val roleCode: String,
)

/**
 * The Customer's own contact details and contact persons (`BR-092`, `BR-095`).
 *
 * A field the office never recorded is `null`, which is a different thing from the whole block being
 * absent: the block is absent when the API did not admit this caller to the Customer at all.
 */
@Serializable
data class JobDetailsCustomerContactDto(
    val email: String? = null,
    val phone: String? = null,
    val notes: String? = null,
    /**
     * The Customer's contact persons, primary first (`BR-092`, `BR-095`).
     *
     * `[]` for a Customer with none — an individual Customer is their own primary and holds no contact
     * person row at all (`BR-095`) — and for an answer that predates the field, which is what a
     * working-set row written by an earlier build holds, so the block stays readable rather than failing
     * the whole read (`BR-042`, `offline-first-architecture.md` §10).
     */
    val contacts: List<JobDetailsContactPersonDto> = emptyList(),
)

/**
 * One of the Customer's contact persons on the field read (`BR-092`, `BR-095`).
 *
 * Exactly the fields `BR-092` names — the person's name, phone number, email address and whether they
 * are the Customer's flagged primary — so the field read cannot drift into reporting the billing-contact
 * flag, the free-text `role`, the job-contact flag or the contact's write token (`BR-041`).
 *
 * [isPrimary] defaults to `false` so an answer that predates the field, or a working-set row an earlier
 * build wrote, is read as "no contact is flagged" rather than failing the read (`BR-042`,
 * `offline-first-architecture.md` §10). That is the safe reading: `BR-095` makes the Customer itself the
 * effective primary when nothing is flagged, which is exactly what a payload without the field describes.
 */
@Serializable
data class JobDetailsContactPersonDto(
    val firstName: String,
    val lastName: String,
    val phone: String? = null,
    val email: String? = null,
    val isPrimary: Boolean = false,
)

/** The address the Job preserves for its Property (`BR-056`). */
@Serializable
data class JobDetailsAddressDto(
    val propertyName: String? = null,
    val addressLine1: String? = null,
    val addressLine2: String? = null,
    val city: String? = null,
    val province: String? = null,
    val postalCode: String? = null,
    val country: String? = null,
)
