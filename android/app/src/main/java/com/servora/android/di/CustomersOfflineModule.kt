package com.servora.android.di

import com.servora.android.data.customers.PropertyLifecycleHandler
import com.servora.android.data.offline.OfflineOperationHandler
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

/**
 * The offline-capable operations the customer feature contributes.
 *
 * A handler is contributed by the feature that owns the operation, which is what keeps route and
 * payload knowledge out of the generic replay engine (`Project.md` §12).
 */
@Module
@InstallIn(SingletonComponent::class)
internal object CustomersOfflineModule {

    /** Archive and restore are the Property lifecycle's queued operations (`BR-086`). */
    @Provides
    @IntoSet
    fun providePropertyLifecycleHandler(
        handler: PropertyLifecycleHandler,
    ): OfflineOperationHandler = handler
}
