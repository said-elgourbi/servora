package com.servora.android.data.push

import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.PUT
import retrofit2.http.Path

interface PushInstallationsApi {
    @PUT("push/installations/{id}")
    suspend fun put(
        @Header("Authorization") authorization: String,
        @Header("X-Push-Installation-Secret") secret: String,
        @Path("id") id: String,
        @Body request: PushRegistrationRequest,
    ): PushRegistrationResponse

    @GET("push/installations/{id}")
    suspend fun get(
        @Header("Authorization") authorization: String,
        @Header("X-Push-Installation-Secret") secret: String,
        @Path("id") id: String,
    ): PushRegistrationResponse

    @DELETE("push/installations/{id}")
    suspend fun delete(
        @Header("Authorization") authorization: String,
        @Header("X-Push-Installation-Secret") secret: String,
        @Path("id") id: String,
    )
}

@Serializable
data class PushRegistrationRequest(
    val expectedRevision: Int,
    val applicationId: String,
    val registration: PushProviderRegistration,
    val locale: String,
    val alertsEnabled: Boolean,
    val notificationPermission: String,
    val channelEnabled: Boolean,
)

@Serializable
data class PushProviderRegistration(val kind: String, val value: String)

@Serializable
data class PushRegistrationResponse(
    val installationId: String,
    val bindingId: String,
    val revision: Int,
    val alertsEnabled: Boolean,
    val deliveryEnabled: Boolean,
    val registeredAt: String,
    val organizationId: String? = null,
)

/** Latest device state; never stored as a historical mutation in the business outbox. */
data class PushDeviceState(
    val locale: String = "en-CA",
    val notificationPermission: String = "NOT_REQUESTED",
    val channelEnabled: Boolean = false,
)
