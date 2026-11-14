package dev.duardo.neotransfer.platform

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.util.Base64
import dev.duardo.neotransfer.engine.AppCode
import dev.duardo.neotransfer.engine.CodeAttempts
import dev.duardo.neotransfer.engine.CodeKey
import dev.duardo.neotransfer.engine.PreparedCode
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.time.Duration
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * The app code. Its key is PBKDF2-HMAC-SHA256 of the 6 digits with a random salt and as many rounds as take about
 * [TARGET_MILLIS] on this phone, measured when the code is set. The code is never stored: a known blob encrypted under
 * its key, wrapped by a Keystore key that asks nothing (so it can only be tried on this phone), tells a right one by
 * its GCM tag.
 *
 * The fingerprint opens the app in place of the code with a per-use key of its own that dies with a new enrollment,
 * made when the code is set: a finger enrolled afterwards never opens it. Once dead, the fingerprint is not offered
 * until the code is typed again ([renewFingerprint]); on phones whose fingerprint keys never authorize it is not offered.
 *
 * Waits after wrong codes run on the clock since boot ([elapsed]), never the wall clock: setting the time forward or
 * back neither ends nor stretches them, and after a reboot ([boots]) the whole wait starts again.
 */
class AndroidAppCode(
    context: Context,
    private val phone: PhoneKeys,
    private val vault: BankPinVault,
    /** PBKDF2 rounds for a new code. */
    private val rounds: () -> Int = ::calibratedRounds,
    /** Milliseconds since boot. */
    private val elapsed: () -> Long = SystemClock::elapsedRealtime,
    /** How many times the phone has booted. */
    private val boots: () -> Int = { Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, 0) },
) : AppCode {

    private val preferences = context.getSharedPreferences("app_code", Context.MODE_PRIVATE)
    private val random = SecureRandom()

    override fun active(): Boolean = preferences.contains(VERIFIER)

    override fun protectsKeys(): Boolean = active() && preferences.getBoolean(PROTECTS, false)

    override fun setProtectsKeys(on: Boolean) {
        check(preferences.edit().putBoolean(PROTECTS, on).commit()) { "No se pudo guardar el código" }
    }

    override fun check(code: CharArray): CodeKey? {
        if (!active()) return null
        val key = derive(code, bytes(SALT), preferences.getInt(ROUNDS, 0))
        val known = try {
            val sealed = phone.finish(phone.cipher(WRAP_ALIAS, Cipher.DECRYPT_MODE, bytes(VERIFIER_IV)), bytes(VERIFIER))
            Cipher.getInstance(TRANSFORMATION)
                .apply { init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, sealed, 0, GCM_IV)) }
                .doFinal(sealed, GCM_IV, sealed.size - GCM_IV)
        } catch (_: GeneralSecurityException) {
            null
        }
        if (known == null || !known.contentEquals(KNOWN)) {
            key.fill(0)
            return null
        }
        return CodeKey(key)
    }

    override fun prepare(code: CharArray): PreparedCode {
        val salt = ByteArray(16).also(random::nextBytes)
        val rounds = rounds()
        return PreparedCode(CodeKey(derive(code, salt, rounds)), salt, rounds)
    }

    override fun commit(prepared: PreparedCode) {
        val inner = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(prepared.key.bytes, "AES")) }
        val sealed = inner.iv + inner.doFinal(KNOWN)
        phone.ensure(WRAP_ALIAS, KeyAuth.NONE)
        val outer = phone.cipher(WRAP_ALIAS, Cipher.ENCRYPT_MODE)
        val verifier = phone.finish(outer, sealed)
        check(preferences.edit()
            .putString(SALT, encode(prepared.salt))
            .putInt(ROUNDS, prepared.rounds)
            .putString(VERIFIER, encode(verifier))
            .putString(VERIFIER_IV, encode(outer.iv))
            .putBoolean(PROTECTS, protectsKeys())
            .commit()) { "No se pudo guardar el código" }
        phone.delete(FINGERPRINT_ALIAS)
        renewFingerprint()
    }

    override fun clear() {
        check(preferences.edit().clear().commit()) { "No se pudo borrar el código" }
        phone.delete(WRAP_ALIAS)
        phone.delete(FINGERPRINT_ALIAS)
    }

    override fun attempts(): CodeAttempts {
        val failures = preferences.getInt(FAILURES, 0)
        val wait = preferences.getLong(WAIT, 0)
        if (wait <= 0) return CodeAttempts(failures)
        if (preferences.getInt(WAIT_BOOT, -1) != boots()) {
            // Rebooted: the clock since boot started over, and so does the whole wait.
            saveAttempts(CodeAttempts(failures, Duration.ofMillis(wait)))
            return CodeAttempts(failures, Duration.ofMillis(wait))
        }
        // Never longer than the wait itself, whatever the clock did.
        val left = (wait - (elapsed() - preferences.getLong(WAIT_FROM, 0))).coerceIn(0, wait)
        return CodeAttempts(failures, Duration.ofMillis(left).takeIf { left > 0 })
    }

    override fun saveAttempts(attempts: CodeAttempts) {
        check(preferences.edit()
            .putInt(FAILURES, attempts.failures)
            .putLong(WAIT, attempts.wait?.toMillis() ?: 0)
            .putLong(WAIT_FROM, elapsed())
            .putInt(WAIT_BOOT, boots())
            .commit()) { "No se pudo guardar el código" }
    }

    override fun fingerprintCipher(): Cipher? {
        if (!active() || vault.anyOnly() || !phone.fingerprint() || !phone.contains(FINGERPRINT_ALIAS)) return null
        return try {
            phone.cipher(FINGERPRINT_ALIAS, Cipher.ENCRYPT_MODE)
        } catch (_: KeyPermanentlyInvalidatedException) {
            phone.delete(FINGERPRINT_ALIAS)
            null
        }
    }

    override fun fingerprintPassed(authorized: Cipher): Boolean = try {
        phone.finish(authorized, byteArrayOf(0))
        true
    } catch (e: Exception) {
        if (rejectedAfterFingerprint(e)) vault.markAnyOnly()
        phone.delete(FINGERPRINT_ALIAS)
        false
    }

    /**
     * Makes the key again, without opening a Keystore operation to test the old one: a key not killed by an enrollment
     * trusts the same fingers a new one does, so making it again changes nothing that matters.
     */
    override fun renewFingerprint() {
        if (!active() || vault.anyOnly() || !phone.fingerprint()) return
        phone.delete(FINGERPRINT_ALIAS)
        phone.ensure(FINGERPRINT_ALIAS, KeyAuth.FINGERPRINT)
    }

    private fun bytes(name: String): ByteArray = Base64.decode(requireNotNull(preferences.getString(name, null)), Base64.NO_WRAP)
    private fun encode(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

    internal companion object {
        const val WRAP_ALIAS = "dev.duardo.neotransfer.app-code"
        const val FINGERPRINT_ALIAS = "dev.duardo.neotransfer.app-code.fingerprint"
        private const val SALT = "salt"
        private const val ROUNDS = "rounds"
        private const val VERIFIER = "verifier"
        private const val VERIFIER_IV = "verifier_iv"
        private const val PROTECTS = "protects_keys"
        private const val FAILURES = "failures"
        private const val WAIT = "wait"
        private const val WAIT_FROM = "wait_from"
        private const val WAIT_BOOT = "wait_boot"
        private const val GCM_IV = 12
        private const val TARGET_MILLIS = 250L
        private const val PROBE_ROUNDS = 20_000
        private const val FEWEST_ROUNDS = 100_000
        private const val MOST_ROUNDS = 2_000_000
        private val KNOWN = "NeoTransfer".toByteArray()

        fun derive(code: CharArray, salt: ByteArray, rounds: Int): ByteArray {
            val spec = PBEKeySpec(code, salt, rounds, 256)
            try {
                return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            } finally {
                spec.clearPassword()
            }
        }

        /** The rounds that take about [TARGET_MILLIS] here, from a timed probe; between [FEWEST_ROUNDS] and [MOST_ROUNDS]. */
        fun calibratedRounds(): Int {
            val start = System.nanoTime()
            derive(CharArray(6) { '0' }, ByteArray(16), PROBE_ROUNDS).fill(0)
            val millis = ((System.nanoTime() - start) / 1_000_000).coerceAtLeast(1)
            return (PROBE_ROUNDS * TARGET_MILLIS / millis).coerceIn(FEWEST_ROUNDS.toLong(), MOST_ROUNDS.toLong()).toInt()
        }
    }
}
