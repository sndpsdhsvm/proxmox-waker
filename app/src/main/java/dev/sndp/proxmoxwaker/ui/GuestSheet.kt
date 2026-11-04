package dev.sndp.proxmoxwaker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sndp.proxmoxwaker.data.Guest
import dev.sndp.proxmoxwaker.data.GuestStatus
import dev.sndp.proxmoxwaker.data.GuestType
import kotlinx.coroutines.launch

/** The power actions a guest row can offer. */
enum class GuestAction {
    START,
    SHUTDOWN,
    REBOOT,
    FORCE_STOP,
    RESET,
}

/**
 * A bottom sheet with the less-common guest actions. The main list keeps the
 * one-tap Start/Stop affordance; this is where reboots and the scary buttons live.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GuestActionSheet(
    guest: Guest,
    anyBusy: Boolean,
    onDismiss: () -> Unit,
    onAction: (GuestAction) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val running = guest.status == GuestStatus.RUNNING
    val statusColor = guestStatusColor(guest.status)

    val choose: (GuestAction) -> Unit = { action ->
        scope.launch {
            sheetState.hide()
            onAction(action)
            onDismiss()
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        dragHandle = {
            BottomSheetDefaults.DragHandle(color = MaterialTheme.colorScheme.outlineVariant)
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 22.dp)
                .padding(bottom = 18.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(
                            if (running) statusColor.copy(alpha = 0.12f)
                            else MaterialTheme.colorScheme.surfaceVariant,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (guest.type == GuestType.LXC) {
                            Icons.Outlined.Dns
                        } else {
                            Icons.Outlined.Computer
                        },
                        contentDescription = null,
                        modifier = Modifier.size(23.dp),
                        tint = if (running) statusColor else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Spacer(Modifier.width(14.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = guest.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = "${guest.type.label} ${guest.vmid}  ·  ${guest.status.label.uppercase()}",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 0.6.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                StatusDot(color = statusColor, pulsing = running, size = 8.dp)
            }

            Spacer(Modifier.height(22.dp))

            Text(
                text = "ACTIONS",
                style = MaterialTheme.typography.labelSmall,
                letterSpacing = 1.2.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(10.dp))

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (running) {
                    SheetAction(
                        title = "Reboot",
                        subtitle = "Ask the guest to restart gracefully",
                        icon = Icons.Outlined.RestartAlt,
                        tint = MaterialTheme.colorScheme.primary,
                        enabled = !anyBusy,
                        onClick = { choose(GuestAction.REBOOT) },
                    )
                    SheetAction(
                        title = "Shut down",
                        subtitle = "Send an ACPI shutdown request",
                        icon = Icons.Outlined.PowerSettingsNew,
                        tint = MaterialTheme.colorScheme.tertiary,
                        enabled = !anyBusy,
                        onClick = { choose(GuestAction.SHUTDOWN) },
                    )
                    SheetAction(
                        title = "Force stop",
                        subtitle = "Cut power immediately — unsaved work may be lost",
                        icon = Icons.Outlined.StopCircle,
                        tint = MaterialTheme.colorScheme.error,
                        enabled = !anyBusy,
                        onClick = { choose(GuestAction.FORCE_STOP) },
                    )
                    if (guest.type == GuestType.QEMU) {
                        SheetAction(
                            title = "Reset",
                            subtitle = "Hard reset, like pressing the reset button",
                            icon = Icons.Outlined.Bolt,
                            tint = MaterialTheme.colorScheme.error,
                            enabled = !anyBusy,
                            onClick = { choose(GuestAction.RESET) },
                        )
                    }
                } else {
                    SheetAction(
                        title = "Start",
                        subtitle = "Boot this machine",
                        icon = Icons.Outlined.PlayArrow,
                        tint = StatusColors.running,
                        enabled = !anyBusy,
                        onClick = { choose(GuestAction.START) },
                    )
                }
            }

            Spacer(Modifier.height(6.dp))

            TextButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.End),
            ) {
                Text("Cancel")
            }
        }
    }
}

@Composable
private fun SheetAction(
    title: String,
    subtitle: String,
    icon: ImageVector,
    tint: Color,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }

    Surface(
        onClick = onClick,
        enabled = enabled,
        interactionSource = interaction,
        modifier = Modifier
            .fillMaxWidth()
            .pressScale(interaction, enabled),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(tint.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = tint,
                )
            }

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.titleSmall)
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
