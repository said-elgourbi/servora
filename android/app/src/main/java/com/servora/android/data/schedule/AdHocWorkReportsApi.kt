package com.servora.android.data.schedule

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

interface AdHocWorkReportsApi {
    @GET("jobs/ad-hoc-work-reports/review-customer-options")
    suspend fun reviewCustomerOptions(@Header("Authorization") authorization: String, @Query("q") query: String): List<AdHocReportCustomerOptionDto>

    @GET("jobs/ad-hoc-work-reports/review-property-options")
    suspend fun reviewPropertyOptions(@Header("Authorization") authorization: String, @Query("customerId") customerId: String): List<AdHocReportPropertyOptionDto>

    @POST("jobs/ad-hoc-work-reports/{reportId}/resolve-location")
    suspend fun resolveLocation(@Header("Authorization") authorization: String, @Path("reportId") reportId: String, @Body request: ResolveAdHocLocationRequestDto): AdHocWorkReportDto

    @POST("jobs/ad-hoc-work-reports")
    suspend fun submit(
        @Header("Authorization") authorization: String,
        @Body request: SubmitAdHocWorkReportRequestDto,
    ): AdHocWorkReportDto

    /** `GET jobs/ad-hoc-work-reports` — the reports the caller may read (`BR-AH-002`). */
    @GET("jobs/ad-hoc-work-reports")
    suspend fun list(
        @Header("Authorization") authorization: String,
    ): List<AdHocWorkReportDto>

    /** `POST .../link` — reconciles a pending report into an existing Job (`BR-AH-002`, `BR-AH-006`). */
    @POST("jobs/ad-hoc-work-reports/{reportId}/link")
    suspend fun link(
        @Header("Authorization") authorization: String,
        @Path("reportId") reportId: String,
        @Body request: LinkAdHocWorkReportRequestDto,
    ): AdHocReconciliationJobDto

    /** `POST .../convert` — reconciles a pending report into a new Job (`BR-AH-002`). */
    @POST("jobs/ad-hoc-work-reports/{reportId}/convert")
    suspend fun convert(
        @Header("Authorization") authorization: String,
        @Path("reportId") reportId: String,
        @Body request: ConvertAdHocWorkReportRequestDto,
    ): AdHocReconciliationJobDto

    /** `POST .../reject` — voids a pending report as not legitimate work (`BR-AH-007`). */
    @POST("jobs/ad-hoc-work-reports/{reportId}/reject")
    suspend fun reject(
        @Header("Authorization") authorization: String,
        @Path("reportId") reportId: String,
        @Body request: RejectAdHocWorkReportRequestDto,
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

    /**
     * `GET .../review-job-options` — the Jobs an office reviewer may link a pending report to
     * (`BR-AH-002`). [customerId] scopes the list to a Customer; [query] searches the whole
     * organization by Job number or title when no Customer is known. Retrofit omits a `null` query.
     */
    @GET("jobs/ad-hoc-work-reports/review-job-options")
    suspend fun reviewJobOptions(
        @Header("Authorization") authorization: String,
        @Query("customerId") customerId: String?,
        @Query("q") query: String?,
    ): List<AdHocReportJobOptionDto>
}
