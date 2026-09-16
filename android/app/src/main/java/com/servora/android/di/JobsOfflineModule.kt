package com.servora.android.di

import android.content.Context
import coil3.ImageLoader
import coil3.disk.DiskCache
import com.servora.android.data.jobs.ContentResolverJobPhotoPickedItems
import com.servora.android.data.jobs.AndroidJobPhotoExportTarget
import com.servora.android.data.jobs.DefaultJobPhotoExporter
import com.servora.android.data.jobs.DefaultJobPhotoImages
import com.servora.android.data.jobs.DefaultJobPhotoProcessing
import com.servora.android.data.jobs.JobPhotoExportTarget
import com.servora.android.data.jobs.JobPhotoExporter
import com.servora.android.data.jobs.JobPhotoFetcherFactory
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
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Singleton
import okio.Path.Companion.toOkioPath

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

/**
 * The image stack the photo evidence is drawn with (`D4b`, `ADR-016`).
 *
 * The stack is built here, with this feature's fetcher, and installed as the app's own stack in
 * `ServoraApplication`, so every `AsyncImage`/`SubcomposeAsyncImage` in the app draws a photo through
 * the reader that knows where evidence lives — the device's own file or the API, with the session
 * renewal it has always had (`BR-007`) — instead of a second stack with caches of its own.
 */
@Module
@InstallIn(SingletonComponent::class)
internal object JobPhotoImageModule {

    /**
     * The app's image stack: the library's default caches, plus this feature's reader (`D4b`).
     *
     * The reader is what tells the stack where evidence lives, and it releases the stack's own caches
     * when another session reads — so this provider is what makes that release the app's stack rather
     * than a second one (`JobPhotoImageCacheScope`).
     */
    @Provides
    @Singleton
    fun provideJobPhotoImageLoader(
        @ApplicationContext context: Context,
        fetcherFactory: JobPhotoFetcherFactory,
    ): ImageLoader = ImageLoader.Builder(context)
        .components { add(fetcherFactory) }
        .diskCache {
            DiskCache.Builder()
                .directory(context.cacheDir.resolve(DISK_CACHE_DIRECTORY).toOkioPath())
                // The share of the cache directory the library keeps for its own disk cache, matching
                // its default: it is a cache, so the platform may reclaim all of it (`D4b`).
                .maxSizePercent(DISK_CACHE_PERCENT)
                .build()
        }
        .build()

    /** The feature's own cache directory name, so nothing else in the app writes into it. */
    private const val DISK_CACHE_DIRECTORY = "job-photo-cache"

    private const val DISK_CACHE_PERCENT = 0.02
}

/** Binds the photo-evidence feature's local stores to their implementations. */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class JobsOfflineBindingsModule {

    /** The bytes of a captured photo live in app-private storage, never in the local database. */
    @Binds
    @Singleton
    abstract fun bindJobPhotoFiles(implementation: PrivateJobPhotoFiles): JobPhotoFiles

    /**
     * What the tray, the gallery, the review preview and the viewer draw a photo with (`D4b`): the
     * request the image stack loads, with the session's own cache keys.
     */
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

    /**
     * Taking a photo out of Servora: saving it into the device's own gallery, or handing it to another
     * application through the platform's share sheet (`D12`, `D13`).
     */
    @Binds
    @Singleton
    abstract fun bindJobPhotoExporter(implementation: DefaultJobPhotoExporter): JobPhotoExporter

    /** Where an export writes on a device: the shared gallery and the platform's share sheet. */
    @Binds
    @Singleton
    abstract fun bindJobPhotoExportTarget(
        implementation: AndroidJobPhotoExportTarget,
    ): JobPhotoExportTarget
}
