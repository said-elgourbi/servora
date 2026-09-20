package com.servora.android.data.jobs

import kotlinx.serialization.Serializable

/*
 * Wire contract for `POST /jobs` (`docs/api/job-details.md` §6).
 *
 * The request carries only what the caller supplies: the Customer the Job is for, the Property the work
 * is performed at and the title, with an optional description. Everything else the Job is created with
 * — its identifier, its organization-scoped number, its `NEW` status, its address snapshot and its
 * version — belongs to the backend and is never sent (`BR-001`, `BR-052`, `BR-056`, `BR-058`).
 *
 * The shared `Json` configuration omits members that keep their default, so an unfilled description is
 * simply absent from the request.
 */

/**
 * `POST /jobs` — the fields a Job is created with (`BR-053`, `BR-056`, `BR-094`).
 *
 * The supported create operation requires a Property: a client must not send a request without one, and
 * the API refuses one that names a Property the Customer does not currently hold (`BR-094`).
 */
@Serializable
data class CreateJobRequest(
    val customerId: String,
    val propertyId: String,
    val title: String,
    val description: String? = null,
)
