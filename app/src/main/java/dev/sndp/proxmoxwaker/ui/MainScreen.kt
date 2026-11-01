package dev.sndp.proxmoxwaker.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.sndp.proxmoxwaker.data.Guest
import dev.sndp.proxmoxwaker.data.GuestStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(vm: MainViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    var confirmShutdown by remember { mutableStateOf(false) }

    val snackbarHostState = remember { SnackbarHostState() }

    // System back closes Settings instead of leaving the app.
    BackHandler(enabled = state.showSettings) { vm.closeSettings() }

    // Settings shows its own result notes. Letting the snackbar consume the
    // message here would clear it before Settings could render it.
    LaunchedEffect(state.message, state.error, state.showSettings) {
        if (state.showSettings) return@LaunchedEffect
        val msg = state.error ?: state.message
        if (!msg.isNullOrBlank()) {
            snackbarHostState.currentSnackbarData?.dismiss()
            if (snackbarHostState.currentSnackbarData?.visuals?.message == msg) {
            } else {
                snackbarHostState.currentSnackbarData?.dismiss()
                kotlinx.coroutines.coroutineScope {
                    snackbarHostState.showSnackbar(
                        message = msg,
                        duration = SnackbarDuration.Short,
                    )
                }
            }
            if (state.error != null) vm.dismissError() else if (state.message != null) vm.dismissMessage()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            snackbarHost = {
                SnackbarHost(
                    hostState = snackbarHostState,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                ) { data ->
                    androidx.compose.material3.Snackbar(
                        snackbarData = data,
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                        actionColor = MaterialTheme.colorScheme.primary,
                        dismissActionContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        shape = RoundedCornerShape(14.dp),
                    )
                }
            },
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        scrolledContainerColor = Color.Transparent,
                    ),
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            BrandMark()
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = "Proxmox Waker",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Medium,
                                )
                                Text(
                                    text = state.config.host.ifBlank { "Not configured" },
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    },
                    actions = {
                        IconButton(onClick = vm::openSettings) {
                            Icon(Icons.Outlined.Settings, contentDescription = "Settings")
                        }
                    },
                )
            },
        ) { padding ->
            val pullState = rememberPullToRefreshState()
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .pullToRefresh(
                        isRefreshing = state.refreshing,
                        state = pullState,
                        enabled = state.serverReachable,
                        onRefresh = vm::refresh,
                    ),
                contentAlignment = Alignment.TopStart,
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    // Messages shown as Toasts

                    item {
                        NodePanel(
                            state = state,
                            onWake = vm::wakeServer,
                            onShutdown = { confirmShutdown = true },
                        )
                    }

                    item {
                        SectionHeader(state)
                    }

                    item {
                        if (state.guests.isEmpty()) {
                            EmptyState(state)
                        } else {
                            GuestList(
                                state = state,
                                onStart = vm::startGuest,
                                onShutdown = vm::shutdownGuest,
                            )
                        }
                    }

                    item {
                        Text(
                            text = "AUTO-REFRESH  ·  10S",
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp),
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                            textAlign = TextAlign.Center,
                            letterSpacing = 1.1.sp,
                        )
                    }
                }

                PullToRefreshDefaults.Indicator(
                    modifier = Modifier.align(Alignment.TopCenter),
                    isRefreshing = state.refreshing,
                    state = pullState,
                )
            }
        }

        if (state.showSettings) {
            SettingsScreen(
                initial = state.config,
                onSave = vm::saveAndConnect,
                onTest = { vm.testConnection(it, loadGuestsOnSuccess = false) },
                onClose = vm::closeSettings,
                statusMessage = state.message,
                statusError = state.error,
                testing = state.phase == Phase.LOADING_GUESTS,
                requireBiometric = state.requireBiometric,
                biometricAvailable = state.biometricAvailable,
                biometricMessage = state.biometricMessage,
                onToggleBiometric = vm::setRequireBiometric,
                localNetworkAvailable = remember(state.showSettings) {
                    vm.localNetworkAvailable()
                },
            )
        }
    }

    if (confirmShutdown) {
        ShutdownNodeDialog(
            host = state.config.host.ifBlank { "the node" },
            onConfirm = {
                confirmShutdown = false
                vm.shutdownNode()
            },
            onDismiss = { confirmShutdown = false },
        )
    }
}

@Composable
private fun BrandMark() {
    Surface(
        modifier = Modifier.size(38.dp),
        shape = RoundedCornerShape(13.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Box(contentAlignment = Alignment.Center) {
            PowerMark(
                modifier = Modifier.size(21.dp),
                arcColor = MaterialTheme.colorScheme.onSurface,
                barColor = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun PowerMark(
    modifier: Modifier = Modifier,
    arcColor: Color = MaterialTheme.colorScheme.onSurface,
    barColor: Color = MaterialTheme.colorScheme.primary,
) {
    Canvas(modifier = modifier) {
        val stroke = size.minDimension * 0.1f
        val radius = size.minDimension * 0.33f
        val center = Offset(size.width / 2f, size.height / 2f + size.height * 0.035f)

        drawArc(
            color = arcColor,
            startAngle = -55f,
            sweepAngle = 290f,
            useCenter = false,
            topLeft = Offset(center.x - radius, center.y - radius),
            size = Size(radius * 2f, radius * 2f),
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
        drawLine(
            color = barColor,
            start = Offset(size.width / 2f, size.height * 0.14f),
            end = Offset(size.width / 2f, center.y),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
    }
}

@Composable
private fun SectionHeader(state: UiState) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "MACHINES",
            style = MaterialTheme.typography.labelSmall,
            letterSpacing = 1.2.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (state.refreshing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(12.dp),
                    strokeWidth = 1.5.dp,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(8.dp))
            }
            Text(
                text = when {
                    !state.configured -> "—"
                    state.refreshing -> "READING"
                    !state.serverReachable -> "OFFLINE"
                    else -> "${state.guests.size} FOUND"
                },
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun GuestList(
    state: UiState,
    onStart: (Guest) -> Unit,
    onShutdown: (Guest) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column {
            state.guests.forEachIndexed { index, guest ->
                GuestRow(
                    guest = guest,
                    busy = state.startingGuestId == guest.id,
                    anyBusy = state.busy,
                    onStart = { onStart(guest) },
                    onShutdown = { onShutdown(guest) },
                )
                if (index != state.guests.lastIndex) {
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun GuestRow(
    guest: Guest,
    busy: Boolean,
    anyBusy: Boolean,
    onStart: () -> Unit,
    onShutdown: () -> Unit,
) {
    val running = guest.status == GuestStatus.RUNNING
    val statusColor = guestStatusColor(guest.status)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (guest.type.label == "CT") {
                    Icons.Outlined.Dns
                } else {
                    Icons.Outlined.Computer
                },
                contentDescription = null,
                modifier = Modifier.size(19.dp),
                tint = if (running) statusColor else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = guest.name,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "${guest.type.label} ${guest.vmid}",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(statusColor),
                )
                Spacer(Modifier.width(5.dp))
                Text(
                    text = guest.status.label.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    letterSpacing = 0.6.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        when {
            busy -> CircularProgressIndicator(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .size(20.dp),
                strokeWidth = 2.dp,
            )

            running -> TextButton(
                onClick = onShutdown,
                enabled = !anyBusy,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            ) {
                Icon(Icons.Outlined.StopCircle, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(5.dp))
                Text("Stop")
            }

            else -> TextButton(
                onClick = onStart,
                enabled = !anyBusy,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.primary,
                ),
            ) {
                Icon(Icons.Outlined.PlayArrow, contentDescription = null, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(4.dp))
                Text("Start")
            }
        }
    }
}

@Composable
private fun NodePanel(
    state: UiState,
    onWake: () -> Unit,
    onShutdown: () -> Unit,
) {
    val online = state.serverReachable
    val shuttingDown = state.phase == Phase.SHUTTING_DOWN
    val waking = state.phase == Phase.WAKING || state.phase == Phase.WAITING_FOR_SERVER

    val statusColor = when {
        shuttingDown -> MaterialTheme.colorScheme.error
        waking -> StatusColors.warn
        online -> StatusColors.running
        else -> StatusColors.stopped
    }

    val title = when (state.phase) {
        Phase.WAKING -> "Sending magic packet"
        Phase.WAITING_FOR_SERVER -> "Waking up"
        Phase.SHUTTING_DOWN -> "Shutting down"
        Phase.LOADING_GUESTS -> if (online) "Reading machines" else "Connecting"
        Phase.STARTING -> "Working"
        Phase.IDLE -> when {
            !state.configured -> "Not configured"
            online -> "Online"
            else -> "Offline"
        }
    }

    val subtitle = when {
        !state.configured -> "Open settings to connect your Proxmox node."
        shuttingDown -> "Waiting for the API to stop answering. This can take a minute."
        waking -> "The node is booting. This can take a minute."
        online -> "${state.config.host} · ${state.guests.size} machines"
        state.config.host.isNotBlank() -> "${state.config.host} · Wake it to manage its machines"
        else -> "Add your node in settings."
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(statusColor),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "SERVER",
                    style = MaterialTheme.typography.labelSmall,
                    letterSpacing = 1.2.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = state.config.node.ifBlank { "auto-detect" },
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(16.dp))

            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )

            Spacer(Modifier.height(4.dp))

            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (shuttingDown || waking || state.phase == Phase.LOADING_GUESTS) {
                Spacer(Modifier.height(16.dp))
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .clip(CircleShape),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
            }

            if (online && state.nodeStatus != null) {
                val ns = state.nodeStatus
                Spacer(Modifier.height(16.dp))
                if (ns.cpuCount > 0 && ns.cpuModel.isNotBlank()) {
                    Text(
                        text = "${ns.cpuCount} x ${ns.cpuModel}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(4.dp))
                } else if (ns.cpuModel.isNotBlank()) {
                    Text(
                        text = ns.cpuModel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(4.dp))
                } else if (ns.cpuCount > 0) {
                    Text(
                        text = "${ns.cpuCount} CPUs",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                }
                if (ns.uptimeText().isNotBlank()) {
                    Text(
                        text = "Uptime: ${ns.uptimeText()}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                } else if (ns.cpuCount > 0 || ns.cpuModel.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                } else {
                    Spacer(Modifier.height(6.dp))
                }
                ResourceBar(label = "CPU", percent = ns.cpuPercent)
                Spacer(Modifier.height(8.dp))
                ResourceBar(label = "RAM", percent = ns.memoryPercent, used = ns.memoryUsed, total = ns.memoryTotal)
                Spacer(Modifier.height(8.dp))
                ResourceBar(label = "HDD", percent = ns.diskPercent, used = ns.rootUsed, total = ns.rootTotal)
                if (ns.swapTotal > 0) {
                    Spacer(Modifier.height(8.dp))
                    ResourceBar(label = "SWAP", percent = ns.swapPercent, used = ns.swapUsed, total = ns.swapTotal)
                }
            }

            Spacer(Modifier.height(18.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = onWake,
                    enabled = state.configured && state.config.canWake && !online && !state.busy,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Icon(
                        Icons.Outlined.PowerSettingsNew,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Wake")
                }

                OutlinedButton(
                    onClick = onShutdown,
                    enabled = state.configured && online && !state.busy,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(
                        1.dp,
                        if (state.configured && online && !state.busy) {
                            MaterialTheme.colorScheme.error.copy(alpha = 0.55f)
                        } else {
                            MaterialTheme.colorScheme.outlineVariant
                        },
                    ),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Icon(
                        Icons.Outlined.PowerSettingsNew,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Shut down")
                }
            }
        }
    }
}

@Composable
private fun ResourceBar(
    label: String,
    percent: Int,
    used: Long = 0,
    total: Long = 0,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            if (total > 0) {
                Text(
                    text = "${formatBytes(used)} / ${formatBytes(total)} ($percent%)",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    text = "$percent%",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { (percent / 100f).coerceIn(0f, 1f) },
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(CircleShape),
            color = if (percent >= 90) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
        )
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024 && unit < units.size - 1) {
        value /= 1024
        unit++
    }
    return if (unit == 0) "${value.toLong()}B" else String.format("%.1f%s", value, units[unit])
}

@Composable
private fun EmptyState(state: UiState) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (state.refreshing) {
                CircularProgressIndicator(modifier = Modifier.size(26.dp), strokeWidth = 2.dp)
            } else {
                Icon(
                    imageVector = if (state.configured) Icons.Outlined.CloudOff else Icons.Outlined.Dns,
                    contentDescription = null,
                    modifier = Modifier.size(30.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(14.dp))
            Text(
                text = when {
                    !state.configured -> "No node configured"
                    state.refreshing -> "Reading machines"
                    !state.serverReachable -> "The node is offline"
                    else -> "No machines found"
                },
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = when {
                    !state.configured -> "Open settings and add your Proxmox host to get started."
                    state.refreshing -> "This only takes a moment."
                    !state.serverReachable -> "Wake the server and it will connect automatically."
                    else -> "Start a VM or container in Proxmox and it will appear here."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun Banner(
    state: UiState,
    onDismissError: () -> Unit,
    onDismissMessage: () -> Unit,
) {
    AnimatedVisibility(visible = state.error != null) {
        Surface(
            color = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.25f)),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Outlined.ErrorOutline,
                    contentDescription = null,
                    modifier = Modifier.size(19.dp),
                    tint = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = state.error.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDismissError) {
                    Icon(
                        Icons.Outlined.Close,
                        contentDescription = "Dismiss",
                        modifier = Modifier.size(17.dp),
                    )
                }
            }
        }
    }

    AnimatedVisibility(visible = state.message != null && state.error == null) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurface,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Outlined.Info,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.secondary,
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = state.message.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDismissMessage) {
                    Icon(
                        Icons.Outlined.Close,
                        contentDescription = "Dismiss",
                        modifier = Modifier.size(17.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ShutdownNodeDialog(
    host: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                Icons.Outlined.PowerSettingsNew,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
            )
        },
        title = { Text("Shut down the node?") },
        text = {
            Text(
                "This sends an ACPI shutdown to $host. All running VMs and containers " +
                    "will stop. You can wake it again with Wake-on-LAN.",
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
            ) {
                Text("Shut down")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
        containerColor = MaterialTheme.colorScheme.surface,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun guestStatusColor(status: GuestStatus): Color = when (status) {
    GuestStatus.RUNNING -> StatusColors.running
    GuestStatus.STOPPED -> StatusColors.stopped
    GuestStatus.UNKNOWN -> StatusColors.stopped
    else -> StatusColors.warn
}
