package com.arbelonson.ozen

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import com.arbelonson.ozen.core.NetworkConditions
import com.arbelonson.ozen.core.NetworkMonitoring

class AndroidNetworkMonitor(context: Context) : NetworkMonitoring {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)

    override var current: NetworkConditions? = null
        private set

    override var onChange: ((NetworkConditions) -> Unit)? = null

    var alsoOnChange: ((NetworkConditions) -> Unit)? = null

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = report(conditions(capabilities))

        override fun onLost(network: Network) = report(NetworkConditions.offline)
    }

    init {
        connectivity.registerDefaultNetworkCallback(callback, Handler(Looper.getMainLooper()))
    }

    fun close() = connectivity.unregisterNetworkCallback(callback)

    private fun report(conditions: NetworkConditions) {
        if (conditions == current) return
        current = conditions
        onChange?.invoke(conditions)
        alsoOnChange?.invoke(conditions)
    }

    private fun conditions(capabilities: NetworkCapabilities): NetworkConditions {
        val metered = !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        return NetworkConditions(
            isConnected = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
            isExpensive = metered,
            isConstrained = metered && connectivity.restrictBackgroundStatus == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED,
        )
    }
}
