package dev.sndp.proxmoxwaker

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.withResumed
import dev.sndp.proxmoxwaker.security.BiometricAuth
import dev.sndp.proxmoxwaker.ui.AppTheme
import dev.sndp.proxmoxwaker.ui.LockScreen
import dev.sndp.proxmoxwaker.ui.LockSplash
import dev.sndp.proxmoxwaker.ui.MainScreen
import dev.sndp.proxmoxwaker.ui.MainViewModel

/**
 * Hosts the Compose UI and owns the fingerprint prompt.
 *
 * [FragmentActivity] is required by androidx.biometric. The app asks for the
 * fingerprint on a fresh start only; switching away and coming back does not
 * re-lock, which keeps day-to-day use quiet.
 */
class MainActivity : FragmentActivity() {

    /** Guards against stacking two system prompts on top of each other. */
    private var promptVisible = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AppTheme {
                val vm: MainViewModel = viewModel()
                val state by vm.state.collectAsStateWithLifecycle()
                val lifecycleOwner = LocalLifecycleOwner.current

                DisposableEffect(lifecycleOwner) {
                    val observer = LifecycleEventObserver { _, event ->
                        // Coming back is a chance to notice newly enrolled fingers.
                        // Backgrounding deliberately does not re-arm the lock.
                        if (event == Lifecycle.Event.ON_START) vm.refreshBiometricStatus()
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
                }

                // A new lockRequestId means "show the prompt now". Cancelling keeps
                // the lock screen up but does not bump the id, so it will not loop.
                // Waiting for RESUMED avoids starting a prompt from the background.
                LaunchedEffect(state.lockRequestId, state.securityReady) {
                    if (state.securityReady && state.locked && state.lockRequestId > 0) {
                        lifecycleOwner.lifecycle.withResumed { requestUnlock(vm) }
                    }
                }

                val stage = when {
                    !state.securityReady -> AppStage.SPLASH
                    state.locked -> AppStage.LOCKED
                    else -> AppStage.MAIN
                }

                AnimatedContent(
                    targetState = stage,
                    transitionSpec = {
                        (fadeIn(animationSpec = tween(durationMillis = 420)) +
                            scaleIn(initialScale = 0.985f)) togetherWith
                            fadeOut(animationSpec = tween(durationMillis = 220))
                    },
                    label = "appStage",
                ) { target ->
                    when (target) {
                        AppStage.SPLASH -> LockSplash()
                        AppStage.LOCKED -> LockScreen(
                            host = state.config.host,
                            message = state.lockMessage,
                            onUnlock = { requestUnlock(vm) },
                        )
                        AppStage.MAIN -> {
                            // Keep the list current while visible; reads happen in
                            // the background so the UI never flashes a loader.
                            LifecycleResumeEffect(Unit) {
                                vm.onScreenVisible()
                                onPauseOrDispose { vm.onScreenHidden() }
                            }
                            MainScreen(vm)
                        }
                    }
                }
            }
        }
    }

    private fun requestUnlock(vm: MainViewModel) {
        if (promptVisible) return
        promptVisible = true

        val host = vm.state.value.config.host
        try {
            BiometricAuth.prompt(
                activity = this,
                title = "Unlock Proxmox Waker",
                subtitle = if (host.isNotBlank()) {
                    "Confirm your fingerprint to control $host"
                } else {
                    "Confirm your fingerprint to continue"
                },
                onSuccess = {
                    promptVisible = false
                    vm.onUnlocked()
                },
                onError = { message ->
                    promptVisible = false
                    vm.onUnlockError(message)
                },
                onFailed = vm::onUnlockFailed,
            )
        } catch (e: Exception) {
            promptVisible = false
            vm.onUnlockError(
                e.message?.takeIf { it.isNotBlank() }
                    ?: "Could not start fingerprint authentication.",
            )
        }
    }
}

private enum class AppStage { SPLASH, LOCKED, MAIN }
