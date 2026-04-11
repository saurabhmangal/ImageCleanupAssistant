package com.saura.imagecleanupassistant.mobile

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Monitors network connectivity status and provides real-time updates.
 * Helps handle WiFi disconnections gracefully during long operations.
 */
class NetworkStatusManager(private val context: Context) {
    
    sealed class NetworkStatus {
        object Connected : NetworkStatus()
        object ConnectedMetered : NetworkStatus()  // Mobile data, limited
        object Reconnecting : NetworkStatus()
        object Offline : NetworkStatus()
    }
    
    private val connectivityManager = context.getSystemService(
        Context.CONNECTIVITY_SERVICE
    ) as ConnectivityManager
    
    /**
     * Observes network status changes as a Flow.
     * Emits new status whenever network state changes.
     */
    fun observeStatus(): Flow<NetworkStatus> = callbackFlow {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(getNetworkStatus())
            }
            
            override fun onLost(network: Network) {
                trySend(NetworkStatus.Offline)
            }
            
            override fun onCapabilitiesChanged(
                network: Network,
                capabilities: NetworkCapabilities
            ) {
                trySend(getNetworkStatus())
            }
        }
        
        connectivityManager.registerDefaultNetworkCallback(callback)
        
        // Emit initial status
        trySend(getNetworkStatus())
        
        awaitClose {
            connectivityManager.unregisterNetworkCallback(callback)
        }
    }
    
    /**
     * Check current network connectivity status immediately.
     */
    fun getCurrentStatus(): NetworkStatus = getNetworkStatus()
    
    /**
     * Returns true if device is connected to network.
     */
    fun isConnected(): Boolean = getCurrentStatus() != NetworkStatus.Offline
    
    /**
     * Returns true if connected to WiFi (unmetered, good for large transfers).
     */
    fun isConnectedToWiFi(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }
    
    /**
     * Returns true if connected to mobile data (metered, warn user).
     */
    fun isConnectedToMobileData(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
    }
    
    private fun getNetworkStatus(): NetworkStatus {
        val network = connectivityManager.activeNetwork ?: return NetworkStatus.Offline
        val capabilities = connectivityManager.getNetworkCapabilities(network)
            ?: return NetworkStatus.Offline
        
        return when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkStatus.Connected
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkStatus.ConnectedMetered
            else -> NetworkStatus.Offline
        }
    }
}
