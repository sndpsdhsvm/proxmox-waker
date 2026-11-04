package dev.sndp.proxmoxwaker.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.sndp.proxmoxwaker.data.Guest
import dev.sndp.proxmoxwaker.data.GuestStatus
import dev.sndp.proxmoxwaker.data.NodeStatus
import dev.sndp.proxmoxwaker.data.ProxmoxConfig
import dev.sndp.proxmoxwaker.data.SettingsRepository
import dev.sndp.proxmoxwaker.net.NetworkUtils
import dev.sndp.proxmoxwaker.net.ProxmoxClient
import dev.sndp.proxmoxwaker.net.ProxmoxException
import dev.sndp.proxmoxwaker.net.TlsSupport
import dev.sndp.proxmoxwaker.net.WakeOnLan
import dev.sndp.proxmoxwaker.security.BiometricAuth
import java.io.IOException
import java.net.ConnectException
import java.net.UnknownHostException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the main screen is doing right now, used to drive the status banner. */
enum class Phase {
    IDLE,
    WAKING,
    WAITING_FOR_SERVER,
    LOADING_GUESTS,
    STARTING,
    SHUTTING_DOWN,
}

data class UiState(
    val config: ProxmoxConfig = ProxmoxConfig(),
    val configured: Boolean = false,
    val phase: Phase = Phase.IDLE,
    val serverReachable: Boolean = false,
    val guests: List<Guest> = emptyList(),
    val nodeStatus: NodeStatus? = null,
    val message: String? = null,
    val error: String? = null,
    val startingGuestId: String? = null,
    val showSettings: Boolean = false,
    /** True only for a user-initiated refresh. */
    val refreshing: Boolean = false,
    /** Fingerprint unlock is enabled in settings. */
    val requireBiometric: Boolean = false,
    /** Fingerprint unlock can be used on this device right now. */
    val biometricAvailable: Boolean = false,
    /** Why fingerprint unlock is unavailable, when it is. */
    val biometricMessage: String? = null,
    /** False until the saved security preference has been read from disk. */
    val securityReady: Boolean = false,
    /** True while the lock screen covers the controls. */
    val locked: Boolean = false,
    /** Incremented when a fresh fingerprint prompt should be shown. */
    val lockRequestId: Int = 0,
    /** Feedback shown on the lock screen after a failed attempt. */
    val lockMessage: String? = null,
) {
    val busy: Boolean get() = phase != Phase.IDLE
}

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val settings = SettingsRepository(app)

    /**
     * The client is built with a TLS layer that accepts Proxmox's per-install
     * self-signed CA. The callback records the accepted fingerprint so a changed
     * certificate is visible to the user.
     */
    private val client = ProxmoxClient(
        TlsSupport.client { fingerprint ->
            viewModelScope.launch { settings.updateFingerprint(fingerprint.sha256) }
        },
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var refreshJob: Job? = null

    /** Consecutive failed reads, so one dropped packet does not blank the screen. */
    private var readFailures = 0

    init {
        viewModelScope.launch {
            combine(settings.config, settings.requireBiometric) { config, require ->
                config to require
            }.collect { (config, requireBiometric) ->
                val status = BiometricAuth.status(getApplication())
                val previousHost = _state.value.config.host
                val hostChanged = previousHost.isNotBlank() && previousHost != config.host
                _state.update { current ->
                    // Lock only on the first read, which is a fresh app start (or a
                    // restart after the app was killed). Returning from the background
                    // does not re-lock. Never lock when the sensor cannot be used,
                    // otherwise removing a fingerprint would lock the app for good.
                    val canLock = requireBiometric && status.canUse
                    val freshLock = canLock && !current.securityReady
                    current.copy(
                        config = config,
                        configured = config.canTalkToApi,
                        showSettings = if (!config.canTalkToApi) true else current.showSettings,
                        requireBiometric = requireBiometric,
                        biometricAvailable = status.canUse,
                        biometricMessage = status.message,
                        securityReady = true,
                        guests = if (hostChanged) emptyList() else current.guests,
                        nodeStatus = if (hostChanged) null else current.nodeStatus,
                        locked = when {
                            !canLock -> false
                            freshLock -> true
                            else -> current.locked
                        },
                        lockRequestId = if (freshLock) current.lockRequestId + 1 else current.lockRequestId,
                        lockMessage = if (!canLock) null else current.lockMessage,
                    )
                }
            }
        }
    }

    // ------------------------------------------------------------- user actions

    /** Re-checks the sensor when the app comes back, e.g. after enrolling a finger. */
    fun refreshBiometricStatus() {
        val status = BiometricAuth.status(getApplication())
        _state.update { current ->
            if (current.requireBiometric && !status.canUse && current.locked) {
                current.copy(
                    locked = false,
                    biometricAvailable = false,
                    biometricMessage = status.message,
                    message = status.message ?: current.message,
                )
            } else {
                current.copy(
                    biometricAvailable = status.canUse,
                    biometricMessage = status.message,
                )
            }
        }
    }

    fun setRequireBiometric(enabled: Boolean) {
        if (enabled) {
            val status = BiometricAuth.status(getApplication())
            if (!status.canUse) {
                _state.update {
                    it.copy(
                        error = status.message ?: "Fingerprint unlock is not available.",
                        showSettings = true,
                    )
                }
                return
            }
        }
        viewModelScope.launch {
            settings.setRequireBiometric(enabled)
            if (!enabled) {
                _state.update { it.copy(locked = false, lockMessage = null) }
            }
        }
    }

    fun onUnlocked() = _state.update {
        it.copy(locked = false, lockMessage = null)
    }

    fun onUnlockFailed() = _state.update {
        it.copy(lockMessage = "Fingerprint not recognized. Try again.")
    }

    fun onUnlockError(message: String) = _state.update {
        it.copy(lockMessage = message)
    }

    fun openSettings() = _state.update {
        it.copy(showSettings = true, error = null, message = null)
    }

    fun closeSettings() = _state.update {
        // Always allow leaving Settings: the main screen already explains that
        // nothing is configured and offers the gear to come back.
        it.copy(showSettings = false, error = null)
    }

    fun dismissError() = _state.update { it.copy(error = null) }

    fun dismissMessage() = _state.update { it.copy(message = null) }

    fun saveAndConnect(config: ProxmoxConfig) {
        viewModelScope.launch {
            settings.save(config)
            client.forgetSession()
            _state.update { it.copy(showSettings = false, error = null) }
            testConnection(config)
        }
    }

    /** Sends the magic packet, then polls until the API answers and loads guests. */
    fun wakeServer() {
        val config = _state.value.config
        if (!config.canWake) {
            _state.update { it.copy(error = "No MAC address set. Add one in Settings to wake the server.") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(phase = Phase.WAKING, error = null, message = null) }
            try {
                val targets = WakeOnLan.sendWithFallback(
                    macString = config.macAddress,
                    broadcast = config.wolBroadcast,
                    port = config.wolPort,
                )
                _state.update {
                    it.copy(
                        phase = Phase.WAITING_FOR_SERVER,
                        message = "Magic packet sent to ${targets.joinToString(", ")}. " +
                            "Waiting for the server to boot...",
                    )
                }
                waitForServerThenLoad()
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        phase = Phase.IDLE,
                        error = "Could not send the magic packet: ${e.message ?: "unknown error"}. " +
                            "Check the MAC address and that Wi-Fi is connected.",
                    )
                }
            }
        }
    }

    /** Loads (or reloads) the guest list from the API. Used by pull-to-refresh. */
    fun refresh() {
        val config = _state.value.config
        if (!config.canTalkToApi) {
            _state.update { it.copy(error = "Add your Proxmox host and login in Settings first.") }
            return
        }
        if (_state.value.busy || _state.value.refreshing) return

        viewModelScope.launch {
            _state.update { it.copy(refreshing = true, error = null) }
            try {
                val guests = client.listGuests(config)
                val nodeStatus = runCatching { client.fetchNodeStatus(config) }.getOrNull()
                readFailures = 0
                _state.update {
                    it.copy(
                        refreshing = false,
                        guests = guests,
                        serverReachable = true,
                        nodeStatus = nodeStatus,
                        message = null,
                    )
                }
            } catch (e: Exception) {
                readFailures = 0
                _state.update {
                    it.copy(
                        refreshing = false,
                        serverReachable = false,
                        guests = emptyList(),
                        nodeStatus = null,
                        error = e.userMessage(),
                    )
                }
            }
        }
    }

    fun startGuest(guest: Guest) {
        val config = _state.value.config
        viewModelScope.launch {
            _state.update {
                it.copy(phase = Phase.STARTING, startingGuestId = guest.id, error = null)
            }
            try {
                client.startGuest(config, guest)
                _state.update {
                    it.copy(
                        phase = Phase.IDLE,
                        startingGuestId = null,
                        message = "Start command sent for ${guest.name}.",
                    )
                }
                // Give Proxmox a moment, then re-read the real status.
                delay(2_500)
                refresh()
            } catch (e: Exception) {
                _state.update {
                    it.copy(phase = Phase.IDLE, startingGuestId = null, error = e.userMessage())
                }
            }
        }
    }

    fun shutdownGuest(guest: Guest) {
        val config = _state.value.config
        viewModelScope.launch {
            _state.update {
                it.copy(phase = Phase.STARTING, startingGuestId = guest.id, error = null)
            }
            try {
                client.shutdownGuest(config, guest)
                _state.update {
                    it.copy(
                        phase = Phase.IDLE,
                        startingGuestId = null,
                        message = "Graceful shutdown sent for ${guest.name}.",
                    )
                }
                delay(3_000)
                refresh()
            } catch (e: Exception) {
                _state.update {
                    it.copy(phase = Phase.IDLE, startingGuestId = null, error = e.userMessage())
                }
            }
        }
    }

    fun rebootGuest(guest: Guest) {
        val config = _state.value.config
        viewModelScope.launch {
            _state.update {
                it.copy(phase = Phase.STARTING, startingGuestId = guest.id, error = null)
            }
            try {
                client.rebootGuest(config, guest)
                _state.update {
                    it.copy(
                        phase = Phase.IDLE,
                        startingGuestId = null,
                        message = "Reboot command sent for ${guest.name}.",
                    )
                }
                delay(3_000)
                refresh()
            } catch (e: Exception) {
                _state.update {
                    it.copy(phase = Phase.IDLE, startingGuestId = null, error = e.userMessage())
                }
            }
        }
    }

    fun resetGuest(guest: Guest) {
        val config = _state.value.config
        viewModelScope.launch {
            _state.update {
                it.copy(phase = Phase.STARTING, startingGuestId = guest.id, error = null)
            }
            try {
                client.resetGuest(config, guest)
                _state.update {
                    it.copy(
                        phase = Phase.IDLE,
                        startingGuestId = null,
                        message = "Hard reset sent for ${guest.name}.",
                    )
                }
                delay(3_000)
                refresh()
            } catch (e: Exception) {
                _state.update {
                    it.copy(phase = Phase.IDLE, startingGuestId = null, error = e.userMessage())
                }
            }
        }
    }

    fun stopGuest(guest: Guest) {
        val config = _state.value.config
        viewModelScope.launch {
            _state.update {
                it.copy(phase = Phase.STARTING, startingGuestId = guest.id, error = null)
            }
            try {
                client.stopGuest(config, guest)
                _state.update {
                    it.copy(
                        phase = Phase.IDLE,
                        startingGuestId = null,
                        message = "Force stop sent for ${guest.name}.",
                    )
                }
                delay(3_000)
                refresh()
            } catch (e: Exception) {
                _state.update {
                    it.copy(phase = Phase.IDLE, startingGuestId = null, error = e.userMessage())
                }
            }
        }
    }

    /**
     * Shuts down the Proxmox node itself, then watches the API until it stops
     * answering so the UI flips to "offline" without waiting for a manual refresh.
     */
    fun shutdownNode() {
        val config = _state.value.config
        if (!config.canTalkToApi) {
            _state.update { it.copy(error = "Add your Proxmox host and login in Settings first.") }
            return
        }
        if (_state.value.busy) return

        viewModelScope.launch {
            _state.update {
                it.copy(
                    phase = Phase.SHUTTING_DOWN,
                    startingGuestId = null,
                    error = null,
                    message = null,
                )
            }

            val failure = runCatching { client.shutdownNode(config) }.exceptionOrNull()
            if (failure != null && (!failure.isNetworkFailure() || failure.isDefinitelyNotSent())) {
                _state.update {
                    it.copy(phase = Phase.IDLE, error = failure.userMessage())
                }
                return@launch
            }

            // A dropped connection right after sending is expected while the node
            // powers off, so treat it as a successful request and keep watching.
            _state.update {
                it.copy(
                    message = "Shutdown command sent. Waiting for ${config.host} to power off...",
                )
            }
            waitForNodeOffline(config)
        }
    }

    fun testConnection(config: ProxmoxConfig, loadGuestsOnSuccess: Boolean = true) {
        viewModelScope.launch {
            _state.update { it.copy(phase = Phase.LOADING_GUESTS, error = null, message = null) }
            try {
                client.forgetSession()
                val summary = client.testConnection(config)

                // Persist the node the server actually reported, so the guest calls
                // target a real node instead of the "pve" placeholder.
                client.resolvedNode?.let { settings.updateNode(it) }

                val guests = if (loadGuestsOnSuccess) {
                    runCatching { client.listGuests(config) }
                        .onFailure { throw it }
                        .getOrDefault(emptyList())
                } else {
                    emptyList()
                }
                val nodeStatus = if (loadGuestsOnSuccess) {
                    runCatching { client.fetchNodeStatus(config) }.getOrNull()
                } else {
                    null
                }

                _state.update {
                    it.copy(
                        phase = Phase.IDLE,
                        serverReachable = true,
                        guests = if (loadGuestsOnSuccess) guests else it.guests,
                        nodeStatus = nodeStatus,
                        message = "Connected to $summary.",
                    )
                }
                if (loadGuestsOnSuccess) {
                    }
            } catch (e: Exception) {
                _state.update { it.copy(phase = Phase.IDLE, error = e.userMessage()) }
            }
        }
    }

    // ---------------------------------------------------------------- internals

    /**
     * Polls the API until it answers. Proxmox takes a while to boot: the API port
     * usually opens before the services behind it are ready, so we treat a
     * successful guest listing as "ready". Gives up after [timeoutMillis].
     */
    private suspend fun waitForServerThenLoad(timeoutMillis: Long = 5 * 60 * 1000) {
        val config = _state.value.config
        val deadline = System.currentTimeMillis() + timeoutMillis
        var lastError: String? = null

        while (System.currentTimeMillis() < deadline) {
            try {
                val guests = client.listGuests(config)
                val nodeStatus = runCatching { client.fetchNodeStatus(config) }.getOrNull()
                _state.update {
                    it.copy(
                        phase = Phase.IDLE,
                        guests = guests,
                        serverReachable = true,
                        nodeStatus = nodeStatus,
                        message = "Server is up. ${guests.count { g -> g.status == GuestStatus.RUNNING }} " +
                            "guest(s) running.",
                    )
                }
                return
            } catch (e: Exception) {
                lastError = e.userMessage()
                delay(5_000)
            }
        }
        _state.update {
            it.copy(
                phase = Phase.IDLE,
                serverReachable = false,
                error = "The server did not come up within 5 minutes. " +
                    "Last error: $lastError",
            )
        }
    }

    /**
     * Waits for the node to stop answering after a shutdown request. Proxmox may
     * keep serving the API for a little while, so a single failed call is not
     * enough: we poll until the failure is a real network error.
     */
    private suspend fun waitForNodeOffline(
        config: ProxmoxConfig,
        timeoutMillis: Long = 4 * 60 * 1000,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMillis

        while (System.currentTimeMillis() < deadline) {
            try {
                // Still answering. Give the shutdown task time to progress.
                client.listGuests(config)
                delay(3_000)
            } catch (e: Exception) {
                if (!e.isNetworkFailure()) {
                    _state.update { it.copy(phase = Phase.IDLE, error = e.userMessage()) }
                    return
                }
                _state.update {
                    it.copy(
                        phase = Phase.IDLE,
                        serverReachable = false,
                        error = null,
                        message = NODE_OFF_MESSAGE,
                    )
                }
                return
            }
        }

        _state.update {
            it.copy(
                phase = Phase.IDLE,
                message = "Shutdown requested, but ${config.host} is still answering. " +
                    "Pull down to refresh.",
            )
        }
    }

    /**
     * Keeps the guest list current while the screen is visible.
     *
     * Reads run in the background and never touch [UiState.refreshing], so the
     * list updates in place instead of flashing a loading state. The first read
     * happens immediately, then every [intervalMillis] while the screen is up.
     */
    fun onScreenVisible(intervalMillis: Long = 3_000) {
        if (refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch {
            // Config is read from disk asynchronously. Wait for it before the first
            // read so returning to the app does not sit on a stale "offline" state.
            while (!_state.value.configured) delay(100)
            if (_state.value.phase == Phase.IDLE) refreshQuietly()
            while (true) {
                delay(intervalMillis)
                if (_state.value.phase == Phase.IDLE && _state.value.configured) {
                    refreshQuietly()
                }
            }
        }
    }

    fun onScreenHidden() {
        refreshJob?.cancel()
        refreshJob = null
    }

    private suspend fun refreshQuietly() {
        if (!_state.value.configured) return
        val config = _state.value.config
        runCatching { client.listGuests(config) }
            .onSuccess { guests ->
                val nodeStatus = runCatching { client.fetchNodeStatus(config) }.getOrNull()
                readFailures = 0
                _state.update {
                    it.copy(
                        guests = guests,
                        serverReachable = true,
                        nodeStatus = nodeStatus,
                        message = it.message.takeUnless { message -> message == NODE_OFF_MESSAGE },
                    )
                }
            }
            .onFailure {
                readFailures++
                // Drop the list once the node is really gone. Waiting for a second
                // failure keeps a single dropped packet from clearing the screen.
                if (readFailures >= 2) {
                    _state.update {
                        it.copy(serverReachable = false, guests = emptyList(), nodeStatus = null)
                    }
                } else {
                    _state.update { it.copy(serverReachable = false) }
                }
            }
    }

    override fun onCleared() {
        onScreenHidden()
        super.onCleared()
    }

    private fun Throwable.userMessage(): String =
        if (this is ProxmoxException) message ?: "Unknown error"
        else message?.takeIf { it.isNotBlank() } ?: "Unknown error"

    /** True when this failure came from the network layer, not an HTTP/API error. */
    private fun Throwable.isNetworkFailure(): Boolean {
        var cause: Throwable? = this
        while (cause != null) {
            if (cause is IOException) return true
            cause = cause.cause
        }
        return false
    }

    /**
     * True when the network failure happened before the request could leave the
     * device. These are not evidence that the node accepted a shutdown command.
     */
    private fun Throwable.isDefinitelyNotSent(): Boolean {
        var cause: Throwable? = this
        while (cause != null) {
            if (cause is ConnectException || cause is UnknownHostException) return true
            cause = cause.cause
        }
        return false
    }

    /** Exposed for the settings screen so it can warn about being offline. */
    fun localNetworkAvailable(): Boolean = NetworkUtils.hasLocalNetwork()

    private companion object {
        const val NODE_OFF_MESSAGE = "Node is off. Wake it when you're ready."
    }
}
