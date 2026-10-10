package com.arbelonson.ozen.core

data class NetworkConditions(
    val isConnected: Boolean,
    val isExpensive: Boolean = false,
    val isConstrained: Boolean = false,
) {
    companion object {
        val wifi = NetworkConditions(isConnected = true)
        val cellular = NetworkConditions(isConnected = true, isExpensive = true)
        val offline = NetworkConditions(isConnected = false)
    }
}

interface NetworkMonitoring {
    val current: NetworkConditions?
    var onChange: ((NetworkConditions) -> Unit)?
}

object ModelDownloadGate {
    enum class Decision { Proceed, WaitForWiFi, Offline }

    fun decide(network: NetworkConditions?, allowCellular: Boolean): Decision {
        if (network == null) return Decision.Proceed
        if (!network.isConnected) return Decision.Offline
        if ((network.isExpensive || network.isConstrained) && !allowCellular) {
            return Decision.WaitForWiFi
        }
        return Decision.Proceed
    }

    fun canRetryDownload(network: NetworkConditions, allowCellular: Boolean): Boolean =
        decide(network, allowCellular) == Decision.Proceed
}
