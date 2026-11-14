package dev.duardo.neotransfer.platform

import android.content.Context
import android.security.KeyStoreException
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.UserNotAuthenticatedException
import android.util.Base64
import dev.duardo.neotransfer.engine.KeyVault
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-GCM under a Keystore key that needs strong biometrics and dies when a new fingerprint is enrolled. Ciphers must
 * be authorized with `BiometricPrompt.CryptoObject` before [encrypt] / [decrypt].
 *
 * The key needs the fingerprint on every use. Some Keystores (Unisoc T612, realme Note 60) reject that use right after
 * a valid fingerprint (`KEY_USER_NOT_AUTHENTICATED`): on the first such rejection the vault moves for good to a key
 * that the fingerprint unlocks for [TIMED_SECONDS]. The prompt then authorizes a cipher of a separate per-use gate
 * key, so it is still the strong fingerprint with a cipher, and [encrypt] / [decrypt] use the timed key right after.
 */
class BankPinVault(context: Context) : KeyVault {
    private val preferences = context.getSharedPreferences("key_vault", Context.MODE_PRIVATE)
    private val device = context.getSharedPreferences("key_vault_device", Context.MODE_PRIVATE)
    private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private val timed get() = device.getBoolean("timed", false)

    override fun hasKeys(): Boolean = preferences.contains("ciphertext") && preferences.contains("iv")

    override fun reset() {
        check(preferences.edit().clear().commit()) { "No se pudo borrar la clave" }
        for (alias in listOf(KEY_ALIAS, TIMED_ALIAS, GATE_ALIAS)) if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
    }

    override fun encryptCipher(): Cipher = if (timed) gateCipher() else try {
        Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key(KEY_ALIAS, 0)) }
    } catch (_: KeyPermanentlyInvalidatedException) {
        reset()
        encryptCipher()
    }

    override fun encrypt(authorized: Cipher, plaintext: ByteArray) {
        val cipher = if (timed) timedCipher(Cipher.ENCRYPT_MODE) else authorized
        val encrypted = try {
            cipher.doFinal(plaintext)
        } catch (e: Exception) {
            if (timed || !rejectedAfterFingerprint(e)) throw e
            // This Keystore cannot use a per-use key: the fingerprint just passed, so the timed key opens now.
            check(device.edit().putBoolean("timed", true).commit()) { "No se pudo guardar la clave" }
            if (keyStore.containsAlias(KEY_ALIAS)) keyStore.deleteEntry(KEY_ALIAS)
            return encrypt(authorized, plaintext)
        }
        check(preferences.edit()
            .putString("ciphertext", Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .commit()) { "No se pudo guardar la clave" }
    }

    override fun decryptCipher(): Cipher? {
        check(hasKeys()) { "No hay claves guardadas" }
        return try {
            if (timed) {
                // Only to learn whether a new fingerprint killed the key: without a recent fingerprint it cannot open.
                try {
                    Cipher.getInstance(TRANSFORMATION).init(Cipher.DECRYPT_MODE, key(TIMED_ALIAS, TIMED_SECONDS), GCMParameterSpec(128, iv()))
                } catch (_: UserNotAuthenticatedException) {
                }
                gateCipher()
            } else {
                Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.DECRYPT_MODE, key(KEY_ALIAS, 0), GCMParameterSpec(128, iv())) }
            }
        } catch (_: KeyPermanentlyInvalidatedException) {
            reset()
            null
        }
    }

    override fun decrypt(authorized: Cipher): ByteArray =
        (if (timed) timedCipher(Cipher.DECRYPT_MODE) else authorized)
            .doFinal(Base64.decode(requireNotNull(preferences.getString("ciphertext", null)), Base64.NO_WRAP))

    private fun iv(): ByteArray = Base64.decode(requireNotNull(preferences.getString("iv", null)), Base64.NO_WRAP)

    /** The timed key's cipher, right after the fingerprint. */
    private fun timedCipher(mode: Int): Cipher = Cipher.getInstance(TRANSFORMATION).apply {
        if (mode == Cipher.ENCRYPT_MODE) init(mode, key(TIMED_ALIAS, TIMED_SECONDS))
        else init(mode, key(TIMED_ALIAS, TIMED_SECONDS), GCMParameterSpec(128, iv()))
    }

    /** What the prompt authorizes in timed mode; it never encrypts anything. */
    private fun gateCipher(): Cipher = try {
        Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key(GATE_ALIAS, 0)) }
    } catch (_: KeyPermanentlyInvalidatedException) {
        keyStore.deleteEntry(GATE_ALIAS)
        gateCipher()
    }

    /** The key at [alias], made on first use: [seconds] after the fingerprint, or 0 for every use. */
    private fun key(alias: String, seconds: Int): SecretKey {
        if (!keyStore.containsAlias(alias)) KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(true)
                .setUserAuthenticationParameters(seconds, KeyProperties.AUTH_BIOMETRIC_STRONG)
                .setInvalidatedByBiometricEnrollment(true)
                .build())
            generateKey()
        }
        return keyStore.getKey(alias, null) as SecretKey
    }

    /** The Keystore turned down the per-use key that the fingerprint had just authorized. */
    private fun rejectedAfterFingerprint(error: Throwable): Boolean = generateSequence(error) { it.cause }.any {
        it is UserNotAuthenticatedException ||
            (it is KeyStoreException && it.numericErrorCode == KeyStoreException.ERROR_USER_AUTHENTICATION_REQUIRED)
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_ALIAS = "dev.duardo.neotransfer.bank-keys"
        const val TIMED_ALIAS = "dev.duardo.neotransfer.bank-keys.timed"
        const val GATE_ALIAS = "dev.duardo.neotransfer.bank-keys.gate"
        const val TIMED_SECONDS = 10
    }
}
