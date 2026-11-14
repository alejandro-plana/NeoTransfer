package dev.duardo.neotransfer.platform

import android.app.KeyguardManager
import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.security.KeyStoreException
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.UserNotAuthenticatedException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** How a Keystore key is opened. */
enum class KeyAuth {
    /** For every use, a strong fingerprint; a new enrollment kills it. */
    FINGERPRINT,

    /** For every use, a strong fingerprint or the screen lock. */
    ANY,

    /** Never asks: it only binds what it wraps to this phone. */
    NONE,
}

/** What the vault and the app code need of the phone: its Keystore and its locks. Android: [AndroidPhoneKeys]; tests: a fake. */
interface PhoneKeys {
    /** A strong fingerprint is enrolled and usable. */
    fun fingerprint(): Boolean

    /** A secure screen lock (PIN, pattern or password) is set. */
    fun secureLock(): Boolean

    fun contains(alias: String): Boolean
    fun delete(alias: String)

    /** Makes the AES-256-GCM key at [alias] with [auth] when it is missing. */
    fun ensure(alias: String, auth: KeyAuth)

    /**
     * A cipher of the key at [alias]: to encrypt, or to decrypt with [iv].
     * @throws KeyPermanentlyInvalidatedException when a new enrollment killed it, or it is gone
     */
    fun cipher(alias: String, mode: Int, iv: ByteArray? = null): Cipher

    /** The last step of a cipher; with an authorized one, where the Keystore may still turn the authorization down. */
    fun finish(cipher: Cipher, input: ByteArray): ByteArray = cipher.doFinal(input)
}

class AndroidPhoneKeys(private val context: Context) : PhoneKeys {
    private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    override fun fingerprint() = context.getSystemService(BiometricManager::class.java)
        .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS

    override fun secureLock() = context.getSystemService(KeyguardManager::class.java).isDeviceSecure

    override fun contains(alias: String) = keyStore.containsAlias(alias)

    override fun delete(alias: String) {
        if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
    }

    override fun ensure(alias: String, auth: KeyAuth) {
        if (keyStore.containsAlias(alias)) return
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
        when (auth) {
            KeyAuth.FINGERPRINT -> spec.setUserAuthenticationRequired(true)
                .setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
                .setInvalidatedByBiometricEnrollment(true)
            KeyAuth.ANY -> spec.setUserAuthenticationRequired(true)
                .setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL)
                .setInvalidatedByBiometricEnrollment(false)
            KeyAuth.NONE -> Unit
        }
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(spec.build())
            generateKey()
        }
    }

    override fun cipher(alias: String, mode: Int, iv: ByteArray?): Cipher {
        val key = keyStore.getKey(alias, null) as SecretKey? ?: throw KeyPermanentlyInvalidatedException()
        return Cipher.getInstance(TRANSFORMATION).apply {
            if (iv == null) init(mode, key) else init(mode, key, GCMParameterSpec(128, iv))
        }
    }
}

internal const val TRANSFORMATION = "AES/GCM/NoPadding"

/**
 * The Keystore turned down a per-use key that the fingerprint had just authorized. Some phones (realme Note 60: Unisoc
 * T612, Silead sensor) bind fingerprint keys to an authenticator id their own fingerprint tokens never carry.
 */
internal fun rejectedAfterFingerprint(error: Throwable): Boolean = generateSequence(error) { it.cause }.any {
    it is UserNotAuthenticatedException ||
        (it is KeyStoreException && it.numericErrorCode == KeyStoreException.ERROR_USER_AUTHENTICATION_REQUIRED)
}
