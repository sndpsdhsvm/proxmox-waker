package dev.sndp.proxmoxwaker.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.Refresh
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
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
    var actionGuest by remember { mutableStateOf<Guest?>(null) }
    var confirmGuestAction by remember { mutableStateOf<Pair<Guest, GuestAction>?>(null) }
    var wasWaking by remember { mutableStateOf(false) }
    var celebrate by remember { mutableStateOf(false) }

    val haptics = LocalHapticFeedback.current
    val tick = { haptics.performHapticFeedback(HapticFeedbackType.LongPress) }

    val snackbarHostState = remember { SnackbarHostState() }

    // System back closes Settings instead of leaving the app.
    BackHandler(enabled = state.showSettings) { vm.closeSettings() }

    // A successful wake gets a small celebration once the API answers again.
    LaunchedEffect(state.phase, state.serverReachable) {
        val wakingNow = state.phase == Phase.WAKING || state.phase == Phase.WAITING_FOR_SERVER
        if (wakingNow) wasWaking = true
        if (wasWaking && state.serverReachable && state.phase == Phase.IDLE) {
            wasWaking = false
            celebrate = true
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        }
    }

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
        AuroraBackdrop(modifier = Modifier.fillMaxSize())

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
            containerColor = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onBackground,
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
                        val refreshRotation by animateFloatAsState(
                            targetValue = if (state.refreshing) 360f else 0f,
                            animationSpec = tween(durationMillis = 850, easing = LinearEasing),
                            label = "refreshAction",
                        )
                        IconButton(
                            onClick = {
                                tick()
                                vm.refresh()
                            },
                            enabled = state.configured && !state.refreshing && !state.busy,
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Refresh,
                                contentDescription = "Refresh",
                                modifier = Modifier
                                    .size(20.dp)
                                    .graphicsLayer {
                                        rotationZ = if (state.refreshing) refreshRotation else 0f
                                    },
                                tint = if (state.refreshing) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                        IconButton(onClick = vm::openSettings) {
                            Icon(Icons.Outlined.Settings, contentDescription = "Settings")
                        }
                    },
                )
            },
        ) { padding ->
            val pullState = rememberPullToRefreshState()
            val listState = rememberLazyListState()
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .pullToRefresh(
                        isRefreshing = state.refreshing,
                        state = pullState,
                        enabled = state.configured,
                        onRefresh = vm::refresh,
                    ),
                contentAlignment = Alignment.TopStart,
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    item {
                        AnimatedEntrance(order = 0) {
                            NodePanel(
                                state = state,
                                onWake = {
                                    tick()
                                    vm.wakeServer()
                                },
                                onShutdown = {
                                    tick()
                                    confirmShutdown = true
                                },
                            )
                        }
                    }

                    item {
                        AnimatedEntrance(order = 1) {
                            SectionHeader(state)
                        }
                    }

                    item {
                        AnimatedEntrance(order = 2) {
                            when {
                                state.guests.isEmpty() && state.refreshing -> SkeletonGuestList()
                                state.guests.isEmpty() -> EmptyState(state)
                                else -> GuestList(
                                    state = state,
                                    online = state.serverReachable,
                                    onStart = { guest ->
                                        tick()
                                        vm.startGuest(guest)
                                    },
                                    onShutdown = { guest ->
                                        tick()
                                        vm.shutdownGuest(guest)
                                    },
                                    onMore = { actionGuest = it },
                                )
                            }
                        }
                    }

                    item {
                        AnimatedEntrance(order = 3) {
                            SyncFooter(state)
                        }
                    }
                }

                AuroraRefreshIndicator(
                    isRefreshing = state.refreshing,
                    distanceFraction = pullState.distanceFraction,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .offset(y = (pullState.distanceFraction * 56f - 26f).dp)
                        .padding(top = 8.dp),
                )
            }
        }

        if (celebrate) {
            ConfettiOverlay(
                active = true,
                onFinished = { celebrate = false },
                modifier = Modifier.fillMaxSize(),
            )
        }

        AnimatedVisibility(
            visible = state.showSettings,
            enter = slideInHorizontally(animationSpec = tween(durationMillis = 320)) { it } +
                fadeIn(animationSpec = tween(durationMillis = 220)),
            exit = slideOutHorizontally(animationSpec = tween(durationMillis = 260)) { it } +
                fadeOut(animationSpec = tween(durationMillis = 160)),
        ) {
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

    actionGuest?.let { guest ->
        GuestActionSheet(
            guest = guest,
            anyBusy = state.busy,
            onDismiss = { actionGuest = null },
            onAction = { action ->
                tick()
                when (action) {
                    GuestAction.START -> vm.startGuest(guest)
                    GuestAction.SHUTDOWN -> vm.shutdownGuest(guest)
                    GuestAction.REBOOT -> vm.rebootGuest(guest)
                    GuestAction.FORCE_STOP, GuestAction.RESET -> {
                        confirmGuestAction = guest to action
                    }
                }
            },
        )
    }

    if (confirmShutdown) {
        ShutdownNodeDialog(
            host = state.config.host.ifBlank { "the node" },
            onConfirm = {
                confirmShutdown = false
                tick()
                vm.shutdownNode()
            },
            onDismiss = { confirmShutdown = false },
        )
    }

    confirmGuestAction?.let { (guest, action) ->
        GuestActionConfirmDialog(
            guest = guest,
            action = action,
            onConfirm = {
                confirmGuestAction = null
                tick()
                when (action) {
                    GuestAction.FORCE_STOP -> vm.stopGuest(guest)
                    GuestAction.RESET -> vm.resetGuest(guest)
                    else -> Unit
                }
            },
            onDismiss = { confirmGuestAction = null },
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
internal fun PowerMark(
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
    val count by animateIntAsState(
        targetValue = state.guests.size,
        animationSpec = tween(durationMillis = 500),
        label = "guestCount",
    )

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
                    else -> "$count FOUND"
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
    online: Boolean,
    onStart: (Guest) -> Unit,
    onShutdown: (Guest) -> Unit,
    onMore: (Guest) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column {
            state.guests.forEachIndexed { index, guest ->
                AnimatedEntrance(
                    order = index.coerceAtMost(5),
                    stepMillis = 40L,
                ) {
                    Column {
                        GuestRow(
                            guest = guest,
                            online = online,
                            busy = state.startingGuestId == guest.id,
                            anyBusy = state.busy,
                            onStart = { onStart(guest) },
                            onShutdown = { onShutdown(guest) },
                            onMore = { onMore(guest) },
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
    }
}

private enum class GuestRowAction { BUSY, STOP, START }

@Composable
private fun GuestRow(
    guest: Guest,
    online: Boolean,
    busy: Boolean,
    anyBusy: Boolean,
    onStart: () -> Unit,
    onShutdown: () -> Unit,
    onMore: () -> Unit,
) {
    val running = guest.status == GuestStatus.RUNNING
    val statusColor by animateColorAsState(
        targetValue = guestStatusColor(guest.status),
        animationSpec = tween(durationMillis = 420),
        label = "guestStatus-${guest.id}",
    )
    val rowTint by animateColorAsState(
        targetValue = if (running) statusColor.copy(alpha = 0.05f) else Color.Transparent,
        animationSpec = tween(durationMillis = 500),
        label = "guestTint-${guest.id}",
    )
    val rowAction = when {
        busy -> GuestRowAction.BUSY
        running -> GuestRowAction.STOP
        else -> GuestRowAction.START
    }
    val quickInteraction = remember { MutableInteractionSource() }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(rowTint)
            .padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(
                    if (running) statusColor.copy(alpha = 0.11f)
                    else MaterialTheme.colorScheme.surfaceVariant,
                ),
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
                color = MaterialTheme.colorScheme.onSurface,
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
                StatusDot(color = statusColor, pulsing = running, size = 6.dp)
                Spacer(Modifier.width(5.dp))
                Text(
                    text = guest.status.label.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    letterSpacing = 0.6.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        AnimatedContent(
            targetState = rowAction,
            transitionSpec = {
                (fadeIn(animationSpec = tween(durationMillis = 180)) +
                    scaleIn(initialScale = 0.92f)) togetherWith
                    (fadeOut(animationSpec = tween(durationMillis = 120)) +
                        scaleOut(targetScale = 0.92f))
            },
            contentAlignment = Alignment.Center,
            label = "guestAction-${guest.id}",
        ) { action ->
            when (action) {
                GuestRowAction.BUSY -> CircularProgressIndicator(
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .size(20.dp),
                    strokeWidth = 2.dp,
                )

                GuestRowAction.STOP -> TextButton(
                    onClick = onShutdown,
                    enabled = online && !anyBusy,
                    interactionSource = quickInteraction,
                    modifier = Modifier.pressScale(quickInteraction, online && !anyBusy),
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                ) {
                    Icon(Icons.Outlined.StopCircle, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("Stop")
                }

                GuestRowAction.START -> TextButton(
                    onClick = onStart,
                    enabled = online && !anyBusy,
                    interactionSource = quickInteraction,
                    modifier = Modifier.pressScale(quickInteraction, online && !anyBusy),
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

        IconButton(
            onClick = onMore,
            enabled = online && !busy,
        ) {
            Icon(
                imageVector = Icons.Outlined.MoreVert,
                contentDescription = "More actions for ${guest.name}",
                modifier = Modifier.size(19.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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

    val title = when (state.phase) {
        Phase.WAKING -> "Sending magic"
        Phase.WAITING_FOR_SERVER -> "Waking up"
        Phase.SHUTTING_DOWN -> "Powering down"
        Phase.LOADING_GUESTS -> if (online) "Reading machines" else "Connecting"
        Phase.STARTING -> "Working"
        Phase.IDLE -> when {
            !state.configured -> "Not configured"
            online -> "Online"
            else -> "Asleep"
        }
    }

    val running = state.guests.count { it.status == GuestStatus.RUNNING }
    val animatedTotal by animateIntAsState(
        targetValue = state.guests.size,
        animationSpec = tween(durationMillis = 500),
        label = "nodeGuestCount",
    )
    val animatedRunning by animateIntAsState(
        targetValue = running,
        animationSpec = tween(durationMillis = 650),
        label = "nodeRunningCount",
    )
    val subtitle = when {
        !state.configured -> "Add your Proxmox host in settings to begin."
        shuttingDown -> "Watching the API until it goes quiet."
        waking -> "Hold tight — the node is booting."
        online -> "${state.config.host} · $animatedTotal machines, $animatedRunning running"
        state.config.host.isNotBlank() -> "${state.config.host} is asleep. Send it a magic packet."
        else -> "Add your node in settings."
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PowerCore(
                    online = online,
                    waking = waking,
                    shuttingDown = shuttingDown,
                    modifier = Modifier.size(62.dp),
                )

                Spacer(Modifier.width(14.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
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

                    Spacer(Modifier.height(4.dp))

                    Text(
                        text = title,
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )

                    Spacer(Modifier.height(3.dp))

                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

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
    val target = (percent / 100f).coerceIn(0f, 1f)
    val animated by animateFloatAsState(
        targetValue = target,
        animationSpec = tween(durationMillis = 800),
        label = "resourceBar-$label",
    )
    val barColor by animateColorAsState(
        targetValue = if (percent >= 90) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.primary
        },
        animationSpec = tween(durationMillis = 500),
        label = "resourceColor-$label",
    )
    val animatedPercent by animateIntAsState(
        targetValue = percent,
        animationSpec = tween(durationMillis = 800),
        label = "resourcePercent-$label",
    )

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
                    text = "${formatBytes(used)} / ${formatBytes(total)} ($animatedPercent%)",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    text = "$animatedPercent%",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { animated },
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(CircleShape),
            color = barColor,
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
private fun SyncFooter(state: UiState) {
    val label = when {
        !state.configured -> "NOT CONNECTED"
        state.refreshing -> "SYNCING…"
        !state.serverReachable && state.guests.isNotEmpty() -> "RECONNECTING…"
        !state.serverReachable -> "OFFLINE · RETRYING"
        else -> "LIVE"
    }
    val dotColor = when {
        !state.configured || !state.serverReachable -> StatusColors.stopped
        state.refreshing -> StatusColors.warn
        else -> StatusColors.running
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(
            color = dotColor,
            size = 5.dp,
            pulsing = state.configured && state.serverReachable && !state.refreshing,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            letterSpacing = 1.1.sp,
        )
    }
}

@Composable
private fun SkeletonGuestList() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column {
            repeat(3) { index ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ShimmerBlock(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(RoundedCornerShape(12.dp)),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        ShimmerBlock(
                            modifier = Modifier
                                .fillMaxWidth(0.55f)
                                .height(12.dp)
                                .clip(CircleShape),
                        )
                        Spacer(Modifier.height(8.dp))
                        ShimmerBlock(
                            modifier = Modifier
                                .fillMaxWidth(0.35f)
                                .height(9.dp)
                                .clip(CircleShape),
                        )
                    }
                    Spacer(Modifier.width(16.dp))
                    ShimmerBlock(
                        modifier = Modifier
                            .size(width = 54.dp, height = 28.dp)
                            .clip(RoundedCornerShape(8.dp)),
                    )
                }
                if (index != 2) {
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
                Bobbing {
                    Icon(
                        imageVector = if (state.configured) Icons.Outlined.CloudOff else Icons.Outlined.Dns,
                        contentDescription = null,
                        modifier = Modifier.size(30.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
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
                color = MaterialTheme.colorScheme.onSurface,
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
private fun GuestActionConfirmDialog(
    guest: Guest,
    action: GuestAction,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val forceStop = action == GuestAction.FORCE_STOP
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                Icons.Outlined.PowerSettingsNew,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
            )
        },
        title = {
            Text(if (forceStop) "Force stop ${guest.name}?" else "Reset ${guest.name}?")
        },
        text = {
            Text(
                if (forceStop) {
                    "This cuts power immediately. Anything unsaved inside the guest may be lost."
                } else {
                    "This is a hard reset, like pressing the reset button. " +
                        "Anything unsaved inside the guest may be lost."
                },
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
                Text(if (forceStop) "Force stop" else "Reset")
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
internal fun guestStatusColor(status: GuestStatus): Color = when (status) {
    GuestStatus.RUNNING -> StatusColors.running
    GuestStatus.STOPPED -> StatusColors.stopped
    GuestStatus.UNKNOWN -> StatusColors.stopped
    else -> StatusColors.warn
}
