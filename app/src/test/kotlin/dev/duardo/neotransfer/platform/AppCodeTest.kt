package dev.duardo.neotransfer.platform

import android.app.Application
import android.content.Context
import dev.duardo.neotransfer.engine.CodeAttempts
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Duration
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [AndroidAppCode] over a faked Keystore: setting and checking the code, what is stored, its fingerprint key. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AppCodeTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val phone = FakePhoneKeys()
    private val vault = BankPinVault(context, phone)
    /** The clock since boot and the boot count, which the waits run on. */
    private var sinceBoot = 50_000L
    private var boot = 3
    private fun appCode() = AndroidAppCode(context, phone, vault, { 1_000 }, { sinceBoot }, { boot })

    @Test fun `the right code gives the key it was set with, a wrong one nothing, and the code is never stored`() {
        val code = appCode()
        assertFalse(code.active())
        val key = code.create("246813".toCharArray())
        assertTrue(code.active())
        assertContentEquals(key.bytes, appCode().check("246813".toCharArray())?.bytes)
        assertNull(appCode().check("246812".toCharArray()))
        val stored = context.getSharedPreferences("app_code", Context.MODE_PRIVATE).all.values.joinToString()
        assertFalse("246813" in stored)
    }

    @Test fun `the verifier is tied to this phone's Keystore`() {
        val code = appCode()
        code.create("246813".toCharArray())
        phone.delete(AndroidAppCode.WRAP_ALIAS) // as on another phone: the wrap key is not there
        assertNull(code.check("246813".toCharArray()))
    }

    @Test fun `counter and wait outlive the process and run on the clock since boot, whatever the wall clock does`() {
        appCode().apply {
            create("246813".toCharArray())
            saveAttempts(CodeAttempts(6, Duration.ofMinutes(1)))
        }
        sinceBoot += 20_000
        assertEquals(CodeAttempts(6, Duration.ofSeconds(40)), appCode().attempts())
        sinceBoot += 40_000
        assertEquals(CodeAttempts(6), appCode().attempts())
    }

    @Test fun `after a reboot the whole wait starts again, and a wait never lasts longer than itself`() {
        val code = appCode()
        code.create("246813".toCharArray())
        code.saveAttempts(CodeAttempts(5, Duration.ofSeconds(30)))
        sinceBoot += 25_000
        boot++ // rebooted: the clock since boot starts over
        sinceBoot = 1_000
        assertEquals(CodeAttempts(5, Duration.ofSeconds(30)), code.attempts())
        sinceBoot += 10_000
        assertEquals(CodeAttempts(5, Duration.ofSeconds(20)), code.attempts())
        sinceBoot -= 1_000_000 // a clock that went back never stretches it
        assertEquals(CodeAttempts(5, Duration.ofSeconds(30)), code.attempts())
    }

    @Test fun `the code's fingerprint turned down never takes the vault's fingerprint key holding the saved keys`() {
        vault.encrypt(vault.encryptCipher(), "BPA=1234".toByteArray())
        val code = appCode()
        code.create("246813".toCharArray())
        phone.rejectsFingerprintKeys = true
        assertFalse(code.fingerprintPassed(assertNotNull(code.fingerprintCipher())))
        assertTrue(vault.anyOnly())
        assertTrue(phone.contains(BankPinVault.FINGERPRINT_ALIAS))
        phone.rejectsFingerprintKeys = false
        assertContentEquals("BPA=1234".toByteArray(), vault.decrypt(assertNotNull(vault.decryptCipher())))
    }

    @Test fun `the fingerprint stands in for the code until a new one is enrolled, and comes back once the code is typed`() {
        val code = appCode()
        code.create("246813".toCharArray())
        assertEquals(KeyAuth.FINGERPRINT, phone.auth(AndroidAppCode.FINGERPRINT_ALIAS))
        assertTrue(code.fingerprintPassed(assertNotNull(code.fingerprintCipher())))
        phone.enroll()
        assertNull(code.fingerprintCipher()) // a finger enrolled after the code never opens the app
        code.renewFingerprint()
        assertTrue(code.fingerprintPassed(assertNotNull(code.fingerprintCipher())))
    }

    @Test fun `a phone whose fingerprint keys never authorize offers only the code`() {
        phone.rejectsFingerprintKeys = true
        val code = appCode()
        code.create("246813".toCharArray())
        assertFalse(code.fingerprintPassed(assertNotNull(code.fingerprintCipher())))
        assertTrue(vault.anyOnly())
        assertNull(code.fingerprintCipher())
        code.renewFingerprint()
        assertNull(code.fingerprintCipher())
    }

    @Test fun `without a fingerprint only the code is offered`() {
        phone.fingerprint = false
        val code = appCode()
        code.create("246813".toCharArray())
        assertNull(code.fingerprintCipher())
    }

    @Test fun `changing it keeps whether it guards the keys, turning it off drops everything`() {
        val code = appCode()
        code.create("246813".toCharArray())
        code.setProtectsKeys(true)
        code.create("135792".toCharArray())
        assertTrue(code.protectsKeys())
        assertNull(code.check("246813".toCharArray()))
        assertNotNull(code.check("135792".toCharArray()))
        code.clear()
        assertFalse(code.active())
        assertFalse(code.protectsKeys())
        assertFalse(phone.contains(AndroidAppCode.FINGERPRINT_ALIAS))
        assertFalse(phone.contains(AndroidAppCode.WRAP_ALIAS))
    }

    @Test fun `rounds are measured to take a while, between 100 000 and 2 000 000`() {
        assertTrue(AndroidAppCode.calibratedRounds() in 100_000..2_000_000)
    }
}
