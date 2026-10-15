package com.arbelonson.ozen.core

object PrivateRoute {
    enum class Transport { Wifi, Ethernet, Cellular, Vpn, Other }

    private val local = setOf(Transport.Wifi, Transport.Ethernet)

    fun <N> choose(host: String, defaultTransports: Set<Transport>, available: List<Pair<N, Set<Transport>>>): N? {
        if (!HomeServer.isPrivate(host)) return null
        if (Transport.Vpn in defaultTransports || defaultTransports.any { it in local }) return null
        return available.firstOrNull { (_, transports) ->
            transports.any { it in local } && Transport.Cellular !in transports && Transport.Vpn !in transports
        }?.first
    }
}
