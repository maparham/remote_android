package com.example.lanremote.server

import java.net.Inet4Address
import java.net.NetworkInterface

object NetworkUtil {
    /**
     * Best-guess IPv4 address a LAN controller can reach. Prefers Wi-Fi, then any
     * broadcast (non-point-to-point) site-local interface (Wi-Fi/Ethernet/USB), and
     * only falls back to anything else. This avoids picking the cellular interface
     * (rmnet, point-to-point) whose IP a LAN client cannot route to.
     */
    fun lanIpAddress(): String? {
        val ifaces = try {
            NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
        } catch (e: Exception) {
            return null
        }

        fun ipv4Of(predicate: (NetworkInterface) -> Boolean): String? =
            ifaces.filter(predicate)
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { !it.isLoopbackAddress }
                ?.hostAddress

        // 1. Wi-Fi.
        ipv4Of { it.name.startsWith("wlan") }?.let { return it }
        // 2. Any broadcast site-local interface (Ethernet, USB tether) — excludes cellular.
        ifaces.filter { !it.isPointToPoint }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress }
            ?.let { return it.hostAddress }
        // 3. Last resort: anything non-loopback.
        return ipv4Of { true }
    }

    /** True when the chosen address is a real private LAN range (not cellular/public). */
    fun isReachableLan(ip: String?): Boolean {
        if (ip == null) return false
        return try {
            val addr = Inet4Address.getByName(ip)
            addr.isSiteLocalAddress &&
                NetworkInterface.getNetworkInterfaces().toList()
                    .any { nif -> nif.isUp && !nif.isPointToPoint &&
                        nif.inetAddresses.toList().any { it.hostAddress == ip } }
        } catch (e: Exception) {
            false
        }
    }
}
