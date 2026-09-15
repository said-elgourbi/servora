package com.servora.android.data.jobs

/**
 * A picker source that hands over nothing, for instrumented tests about something else.
 *
 * The Job Details destination is wired by the application shell, so a navigation test has to build the
 * ViewModel that destination uses. These tests are about where navigation goes, not about photo
 * evidence, so this answers the smallest honest thing: no bytes for any address (`qa.md` §6.2).
 */
fun inertJobPhotoPickedItems(): JobPhotoPickedItems = InertJobPhotoPickedItems

private object InertJobPhotoPickedItems : JobPhotoPickedItems {
    override suspend fun bytes(uri: String): ByteArray? = null
}
