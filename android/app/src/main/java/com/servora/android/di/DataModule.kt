package com.servora.android.di

import com.servora.android.data.auth.AuthRepository
import com.servora.android.data.auth.DefaultAuthRepository
import com.servora.android.data.device.DeviceIdentity
import com.servora.android.data.device.DeviceIdentityProvider
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Data-layer bindings that are not about transport. */
@Module
@InstallIn(SingletonComponent::class)
abstract class DataModule {

    @Binds
    @Singleton
    abstract fun bindAuthRepository(implementation: DefaultAuthRepository): AuthRepository

    companion object {
        /** Exposes the installation identity as a plain value for injection. */
        @Provides
        @Singleton
        fun provideDeviceIdentity(provider: DeviceIdentityProvider): DeviceIdentity =
            provider.current()
    }
}
