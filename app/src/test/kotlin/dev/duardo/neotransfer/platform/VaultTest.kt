package dev.duardo.neotransfer.platform

import android.app.Application
import android.content.Context
import org.robolectric.RuntimeEnvironment
import dev.duardo.neotransfer.engine.AppController
import dev.duardo.neotransfer.engine.CodeKey
import dev.duardo.neotransfer.engine.KeysLost
import dev.duardo.neotransfer.engine.PhoneLock
import dev.duardo.neotransfer.engine.VaultMoved
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import javax.crypto.Cipher
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [BankPinVault] over a faked Keystore: which key each phone gets, the realme fallback, enrollment, the code. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class VaultTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val keys = "BPA=1234;BANDEC=12345".toByteArray()

    /** What the prompt would do: hand the cipher back authorized. */
    private fun BankPinVault.save(plaintext: ByteArray) = encrypt(encryptCipher(), plaintext)
    private fun BankPinVault.open(): ByteArray = decrypt(requireNotNull(decryptCipher()))

    @Test fun `a phone with a fingerprint gets the fingerprint-only key, as before`() {
        val phone = FakePhoneKeys()
        val vault = BankPinVault(context, phone)
        assertEquals(PhoneLock.FINGERPRINT, vault.phoneLock())
        vault.save(keys)
        assertEquals(KeyAuth.FINGERPRINT, phone.auth(BankPinVault.FINGERPRINT_ALIAS))
        assertContentEquals(keys, vault.open())
    }

    @Test fun `a new fingerprint forgets the keys`() {
        val phone = FakePhoneKeys()
        val vault = BankPinVault(context, phone)
        vault.save(keys)
        phone.enroll()
        assertNull(vault.decryptCipher())
        assertFalse(vault.hasKeys())
    }

    @Test fun `a screen lock without a fingerprint gets the key the screen lock opens, and keeps it once a fingerprint comes`() {
        val phone = FakePhoneKeys(fingerprint = false)
        val vault = BankPinVault(context, phone)
        assertEquals(PhoneLock.ANY, vault.phoneLock())
        vault.save(keys)
        assertEquals(KeyAuth.ANY, phone.auth(BankPinVault.ANY_ALIAS))
        phone.enroll()
        assertEquals(PhoneLock.ANY, vault.phoneLock()) // the key that holds them keeps them
        assertContentEquals(keys, vault.open())
    }

    @Test fun `without a secure lock nothing can be saved`() {
        val vault = BankPinVault(context, FakePhoneKeys(fingerprint = false, secureLock = false))
        assertEquals(PhoneLock.NONE, vault.phoneLock())
        assertEquals(AppController.NO_LOCK, assertFailsWith<IllegalStateException> { vault.encryptCipher() }.message)
    }

    @Test fun `realme - the fingerprint key turned down moves the phone for good to the key the screen lock opens`() {
        val phone = FakePhoneKeys().apply { rejectsFingerprintKeys = true }
        val vault = BankPinVault(context, phone)
        assertEquals(PhoneLock.FINGERPRINT, vault.phoneLock())
        assertFailsWith<VaultMoved> { vault.save(keys) }
        assertFalse(vault.hasKeys())
        assertFalse(phone.contains(BankPinVault.FINGERPRINT_ALIAS))
        // The save goes again at once with a new prompt, now for the fingerprint or the screen lock.
        assertEquals(PhoneLock.ANY, vault.phoneLock())
        vault.save(keys)
        assertEquals(KeyAuth.ANY, phone.auth(BankPinVault.ANY_ALIAS))
        assertContentEquals(keys, vault.open())
        // For good: another process, and an emptied vault, still go by the screen-lock key.
        val later = BankPinVault(context, phone)
        later.reset()
        assertTrue(later.anyOnly())
        assertEquals(PhoneLock.ANY, later.phoneLock())
    }

    @Test fun `a blob saved by 1_2 (no key name) opens with the fingerprint key`() {
        val phone = FakePhoneKeys()
        phone.ensure(BankPinVault.FINGERPRINT_ALIAS, KeyAuth.FINGERPRINT)
        val cipher = phone.cipher(BankPinVault.FINGERPRINT_ALIAS, Cipher.ENCRYPT_MODE)
        val sealed = cipher.doFinal(keys)
        context.getSharedPreferences("key_vault", Context.MODE_PRIVATE).edit()
            .putString("ciphertext", android.util.Base64.encodeToString(sealed, android.util.Base64.NO_WRAP))
            .putString("iv", android.util.Base64.encodeToString(cipher.iv, android.util.Base64.NO_WRAP))
            .commit()
        val vault = BankPinVault(context, phone)
        assertEquals(PhoneLock.FINGERPRINT, vault.phoneLock())
        assertContentEquals(keys, vault.open())
    }

    @Test fun `under the code the blob opens with the code's key only, and going back drops the code's wrap`() {
        val phone = FakePhoneKeys()
        val vault = BankPinVault(context, phone)
        vault.save(keys)
        val code = CodeKey(ByteArray(32) { it.toByte() })
        vault.encrypt(code, keys)
        assertTrue(vault.underCode())
        assertFalse(phone.contains(BankPinVault.FINGERPRINT_ALIAS))
        assertContentEquals(keys, vault.decrypt(code))
        assertFailsWith<Exception> { vault.decrypt(CodeKey(ByteArray(32))) }
        // The phone's lock again: a fresh key of this phone.
        assertEquals(PhoneLock.FINGERPRINT, vault.phoneLock())
        vault.save(keys)
        assertFalse(vault.underCode())
        assertFalse(phone.contains(BankPinVault.CODE_ALIAS))
        assertContentEquals(keys, vault.open())
    }

    @Test fun `a save goes by the key its cipher was made with, even if the phone changes in between`() {
        val phone = FakePhoneKeys()
        val vault = BankPinVault(context, phone)
        val cipher = vault.encryptCipher()
        assertEquals(PhoneLock.FINGERPRINT, vault.lockOf(cipher))
        phone.fingerprint = false // the prompt is up, the fingerprints are gone
        assertEquals(PhoneLock.ANY, vault.phoneLock())
        assertEquals(PhoneLock.FINGERPRINT, vault.lockOf(cipher))
        vault.encrypt(cipher, keys)
        val opener = assertNotNull(vault.decryptCipher())
        assertEquals(PhoneLock.FINGERPRINT, vault.lockOf(opener))
        assertContentEquals(keys, vault.decrypt(opener))
        assertFailsWith<IllegalStateException> { vault.encrypt(javax.crypto.Cipher.getInstance("AES/GCM/NoPadding"), keys) } // not one of the vault's
    }

    @Test fun `once marked, a save moves to the screen-lock key even with keys under the fingerprint key`() {
        val phone = FakePhoneKeys()
        val vault = BankPinVault(context, phone)
        vault.save(keys)
        phone.rejectsFingerprintKeys = true
        vault.markAnyOnly()
        assertTrue(phone.contains(BankPinVault.FINGERPRINT_ALIAS)) // it still holds them
        val cipher = vault.encryptCipher()
        assertEquals(PhoneLock.ANY, vault.lockOf(cipher))
        vault.encrypt(cipher, keys)
        assertEquals(KeyAuth.ANY, phone.auth(BankPinVault.ANY_ALIAS))
        assertFalse(phone.contains(BankPinVault.FINGERPRINT_ALIAS))
        assertContentEquals(keys, vault.open())
    }

    @Test fun `PR 3's test build leaves nothing behind`() {
        val phone = FakePhoneKeys()
        context.getSharedPreferences("key_vault_device", Context.MODE_PRIVATE).edit().putBoolean("timed", true).commit()
        phone.ensure("dev.duardo.neotransfer.bank-keys.timed", KeyAuth.ANY)
        phone.ensure("dev.duardo.neotransfer.bank-keys.gate", KeyAuth.FINGERPRINT)
        val vault = BankPinVault(context, phone)
        assertFalse(context.getSharedPreferences("key_vault_device", Context.MODE_PRIVATE).contains("timed"))
        vault.reset()
        assertFalse(phone.contains("dev.duardo.neotransfer.bank-keys.timed") || phone.contains("dev.duardo.neotransfer.bank-keys.gate"))
    }

    @Test fun `a blob staged under a new code becomes current with the new code, or is dropped when the old one opens`() {
        val vault = BankPinVault(context, FakePhoneKeys())
        val old = CodeKey(ByteArray(32) { 1 })
        val new = CodeKey(ByteArray(32) { 2 })
        vault.encrypt(old, keys)
        vault.stage(new, keys)
        assertContentEquals(keys, vault.decrypt(new)) // cut short after the commit: the staged one
        assertFailsWith<KeysLost> { vault.decrypt(old) }
        vault.stage(old, keys)
        assertContentEquals(keys, vault.decrypt(new)) // the current one; the stale stage goes
        assertFailsWith<KeysLost> { vault.decrypt(old) }
        vault.stage(old, keys)
        vault.promote()
        assertContentEquals(keys, vault.decrypt(old))
    }
}
