package com.servora.android.di

import com.servora.android.data.offline.OfflineOperationHandler
import com.servora.android.data.schedule.AdHocWorkReportSubmitHandler
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

/**
 * The offline-capable operation the schedule feature contributes.
 *
 * A handler is contributed by the feature that owns the operation, which is what keeps route and
 * payload knowledge out of the generic replay engine (`Project.md` §12).
 */
@Module
@InstallIn(SingletonComponent::class)
internal object ScheduleOfflineModule {

    /** Reporting ad-hoc work is the schedule feature's queued operation (`BR-AH-001`). */
    @Provides
    @IntoSet
    fun provideAdHocWorkReportSubmitHandler(
        handler: AdHocWorkReportSubmitHandler,
    ): OfflineOperationHandler = handler
}
