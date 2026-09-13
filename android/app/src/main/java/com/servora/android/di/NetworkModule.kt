package com.servora.android.di

import com.servora.android.BuildConfig
import com.servora.android.data.auth.AuthApi
import com.servora.android.data.customers.CustomersApi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/** Transport dependencies for reaching the Servora API. */
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    private val JSON_MEDIA_TYPE = "application/json".toMediaType()

    /**
     * Unknown response fields are ignored so a backend that adds a field does not
     * break an older build (`dev.md` §7).
     */
    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
    }

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient = OkHttpClient.Builder().build()

    /**
     * The API address comes from build configuration, never from application logic
     * (`dev.md` §5).
     */
    @Provides
    @Singleton
    fun provideRetrofit(client: OkHttpClient, json: Json): Retrofit =
        Retrofit.Builder()
            .baseUrl(BuildConfig.API_BASE_URL)
            .client(client)
            .addConverterFactory(json.asConverterFactory(JSON_MEDIA_TYPE))
            .build()

    @Provides
    @Singleton
    fun provideAuthApi(retrofit: Retrofit): AuthApi = retrofit.create(AuthApi::class.java)

    @Provides
    @Singleton
    fun provideCustomersApi(retrofit: Retrofit): CustomersApi =
        retrofit.create(CustomersApi::class.java)
}
