package com.servora.android.data.schedule

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path

/** Retrofit contract for follow-up Visit request review. */
interface VisitRequestsApi {
    @GET("jobs/visit-requests")
    suspend fun visitRequests(
        @Header("Authorization") authorization: String,
    ): List<FollowUpVisitRequestDto>

    @POST("jobs/{jobId}/visit-requests/{requestId}/clarification")
    suspend fun clarify(
        @Header("Authorization") authorization: String,
        @Path("jobId") jobId: String,
        @Path("requestId") requestId: String,
        @Body request: FollowUpVisitReviewRequestDto,
    ): FollowUpVisitRequestDto

    @POST("jobs/{jobId}/visit-requests/{requestId}/rejection")
    suspend fun reject(
        @Header("Authorization") authorization: String,
        @Path("jobId") jobId: String,
        @Path("requestId") requestId: String,
        @Body request: FollowUpVisitReviewRequestDto,
    ): FollowUpVisitRequestDto
}
