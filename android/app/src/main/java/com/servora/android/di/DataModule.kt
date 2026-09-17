package com.servora.android.di

import com.servora.android.data.auth.AuthRepository
import com.servora.android.data.auth.DefaultAuthRepository
import com.servora.android.data.customers.CustomersRepository
import com.servora.android.data.customers.DefaultCustomersRepository
import com.servora.android.data.customers.DefaultPropertyRepository
import com.servora.android.data.customers.PropertyRepository
import com.servora.android.data.device.DeviceIdentity
import com.servora.android.data.device.DeviceIdentityProvider
import com.servora.android.data.home.DefaultManagerHomeRepository
import com.servora.android.data.home.DefaultTechnicianHomeRepository
import com.servora.android.data.home.ManagerHomeRepository
import com.servora.android.data.home.TechnicianHomeRepository
import com.servora.android.data.jobs.DefaultJobDetailsRepository
import com.servora.android.data.jobs.JobDetailsRepository
import com.servora.android.data.session.AccessTokenSubject
import com.servora.android.data.session.AuthenticatedSubject
import com.servora.android.data.session.DefaultSessionAuthenticator
import com.servora.android.data.session.DefaultSessionManager
import com.servora.android.data.session.EncryptedSessionStorage
import com.servora.android.data.session.SessionAuthenticator
import com.servora.android.data.session.SessionManager
import com.servora.android.data.session.SessionStorage
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.time.Clock
import javax.inject.Singleton

/** Data-layer bindings that are not about transport. */
@Module
@InstallIn(SingletonComponent::class)
abstract class DataModule {

    @Binds
    @Singleton
    abstract fun bindAuthRepository(implementation: DefaultAuthRepository): AuthRepository

    @Binds
    @Singleton
    abstract fun bindSessionAuthenticator(
        implementation: DefaultSessionAuthenticator,
    ): SessionAuthenticator

    /** The stored session is encrypted at rest (`SessionStorage`). */
    @Binds
    @Singleton
    abstract fun bindSessionStorage(implementation: EncryptedSessionStorage): SessionStorage

    @Binds
    @Singleton
    abstract fun bindSessionManager(implementation: DefaultSessionManager): SessionManager

    /** Names the subject local business state is scoped to (`AuthenticatedSubject`). */
    @Binds
    @Singleton
    abstract fun bindAuthenticatedSubject(
        implementation: AccessTokenSubject,
    ): AuthenticatedSubject

    @Binds
    @Singleton
    abstract fun bindCustomersRepository(
        implementation: DefaultCustomersRepository,
    ): CustomersRepository

    @Binds
    @Singleton
    abstract fun bindPropertyRepository(
        implementation: DefaultPropertyRepository,
    ): PropertyRepository

    @Binds
    @Singleton
    abstract fun bindManagerHomeRepository(
        implementation: DefaultManagerHomeRepository,
    ): ManagerHomeRepository

    @Binds
    @Singleton
    abstract fun bindTechnicianHomeRepository(
        implementation: DefaultTechnicianHomeRepository,
    ): TechnicianHomeRepository

    @Binds
    @Singleton
    abstract fun bindJobDetailsRepository(
        implementation: DefaultJobDetailsRepository,
    ): JobDetailsRepository

    companion object {
        /** Exposes the installation identity as a plain value for injection. */
        @Provides
        @Singleton
        fun provideDeviceIdentity(provider: DeviceIdentityProvider): DeviceIdentity =
            provider.current()

        /**
         * The clock the session manager compares an access token's expiry against.
         *
         * Provided rather than called statically so a test can pin "now" (`dev.md` §1).
         */
        @Provides
        @Singleton
        fun provideClock(): Clock = Clock.systemUTC()
    }
}
