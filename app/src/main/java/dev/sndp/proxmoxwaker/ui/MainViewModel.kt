package dev.sndp.proxmoxwaker.ui

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.sndp.proxmoxwaker.data.Guest
import dev.sndp.proxmoxwaker.data.GuestStatus
import dev.sndp.proxmoxwaker.data.NodeStatus
import dev.sndp.proxmoxwaker.data.ProxmoxConfig
import dev.sndp.proxmoxwaker.data.SettingsRepository
import dev.sndp.proxmoxwaker.net.ProxmoxClient
import dev.sndp.proxmoxwaker.net.ProxmoxException
import dev.sndp.proxmoxwaker.net.TlsSupport
import dev.sndp.proxmoxwaker.net.WakeOnLan
import dev.sndp.proxmoxwaker.security.BiometricAuth
import java.io.IOException
import java.net.ConnectException
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
    /** True when the phone has a Wi-Fi, Ethernet, or VPN network. */
    val networkAvailable: Boolean = true,
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
    @Volatile
    private var readFailures = 0

    private val connectivity =
        getApplication<Application>().getSystemService(ConnectivityManager::class.java)

    /** Serialises reads so a manual refresh and the poll loop never overlap. */
    private val readLock = Mutex()

    /**
     * The network Android currently treats as the default. Tracked from the
     * callback instead of [ConnectivityManager.activeNetwork], because inside
     * onLost the active network can still be the one that just went away. That
     * stale read is what left a dropped Wi-Fi connection showing "Offline"
     * instead of "No connection".
     */
    @Volatile
    private var defaultNetwork: Network? = null

    /** The default network whose capabilities were last published to the UI. */
    @Volatile
    private var publishedNetwork: Network? = null

    /**
     * Incremented whenever the route to the LAN changes. A read that started on
     * the old route (or the old host) must not publish its result: it can finish
     * after Wi-Fi drops and would otherwise flip the UI back to "Online".
     */
    private val networkEpoch = AtomicLong(0)

    /**
     * Wi-Fi dropping is known to the OS immediately, but a connect attempt to a
     * LAN address can take seconds to time out. Publish the new state as soon as
     * Android reports a change, and read again as soon as a usable route appears.
     */
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            // A handover (Wi-Fi -> Ethernet, or Wi-Fi -> mobile) can deliver
            // onAvailable without a matching onLost. Adopt it and republish.
            defaultNetwork = network
            publishNetworkState()
        }

        override fun onLost(network: Network) {
            if (defaultNetwork == network) defaultNetwork = null

            // Do not trust activeNetwork if it still points at the network that
            // just disappeared. A different default is a real handover.
            val active = connectivity.activeNetwork
            if (active != null && active != network) defaultNetwork = active

            publishNetworkState()
        }

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            // Signal strength changes arrive here all the time. Only the default
            // network matters, and only its transports change what we can do.
            if (defaultNetwork == null || defaultNetwork == network) {
                defaultNetwork = network
                publishNetworkState()
            }
        }

        override fun onUnavailable() {
            defaultNetwork = null
            publishNetworkState()
        }
    }

    /**
     * The Proxmox API is a LAN address, so cellular alone is not enough to
     * reach it. A VPN counts, since that can carry the LAN.
     */
    private fun refreshNetworkAvailability() {
        defaultNetwork = connectivity.activeNetwork
        publishNetworkState()
    }

    /**
     * Recomputes whether the phone can reach the LAN and reconciles that with
     * the node state. "No connection" (phone has no usable route) and "Offline"
     * (route exists but the node does not answer) are deliberately separate.
     */
    private fun publishNetworkState() {
        val network = defaultNetwork
        val caps = network?.let(connectivity::getNetworkCapabilities)
        val available = caps != null && (
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            )

        val previous = _state.value
        val routeChanged = network != publishedNetwork
        val availabilityChanged = available != previous.networkAvailable
        publishedNetwork = network

        if (routeChanged || availabilityChanged) networkEpoch.incrementAndGet()

        if (availabilityChanged) {
            _state.update { it.copy(networkAvailable = available) }
        }

        if (!available) {
            // The phone cannot reach the LAN at all, so any node data on screen
            // is stale and unverifiable. Clear it immediately.
            markOffline()
        } else if (routeChanged || availabilityChanged) {
            // A new route is unverified. Stop claiming the node is online until
            // the first read on this route succeeds; the old guest list stays as
            // a hint and will be cleared if the node turns out to be unreachable.
            if (previous.serverReachable) {
                _state.update { it.copy(serverReachable = false) }
            }
            if (previous.configured && previous.phase == Phase.IDLE) {
                // Read now instead of waiting for the next poll tick.
                viewModelScope.launch { refreshQuietly() }
            }
        }
    }

    private fun markOffline() {
        readFailures = 0
        _state.update {
            if (it.guests.isEmpty() && it.nodeStatus == null && !it.serverReachable && !it.refreshing) {
                it
            } else {
                it.copy(
                    serverReachable = false,
                    guests = emptyList(),
                    nodeStatus = null,
                    refreshing = false,
                )
            }
        }
    }

    init {
        // Seed from activeNetwork before registering: the callback can deliver
        // onAvailable synchronously, and a later activeNetwork read here must
        // not clobber the network the callback just adopted.
        refreshNetworkAvailability()
        runCatching { connectivity.registerDefaultNetworkCallback(networkCallback) }
        viewModelScope.launch {
            combine(settings.config, settings.requireBiometric) { config, require ->
                config to require
            }.collect { (config, requireBiometric) ->
                val status = BiometricAuth.status(getApplication())
                val previous = _state.value.config
                val targetChanged = previous.host.isNotBlank() && (
                    previous.baseUrl != config.baseUrl ||
                        previous.username != config.username
                    )
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
                        // Reachability and guests belong to the old host. A new
                        // host starts out unverified until it answers.
                        serverReachable = if (targetChanged) false else current.serverReachable,
                        guests = if (targetChanged) emptyList() else current.guests,
                        nodeStatus = if (targetChanged) null else current.nodeStatus,
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
        if (!_state.value.networkAvailable) {
            _state.update { it.copy(error = NO_CONNECTION_MESSAGE) }
            return
        }
        if (_state.value.busy) return
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
        if (!_state.value.networkAvailable) {
            _state.update { it.copy(error = NO_CONNECTION_MESSAGE) }
            return
        }
        if (_state.value.busy || _state.value.refreshing) return

        viewModelScope.launch {
            _state.update { it.copy(refreshing = true, error = null) }
            // Share the lock with the poll loop so a manual refresh cannot race
            // a background read and apply an older guest list last.
            readLock.withLock {
                val epoch = networkEpoch.get()
                try {
                    val guests = client.listGuests(config)
                    val nodeStatus = runCatching { client.fetchNodeStatus(config) }.getOrNull()
                    readFailures = 0
                    _state.update { current ->
                        if (!isCurrentRead(epoch, config, current)) {
                            current.copy(refreshing = false)
                        } else {
                            current.copy(
                                refreshing = false,
                                guests = guests,
                                serverReachable = true,
                                nodeStatus = nodeStatus,
                                message = null,
                            )
                        }
                    }
                } catch (e: Exception) {
                    readFailures = 0
                    _state.update { current ->
                        if (!isCurrentRead(epoch, config, current)) {
                            // The route or target changed while this read was in
                            // flight. The callback already reconciled the state;
                            // do not report a stale error or a stale guest list.
                            current.copy(refreshing = false)
                        } else {
                            current.copy(
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
                refreshQuietly()
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
                refreshQuietly()
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
                refreshQuietly()
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
                refreshQuietly()
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
                refreshQuietly()
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
        if (!_state.value.networkAvailable) {
            _state.update { it.copy(phase = Phase.IDLE, error = NO_CONNECTION_MESSAGE) }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(phase = Phase.LOADING_GUESTS, error = null, message = null) }
            // Testing talks to the same client as the poll loop, so keep it from
            // clearing the session in the middle of a background read.
            readLock.withLock {
                // Capture the route after taking the lock: a handover while this
                // request waited should not invalidate the read it is about to do.
                val epoch = networkEpoch.get()
                try {
                    client.forgetSession()
                    val summary = client.testConnection(config)

                    // Persist the node the server actually reported, so the guest calls
                    // target a real node instead of the "pve" placeholder. A test from
                    // Settings uses unsaved values, so it must not rewrite the saved
                    // node or claim that the main screen's node is online.
                    if (loadGuestsOnSuccess) {
                        client.resolvedNode?.let { settings.updateNode(it) }
                    }

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

                    _state.update { current ->
                        if (epoch != networkEpoch.get() || !current.networkAvailable) {
                            // The network changed while connecting. Do not report a
                            // stale success for a route that is already gone.
                            current.copy(phase = Phase.IDLE, error = NO_CONNECTION_MESSAGE)
                        } else {
                            current.copy(
                                phase = Phase.IDLE,
                                serverReachable = if (loadGuestsOnSuccess) true else current.serverReachable,
                                guests = if (loadGuestsOnSuccess) guests else current.guests,
                                nodeStatus = if (loadGuestsOnSuccess) nodeStatus else current.nodeStatus,
                                message = "Connected to $summary.",
                            )
                        }
                    }
                } catch (e: Exception) {
                    _state.update { current ->
                        current.copy(
                            phase = Phase.IDLE,
                            // A failed save-and-connect must not leave the main
                            // screen claiming the previous host is still online.
                            serverReachable = if (loadGuestsOnSuccess) false else current.serverReachable,
                            error = if (!current.networkAvailable) NO_CONNECTION_MESSAGE else e.userMessage(),
                        )
                    }
                } finally {
                    // An unsaved Settings test used a different host and node.
                    // Drop that session so the main screen resolves the saved
                    // host again instead of borrowing the test host's node name.
                    if (!loadGuestsOnSuccess) client.forgetSession()
                }
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
            if (!isCurrentTarget(config)) return

            // A magic packet may already be on its way, but there is no point
            // hammering the API while the phone has no route to the LAN.
            if (!_state.value.networkAvailable) {
                lastError = NO_CONNECTION_MESSAGE
                delay(2_000)
                continue
            }

            val epoch = networkEpoch.get()
            try {
                val guests = client.listGuests(config)
                val nodeStatus = runCatching { client.fetchNodeStatus(config) }.getOrNull()
                val applied = applyIfCurrent(epoch, config) {
                    it.copy(
                        phase = Phase.IDLE,
                        guests = guests,
                        serverReachable = true,
                        nodeStatus = nodeStatus,
                        message = "Server is up. ${guests.count { g -> g.status == GuestStatus.RUNNING }} " +
                            "guest(s) running.",
                    )
                }
                if (applied) return
                lastError = NO_CONNECTION_MESSAGE
            } catch (e: Exception) {
                lastError = e.userMessage()
                delay(5_000)
            }
        }
        _state.update {
            it.copy(
                phase = Phase.IDLE,
                serverReachable = false,
                error = if (!it.networkAvailable) {
                    NO_CONNECTION_MESSAGE
                } else {
                    "The server did not come up within 5 minutes. Last error: $lastError"
                },
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
            if (!isCurrentTarget(config)) return

            // Losing Wi-Fi is not evidence that the node powered off. Wait for
            // a route to come back and keep watching instead of declaring it
            // asleep on the strength of a local network problem.
            if (!_state.value.networkAvailable) {
                delay(3_000)
                continue
            }

            val epoch = networkEpoch.get()
            try {
                // Still answering. Give the shutdown task time to progress.
                client.listGuests(config)
                delay(3_000)
            } catch (e: Exception) {
                if (!e.isNetworkFailure()) {
                    _state.update { it.copy(phase = Phase.IDLE, error = e.userMessage()) }
                    return
                }
                if (!_state.value.networkAvailable || epoch != networkEpoch.get()) {
                    // The failing call ran on a route that just went away.
                    delay(3_000)
                    continue
                }
                _state.update {
                    it.copy(
                        phase = Phase.IDLE,
                        serverReachable = false,
                        // Drop the machine list in the same state emission as the
                        // offline flag. Otherwise the node card turns offline a
                        // few seconds before the guest card clears.
                        guests = emptyList(),
                        nodeStatus = null,
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
                message = if (!it.networkAvailable) {
                    NO_CONNECTION_MESSAGE
                } else {
                    "Shutdown requested, but ${config.host} is still answering. " +
                        "Pull down to refresh."
                },
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
            if (canReadNow()) refreshQuietly()
            while (true) {
                delay(intervalMillis)
                if (canReadNow()) refreshQuietly()
            }
        }
    }

    fun onScreenHidden() {
        refreshJob?.cancel()
        refreshJob = null
    }

    /** A background read only makes sense when there is a route and the app is idle. */
    private fun canReadNow(): Boolean {
        val current = _state.value
        return current.configured &&
            current.phase == Phase.IDLE &&
            current.networkAvailable &&
            !current.refreshing
    }

    private suspend fun refreshQuietly() {
        readLock.withLock {
            if (!canReadNow()) return@withLock
            val config = _state.value.config
            val epoch = networkEpoch.get()

            try {
                val guests = client.listGuests(config)
                val nodeStatus = runCatching { client.fetchNodeStatus(config) }.getOrNull()
                readFailures = 0
                applyIfCurrent(epoch, config) {
                    it.copy(
                        guests = guests,
                        serverReachable = true,
                        nodeStatus = nodeStatus,
                        message = it.message.takeUnless { message -> message == NODE_OFF_MESSAGE },
                    )
                }
            } catch (e: Exception) {
                if (!isCurrentRead(epoch, config, _state.value)) return@withLock

                readFailures++
                // Drop the list once the node is really gone. Waiting for a second
                // failure keeps a single dropped packet from clearing the screen.
                val clear = readFailures >= 2
                applyIfCurrent(epoch, config) {
                    it.copy(
                        serverReachable = false,
                        guests = if (clear) emptyList() else it.guests,
                        nodeStatus = if (clear) null else it.nodeStatus,
                    )
                }
            }
        }
    }

    override fun onCleared() {
        runCatching { connectivity.unregisterNetworkCallback(networkCallback) }
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

    /** True when both configs describe the same API endpoint and credentials. */
    private fun sameEndpoint(a: ProxmoxConfig, b: ProxmoxConfig): Boolean =
        a.baseUrl == b.baseUrl && a.username == b.username && a.password == b.password

    /** True while [config] still describes the host the app is pointed at. */
    private fun isCurrentTarget(config: ProxmoxConfig): Boolean =
        sameEndpoint(_state.value.config, config)

    /**
     * True when a result produced by a read started at [epoch] still applies.
     * The network callback bumps the epoch whenever the route changes, so a read
     * that outlived Wi-Fi cannot resurrect a stale "Online" state.
     */
    private fun isCurrentRead(epoch: Long, config: ProxmoxConfig, current: UiState): Boolean =
        epoch == networkEpoch.get() &&
            current.networkAvailable &&
            sameEndpoint(current.config, config)

    /**
     * Atomically applies [transform] only if the read is still current. Using a
     * compare-and-set loop closes the gap between checking the epoch and writing
     * the result: a callback that invalidates the read in between makes the CAS
     * fail instead of letting the stale result win.
     */
    private fun applyIfCurrent(
        epoch: Long,
        config: ProxmoxConfig,
        transform: (UiState) -> UiState,
    ): Boolean {
        while (true) {
            val current = _state.value
            if (!isCurrentRead(epoch, config, current)) return false
            if (_state.compareAndSet(current, transform(current))) return true
        }
    }

    private companion object {
        const val NODE_OFF_MESSAGE = "Node is off. Wake it when you're ready."
        const val NO_CONNECTION_MESSAGE =
            "No connection. Connect to Wi-Fi, Ethernet, or a VPN and try again."
    }
}
