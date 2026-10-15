package com.arbelonson.ozen.core

object PrivateRoute {
    enum class Transport { Wifi, Ethernet, Cellular, Vpn, Other }

    private val local = setOf(Transport.Wifi, Transport.Ethernet)

    fun <N> choose(host: String, defaultTransports: Set<Transport>, available: List<Pair<N, Set<Transport>>>): N? {
        if (!HomeServer.isPrivate(host) || isTailnet(host)) return null
        if (Transport.Vpn in defaultTransports || defaultTransports.any { it in local }) return null
        return available.firstOrNull { (_, transports) ->
            transports.any { it in local } && Transport.Cellular !in transports && Transport.Vpn !in transports
        }?.first
    }

    private fun isTailnet(host: String): Boolean {
        val name = host.lowercase(java.util.Locale.ROOT).trim { it == '[' || it == ']' }
        if (name.endsWith(".ts.net") || name.startsWith("fd7a:115c:a1e0:")) return true
        val parts = name.split(".")
        val second = parts.getOrNull(1)?.toIntOrNull()
        return parts.size == 4 && parts[0] == "100" && second != null && second in 64..127
    }
}
