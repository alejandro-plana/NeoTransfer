package dev.duardo.neotransfer.platform

import android.app.Activity
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.crypto.Cipher
import kotlin.coroutines.resume

sealed interface BiometricResult {
    /** [cipher] is the authorized one when the prompt carried a cipher. */
    data class Passed(val cipher: Cipher?) : BiometricResult
    data object Cancelled : BiometricResult
    /** Too many failed attempts: the sensor rests for a while. */
    data object LockedOut : BiometricResult
    /** Nothing the prompt allows is set: no fingerprint enrolled (and, when it allows it, no secure screen lock). */
    data object Unavailable : BiometricResult
    data class Failed(val message: String) : BiometricResult
}

/**
 * The system prompt, with or without a Keystore cipher to authorize: strong biometrics alone, or with [screenLock] the
 * screen lock too. Android allows no negative button with the screen lock: the prompt itself offers it.
 *
 * Biometrics locked out: [BiometricResult.LockedOut] without a prompt. Strong biometrics otherwise unavailable on a
 * phone with a screen lock (not enabled for apps, sensor unavailable): with [fallback], the screen lock confirms instead,
 * without the cipher ([BiometricResult.Passed] carries none).
 */
class BiometricGate(private val activity: Activity) {
    private fun authenticators(screenLock: Boolean) =
        if (screenLock) BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        else BiometricManager.Authenticators.BIOMETRIC_STRONG

    private fun status(screenLock: Boolean): Int = activity.getSystemService(BiometricManager::class.java).canAuthenticate(authenticators(screenLock))

    suspend fun prompt(title: String, subtitle: String?, cipher: Cipher?, screenLock: Boolean, fallback: Boolean): BiometricResult {
        when (status(screenLock)) {
            BiometricManager.BIOMETRIC_SUCCESS -> Unit
            // The same codes the prompt reports; canAuthenticate gives them where the platform reports a lockout there.
            BiometricPrompt.BIOMETRIC_ERROR_LOCKOUT, BiometricPrompt.BIOMETRIC_ERROR_LOCKOUT_PERMANENT -> return BiometricResult.LockedOut
            else -> return if (!screenLock && fallback && status(screenLock = true) == BiometricManager.BIOMETRIC_SUCCESS) {
                prompt(title, subtitle, null, screenLock = true, fallback = false)
            } else {
                BiometricResult.Unavailable
            }
        }
        return suspendCancellableCoroutine { continuation ->
            val cancellation = CancellationSignal()
            fun finish(result: BiometricResult) {
                if (continuation.isActive) continuation.resume(result)
            }
            val prompt = BiometricPrompt.Builder(activity)
                .setTitle(title)
                .apply { subtitle?.let(::setSubtitle) }
                .setAllowedAuthenticators(authenticators(screenLock))
                .apply { if (!screenLock) setNegativeButton("Cancelar", activity.mainExecutor) { _, _ -> finish(BiometricResult.Cancelled) } }
                .build()
            val callback = object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    val authorized = result.cryptoObject?.cipher
                    finish(if (cipher == null || authorized === cipher) BiometricResult.Passed(authorized) else BiometricResult.Failed("No se pudo verificar la huella"))
                }

                override fun onAuthenticationError(code: Int, message: CharSequence) = finish(when (code) {
                    BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED, BiometricPrompt.BIOMETRIC_ERROR_CANCELED,
                    BiometricPrompt.BIOMETRIC_ERROR_TIMEOUT -> BiometricResult.Cancelled
                    BiometricPrompt.BIOMETRIC_ERROR_LOCKOUT, BiometricPrompt.BIOMETRIC_ERROR_LOCKOUT_PERMANENT -> BiometricResult.LockedOut
                    BiometricPrompt.BIOMETRIC_ERROR_NO_BIOMETRICS, BiometricPrompt.BIOMETRIC_ERROR_HW_NOT_PRESENT,
                    BiometricPrompt.BIOMETRIC_ERROR_HW_UNAVAILABLE, BiometricPrompt.BIOMETRIC_ERROR_NO_DEVICE_CREDENTIAL -> BiometricResult.Unavailable
                    else -> BiometricResult.Failed(message.toString())
                })
            }
            if (cipher != null) prompt.authenticate(BiometricPrompt.CryptoObject(cipher), cancellation, activity.mainExecutor, callback)
            else prompt.authenticate(cancellation, activity.mainExecutor, callback)
            continuation.invokeOnCancellation { cancellation.cancel() }
        }
    }
}
