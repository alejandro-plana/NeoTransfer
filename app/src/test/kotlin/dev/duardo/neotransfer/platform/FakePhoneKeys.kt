package dev.duardo.neotransfer.platform

import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.UserNotAuthenticatedException
import java.util.IdentityHashMap
import javax.crypto.Cipher
import javax.crypto.IllegalBlockSizeException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * A phone's Keystore and locks in memory. Keys are software AES keys that remember what opens them; the prompt is not
 * simulated (a cipher counts as authorized). [enroll] kills the fingerprint-only keys as a new enrollment does;
 * [rejectsFingerprintKeys] turns them down at their last step, as the realme Note 60 does right after a valid fingerprint.
 */
class FakePhoneKeys(var fingerprint: Boolean = true, var secureLock: Boolean = true) : PhoneKeys {
    private val keys = mutableMapOf<String, Pair<SecretKey, KeyAuth>>()
    private val dead = mutableSetOf<String>()
    private val owners = IdentityHashMap<Cipher, String>()
    var rejectsFingerprintKeys = false

    fun auth(alias: String): KeyAuth? = keys[alias]?.second

    /** A new fingerprint enrolled: every key for the fingerprint alone dies. */
    fun enroll() {
        fingerprint = true
        keys.filterValues { it.second == KeyAuth.FINGERPRINT }.keys.forEach(dead::add)
    }

    override fun fingerprint() = fingerprint
    override fun secureLock() = secureLock
    override fun contains(alias: String) = alias in keys

    override fun delete(alias: String) {
        keys.remove(alias)
        dead.remove(alias)
    }

    override fun ensure(alias: String, auth: KeyAuth) {
        if (alias in keys) return
        check(auth == KeyAuth.NONE || secureLock) { "Secure lock screen must be enabled" }
        keys[alias] = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey() to auth
    }

    override fun cipher(alias: String, mode: Int, iv: ByteArray?): Cipher {
        val key = keys[alias]?.first?.takeIf { alias !in dead } ?: throw KeyPermanentlyInvalidatedException()
        return Cipher.getInstance(TRANSFORMATION).apply {
            if (iv == null) init(mode, key) else init(mode, key, GCMParameterSpec(128, iv))
            owners[this] = alias
        }
    }

    override fun finish(cipher: Cipher, input: ByteArray): ByteArray {
        if (rejectsFingerprintKeys && owners[cipher]?.let(::auth) == KeyAuth.FINGERPRINT) {
            throw IllegalBlockSizeException("Keystore operation failed").apply { initCause(UserNotAuthenticatedException()) }
        }
        return cipher.doFinal(input)
    }
}
