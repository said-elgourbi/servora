package com.servora.android.data.offline

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * [ConnectivityObserver] a test drives, so a replay can be triggered without a device.
 *
 * It starts with the current availability, as the platform observer does, and every change is
 * emitted once.
 */
class FakeConnectivityObserver(online: Boolean = true) : ConnectivityObserver {

    private val available = MutableStateFlow(online)

    override fun availability(): Flow<Boolean> = available

    override fun isOnline(): Boolean = available.value

    /** Reports that a network became available. */
    fun goOnline() {
        available.value = true
    }

    /** Reports that no network is usable. */
    fun goOffline() {
        available.value = false
    }
}
