package dev.sndp.proxmoxwaker.net

import dev.sndp.proxmoxwaker.data.Guest
import dev.sndp.proxmoxwaker.data.GuestStatus
import dev.sndp.proxmoxwaker.data.GuestType
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
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

/** A failure that can be shown to the user as-is. */
class ProxmoxException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Minimal Proxmox VE API client.
 *
 * Talks to `/api2/json` using the ticket-based login flow:
 * `POST /access/ticket` returns a ticket plus a CSRF token; the ticket goes in a
 * `PVEAuthCookie` cookie and the CSRF token in a `CSRFPreventionToken` header on
 * every subsequent call.
 *
 * Sessions are cached per config so repeated calls do not re-authenticate, and are
 * transparently renewed once when the server reports the ticket expired (HTTP 401).
 */
class ProxmoxClient(
    private val http: OkHttpClient = defaultClient(),
) {

    private data class Session(val ticket: String, val csrfToken: String, val realm: String)

    private var cachedSession: Session? = null
    private var cachedKey: String? = null
    private val lock = Any()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun key(config: ProxmoxConfig) =
        "${config.baseUrl}|${config.username}"

    /** Drops the cached session, e.g. after the password changes. */
    fun forgetSession() {
        synchronized(lock) {
            cachedSession = null
            cachedKey = null
        }
    }

    /**
     * Verifies the credentials and returns the Proxmox version string as a smoke test.
     * @throws ProxmoxException on any failure, with a human-readable message.
     */
    suspend fun testConnection(config: ProxmoxConfig): String = withContext(Dispatchers.IO) {
        forgetSession()
        val body = authorizedCall(config) { session ->
            Request.Builder()
                .url("${config.baseUrl}/version")
                .get()
                .applySession(session)
                .build()
        }
        val root = parseData(body)
        root["version"]?.jsonPrimitive?.contentOrNull?.let { "Proxmox VE $it" } ?: "Connection OK"
    }

    /** Lists every QEMU VM and LXC container on the configured node. */
    suspend fun listGuests(config: ProxmoxConfig): List<Guest> = withContext(Dispatchers.IO) {
        val body = authorizedCall(config) { session ->
            Request.Builder()
                .url("${config.baseUrl}/nodes/${config.node}/qemu")
                .get()
                .applySession(session)
                .build()
        }
        val vms = parseGuestArray(body, GuestType.QEMU)

        val containers = runCatching {
            val ctBody = authorizedCall(config) { session ->
                Request.Builder()
                    .url("${config.baseUrl}/nodes/${config.node}/lxc")
                    .get()
                    .applySession(session)
                    .build()
            }
            parseGuestArray(ctBody, GuestType.LXC)
        }.getOrElse { emptyList() }

        (vms + containers).sortedWith(compareBy({ it.type }, { it.vmid }))
    }

    /**
     * Starts [guest]. Proxmox returns a task UPID; we do not wait for the task to
     * finish, the UI just re-polls the guest list.
     */
    suspend fun startGuest(config: ProxmoxConfig, guest: Guest) = withContext(Dispatchers.IO) {
        authorizedCall(config) { session ->
            Request.Builder()
                .url("${config.baseUrl}/nodes/${config.node}/${guest.type.apiPath}/${guest.vmid}/status/start")
                .post(FormBody.Builder().build())
                .applySession(session)
                .build()
        }
        Unit
    }

    /** Gracefully shuts down [guest] (ACPI shutdown for VMs, shutdown for containers). */
    suspend fun shutdownGuest(config: ProxmoxConfig, guest: Guest) = withContext(Dispatchers.IO) {
        authorizedCall(config) { session ->
            Request.Builder()
                .url("${config.baseUrl}/nodes/${config.node}/${guest.type.apiPath}/${guest.vmid}/status/shutdown")
                .post(FormBody.Builder().build())
                .applySession(session)
                .build()
        }
        Unit
    }

    // ---------------------------------------------------------------- internals

    /**
     * Runs [build] with a valid session. If the server rejects the ticket, logs in
     * again and retries exactly once.
     */
    private fun authorizedCall(config: ProxmoxConfig, build: (Session) -> Request): String {
        val session = session(config)
        try {
            return execute(build(session))
        } catch (e: AuthExpiredException) {
            forgetSession()
            val fresh = session(config)
            return execute(build(fresh))
        }
    }

    private fun session(config: ProxmoxConfig): Session {
        synchronized(lock) {
            val k = key(config)
            cachedSession?.takeIf { cachedKey == k }?.let { return it }
            val created = login(config)
            cachedSession = created
            cachedKey = k
            return created
        }
    }

    private fun login(config: ProxmoxConfig): Session {
        val form = FormBody.Builder()
            .add("username", config.username)
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
                throw ProxmoxException(describeHttpFailure(res.code, text, config))
            }
            val data = runCatching { parseData(text) }.getOrElse {
                throw ProxmoxException("Unexpected response from ${config.host}: ${text.take(200)}")
            }
            val ticket = data["ticket"]?.jsonPrimitive?.contentOrNull
                ?: throw ProxmoxException("Login succeeded but no ticket was returned.")
            val csrf = data["CSRFPreventionToken"]?.jsonPrimitive?.contentOrNull
                ?: throw ProxmoxException("Login succeeded but no CSRF token was returned.")
            val realm = data["username"]?.jsonPrimitive?.contentOrNull ?: config.username
            return Session(ticket, csrf, realm)
        }
    }

    private fun execute(request: Request): String {
        val response = try {
            http.newCall(request).execute()
        } catch (e: Exception) {
            throw ProxmoxException(friendlyNetworkMessage(e), e)
        }
        response.use { res ->
            if (res.code == 401) throw AuthExpiredException()
            val text = res.body?.string().orEmpty()
            if (!res.isSuccessful) {
                throw ProxmoxException(describeApiError(res.code, text))
            }
            return text
        }
    }

    private class AuthExpiredException : Exception("Proxmox session expired")

    private fun Request.Builder.applySession(session: Session): Request.Builder = apply {
        header("Cookie", "PVEAuthCookie=${session.ticket}")
        header("CSRFPreventionToken", session.csrfToken)
    }

    private fun parseData(text: String): JsonObject =
        json.parseToJsonElement(text).jsonObject["data"]?.jsonObject
            ?: throw ProxmoxException("Response did not contain a data object.")

    private fun parseGuestArray(text: String, type: GuestType): List<Guest> {
        val array: JsonArray = runCatching {
            json.parseToJsonElement(text).jsonObject["data"]?.jsonArray ?: JsonArray(emptyList())
        }.getOrElse { JsonArray(emptyList()) }

        return array.mapNotNull { element ->
            val obj = runCatching { element.jsonObject }.getOrNull() ?: return@mapNotNull null
            val vmid = obj["vmid"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
            val status = obj["status"]?.jsonPrimitive?.contentOrNull ?: "unknown"
            val name = obj["name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: "${type.label} $vmid"
            Guest(
                vmid = vmid,
                name = name,
                type = type,
                status = GuestStatus.fromApi(status),
            )
        }
    }

    private fun describeHttpFailure(code: Int, body: String, config: ProxmoxConfig): String = when (code) {
        401 -> "Login failed. Check the username and password (Proxmox wants the realm, e.g. root@pam)."
        403 -> "Login refused (403). The account may be locked or lack permission to log in."
        else -> "Login failed (HTTP $code) at ${config.host}${apiPathHint(config)}: ${extractError(body)}"
    }

    private fun describeApiError(code: Int, body: String): String = when (code) {
        401 -> "Your Proxmox session expired. Try again."
        403 -> "Permission denied. The account needs VM.Audit and VM.PowerMgmt on this guest."
        404 -> "Not found. Check the node name and that the guest still exists."
        595 -> "Proxmox reported a task error: ${extractError(body)}"
        else -> "Request failed (HTTP $code): ${extractError(body)}"
    }

    private fun apiPathHint(config: ProxmoxConfig) = " (${config.baseUrl})"

    /** Pulls Proxmox's own error text out of the JSON envelope when present. */
    private fun extractError(body: String): String {
        if (body.isBlank()) return "no details from server"
        val message = runCatching {
            val obj = json.parseToJsonElement(body).jsonObject
            obj["errors"]?.jsonObject?.entries?.joinToString("; ") { (k, v) ->
                "$k: ${v.jsonPrimitive.contentOrNull ?: v.toString()}"
            } ?: obj["message"]?.jsonPrimitive?.contentOrNull
        }.getOrNull()
        return message?.takeIf { it.isNotBlank() }?.take(300)
            ?: body.trim().take(300)
    }

    private fun translateNetworkError(e: Exception, config: ProxmoxConfig): ProxmoxException {
        val message = when (e) {
            is UnknownHostException ->
                "Cannot find host '${config.host}'. Check the address, and that the phone is on the same network."
            is ConnectException ->
                "Cannot connect to ${config.host}:${config.apiPort}. The server may still be booting, or the port is wrong."
            is SocketTimeoutException ->
                "Connection to ${config.host} timed out. Is the server awake?"
            is SSLException ->
                "TLS problem talking to ${config.host}. Try turning HTTPS off if the API is plain HTTP, " +
                    "or use http://${config.host}:8006."
            else -> friendlyNetworkMessage(e)
        }
        return ProxmoxException(message, e)
    }

    private fun friendlyNetworkMessage(e: Exception): String =
        e.message?.takeIf { it.isNotBlank() }?.let { "Network error: $it" } ?: "Network error."

    companion object {
        /** Timeouts are short on purpose: every call is on a LAN. */
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}
