package com.servora.android.data.customers

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path

/**
 * Retrofit contract for the Property lifecycle endpoints (`BR-082` – `BR-086`).
 *
 * The access token is passed explicitly, as `CustomersApi` does, because the app has no transport
 * interceptor: keeping the header at the call site means a call cannot be sent a credential it was
 * not meant to carry. The organization and the Customer context are never sent — the backend derives
 * them from the session and the path (`BR-001`, `BR-007`).
 */
interface PropertiesApi {
    /** `GET /customers/{customerId}/properties/{propertyId}` — one Property (`properties.view`). */
    @GET("customers/{customerId}/properties/{propertyId}")
    suspend fun detail(
        @Header("Authorization") authorization: String,
        @Path("customerId") customerId: String,
        @Path("propertyId") propertyId: String,
    ): PropertyDetailDto

    /** `PATCH …/{propertyId}` — edits a Property (`BR-084`, `properties.edit`). */
    @PATCH("customers/{customerId}/properties/{propertyId}")
    suspend fun update(
        @Header("Authorization") authorization: String,
        @Path("customerId") customerId: String,
        @Path("propertyId") propertyId: String,
        @Body request: UpdatePropertyRequest,
    ): PropertyDetailDto

    /** `POST …/{propertyId}/archive` — archives a Property (`BR-082`, `properties.archive`). */
    @POST("customers/{customerId}/properties/{propertyId}/archive")
    suspend fun archive(
        @Header("Authorization") authorization: String,
        @Path("customerId") customerId: String,
        @Path("propertyId") propertyId: String,
        @Body request: PropertyLifecycleRequest,
    ): PropertyDetailDto

    /** `POST …/{propertyId}/restore` — restores a Property (`BR-082`, `properties.archive`). */
    @POST("customers/{customerId}/properties/{propertyId}/restore")
    suspend fun restore(
        @Header("Authorization") authorization: String,
        @Path("customerId") customerId: String,
        @Path("propertyId") propertyId: String,
        @Body request: PropertyLifecycleRequest,
    ): PropertyDetailDto

    /**
     * `DELETE …/{propertyId}` — permanently deletes an unreferenced Property (`BR-082`,
     * `properties.delete`).
     *
     * The answer is inspected through [Response] because the route has two meaningful outcomes: `204`
     * when nothing referenced the Property, and `409` when it has history and must be archived
     * instead.
     */
    @DELETE("customers/{customerId}/properties/{propertyId}")
    suspend fun delete(
        @Header("Authorization") authorization: String,
        @Path("customerId") customerId: String,
        @Path("propertyId") propertyId: String,
    ): Response<Unit>
}
