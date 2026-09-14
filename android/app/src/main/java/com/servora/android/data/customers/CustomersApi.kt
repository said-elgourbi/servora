package com.servora.android.data.customers

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.PATCH
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
     * `GET /customers/{id}/properties` — the customer's Properties with their derived row values
     * (`BR-081`).
     *
     * [status] selects the lifecycle projection: the backend defaults to `ACTIVE` and an archived
     * Property must be asked for explicitly (`BR-082`, `BR-083`), so a `null` omits the parameter.
     * The value is a stable code the API validates (`BR-041`).
     */
    @GET("customers/{id}/properties")
    suspend fun properties(
        @Header("Authorization") authorization: String,
        @Path("id") id: String,
        @Query("status") status: String? = null,
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

    /**
     * `POST /customers` — creates an organization-owned customer (`BR-023`).
     *
     * The backend authorizes the write and owns the tenant scope, so the request never names an
     * organization (`BR-001`, `BR-007`). The two methods exist because the create is discriminated:
     * one request shape carries the `individual` payload, the other the `company` payload.
     */
    @POST("customers")
    suspend fun createIndividualCustomer(
        @Header("Authorization") authorization: String,
        @Body request: CreateIndividualCustomerRequest,
    ): CreatedCustomerDto

    /** `POST /customers` — creates an organization-owned company customer (`BR-023`). */
    @POST("customers")
    suspend fun createCompanyCustomer(
        @Header("Authorization") authorization: String,
        @Body request: CreateCompanyCustomerRequest,
    ): CreatedCustomerDto

    /**
     * `POST /customers/{id}/contacts` — records a contact on an existing customer (`BR-023`).
     *
     * The route requires `customers.edit`; the backend decides, and a customer the caller's
     * organization does not own is reported as not found (`BR-001`).
     */
    @POST("customers/{id}/contacts")
    suspend fun createContact(
        @Header("Authorization") authorization: String,
        @Path("id") id: String,
        @Body request: CreateCustomerContactRequest,
    ): CustomerContactDto

    /**
     * `PATCH /customers/{id}` — edits an organization-owned customer (`BR-023`).
     *
     * The edit states the customer's kind, so the same call converts a customer between individual
     * and company when the stated type differs from the stored one; the backend replaces the subtype
     * record and records the conversion (`BR-087`). The backend authorizes the write with
     * `customers.edit` and owns the tenant scope (`BR-001`, `BR-007`).
     */
    @PATCH("customers/{id}")
    suspend fun updateCustomer(
        @Header("Authorization") authorization: String,
        @Path("id") id: String,
        @Body request: UpdateCustomerRequest,
    ): CreatedCustomerDto
}
