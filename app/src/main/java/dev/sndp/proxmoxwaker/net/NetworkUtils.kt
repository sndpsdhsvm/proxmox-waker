package dev.sndp.proxmoxwaker.net

import java.net.Inet4Address
import java.net.NetworkInterface

/** Small helpers for inspecting the device's own network interfaces. */
object NetworkUtils {

    /** All IPv4 addresses currently assigned to the device, loopback included. */
    fun localIpv4Addresses(): List<Inet4Address> = try {
        NetworkInterface.getNetworkInterfaces()
            .toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { iface -> iface.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
    } catch (_: Exception) {
        emptyList()
    }

    /** True when the device appears to have a usable LAN connection. */
    fun hasLocalNetwork(): Boolean = localIpv4Addresses().isNotEmpty()
}
