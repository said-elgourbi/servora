package com.servora.android.di

import com.servora.android.data.jobs.ContentResolverJobPhotoPickedItems
import com.servora.android.data.jobs.DefaultJobPhotoImages
import com.servora.android.data.jobs.DefaultJobPhotoProcessing
import com.servora.android.data.jobs.JobPhotoFiles
import com.servora.android.data.jobs.JobPhotoImages
import com.servora.android.data.jobs.JobPhotoPickedItems
import com.servora.android.data.jobs.JobPhotoProcessing
import com.servora.android.data.jobs.JobPhotoUploadHandler
import com.servora.android.data.jobs.PrivateJobPhotoFiles
import com.servora.android.data.offline.OfflineOperationHandler
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Singleton

/**
 * The photo-evidence feature's offline and local-storage wiring (`BR-015`, `BR-027`, `BR-086`).
 *
 * A handler is contributed by the feature that owns the operation, which is what keeps route and
 * payload knowledge out of the generic replay engine (`Project.md` §12). The pending-photo store and
 * the app-private file storage are bound here for the same reason: they are the Jobs feature's local
 * state, not a general-purpose store.
 */
@Module
@InstallIn(SingletonComponent::class)
internal object JobsOfflineModule {

    /** A queued photo upload is the Jobs feature's offline-capable operation (`BR-015`, §9). */
    @Provides
    @IntoSet
    fun provideJobPhotoUploadHandler(
        handler: JobPhotoUploadHandler,
    ): OfflineOperationHandler = handler
}

/** Binds the photo-evidence feature's local stores to their implementations. */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class JobsOfflineBindingsModule {

    /** The bytes of a captured photo live in app-private storage, never in the local database. */
    @Binds
    @Singleton
    abstract fun bindJobPhotoFiles(implementation: PrivateJobPhotoFiles): JobPhotoFiles

    /** The thumbnails the tray and the gallery draw (`BR-015`). */
    @Binds
    @Singleton
    abstract fun bindJobPhotoImages(implementation: DefaultJobPhotoImages): JobPhotoImages

    /**
     * The two device-side preparation steps a photo goes through before it becomes a draft
     * (`D3b`, `D3c`).
     */
    @Binds
    @Singleton
    abstract fun bindJobPhotoProcessing(
        implementation: DefaultJobPhotoProcessing,
    ): JobPhotoProcessing

    /**
     * The bytes of a photo the technician picked are read from the device's own content provider,
     * which is the picker's half of the library source (`D3`).
     */
    @Binds
    @Singleton
    abstract fun bindJobPhotoPickedItems(
        implementation: ContentResolverJobPhotoPickedItems,
    ): JobPhotoPickedItems
}
