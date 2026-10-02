package com.servora.android.domain.model

/**
 * The discoverability options the ad-hoc work report form presents (`BR-AH-009`).
 *
 * A technician reports field work that happened without a recorded Visit, and the form lets them say
 * *which* Customer it was for, *which* Property, and optionally *which* Job it relates to. Those
 * choices are discovered, not typed by id: the API answers a scoped search rather than exposing the
 * whole Customer list, and the Property and Job choices are derived from the selected Customer
 * (`BR-050`, `BR-094`).
 *
 * These are presentation values only — a stable identifier plus what the form shows. Which ids the
 * caller may submit is re-validated by the API on the submit route, so a client can never act on an
 * option it was not offered (`BR-001`, `BR-007`).
 */

/** One Customer the reporter may select, as the type-ahead search answers it. */
data class AdHocReportCustomerOption(
    val id: String,
    val displayName: String,
)

/** One active Property of the selected Customer the reporter may select (`BR-050`). */
data class AdHocReportPropertyOption(
    val id: String,
    val name: String?,
    val addressLine1: String,
    val addressLine2: String?,
    val city: String,
    val province: String,
    val postalCode: String,
    val country: String,
)

/** One Job of the selected Customer the reporter may name as the related work. */
data class AdHocReportJobOption(
    val id: String,
    val jobNumber: Int,
    val title: String,
)
