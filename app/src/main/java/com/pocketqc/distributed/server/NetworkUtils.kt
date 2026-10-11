package com.pocketqc.distributed.server

import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

object NetworkUtils {

    private val EXCLUDED_INTERFACE_PREFIXES = listOf(
        "rmnet", "ccmni", "pdp", "ppp", "dummy", "tun", "tap", "sit", "ip6tnl", "v4-"
    )

    private val WIFI_HOTSPOT_PATTERNS = listOf(
        "wlan", "swlan", "softap", "ap", "wifi", "rndis", "eth", "p2p", "tether"
    )

    /**
     * Finds the primary reachable LAN IPv4 address of the Android device.
     * Prioritizes active Wi-Fi, Hotspot (SoftAP), and USB tethering interfaces,
     * while strictly excluding mobile cellular data interfaces (rmnet, ccmni).
     */
    fun getLocalIpAddress(): String {
        val allIps = getAllAvailableIpAddresses()
        return allIps.firstOrNull() ?: "127.0.0.1"
    }

    /**
     * Returns all active non-loopback LAN IPv4 addresses sorted by connectivity priority:
     * 1. Hotspot Gateway addresses (e.g. 192.168.43.1, 192.168.49.1)
     * 2. Wi-Fi / LAN Private Class C (192.168.*)
     * 3. Private Class B (172.16-31.*)
     * 4. Private Class A (10.* non-cellular)
     */
    fun getAllAvailableIpAddresses(): List<String> {
        val candidates = mutableListOf<Pair<String, Int>>()

        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())

            for (intf in interfaces) {
                if (!intf.isUp || intf.isLoopback) continue
                val name = intf.name.lowercase()

                // Exclude known mobile cellular and virtual tunnel interfaces
                if (EXCLUDED_INTERFACE_PREFIXES.any { name.startsWith(it) }) continue

                val isWifiOrHotspot = WIFI_HOTSPOT_PATTERNS.any { name.contains(it) }

                for (addr in Collections.list(intf.inetAddresses)) {
                    if (addr.isLoopbackAddress || addr !is Inet4Address) continue
                    val host = addr.hostAddress ?: continue
                    if (host.startsWith("127.")) continue

                    var score = 10

                    if (isWifiOrHotspot) {
                        score += 30
                    }

                    // Android Hotspot default gateways
                    if (host == "192.168.43.1" || host == "192.168.49.1" || host == "192.168.50.1") {
                        score += 50
                    } else if (host.startsWith("192.168.")) {
                        score += 40
                    } else if (isPrivateClassB(host)) {
                        score += 30
                    } else if (host.startsWith("10.")) {
                        score += 20
                    }

                    candidates.add(host to score)
                }
            }
        } catch (_: Throwable) {
            // Fallback on network query failure
        }

        return candidates
            .sortedByDescending { it.second }
            .map { it.first }
            .distinct()
    }

    private fun isPrivateClassB(ip: String): Boolean {
        if (!ip.startsWith("172.")) return false
        val secondOctet = ip.split(".").getOrNull(1)?.toIntOrNull() ?: return false
        return secondOctet in 16..31
    }

    /**
     * Sanitizes user-entered host string:
     * - Strips protocols (http://, https://, ws://)
     * - Strips path segments (/api/v1/...)
     * - Extracts embedded port if entered (e.g. 192.168.1.5:8080)
     * - Cleans duplicate prefixes and trailing whitespace/slashes
     */
    fun normalizeHostAndPort(rawHost: String, defaultPort: Int = 8080): Pair<String, Int> {
        var clean = rawHost.trim()

        // Strip scheme
        if (clean.contains("://")) {
            clean = clean.substringAfter("://")
        }

        // Strip path
        if (clean.contains("/")) {
            clean = clean.substringBefore("/")
        }

        // Check for embedded port e.g. 192.168.1.5:8080
        var port = defaultPort
        if (clean.contains(":")) {
            val parts = clean.split(":")
            clean = parts[0].trim()
            val parsedPort = parts.getOrNull(1)?.trim()?.toIntOrNull()
            if (parsedPort != null && parsedPort in 1..65535) {
                port = parsedPort
            }
        }

        // Handle accidental double-prefix e.g. 192.168.192.168.1.5
        if (clean.startsWith("192.168.192.168.")) {
            clean = clean.substringAfter("192.168.")
        }

        return clean to port
    }
}
