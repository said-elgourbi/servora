package com.servora.android.data.offline

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn

/**
 * Whether the device currently has a network, and when that changes.
 *
 * Connectivity is a *trigger*, never a gate: an operation is queued whenever the API is unreachable,
 * whether the device believes it is online or not (§7). This observer therefore only makes a replay
 * happen sooner when a network comes back; it never decides whether a change may be queued.
 */
interface ConnectivityObserver {
    /** Emits the current availability and then every change, without repeating a value. */
    fun availability(): Flow<Boolean>

    /** Whether a network is available right now, as far as the device reports. */
    fun isOnline(): Boolean
}

/** Default [ConnectivityObserver], reading the platform's default network. */
@Singleton
class AndroidConnectivityObserver @Inject constructor(
    @ApplicationContext private val context: Context,
) : ConnectivityObserver {

    override fun availability(): Flow<Boolean> = callbackFlow {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        if (manager == null) {
            // Without the system service nothing can be observed; the caller's other triggers still
            // queue and replay work.
            trySend(false)
            awaitClose { }
            return@callbackFlow
        }

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(true)
            }

            override fun onLost(network: Network) {
                // The platform reports the loss while the manager may still name the network it just
                // lost, so availability is simply re-read. A trigger that fires late costs nothing:
                // replay is ordered and idempotent either way.
                trySend(manager.hasUsableNetwork())
            }
        }

        manager.registerDefaultNetworkCallback(callback)
        trySend(manager.hasUsableNetwork())
        awaitClose { manager.unregisterNetworkCallback(callback) }
    }.distinctUntilChanged().flowOn(Dispatchers.Default)

    override fun isOnline(): Boolean =
        context.getSystemService(ConnectivityManager::class.java)?.hasUsableNetwork() ?: false
}

/**
 * Whether the device currently has a network it can actually reach the API through.
 *
 * `ACCESS_NETWORK_STATE` is declared by the application, which is what this reads; it grants no
 * access to anything the API protects (`BR-007`).
 */
private fun ConnectivityManager.hasUsableNetwork(): Boolean {
    val capabilities = getNetworkCapabilities(activeNetwork) ?: return false
    return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}
