package dev.sndp.proxmoxwaker

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.sndp.proxmoxwaker.ui.AppTheme
import dev.sndp.proxmoxwaker.ui.MainScreen
import dev.sndp.proxmoxwaker.ui.MainViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AppTheme {
                val vm: MainViewModel = viewModel()
                // Refresh the guest list periodically while the screen is visible.
                DisposableEffect(Unit) {
                    vm.startPolling()
                    onDispose { vm.stopPolling() }
                }
                MainScreen(vm)
            }
        }
    }
}
