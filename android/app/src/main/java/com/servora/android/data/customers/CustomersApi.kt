package com.servora.android.data.customers

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Retrofit contract for the customer endpoints.
 *
 * The access token is passed explicitly, as `AuthApi.me` does, because the app has no transport
 * interceptor yet: keeping the header at the call site means a call cannot be sent a credential
 * it was not meant to carry.
 */
interface CustomersApi {
    /**
     * `GET /customers` — the organization's customers, newest first (`customers.view`).
     *
     * [status] and [jobs] narrow the list on the backend, which remains the authority for which
     * rows a caller receives (`BR-001`, `BR-007`). The values are the stable filter codes the API
     * documents (`BR-041`); the backend rejects an unrecognized code.
     */
    @GET("customers")
    suspend fun list(
        @Header("Authorization") authorization: String,
        @Query("status") status: String,
        @Query("jobs") jobs: String,
    ): List<CustomerDto>

    /**
     * `GET /customers/{id}` — one customer's detail header, subtype and contacts (`customers.view`).
     */
    @GET("customers/{id}")
    suspend fun detail(
        @Header("Authorization") authorization: String,
        @Path("id") id: String,
    ): CustomerDetailDto

    /**
     * `GET /customers/{id}/properties` — the customer's active Properties with their derived row
     * values (`BR-081`).
     */
    @GET("customers/{id}/properties")
    suspend fun properties(
        @Header("Authorization") authorization: String,
        @Path("id") id: String,
    ): List<CustomerPropertyDto>

    /**
     * `GET /customers/{id}/jobs` — the customer's Jobs, newest first, each carrying its selected
     * Visit's schedule and technicians (`BR-081`).
     */
    @GET("customers/{id}/jobs")
    suspend fun jobs(
        @Header("Authorization") authorization: String,
        @Path("id") id: String,
    ): List<CustomerJobDto>

    /**
     * `POST /customers/{id}/properties` — creates an organization-owned Property related to the
     * customer (`BR-049`, `BR-050`). The backend authorizes the call and owns the tenant scope.
     */
    @POST("customers/{id}/properties")
    suspend fun createProperty(
        @Header("Authorization") authorization: String,
        @Path("id") id: String,
        @Body request: CreatePropertyRequest,
    ): CustomerPropertyDto
}
