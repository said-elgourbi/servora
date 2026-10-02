package com.servora.android.data.schedule

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Query

interface AdHocWorkReportsApi {
    @POST("jobs/ad-hoc-work-reports")
    suspend fun submit(
        @Header("Authorization") authorization: String,
        @Body request: SubmitAdHocWorkReportRequestDto,
    ): AdHocWorkReportDto

    /** `GET .../customer-options` — the type-ahead Customer search (`BR-AH-009`). */
    @GET("jobs/ad-hoc-work-reports/customer-options")
    suspend fun customerOptions(
        @Header("Authorization") authorization: String,
        @Query("q") query: String,
    ): List<AdHocReportCustomerOptionDto>

    /** `GET .../property-options` — the active Properties of a selected Customer (`BR-050`). */
    @GET("jobs/ad-hoc-work-reports/property-options")
    suspend fun propertyOptions(
        @Header("Authorization") authorization: String,
        @Query("customerId") customerId: String,
    ): List<AdHocReportPropertyOptionDto>

    /** `GET .../job-options` — the Jobs of a selected Customer, the optional related-work hint. */
    @GET("jobs/ad-hoc-work-reports/job-options")
    suspend fun jobOptions(
        @Header("Authorization") authorization: String,
        @Query("customerId") customerId: String,
    ): List<AdHocReportJobOptionDto>
}
