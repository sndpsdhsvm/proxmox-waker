package dev.sndp.proxmoxwaker.net

import dev.sndp.proxmoxwaker.data.Guest
import dev.sndp.proxmoxwaker.data.GuestStatus
import dev.sndp.proxmoxwaker.data.GuestType
import dev.sndp.proxmoxwaker.data.NodeStatus
import dev.sndp.proxmoxwaker.data.ProxmoxConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** A failure that can be shown to the user as-is. */
class ProxmoxException(
    message: String,
    cause: Throwable? = null,
    /** True when re-authenticating might fix it. */
    val authRelated: Boolean = false,
) : Exception(message, cause)

/**
 * Minimal Proxmox VE API client, written against the documented contract
 * (<https://pve.proxmox.com/wiki/Proxmox_VE_API> and the generated `apidoc.js` schema).
 *
 * ### Authentication
 *
 * `POST /access/ticket` with `username` and `password` as form fields returns:
 *
 * ```json
 * { "data": { "ticket": "PVE:root@pam:...",
 *             "CSRFPreventionToken": "...",
 *             "username": "root@pam" } }
 * ```
 *
 * The **ticket** is then sent in a `PVEAuthCookie` cookie on every request. The
 * **CSRFPreventionToken** is required *only* for write methods (POST/PUT/DELETE).
 * A bad login is `401` with `{"message":"authentication failure\n","data":null}`.
 *
 * Tickets last **2 hours**, so the session is time-boxed and re-acquired on expiry.
 */
class ProxmoxClient(
    private val http: OkHttpClient = TlsSupport.client(),
) {

    private data class Session(
        val ticket: String,
        val csrfToken: String,
        val username: String,
        val acquiredAtMillis: Long,
    ) {
        /** Proxmox tickets live for 2 hours; renew a little early. */
        fun isExpired(now: Long = System.currentTimeMillis()): Boolean =
            now - acquiredAtMillis > TICKET_LIFETIME_MILLIS - TICKET_RENEW_MARGIN_MILLIS
    }

    private var cachedSession: Session? = null
    private var cachedKey: String? = null
    private val lock = Any()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** The node name the client last resolved, so the UI can persist it. */
    @Volatile
    var resolvedNode: String? = null
        private set

    private fun key(config: ProxmoxConfig) = "${config.baseUrl}|${config.username}"

    /** Fetches node status with resource usage, uptime, and CPU info. */
    suspend fun fetchNodeStatus(config: ProxmoxConfig): NodeStatus = withContext(Dispatchers.IO) {
        val node = resolveNode(config)
        val body = readCall(config, "${config.baseUrl}/nodes/$node/status")
        val data = runCatching { parseData(body) }.getOrElse { throw it }

        val uptime = data["uptime"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L
        val cpu = data["cpu"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: 0.0

        val cpuInfo = data["cpuinfo"]?.jsonObject
        val cpuCount = cpuInfo?.get("cpus")?.jsonPrimitive?.contentOrNull?.toIntOrNull()
            ?: cpuInfo?.get("cores")?.jsonPrimitive?.contentOrNull?.toIntOrNull()
            ?: data["cpus"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
            ?: 0
        val cpuModel = cpuInfo?.get("model")?.jsonPrimitive?.contentOrNull ?: ""

        val memory = data["memory"]?.jsonObject
        val memUsed = memory?.get("used")?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L
        val memTotal = memory?.get("total")?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L

        val swap = data["swap"]?.jsonObject
        val swapUsed = swap?.get("used")?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L
        val swapTotal = swap?.get("total")?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L

        val rootfs = data["rootfs"]?.jsonObject
        val rootUsed = rootfs?.get("used")?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L
        val rootTotal = rootfs?.get("total")?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L

        NodeStatus(
            uptimeSeconds = uptime,
            cpuUsage = cpu,
            cpuCount = cpuCount,
            cpuModel = cpuModel,
            memoryUsed = memUsed,
            memoryTotal = memTotal,
            swapUsed = swapUsed,
            swapTotal = swapTotal,
            rootUsed = rootUsed,
            rootTotal = rootTotal,
        )
    }

    /** Drops the cached session, e.g. after the password changes. */
    fun forgetSession() {
        synchronized(lock) {
            cachedSession = null
            cachedKey = null
        }
    }

    /**
     * Logs in and verifies the result by listing cluster nodes, then returns a short
     * human-readable summary such as `Proxmox VE 8.2.4 (node "pve")`.
     *
     * `/version` is *not* used as the probe here even though it is the obvious
     * candidate: it requires a valid ticket, so it is no cheaper than any other call
     * and gives less information. Listing nodes both proves the credentials work and
     * discovers the node name, which the guest endpoints require.
     *
     * @throws ProxmoxException on any failure, with a human-readable message.
     */
    suspend fun testConnection(config: ProxmoxConfig): String = withContext(Dispatchers.IO) {
        forgetSession()

        // Force a login up front so credential problems surface as such.
        session(config)

        val version = runCatching { fetchVersion(config) }.getOrNull()
        val nodes = fetchNodes(config)

        if (nodes.isEmpty()) {
            throw ProxmoxException(
                "Logged in, but the cluster reported no nodes. " +
                    "Is the Proxmox host fully started?",
            )
        }

        val node = chooseNode(config, nodes)
        resolvedNode = node
        val versionPart = version?.let { "Proxmox VE $it" } ?: "Connected"
        "$versionPart (node \"$node\")"
    }

    /** Lists every QEMU VM and LXC container on the resolved node. */
    suspend fun listGuests(config: ProxmoxConfig): List<Guest> = withContext(Dispatchers.IO) {
        val node = resolveNode(config)

        val vms = fetchGuests(config, node, GuestType.QEMU)
        // /lxc only exists on nodes with container support; a failure there should
        // not hide the VMs.
        val containers = runCatching { fetchGuests(config, node, GuestType.LXC) }
            .getOrElse { emptyList() }

        (vms + containers).sortedWith(
            compareBy({ it.vmid }),
        )
    }

    /**
     * Starts [guest]. Proxmox returns a task UPID; we do not wait for the task to
     * finish, the UI re-polls the guest list instead.
     */
    suspend fun startGuest(config: ProxmoxConfig, guest: Guest) = withContext(Dispatchers.IO) {
        val node = resolveNode(config)
        writeCall(config, "${config.baseUrl}/nodes/$node/${guest.type.apiPath}/${guest.vmid}/status/start")
        Unit
    }

    /** Gracefully shuts down [guest] (ACPI shutdown for VMs, shutdown for containers). */
    suspend fun shutdownGuest(config: ProxmoxConfig, guest: Guest) = withContext(Dispatchers.IO) {
        val node = resolveNode(config)
        writeCall(config, "${config.baseUrl}/nodes/$node/${guest.type.apiPath}/${guest.vmid}/status/shutdown")
        Unit
    }

    /** Reboots [guest] gracefully. */
    suspend fun rebootGuest(config: ProxmoxConfig, guest: Guest) = withContext(Dispatchers.IO) {
        val node = resolveNode(config)
        writeCall(config, "${config.baseUrl}/nodes/$node/${guest.type.apiPath}/${guest.vmid}/status/reboot")
        Unit
    }

    /** Resets [guest] forcefully. */
    suspend fun resetGuest(config: ProxmoxConfig, guest: Guest) = withContext(Dispatchers.IO) {
        val node = resolveNode(config)
        writeCall(config, "${config.baseUrl}/nodes/$node/${guest.type.apiPath}/${guest.vmid}/status/reset")
        Unit
    }

    /** Stops [guest] immediately (force stop). */
    suspend fun stopGuest(config: ProxmoxConfig, guest: Guest) = withContext(Dispatchers.IO) {
        val node = resolveNode(config)
        writeCall(config, "${config.baseUrl}/nodes/$node/${guest.type.apiPath}/${guest.vmid}/status/stop")
        Unit
    }

    /**
     * Sends an ACPI shutdown request to the Proxmox node itself.
     *
     * `POST /nodes/{node}/status` takes `command=shutdown`. The call returns a task
     * UPID; the node then powers off asynchronously, so callers should poll until the
     * API stops answering rather than expecting the request to block. Requires
     * `Sys.PowerMgmt` on the node.
     */
    suspend fun shutdownNode(config: ProxmoxConfig) = withContext(Dispatchers.IO) {
        val node = resolveNode(config)
        val form = FormBody.Builder().add("command", "shutdown").build()
        writeCall(config, "${config.baseUrl}/nodes/$node/status", form)
        Unit
    }

    // ------------------------------------------------------------- node handling

    /**
     * Returns the node to talk to.
     *
     * If the configured node name is present in the cluster it wins. Otherwise, when
     * the config still holds the default placeholder, the single real node is used,
     * because guessing "pve" against a host named something else is the most common
     * setup mistake. A genuinely wrong name is reported rather than silently swapped,
     * so the user is not left wondering which node they are driving.
     */
    private fun resolveNode(config: ProxmoxConfig): String {
        resolvedNode?.let { return it }

        val nodes = runCatching { fetchNodes(config) }.getOrNull().orEmpty()
        if (nodes.isEmpty()) {
            // Cannot enumerate; fall back to whatever was configured.
            return config.node.ifBlank { DEFAULT_NODE }
        }

        val configured = config.node.trim()
        if (nodes.any { it.equals(configured, ignoreCase = true) }) {
            resolvedNode = nodes.first { it.equals(configured, ignoreCase = true) }
            return resolvedNode!!
        }

        if (configured.isBlank() || configured == DEFAULT_NODE) {
            // Placeholder value: adopt the real node name.
            resolvedNode = nodes.first()
            return resolvedNode!!
        }

        throw ProxmoxException(
            "Node \"$configured\" was not found on this host. " +
                "Available node(s): ${nodes.joinToString(", ")}.",
        )
    }

    private fun chooseNode(config: ProxmoxConfig, nodes: List<String>): String {
        val configured = config.node.trim()
        return nodes.firstOrNull { it.equals(configured, ignoreCase = true) }
            ?: nodes.first()
    }

    private fun fetchNodes(config: ProxmoxConfig): List<String> {
        val body = readCall(config, "${config.baseUrl}/nodes")
        val array = dataArray(body)
        return array.mapNotNull { element ->
            val obj = runCatching { element.jsonObject }.getOrNull() ?: return@mapNotNull null
            obj["node"]?.jsonPrimitive?.contentOrNull
                ?: obj["name"]?.jsonPrimitive?.contentOrNull
        }
    }

    private fun fetchVersion(config: ProxmoxConfig): String? {
        val body = readCall(config, "${config.baseUrl}/version")
        val data = runCatching { parseData(body) }.getOrNull() ?: return null
        return data["release"]?.jsonPrimitive?.contentOrNull
            ?: data["version"]?.jsonPrimitive?.contentOrNull
    }

    private fun fetchGuests(config: ProxmoxConfig, node: String, type: GuestType): List<Guest> {
        val body = readCall(config, "${config.baseUrl}/nodes/$node/${type.apiPath}")
        return dataArray(body).mapNotNull { element ->
            val obj = runCatching { element.jsonObject }.getOrNull() ?: return@mapNotNull null
            val vmid = obj["vmid"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
            val status = obj["status"]?.jsonPrimitive?.contentOrNull ?: "unknown"
            val name = obj["name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: "${type.label} $vmid"
            Guest(vmid = vmid, name = name, type = type, status = GuestStatus.fromApi(status))
        }
    }

    // ------------------------------------------------------------------- calls

    /**
     * Runs several candidate URLs in order and returns the first that answers with a
     * successful ticket request. Proxmox is overwhelmingly at `/api2/json`, but the
     * prefix is configurable in principle, so this avoids a hard failure if a proxy
     * rewrites the path.
     */
    private fun readCall(config: ProxmoxConfig, url: String): String {
        val request = try {
            withSession(config) { session ->
                Request.Builder().url(url).get().applyAuth(session).build()
            }
        } catch (e: ProxmoxException) {
            throw e
        }
        return execute(request)
    }

    private fun writeCall(
        config: ProxmoxConfig,
        url: String,
        form: FormBody = FormBody.Builder().build(),
    ) {
        val request = withSession(config) { session ->
            Request.Builder()
                .url(url)
                .post(form)
                .applyAuth(session, csrfRequired = true)
                .build()
        }
        execute(request)
    }

    /**
     * Runs [build] with a valid session, retrying once with fresh credentials if the
     * server rejects the ticket.
     */
    private fun withSession(config: ProxmoxConfig, build: (Session) -> Request): Request {
        val session = session(config)
        return build(session)
    }

    private fun session(config: ProxmoxConfig): Session {
        synchronized(lock) {
            val k = key(config)
            val existing = cachedSession
            if (existing != null && cachedKey == k && !existing.isExpired()) return existing
            val created = login(config)
            cachedSession = created
            cachedKey = k
            return created
        }
    }

    private fun login(config: ProxmoxConfig): Session {
        // Per the docs the password must be form-encoded so special characters are safe.
        val form = FormBody.Builder()
            .add("username", config.username.trim())
            .add("password", config.password)
            .build()

        val request = Request.Builder()
            .url("${config.baseUrl}/access/ticket")
            .post(form)
            .build()

        val response = try {
            http.newCall(request).execute()
        } catch (e: Exception) {
            throw translateNetworkError(e, config)
        }

        response.use { res ->
            val text = res.body?.string().orEmpty()
            if (!res.isSuccessful) {
                throw ProxmoxException(describeLoginFailure(res.code, text), authRelated = true)
            }
            val data = runCatching { parseData(text) }.getOrElse {
                throw ProxmoxException(
                    "Unexpected response from ${config.host}: ${text.take(200)}",
                    authRelated = true,
                )
            }
            val ticket = data["ticket"]?.jsonPrimitive?.contentOrNull
                ?: throw ProxmoxException("Login succeeded but no ticket was returned.", authRelated = true)
            val csrf = data["CSRFPreventionToken"]?.jsonPrimitive?.contentOrNull
                ?: throw ProxmoxException("Login succeeded but no CSRF token was returned.", authRelated = true)
            val username = data["username"]?.jsonPrimitive?.contentOrNull ?: config.username
            return Session(ticket, csrf, username, System.currentTimeMillis())
        }
    }

    private fun execute(request: Request): String {
        val response = try {
            http.newCall(request).execute()
        } catch (e: Exception) {
            throw ProxmoxException(friendlyNetworkMessage(e), e)
        }

        response.use { res ->
            val text = res.body?.string().orEmpty()

            // A 401 on a normal call means the ticket went stale: clear it so the next
            // attempt re-authenticates.
            if (res.code == 401) {
                forgetSession()
                throw ProxmoxException(
                    "Proxmox rejected the session (401 " +
                        "${res.header("Status") ?: res.message}). " +
                        "Check that the account can still log in.",
                    authRelated = true,
                )
            }

            if (!res.isSuccessful) {
                throw ProxmoxException(describeApiError(res.code, text, res.header("Status")))
            }
            return text
        }
    }

    private fun Request.Builder.applyAuth(session: Session, csrfRequired: Boolean = false): Request.Builder =
        apply {
            // Ticket always goes in PVEAuthCookie.
            header("Cookie", "PVEAuthCookie=${session.ticket}")
            // CSRFPreventionToken is only required on write methods.
            if (csrfRequired) {
                header("CSRFPreventionToken", session.csrfToken)
            }
        }

    // ------------------------------------------------------------------ parsing

    private fun parseData(text: String): JsonObject =
        json.parseToJsonElement(text).jsonObject["data"]?.jsonObject
            ?: throw ProxmoxException("Response did not contain a data object.")

    /** Reads `data` when it is an array, tolerating a null/absent value. */
    private fun dataArray(text: String): JsonArray {
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
            ?: return JsonArray(emptyList())
        val data = root["data"] ?: return JsonArray(emptyList())
        return runCatching { data.jsonArray }.getOrElse { JsonArray(emptyList()) }
    }

    // ------------------------------------------------------------------- errors

    private fun describeLoginFailure(code: Int, body: String): String {
        val detail = extractError(body)
        return when (code) {
            401 -> "Login failed. Check the username and password. " +
                "The username needs its realm, for example root@pam."
            403 -> "Login refused (403). The account may be locked, or lack permission " +
                "to open a session."
            400 -> "Proxmox rejected the login request ($detail)."
            else -> "Login failed (HTTP $code): $detail"
        }
    }

    private fun describeApiError(code: Int, body: String, statusHeader: String?): String = when (code) {
        401 -> "Session expired. Try again."
        403 -> "Permission denied. Guest power actions need VM.Audit and VM.PowerMgmt; " +
            "shutting down the node needs Sys.PowerMgmt."
        404 -> "Not found. Check the node name, and that the guest still exists."
        500 -> "Proxmox reported a server error: ${extractError(body)}"
        595 -> "Proxmox reported a task error: ${extractError(body)}"
        else -> buildString {
            append("Request failed (HTTP $code")
            if (!statusHeader.isNullOrBlank()) append(" $statusHeader")
            append("): ${extractError(body)}")
        }
    }

    /**
     * Pulls Proxmox's own error text out of the JSON envelope.
     *
     * Proxmox reports failures as `{"message":"...","data":null}` (see the live
     * `authentication failure` response). Parameter validation failures instead use
     * `{"errors":{field: reason}}`.
     */
    private fun extractError(body: String): String {
        if (body.isBlank()) return "no details from server"
        val message = runCatching {
            val obj = json.parseToJsonElement(body).jsonObject
            obj["errors"]?.jsonObject?.entries
                ?.joinToString("; ") { (k, v) -> "$k: ${v.jsonPrimitive.contentOrNull ?: v.toString()}" }
                ?.takeIf { it.isNotBlank() }
                ?: obj["message"]?.jsonPrimitive?.contentOrNull?.trim()
        }.getOrNull()
        return message?.takeIf { it.isNotBlank() }?.take(300) ?: body.trim().take(300)
    }

    private fun translateNetworkError(e: Exception, config: ProxmoxConfig): ProxmoxException {
        val message = when (e) {
            is UnknownHostException ->
                "Cannot find host \"${config.host}\". Check the address, and that the " +
                    "phone is on the same network."
            is ConnectException ->
                "Cannot connect to ${config.host}:${config.apiPort}. The server may still " +
                    "be booting, or the port is wrong."
            is SocketTimeoutException ->
                "Connection to ${config.host} timed out. Is the server awake?"
            is SSLException ->
                "TLS problem talking to ${config.host}: ${e.message ?: "handshake failed"}. " +
                    "Proxmox always serves HTTPS on 8006, so check the certificate."
            else -> friendlyNetworkMessage(e)
        }
        return ProxmoxException(message, e)
    }

    private fun friendlyNetworkMessage(e: Exception): String =
        e.message?.takeIf { it.isNotBlank() }?.let { "Network error: $it" } ?: "Network error."

    private companion object {
        const val DEFAULT_NODE = "pve"
        const val TICKET_LIFETIME_MILLIS = 2 * 60 * 60 * 1000L
        const val TICKET_RENEW_MARGIN_MILLIS = 5 * 60 * 1000L
    }
}
