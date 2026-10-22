package dev.sndp.proxmoxwaker.net

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/** Builds and sends Wake-on-LAN magic packets. */
object WakeOnLan {

    /**
     * Parses a MAC address in any of the common forms:
     * `AA:BB:CC:DD:EE:FF`, `AA-BB-CC-DD-EE-FF`, `AABBCCDDEEFF`, or `AABB.CCDD.EEFF`.
     *
     * @return the 6 raw bytes, or null when the input is not a valid MAC address.
     */
    fun parseMac(raw: String): ByteArray? {
        val cleaned = raw.trim().replace(":", "").replace("-", "").replace(".", "")
        if (cleaned.length != 12 || !cleaned.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
            return null
        }
        return ByteArray(6) { i ->
            cleaned.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    /** Formats raw MAC bytes as `AA:BB:CC:DD:EE:FF`, or null when the length is wrong. */
    fun formatMac(bytes: ByteArray): String? {
        if (bytes.size != 6) return null
        return bytes.joinToString(":") { "%02X".format(it) }
    }

    /**
     * The 102-byte magic packet: 6 x 0xFF followed by the target MAC repeated 16 times.
     */
    fun magicPacket(mac: ByteArray): ByteArray {
        require(mac.size == 6) { "MAC address must be 6 bytes, got ${mac.size}" }
        val packet = ByteArray(6 + 16 * 6)
        for (i in 0 until 6) packet[i] = 0xFF.toByte()
        for (repeat in 0 until 16) {
            System.arraycopy(mac, 0, packet, 6 + repeat * 6, 6)
        }
        return packet
    }

    /**
     * Sends a magic packet for [macString] to [broadcast] on [port].
     *
     * The socket is bound to the wildcard address with SO_BROADCAST enabled so the
     * packet can leave the interface. Some home routers drop 255.255.255.255, so
     * callers can try a subnet-directed address (e.g. 192.0.2.255) as a fallback.
     *
     * @throws IllegalArgumentException when the MAC address cannot be parsed.
     */
    fun send(macString: String, broadcast: String, port: Int) {
        val mac = parseMac(macString)
            ?: throw IllegalArgumentException("Invalid MAC address: $macString")
        val packetBytes = magicPacket(mac)
        val address = InetAddress.getByName(broadcast)

        DatagramSocket().use { socket ->
            socket.broadcast = true
            socket.send(DatagramPacket(packetBytes, packetBytes.size, address, port))
        }
    }

    /**
     * Sends the magic packet to [broadcast], and when that address is the global
     * broadcast address, also tries the subnet-directed broadcast derived from the
     * device's own IPv4 address (for example 192.0.2.255). Returns the addresses
     * that were actually used.
     */
    fun sendWithFallback(macString: String, broadcast: String, port: Int): List<String> {
        val attempts = linkedSetOf(broadcast)
        subnetDirectedBroadcast()?.let { attempts += it }

        val used = mutableListOf<String>()
        var lastError: Exception? = null
        for (target in attempts) {
            try {
                send(macString, target, port)
                used += target
            } catch (e: Exception) {
                lastError = e
            }
        }
        if (used.isEmpty() && lastError != null) throw lastError
        return used
    }

    /**
     * Best-effort subnet broadcast address, e.g. 192.0.2.10 -> 192.0.2.255.
     * Returns null when no usable non-loopback IPv4 interface is found.
     */
    private fun subnetDirectedBroadcast(): String? {
        return NetworkUtils.localIpv4Addresses()
            .filterNot { it.isLoopbackAddress }
            .mapNotNull { addr ->
                val raw = addr.address
                if (raw.size != 4) return@mapNotNull null
                // Assume a /24, which covers the overwhelming majority of home LANs.
                "${raw[0].toInt() and 0xFF}.${raw[1].toInt() and 0xFF}." +
                    "${raw[2].toInt() and 0xFF}.255"
            }
            .firstOrNull()
    }
}
