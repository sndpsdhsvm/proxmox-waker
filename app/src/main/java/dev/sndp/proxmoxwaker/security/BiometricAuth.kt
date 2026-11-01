package dev.sndp.proxmoxwaker.security

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * Thin wrapper around [BiometricPrompt] for the app-lock screen.
 *
 * The app can power a whole Proxmox node on and off, so opening it should not be
 * enough to control the server: the person holding the phone has to prove it is
 * them with the fingerprint sensor first. This is a local gate only — it does not
 * replace the Proxmox password, which is still needed for every API call.
 */
object BiometricAuth {

    /** Whether fingerprint unlock can be used right now, and why not if it can't. */
    data class Status(val canUse: Boolean, val message: String? = null)

    fun status(context: Context): Status {
        val result = BiometricManager.from(context)
            .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
        return when (result) {
            BiometricManager.BIOMETRIC_SUCCESS -> Status(canUse = true)
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> Status(
                canUse = false,
                message = "No fingerprint is enrolled on this device. Add one in " +
                    "Android Settings → Security → Fingerprint.",
            )
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> Status(
                canUse = false,
                message = "This device has no fingerprint sensor.",
            )
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> Status(
                canUse = false,
                message = "The fingerprint sensor is unavailable right now. Try again shortly.",
            )
            BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED -> Status(
                canUse = false,
                message = "Android needs a security update before fingerprint unlock can be used.",
            )
            else -> Status(
                canUse = false,
                message = "Fingerprint unlock is not available on this device.",
            )
        }
    }

    /**
     * Shows the system fingerprint prompt.
     *
     * @param onError receives a message suitable for display. Cancelling the prompt
     *   is not an error state; the lock screen simply stays up.
     */
    fun prompt(
        activity: FragmentActivity,
        title: String,
        subtitle: String,
        onSuccess: () -> Unit,
        onError: (message: String) -> Unit,
        onFailed: () -> Unit,
    ) {
        val executor = ContextCompat.getMainExecutor(activity)
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                onSuccess()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                onError(errorMessage(errorCode, errString.toString()))
            }

            override fun onAuthenticationFailed() {
                onFailed()
            }
        }

        val prompt = BiometricPrompt(activity, executor, callback)
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .setNegativeButtonText("Cancel")
            .setConfirmationRequired(false)
            .build()

        prompt.authenticate(info)
    }

    private fun errorMessage(errorCode: Int, fallback: String): String = when (errorCode) {
        BiometricPrompt.ERROR_USER_CANCELED,
        BiometricPrompt.ERROR_NEGATIVE_BUTTON,
        BiometricPrompt.ERROR_CANCELED,
        -> "Unlock cancelled. Tap Unlock to try again."

        BiometricPrompt.ERROR_LOCKOUT,
        BiometricPrompt.ERROR_LOCKOUT_PERMANENT,
        -> "Too many attempts. Wait a moment, then try again."

        BiometricPrompt.ERROR_NO_BIOMETRICS -> "No fingerprint is enrolled on this device."
        BiometricPrompt.ERROR_HW_NOT_PRESENT -> "This device has no fingerprint sensor."
        BiometricPrompt.ERROR_HW_UNAVAILABLE -> "The fingerprint sensor is unavailable right now."
        else -> fallback.ifBlank { "Fingerprint authentication failed." }
    }
}
