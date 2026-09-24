package com.jarvis.routine

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log

class NetworkStateMonitor(
    private val context: Context,
    private val onWifiConnected: (ssid: String) -> Unit,
    private val onWifiDisconnected: () -> Unit
) {
    private val TAG = "NetworkStateMonitor"
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var defaultNetworkCallback: ConnectivityManager.NetworkCallback? = null
    private var isMonitoring = false
    private var currentSsid: String? = null

    fun start() {
        if (isMonitoring) return
        try {
            connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            if (connectivityManager == null) {
                Log.w(TAG, "ConnectivityManager not available")
                return
            }

            // 1. Monitor overall internet connectivity for ControlPlane & DeviceGuardian
            defaultNetworkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    emitNetworkState(network)
                }

                override fun onLost(network: Network) {
                    com.jarvis.controlplane.JarvisEventBus.shared.emit(
                        com.jarvis.controlplane.JarvisEvent.NetworkStateChanged(isOnline = false, isMetered = false)
                    )
                }

                override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                    val isOnline = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    val isMetered = !networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                    com.jarvis.controlplane.JarvisEventBus.shared.emit(
                        com.jarvis.controlplane.JarvisEvent.NetworkStateChanged(isOnline = isOnline, isMetered = isMetered)
                    )
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                connectivityManager?.registerDefaultNetworkCallback(defaultNetworkCallback!!)
            }

            // Emit initial network status
            val activeNet = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) connectivityManager?.activeNetwork else null
            if (activeNet != null) {
                emitNetworkState(activeNet)
            }

            // 2. Specific Wi-Fi SSID tracking
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build()

            networkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    val ssid = resolveSsid(network)
                    currentSsid = ssid
                    Log.i(TAG, "Wi-Fi connected: SSID='$ssid'")
                    onWifiConnected(ssid)
                }

                override fun onLost(network: Network) {
                    Log.i(TAG, "Wi-Fi disconnected (was '$currentSsid')")
                    currentSsid = null
                    onWifiDisconnected()
                }

                override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                    if (networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                        val ssid = resolveSsid(network)
                        if (ssid.isNotBlank() && ssid != currentSsid && ssid != "<unknown ssid>") {
                            currentSsid = ssid
                            Log.i(TAG, "Wi-Fi SSID updated: '$ssid'")
                            onWifiConnected(ssid)
                        }
                    }
                }
            }

            connectivityManager?.registerNetworkCallback(request, networkCallback!!)
            isMonitoring = true
            Log.i(TAG, "NetworkStateMonitor registered successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start NetworkStateMonitor", e)
        }
    }

    private fun emitNetworkState(network: Network) {
        val caps = connectivityManager?.getNetworkCapabilities(network)
        val isOnline = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        val isMetered = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) != true
        com.jarvis.controlplane.JarvisEventBus.shared.emit(
            com.jarvis.controlplane.JarvisEvent.NetworkStateChanged(isOnline = isOnline, isMetered = isMetered)
        )
    }

    fun stop() {
        if (!isMonitoring) return
        try {
            defaultNetworkCallback?.let { connectivityManager?.unregisterNetworkCallback(it) }
            defaultNetworkCallback = null
            networkCallback?.let { connectivityManager?.unregisterNetworkCallback(it) }
            networkCallback = null
            isMonitoring = false
            Log.i(TAG, "NetworkStateMonitor unregistered")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to unregister NetworkStateMonitor", e)
        }
    }

    fun getCurrentSsid(): String {
        return currentSsid ?: ""
    }

    private fun resolveSsid(@Suppress("UNUSED_PARAMETER") network: Network? = null): String {
        return try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val connectionInfo: WifiInfo? = wifiManager?.connectionInfo
            val rawSsid = connectionInfo?.ssid?.replace("\"", "")?.trim()
            if (!rawSsid.isNullOrBlank() && rawSsid != "<unknown ssid>") {
                rawSsid
            } else {
                "WIFI"
            }
        } catch (e: Exception) {
            "WIFI"
        }
    }
}
