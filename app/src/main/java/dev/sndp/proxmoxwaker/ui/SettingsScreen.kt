package dev.sndp.proxmoxwaker.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sndp.proxmoxwaker.data.ProxmoxConfig
import dev.sndp.proxmoxwaker.net.WakeOnLan

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    initial: ProxmoxConfig,
    onSave: (ProxmoxConfig) -> Unit,
    onTest: (ProxmoxConfig) -> Unit,
    onClose: () -> Unit,
    statusMessage: String? = null,
    statusError: String? = null,
    testing: Boolean = false,
    requireBiometric: Boolean = true,
    biometricAvailable: Boolean = false,
    biometricMessage: String? = null,
    onToggleBiometric: (Boolean) -> Unit = {},
    localNetworkAvailable: Boolean = true,
) {
    // Seeded once when the screen opens, then owned by the form. Keying these on
    // `initial` would wipe what the user is typing whenever a background update
    // lands, for example the node name discovered by "Test".
    var host by rememberSaveable { mutableStateOf(initial.host) }
    var port by rememberSaveable { mutableStateOf(initial.apiPort.toString()) }
    var node by rememberSaveable { mutableStateOf(initial.node) }
    var username by rememberSaveable { mutableStateOf(initial.username) }
    var password by rememberSaveable { mutableStateOf(initial.password) }
    var mac by rememberSaveable { mutableStateOf(initial.macAddress) }
    var wolPort by rememberSaveable { mutableStateOf(initial.wolPort.toString()) }
    var broadcast by rememberSaveable { mutableStateOf(initial.wolBroadcast) }
    var showPassword by remember { mutableStateOf(false) }

    val macValid = mac.isBlank() || WakeOnLan.parseMac(mac) != null
    val portValid = port.toIntOrNull()?.let { it in 1..65535 } == true
    val wolPortValid = wolPort.toIntOrNull()?.let { it in 1..65535 } == true
    val canSave = host.isNotBlank() && username.isNotBlank() && password.isNotBlank() &&
        portValid && wolPortValid && macValid

    fun snapshot() = ProxmoxConfig(
        host = host.trim(),
        apiPort = port.toIntOrNull() ?: 8006,
        node = node.trim(),
        username = username.trim(),
        password = password,
        macAddress = mac.trim(),
        wolPort = wolPort.toIntOrNull() ?: 9,
        wolBroadcast = broadcast.trim().ifBlank { "255.255.255.255" },
        certFingerprint = initial.certFingerprint,
    )

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = Color.Transparent,
                ),
                title = {
                    Column {
                        Text("Settings", style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = "PROXMOX WAKER",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 1.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Outlined.Close, contentDescription = "Close")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            SectionTitle("Proxmox API")

            if (!localNetworkAvailable) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.WifiOff,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = "No local network connection. Join Wi-Fi or Ethernet " +
                                "before testing — the Proxmox API is on your LAN.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            OutlinedTextField(
                value = host,
                onValueChange = { host = it },
                label = { Text("Host or IP") },
                placeholder = { Text("192.0.2.10") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Next,
                ),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it.filter(Char::isDigit) },
                    label = { Text("API port") },
                    singleLine = true,
                    isError = !portValid,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = node,
                    onValueChange = { node = it },
                    label = { Text("Node name") },
                    placeholder = { Text("auto") },
                    singleLine = true,
                    supportingText = { Text("Leave blank to detect") },
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.weight(1f),
                )
            }

            InfoNote(
                "The API is always HTTPS on port 8006. The app trusts the server's own " +
                    "certificate after checking it really is a Proxmox certificate.",
            )

            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                label = { Text("Username") },
                placeholder = { Text("root@pam") },
                supportingText = { Text("Include the realm, e.g. root@pam or myuser@pve") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Password") },
                singleLine = true,
                visualTransformation = if (showPassword) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                trailingIcon = {
                    IconButton(onClick = { showPassword = !showPassword }) {
                        Icon(
                            imageVector = if (showPassword) {
                                Icons.Outlined.VisibilityOff
                            } else {
                                Icons.Outlined.Visibility
                            },
                            contentDescription = if (showPassword) "Hide password" else "Show password",
                        )
                    }
                },
                supportingText = { Text("Stored on this device only, and never included in backups.") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
            )

            InfoNote(
                "Starting and stopping guests needs VM.Audit and VM.PowerMgmt. " +
                    "Shutting down the node itself needs Sys.PowerMgmt.",
            )

            Spacer(Modifier.height(6.dp))
            SectionTitle("Wake-on-LAN")

            OutlinedTextField(
                value = mac,
                onValueChange = { mac = it },
                label = { Text("Server MAC address") },
                placeholder = { Text("AA:BB:CC:DD:EE:FF") },
                singleLine = true,
                isError = !macValid,
                supportingText = {
                    Text(
                        if (!macValid) {
                            "That does not look like a MAC address."
                        } else {
                            "Find it in the Proxmox UI under the node's Network tab."
                        },
                    )
                },
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = wolPort,
                    onValueChange = { wolPort = it.filter(Char::isDigit) },
                    label = { Text("WoL port") },
                    singleLine = true,
                    isError = !wolPortValid,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = broadcast,
                    onValueChange = { broadcast = it },
                    label = { Text("Broadcast address") },
                    placeholder = { Text("255.255.255.255") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.weight(1f),
                )
            }

            Spacer(Modifier.height(6.dp))
            SectionTitle("App lock")

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Fingerprint,
                        contentDescription = null,
                        modifier = Modifier.size(22.dp),
                        tint = if (requireBiometric) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Require fingerprint",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = when {
                                requireBiometric ->
                                    "Required each time the app starts, including after it is killed."
                                biometricAvailable ->
                                    "Ask for your fingerprint each time the app starts."
                                else ->
                                    biometricMessage ?: "Fingerprint unlock is not available on this device."
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Switch(
                        checked = requireBiometric,
                        onCheckedChange = onToggleBiometric,
                        enabled = biometricAvailable || requireBiometric,
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = { onSave(snapshot()) },
                    enabled = canSave && !testing,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Save and connect")
                }
                OutlinedButton(
                    onClick = { onTest(snapshot()) },
                    enabled = canSave && !testing,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (testing) "Testing..." else "Test")
                }
            }

            InfoNote(
                "Test checks the connection without saving. Save and connect applies " +
                    "these settings and closes this screen.",
            )

            when {
                testing -> Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                    Text(
                        text = "Testing connection...",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                statusError != null -> ResultNote(statusError, isError = true)
                statusMessage != null -> ResultNote(statusMessage, isError = false)
            }

            if (initial.certFingerprint.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                SectionTitle("Trusted certificate")
                InfoNote(
                    "The app accepted this certificate from the server. If it ever changes " +
                        "unexpectedly, that is worth investigating.",
                )
                Text(
                    text = initial.certFingerprint,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Medium,
        letterSpacing = 1.2.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun InfoNote(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
    )
}

@Composable
private fun ResultNote(text: String, isError: Boolean) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = if (isError) {
            MaterialTheme.colorScheme.errorContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        contentColor = if (isError) {
            MaterialTheme.colorScheme.onErrorContainer
        } else {
            MaterialTheme.colorScheme.onSurface
        },
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
