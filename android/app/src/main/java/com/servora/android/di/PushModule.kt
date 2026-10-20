package com.servora.android.di

import com.servora.android.data.push.FilePushInstallationStorage
import com.servora.android.data.push.FirebasePushTokenProvider
import com.servora.android.data.push.FilePushReceiptStorage
import com.servora.android.data.push.PushReceiptStorage
import com.servora.android.data.push.PushInstallationStorage
import com.servora.android.data.push.PushInstallationsApi
import com.servora.android.data.push.PushTokenProvider
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import retrofit2.Retrofit

@Module
@InstallIn(SingletonComponent::class)
abstract class PushBindingsModule {
    @Binds abstract fun storage(implementation: FilePushInstallationStorage): PushInstallationStorage
    @Binds abstract fun tokens(implementation: FirebasePushTokenProvider): PushTokenProvider
    @Binds abstract fun receipts(implementation: FilePushReceiptStorage): PushReceiptStorage
}

@Module
@InstallIn(SingletonComponent::class)
object PushModule {
    @Provides @Singleton
    fun api(retrofit: Retrofit): PushInstallationsApi = retrofit.create(PushInstallationsApi::class.java)
}
