package dev.sndp.proxmoxwaker.data

/**
 * Everything the app needs to reach and control one Proxmox host.
 *
 * @param host       Hostname or IP of the Proxmox node (e.g. "192.0.2.10").
 * @param apiPort    Proxmox API port. 8006 for the web API, which is the default.
 * @param node       Proxmox node name. Left blank the app discovers it automatically,
 *                   which avoids the most common misconfiguration.
 * @param username   Proxmox user including its realm, e.g. "root@pam".
 * @param password   Password for that user. Stored on-device only.
 * @param macAddress MAC address of the server's NIC, used for Wake-on-LAN.
 * @param wolPort    UDP port for the magic packet. 9 is the standard.
 * @param wolBroadcast Broadcast address to send the magic packet to.
 */
data class ProxmoxConfig(
    val host: String = "",
    val apiPort: Int = 8006,
    val node: String = "",
    val username: String = "root@pam",
    val password: String = "",
    val macAddress: String = "",
    val wolPort: Int = 9,
    val wolBroadcast: String = "255.255.255.255",
    /** SHA-256 of the accepted TLS certificate, shown so changes are visible. */
    val certFingerprint: String = "",
) {
    /**
     * The API is always `https`, even when [apiPort] is 8006 on a plain-HTTP setup:
     * Proxmox answers cleartext requests with a `301` redirect to `https://` and the
     * API itself is only served over TLS (<https://pve.proxmox.com/wiki/Proxmox_VE_API>).
     */
    val baseUrl: String
        get() = "https://$host:$apiPort/api2/json"

    /** True when we have enough to attempt an API call (host + credentials). */
    val canTalkToApi: Boolean
        get() = host.isNotBlank() && username.isNotBlank() && password.isNotBlank()

    /** True when we can send a magic packet. */
    val canWake: Boolean
        get() = macAddress.isNotBlank()
}

/** A virtual machine or LXC container as reported by the Proxmox API. */
data class Guest(
    val vmid: Int,
    val name: String,
    val type: GuestType,
    val status: GuestStatus,
) {
    /** Stable key used for identity checks. */
    val id: String get() = "${type.apiPath}/$vmid"
}

/** Node resource usage and system information. */
data class NodeStatus(
    val uptimeSeconds: Long = 0,
    val cpuUsage: Double = 0.0,
    val cpuCount: Int = 0,
    val cpuModel: String = "",
    val memoryUsed: Long = 0,
    val memoryTotal: Long = 0,
    val swapUsed: Long = 0,
    val swapTotal: Long = 0,
    val rootUsed: Long = 0,
    val rootTotal: Long = 0,
) {
    val cpuPercent: Int get() = (cpuUsage * 100).toInt().coerceIn(0, 100)
    val memoryPercent: Int get() = if (memoryTotal > 0) ((memoryUsed.toDouble() / memoryTotal.toDouble()) * 100).toInt().coerceIn(0, 100) else 0
    val swapPercent: Int get() = if (swapTotal > 0) ((swapUsed.toDouble() / swapTotal.toDouble()) * 100).toInt().coerceIn(0, 100) else 0
    val diskPercent: Int get() = if (rootTotal > 0) ((rootUsed.toDouble() / rootTotal.toDouble()) * 100).toInt().coerceIn(0, 100) else 0

    fun uptimeText(): String {
        if (uptimeSeconds <= 0) return ""
        val days = uptimeSeconds / 86400
        val hours = (uptimeSeconds % 86400) / 3600
        val mins = (uptimeSeconds % 3600) / 60
        return buildString {
            if (days > 0) append("${days}d ")
            if (hours > 0 || days > 0) append("${hours}h ")
            append("${mins}m")
        }.trim()
    }
}

enum class GuestType(val apiPath: String, val label: String) {
    QEMU("qemu", "VM"),
    LXC("lxc", "CT");

    companion object {
        fun fromApi(value: String): GuestType =
            entries.firstOrNull { it.apiPath == value } ?: QEMU
    }
}

enum class GuestStatus(val apiValue: String, val label: String) {
    RUNNING("running", "Running"),
    STOPPED("stopped", "Stopped"),
    PAUSED("paused", "Paused"),
    SUSPENDED("suspended", "Suspended"),
    UNKNOWN("unknown", "Unknown");

    companion object {
        fun fromApi(value: String): GuestStatus =
            entries.firstOrNull { it.apiValue.equals(value, ignoreCase = true) } ?: UNKNOWN
    }
}
