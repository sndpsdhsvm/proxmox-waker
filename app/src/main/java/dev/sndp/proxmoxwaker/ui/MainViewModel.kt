package dev.sndp.proxmoxwaker.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.sndp.proxmoxwaker.data.Guest
import dev.sndp.proxmoxwaker.data.GuestStatus
import dev.sndp.proxmoxwaker.data.ProxmoxConfig
import dev.sndp.proxmoxwaker.data.SettingsRepository
import dev.sndp.proxmoxwaker.net.NetworkUtils
import dev.sndp.proxmoxwaker.net.ProxmoxClient
import dev.sndp.proxmoxwaker.net.ProxmoxException
import dev.sndp.proxmoxwaker.net.TlsSupport
import dev.sndp.proxmoxwaker.net.WakeOnLan
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the main screen is doing right now, used to drive the status banner. */
enum class Phase { IDLE, WAKING, WAITING_FOR_SERVER, LOADING_GUESTS, STARTING }

data class UiState(
    val config: ProxmoxConfig = ProxmoxConfig(),
    val configured: Boolean = false,
    val phase: Phase = Phase.IDLE,
    val serverReachable: Boolean = false,
    val guests: List<Guest> = emptyList(),
    val message: String? = null,
    val error: String? = null,
    val startingGuestId: String? = null,
    val showSettings: Boolean = false,
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

    private var pollJob: Job? = null

    init {
        viewModelScope.launch {
            settings.config.collect { config ->
                val configured = config.canTalkToApi
                _state.update {
                    it.copy(
                        config = config,
                        configured = configured,
                        showSettings = if (!configured) true else it.showSettings,
                    )
                }
            }
        }
    }

    // ------------------------------------------------------------- user actions

    fun openSettings() = _state.update { it.copy(showSettings = true, error = null) }

    fun closeSettings() = _state.update {
        // Only allow closing the sheet once there is a usable config.
        if (it.config.canTalkToApi) it.copy(showSettings = false, error = null)
        else it.copy(error = "Fill in the host and login details first.")
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

    /** Loads (or reloads) the guest list from the API. */
    fun refresh() {
        val config = _state.value.config
        if (!config.canTalkToApi) {
            _state.update { it.copy(error = "Add your Proxmox host and login in Settings first.") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(phase = Phase.LOADING_GUESTS, error = null) }
            try {
                val guests = client.listGuests(config)
                _state.update {
                    it.copy(
                        phase = Phase.IDLE,
                        guests = guests,
                        serverReachable = true,
                        message = null,
                    )
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        phase = Phase.IDLE,
                        serverReachable = false,
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
                        message = "Shutdown command sent for ${guest.name}.",
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

                _state.update {
                    it.copy(
                        phase = Phase.IDLE,
                        serverReachable = true,
                        guests = if (loadGuestsOnSuccess) guests else it.guests,
                        message = "Connected to $summary.",
                    )
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
                _state.update {
                    it.copy(
                        phase = Phase.IDLE,
                        guests = guests,
                        serverReachable = true,
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

    /** Starts a periodic refresh so the guest list stays current while visible. */
    fun startPolling(intervalMillis: Long = 15_000) {
        if (pollJob?.isActive == true) return
        pollJob = viewModelScope.launch {
            while (true) {
                delay(intervalMillis)
                if (_state.value.phase == Phase.IDLE && _state.value.configured) {
                    runCatching { client.listGuests(_state.value.config) }
                        .onSuccess { guests ->
                            _state.update {
                                it.copy(guests = guests, serverReachable = true)
                            }
                        }
                        .onFailure {
                            _state.update { s -> s.copy(serverReachable = false) }
                        }
                }
            }
        }
    }

    fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    override fun onCleared() {
        stopPolling()
        super.onCleared()
    }

    private fun Exception.userMessage(): String =
        if (this is ProxmoxException) message ?: "Unknown error"
        else message?.takeIf { it.isNotBlank() } ?: "Unknown error"

    /** Exposed for the settings screen so it can warn about being offline. */
    fun localNetworkAvailable(): Boolean = NetworkUtils.hasLocalNetwork()
}
