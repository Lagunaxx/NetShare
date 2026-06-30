package com.example.utils

import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

object NetworkUtils {
    fun getLocalIpAddress(): String? {
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            val ipList = mutableListOf<Pair<String, String>>() // Pair of interface name and IP address

            for (intf in interfaces) {
                if (intf.isLoopback || !intf.isUp) continue
                
                val addrs = Collections.list(intf.inetAddresses)
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        val ip = addr.hostAddress
                        if (ip != null && !ip.startsWith("127.")) {
                            ipList.add(Pair(intf.name.lowercase(), ip))
                        }
                    }
                }
            }

            if (ipList.isEmpty()) return null

            // Score each IP address based on interface name and IP range
            // Higher score means more likely to be the correct local network (Wi-Fi/Hotspot) IP
            val scoredList = ipList.map { (name, ip) ->
                var score = 0
                
                // Prioritize interface names
                if (name.contains("wlan") || name.contains("ap") || name.contains("softap")) {
                    score += 100
                }
                
                // Prioritize standard local subnet IP ranges
                if (ip.startsWith("192.168.")) {
                    score += 50
                } else if (ip.startsWith("172.")) {
                    // Check if it's within private range 172.16.0.0 - 172.31.255.255
                    val parts = ip.split(".")
                    if (parts.size >= 2) {
                        val secondOctet = parts[1].toIntOrNull() ?: 0
                        if (secondOctet in 16..31) {
                            score += 40
                        }
                    }
                } else if (ip.startsWith("10.")) {
                    score += 20
                }
                
                // Penalize known mobile data/cellular/VPN interfaces
                if (name.contains("rmnet") || name.contains("pdp") || name.contains("ccmni") || 
                    name.contains("ppp") || name.contains("tun") || name.contains("dummy")) {
                    score -= 200
                }
                
                Triple(name, ip, score)
            }

            // Sort descending by score
            val best = scoredList.maxByOrNull { it.third }
            return best?.second
        } catch (ex: Exception) {
            ex.printStackTrace()
        }
        return null
    }
}
