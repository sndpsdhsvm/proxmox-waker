package dev.sndp.proxmoxwaker.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import dev.sndp.proxmoxwaker.data.ProxmoxConfig
import dev.sndp.proxmoxwaker.net.WakeOnLan

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    initial: ProxmoxConfig,
    onSave: (ProxmoxConfig) -> Unit,
    onTest: (ProxmoxConfig) -> Unit,
    onClose: () -> Unit,
) {
    var host by remember(initial) { mutableStateOf(initial.host) }
    var port by remember(initial) { mutableStateOf(initial.apiPort.toString()) }
    var node by remember(initial) { mutableStateOf(initial.node) }
    var username by remember(initial) { mutableStateOf(initial.username) }
    var password by remember(initial) { mutableStateOf(initial.password) }
    var mac by remember(initial) { mutableStateOf(initial.macAddress) }
    var wolPort by remember(initial) { mutableStateOf(initial.wolPort.toString()) }
    var broadcast by remember(initial) { mutableStateOf(initial.wolBroadcast) }
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
        topBar = {
            TopAppBar(
                title = { Text("Server settings") },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
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
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionTitle("Proxmox API")

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
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = node,
                    onValueChange = { node = it },
                    label = { Text("Node name") },
                    placeholder = { Text("auto") },
                    singleLine = true,
                    supportingText = { Text("Leave blank to detect") },
                    modifier = Modifier.weight(1f),
                )
            }

            InfoNote(
                "Proxmox always serves the API over HTTPS on port 8006, so this app always " +
                    "uses https. The certificate is signed by the server's own private CA, " +
                    "which the app accepts after checking it really is a Proxmox certificate.",
            )

            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                label = { Text("Username") },
                placeholder = { Text("root@pam") },
                supportingText = { Text("Include the realm, e.g. root@pam or myuser@pve") },
                singleLine = true,
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
                                Icons.Default.VisibilityOff
                            } else {
                                Icons.Default.Visibility
                            },
                            contentDescription = if (showPassword) "Hide password" else "Show password",
                        )
                    }
                },
                supportingText = { Text("Stored on this device only, and never included in backups.") },
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(4.dp))
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
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = broadcast,
                    onValueChange = { broadcast = it },
                    label = { Text("Broadcast address") },
                    placeholder = { Text("255.255.255.255") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }

            Spacer(Modifier.height(8.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = { onSave(snapshot()) },
                    enabled = canSave,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Save and connect")
                }
                OutlinedButton(
                    onClick = { onTest(snapshot()) },
                    enabled = canSave,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Test")
                }
            }

            if (initial.certFingerprint.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
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

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun InfoNote(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
