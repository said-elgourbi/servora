package com.servora.android.data.customers

import com.servora.android.data.session.SessionAuthenticator
import com.servora.android.data.session.SessionRenewal
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.HttpException
import retrofit2.Response

/**
 * A [PropertiesApi] a test scripts.
 *
 * It stands in for the generated Retrofit implementation, so a test can decide what each call
 * answers and read back what was sent.
 */
internal class FakePropertiesApi : PropertiesApi {

    var detailAnswer: suspend (customerId: String, propertyId: String) -> PropertyDetailDto =
        { _, _ -> error("the Property read was not scripted") }

    var archiveAnswer: suspend (request: PropertyLifecycleRequest) -> PropertyDetailDto =
        { _ -> error("the archive was not scripted") }

    var restoreAnswer: suspend (request: PropertyLifecycleRequest) -> PropertyDetailDto =
        { _ -> error("the restore was not scripted") }

    var updateAnswer: suspend (request: UpdatePropertyRequest) -> PropertyDetailDto =
        { _ -> error("the edit was not scripted") }

    var deleteAnswer: suspend () -> Response<Unit> = { Response.success(Unit) }

    var lastAuthorization: String? = null
    var lastLifecycleRequest: PropertyLifecycleRequest? = null
    var detailCalls: Int = 0
    var archiveCalls: Int = 0
    var restoreCalls: Int = 0
    var updateCalls: Int = 0
    var deleteCalls: Int = 0

    override suspend fun detail(
        authorization: String,
        customerId: String,
        propertyId: String,
    ): PropertyDetailDto {
        detailCalls += 1
        lastAuthorization = authorization
        return detailAnswer(customerId, propertyId)
    }

    override suspend fun update(
        authorization: String,
        customerId: String,
        propertyId: String,
        request: UpdatePropertyRequest,
    ): PropertyDetailDto {
        updateCalls += 1
        lastAuthorization = authorization
        return updateAnswer(request)
    }

    override suspend fun archive(
        authorization: String,
        customerId: String,
        propertyId: String,
        request: PropertyLifecycleRequest,
    ): PropertyDetailDto {
        archiveCalls += 1
        lastAuthorization = authorization
        lastLifecycleRequest = request
        return archiveAnswer(request)
    }

    override suspend fun restore(
        authorization: String,
        customerId: String,
        propertyId: String,
        request: PropertyLifecycleRequest,
    ): PropertyDetailDto {
        restoreCalls += 1
        lastAuthorization = authorization
        lastLifecycleRequest = request
        return restoreAnswer(request)
    }

    override suspend fun delete(
        authorization: String,
        customerId: String,
        propertyId: String,
    ): Response<Unit> {
        deleteCalls += 1
        lastAuthorization = authorization
        return deleteAnswer()
    }
}

/** A [SessionAuthenticator] a test configures. */
internal class TestSessionAuthenticator(
    private val accessToken: String? = "access-1",
    private val renewal: SessionRenewal = SessionRenewal.Rejected,
) : SessionAuthenticator {

    var renewals: Int = 0
        private set

    override fun accessToken(): String? = accessToken

    override suspend fun renew(rejectedToken: String): SessionRenewal {
        renewals += 1
        return renewal
    }
}

/** A wire Property row a test builds. */
internal fun propertyDto(
    id: String = "p1",
    status: String = "ACTIVE",
    version: Int = 3,
    jobCount: Int = 0,
): PropertyDetailDto = PropertyDetailDto(
    id = id,
    name = "Cedar Lane Building",
    addressLine1 = "987 Cedar Lane",
    city = "Montreal",
    province = "QC",
    postalCode = "H3A 2T6",
    country = "CA",
    status = status,
    version = version,
    jobCount = jobCount,
)

/** The refusal Retrofit raises for [status]. */
internal fun propertyHttpFailure(status: Int): HttpException = HttpException(
    Response.error<PropertyDetailDto>(
        status,
        """{"statusCode":$status,"code":"UNKNOWN","message":"ignored"}"""
            .toResponseBody("application/json".toMediaType()),
    ),
)
