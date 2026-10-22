package dev.sndp.proxmoxwaker.data

/**
 * Everything the app needs to reach and control one Proxmox host.
 *
 * @param host       Hostname or IP of the Proxmox node (e.g. "192.0.2.10").
 * @param apiPort    Proxmox API port. 8006 for the web API, which is the default.
 * @param useHttps   True for https://, false for plain http://.
 * @param node       Proxmox node name as shown in the web UI (e.g. "pve").
 * @param username   Proxmox user, e.g. "root" or "user@pam".
 * @param password   Password for that user. Stored on-device only.
 * @param macAddress MAC address of the server's NIC, used for Wake-on-LAN.
 * @param wolPort    UDP port for the magic packet. 9 is the standard.
 * @param wolBroadcast Broadcast address to send the magic packet to.
 */
data class ProxmoxConfig(
    val host: String = "",
    val apiPort: Int = 8006,
    val useHttps: Boolean = true,
    val node: String = "pve",
    val username: String = "root@pam",
    val password: String = "",
    val macAddress: String = "",
    val wolPort: Int = 9,
    val wolBroadcast: String = "255.255.255.255",
) {
    val baseUrl: String
        get() = "${if (useHttps) "https" else "http"}://$host:$apiPort/api2/json"

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
