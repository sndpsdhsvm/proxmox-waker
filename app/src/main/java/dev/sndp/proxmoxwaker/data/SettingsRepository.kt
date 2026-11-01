package dev.sndp.proxmoxwaker.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(
    name = "proxmox_waker_settings",
)

/**
 * Persists [ProxmoxConfig] on the device.
 *
 * Note: this is plain DataStore, readable by root on a rooted device. It keeps the
 * password out of source control and backups, which is the practical goal here; it
 * is not hardware-backed encryption.
 */
class SettingsRepository(private val context: Context) {

    private object Keys {
        val host = stringPreferencesKey("host")
        val apiPort = intPreferencesKey("api_port")
        val node = stringPreferencesKey("node")
        val username = stringPreferencesKey("username")
        val password = stringPreferencesKey("password")
        val macAddress = stringPreferencesKey("mac_address")
        val wolPort = intPreferencesKey("wol_port")
        val wolBroadcast = stringPreferencesKey("wol_broadcast")
        val certFingerprint = stringPreferencesKey("cert_fingerprint")
        val requireBiometric = booleanPreferencesKey("require_biometric")
    }

    /**
     * Whether the app asks for the device's fingerprint before showing the
     * controls. On by default because this app can power a whole server; if the
     * device has no usable sensor the lock is skipped rather than trapping the
     * user outside their own app.
     */
    val requireBiometric: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.requireBiometric] ?: true
    }

    val config: Flow<ProxmoxConfig> = context.dataStore.data.map { prefs ->
        val defaults = ProxmoxConfig()
        ProxmoxConfig(
            host = prefs[Keys.host] ?: defaults.host,
            apiPort = prefs[Keys.apiPort] ?: defaults.apiPort,
            node = prefs[Keys.node] ?: defaults.node,
            username = prefs[Keys.username] ?: defaults.username,
            password = prefs[Keys.password] ?: defaults.password,
            macAddress = prefs[Keys.macAddress] ?: defaults.macAddress,
            wolPort = prefs[Keys.wolPort] ?: defaults.wolPort,
            wolBroadcast = prefs[Keys.wolBroadcast] ?: defaults.wolBroadcast,
            certFingerprint = prefs[Keys.certFingerprint] ?: defaults.certFingerprint,
        )
    }

    suspend fun current(): ProxmoxConfig = config.first()

    suspend fun save(config: ProxmoxConfig) {
        context.dataStore.edit { prefs ->
            prefs[Keys.host] = config.host.trim()
            prefs[Keys.apiPort] = config.apiPort
            prefs[Keys.node] = config.node.trim()
            prefs[Keys.username] = config.username.trim()
            prefs[Keys.password] = config.password
            prefs[Keys.macAddress] = config.macAddress.trim()
            prefs[Keys.wolPort] = config.wolPort
            prefs[Keys.wolBroadcast] = config.wolBroadcast.trim()
            prefs[Keys.certFingerprint] = config.certFingerprint
        }
    }

    /** Records the node discovered during a successful connection test. */
    suspend fun updateNode(node: String) {
        if (node.isBlank()) return
        context.dataStore.edit { it[Keys.node] = node }
    }

    /** Records the accepted certificate fingerprint. */
    suspend fun updateFingerprint(fingerprint: String) {
        if (fingerprint.isBlank()) return
        context.dataStore.edit { it[Keys.certFingerprint] = fingerprint }
    }

    /** Turns the fingerprint unlock requirement on or off. */
    suspend fun setRequireBiometric(enabled: Boolean) {
        context.dataStore.edit { it[Keys.requireBiometric] = enabled }
    }
}
