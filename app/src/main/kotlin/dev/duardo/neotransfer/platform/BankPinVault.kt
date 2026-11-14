package dev.duardo.neotransfer.platform

import android.content.Context
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.util.Base64
import dev.duardo.neotransfer.engine.AppController
import dev.duardo.neotransfer.engine.CodeKey
import dev.duardo.neotransfer.engine.KeyVault
import dev.duardo.neotransfer.engine.KeysLost
import dev.duardo.neotransfer.engine.PhoneLock
import dev.duardo.neotransfer.engine.VaultMoved
import java.util.WeakHashMap
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-GCM of the keys' blob under one Keystore key, which the prompt authorizes for every use:
 * - with a strong fingerprint enrolled, a key for the fingerprint alone that dies when a new one is enrolled;
 * - with only a secure screen lock, or on a phone whose fingerprint keys never authorize ([anyOnly]), a key the
 *   fingerprint or the screen lock opens;
 * - under the app code, the blob of the code's key ([CodeKey]) wrapped by a key that asks nothing and only ties it to
 *   this phone.
 * The key that holds the blob keeps holding it until the vault is emptied.
 */
class BankPinVault internal constructor(context: Context, private val phone: PhoneKeys) : KeyVault {
    constructor(context: Context) : this(context, AndroidPhoneKeys(context))

    private val preferences = context.getSharedPreferences("key_vault", Context.MODE_PRIVATE)
    /** What this phone showed once and keeps after [reset]. */
    private val device = context.getSharedPreferences("key_vault_device", Context.MODE_PRIVATE)
    /** The key each cipher handed out belongs to: its prompt, the realme check and the save go by it, never recomputed. */
    private val issued = WeakHashMap<Cipher, String>()

    init {
        // A test build of PR #3 (never released) kept this flag; its keys go in [reset].
        if (device.contains(LEGACY_TIMED)) device.edit().remove(LEGACY_TIMED).commit()
    }

    /**
     * This phone's Keystore turned the fingerprint key down right after a valid fingerprint: from then on the keys and
     * the app code's fingerprint go by the key the fingerprint or the screen lock opens.
     */
    fun anyOnly(): Boolean = device.getBoolean(ANY_ONLY, false)

    /** From now on the screen-lock key; the fingerprint key stays only while it holds the saved keys. */
    internal fun markAnyOnly() {
        check(device.edit().putBoolean(ANY_ONLY, true).commit()) { "No se pudo guardar la clave" }
        val holdsKeys = hasKeys() && (savedAlias() ?: FINGERPRINT_ALIAS) == FINGERPRINT_ALIAS
        if (!holdsKeys) phone.delete(FINGERPRINT_ALIAS)
    }

    override fun phoneLock(): PhoneLock = lock(phoneAlias())

    override fun lockOf(cipher: Cipher): PhoneLock = lock(synchronized(issued) { issued[cipher] } ?: phoneAlias())

    private fun lock(alias: String?): PhoneLock = when (alias) {
        FINGERPRINT_ALIAS -> PhoneLock.FINGERPRINT
        ANY_ALIAS -> PhoneLock.ANY
        else -> PhoneLock.NONE
    }

    private fun issue(cipher: Cipher, alias: String): Cipher = cipher.also { synchronized(issued) { issued[it] = alias } }

    override fun hasKeys(): Boolean = preferences.contains(CIPHERTEXT) && preferences.contains(IV)

    override fun underCode(): Boolean = hasKeys() && savedAlias() == CODE_ALIAS

    override fun reset() {
        check(preferences.edit().clear().commit()) { "No se pudo borrar la clave" }
        (ALIASES + LEGACY_ALIASES).forEach(phone::delete)
    }

    override fun encryptCipher(): Cipher {
        val alias = checkNotNull(phoneAlias()) { AppController.NO_LOCK }
        phone.ensure(alias, if (alias == FINGERPRINT_ALIAS) KeyAuth.FINGERPRINT else KeyAuth.ANY)
        return try {
            issue(phone.cipher(alias, Cipher.ENCRYPT_MODE), alias)
        } catch (_: KeyPermanentlyInvalidatedException) {
            // A new fingerprint killed it: what it held cannot be opened, and the save writes the blob anew.
            phone.delete(alias)
            encryptCipher()
        }
    }

    override fun encrypt(authorized: Cipher, plaintext: ByteArray) {
        val alias = checkNotNull(synchronized(issued) { issued[authorized] }) { "No se pudo guardar la clave" }
        check(alias != CODE_ALIAS) { "No se pudo guardar la clave" }
        val encrypted = try {
            phone.finish(authorized, plaintext)
        } catch (e: Exception) {
            if (alias != FINGERPRINT_ALIAS || !rejectedAfterFingerprint(e)) throw e
            markAnyOnly()
            throw VaultMoved()
        }
        save(alias, encrypted, authorized.iv)
    }

    override fun decryptCipher(): Cipher? {
        check(hasKeys()) { "No hay claves guardadas" }
        // A blob without its key's name was saved by 1.2 and earlier, always under the fingerprint key.
        val alias = savedAlias() ?: FINGERPRINT_ALIAS
        return try {
            issue(phone.cipher(alias, Cipher.DECRYPT_MODE, iv()), alias)
        } catch (_: KeyPermanentlyInvalidatedException) {
            reset()
            null
        }
    }

    override fun decrypt(authorized: Cipher): ByteArray = phone.finish(authorized, ciphertext())

    override fun encrypt(code: CodeKey, plaintext: ByteArray) {
        val (ciphertext, iv) = seal(code, plaintext)
        save(CODE_ALIAS, ciphertext, iv)
    }

    /** [plaintext] under the code's key, wrapped by the phone-bound key: the ciphertext and the wrap's IV. */
    private fun seal(code: CodeKey, plaintext: ByteArray): Pair<ByteArray, ByteArray> {
        val inner = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(code.bytes, "AES")) }
        val sealed = inner.iv + inner.doFinal(plaintext)
        try {
            phone.ensure(CODE_ALIAS, KeyAuth.NONE)
            val outer = phone.cipher(CODE_ALIAS, Cipher.ENCRYPT_MODE)
            return phone.finish(outer, sealed) to outer.iv
        } finally {
            sealed.fill(0)
        }
    }

    override fun decrypt(code: CodeKey): ByteArray {
        check(underCode()) { "No hay claves guardadas con el código" }
        try {
            return open(code, CIPHERTEXT, IV).also { if (preferences.contains(STAGED)) clearStaged() }
        } catch (e: AEADBadTagException) {
            if (!preferences.contains(STAGED)) throw KeysLost()
        } catch (_: KeyPermanentlyInvalidatedException) {
            throw KeysLost()
        }
        // The code was changed and the process died before the staged blob became current: it is the one.
        return try {
            open(code, STAGED, STAGED_IV).also { promote() }
        } catch (_: AEADBadTagException) {
            throw KeysLost()
        } catch (_: KeyPermanentlyInvalidatedException) {
            throw KeysLost()
        }
    }

    /** The plaintext of the code's blob at [ciphertextName] / [ivName]. */
    private fun open(code: CodeKey, ciphertextName: String, ivName: String): ByteArray {
        val sealed = phone.finish(phone.cipher(CODE_ALIAS, Cipher.DECRYPT_MODE, bytes(ivName)), bytes(ciphertextName))
        try {
            val inner = Cipher.getInstance(TRANSFORMATION)
            inner.init(Cipher.DECRYPT_MODE, SecretKeySpec(code.bytes, "AES"), GCMParameterSpec(128, sealed, 0, GCM_IV))
            return inner.doFinal(sealed, GCM_IV, sealed.size - GCM_IV)
        } finally {
            sealed.fill(0)
        }
    }

    override fun stage(code: CodeKey, plaintext: ByteArray) {
        check(underCode()) { "No hay claves guardadas con el código" }
        val (ciphertext, iv) = seal(code, plaintext)
        check(preferences.edit()
            .putString(STAGED, Base64.encodeToString(ciphertext, Base64.NO_WRAP))
            .putString(STAGED_IV, Base64.encodeToString(iv, Base64.NO_WRAP))
            .commit()) { "No se pudo guardar la clave" }
    }

    /** One commit: the staged blob replaces the current one, or nothing changes. */
    override fun promote() {
        val ciphertext = preferences.getString(STAGED, null) ?: return
        val iv = requireNotNull(preferences.getString(STAGED_IV, null))
        check(preferences.edit().putString(CIPHERTEXT, ciphertext).putString(IV, iv).remove(STAGED).remove(STAGED_IV).commit()) {
            "No se pudo guardar la clave"
        }
    }

    private fun clearStaged() {
        preferences.edit().remove(STAGED).remove(STAGED_IV).commit()
    }

    override fun savedLock(): PhoneLock? = if (!hasKeys() || underCode()) null else lock(savedAlias() ?: FINGERPRINT_ALIAS)

    /** Writes the blob under [alias] and drops the keys that no longer hold anything. */
    private fun save(alias: String, ciphertext: ByteArray, iv: ByteArray) {
        check(preferences.edit()
            .putString(CIPHERTEXT, Base64.encodeToString(ciphertext, Base64.NO_WRAP))
            .putString(IV, Base64.encodeToString(iv, Base64.NO_WRAP))
            .putString(ALIAS, alias)
            .commit()) { "No se pudo guardar la clave" }
        ALIASES.filter { it != alias }.forEach(phone::delete)
    }

    private fun savedAlias(): String? = preferences.getString(ALIAS, null)

    /**
     * The phone-lock key for the next save: on a phone marked [anyOnly] always the screen-lock one; otherwise the one
     * holding the blob, or the one this phone takes now. Null without a secure screen lock.
     */
    private fun phoneAlias(): String? {
        if (anyOnly()) return ANY_ALIAS.takeIf { phone.secureLock() }
        if (hasKeys()) (savedAlias() ?: FINGERPRINT_ALIAS).takeIf { it != CODE_ALIAS }?.let { return it }
        return when {
            !phone.secureLock() -> null
            phone.fingerprint() -> FINGERPRINT_ALIAS
            else -> ANY_ALIAS
        }
    }

    private fun iv(): ByteArray = bytes(IV)
    private fun ciphertext(): ByteArray = bytes(CIPHERTEXT)
    private fun bytes(name: String): ByteArray = Base64.decode(requireNotNull(preferences.getString(name, null)), Base64.NO_WRAP)

    internal companion object {
        const val FINGERPRINT_ALIAS = "dev.duardo.neotransfer.bank-keys"
        const val ANY_ALIAS = "dev.duardo.neotransfer.bank-keys.any"
        const val CODE_ALIAS = "dev.duardo.neotransfer.bank-keys.code"
        private val ALIASES = listOf(FINGERPRINT_ALIAS, ANY_ALIAS, CODE_ALIAS)
        /** PR #3's keys and flag, on the test build the realme tester ran. */
        private val LEGACY_ALIASES = listOf("dev.duardo.neotransfer.bank-keys.timed", "dev.duardo.neotransfer.bank-keys.gate")
        private const val LEGACY_TIMED = "timed"
        private const val CIPHERTEXT = "ciphertext"
        private const val IV = "iv"
        private const val ALIAS = "alias"
        private const val STAGED = "staged_ciphertext"
        private const val STAGED_IV = "staged_iv"
        private const val ANY_ONLY = "any_only"
        private const val GCM_IV = 12
    }
}
