package dev.duardo.neotransfer.app

import android.app.Application
import android.net.Uri
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import dev.duardo.neotransfer.core.model.Bank
import dev.duardo.neotransfer.core.model.OwnCard
import dev.duardo.neotransfer.data.WalletCard
import dev.duardo.neotransfer.designsystem.component.KeypadKey
import dev.duardo.neotransfer.engine.AppController
import dev.duardo.neotransfer.engine.FakeVault
import dev.duardo.neotransfer.engine.PhoneLock
import dev.duardo.neotransfer.engine.Rig
import dev.duardo.neotransfer.platform.BiometricResult
import dev.duardo.neotransfer.ui.model.LockUi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Instant
import javax.crypto.Cipher
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** What the app does when the prompt cannot be shown: only a phone with no lock at all goes on without one. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PromptRulesTest {
    private val scheduler = TestCoroutineScheduler()
    private val dispatcher = StandardTestDispatcher(scheduler)
    private val start = Instant.now()
    private val application = ApplicationProvider.getApplicationContext<Application>()
    private val host = UnavailableHost()

    private fun settle(advance: Long = 0) {
        scheduler.advanceTimeBy(advance)
        repeat(8) {
            scheduler.runCurrent()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    /** A locked app on a phone with [lock], a BPA card set up and, when it can be saved, its key. */
    private fun lockedApp(lock: PhoneLock): Pair<Rig, AppModel> {
        val rig = Rig(vault = FakeVault(lock), clock = { start.plusMillis(scheduler.currentTime) }, state = dispatcher, io = dispatcher)
        CoroutineScope(dispatcher).launch {
            rig.controller.unlock(null)
            if (lock != PhoneLock.NONE) rig.controller.saveKey(rig.controller.keyCipher(), Bank.BPA, "1234".toCharArray())
            rig.controller.saveCard(WalletCard(OwnCard("9212120123456783", Bank.BPA)))
        }
        settle()
        val model = AppModel(application, rig.controller, dispatcher).also { it.host = host }
        rig.controller.onBackground()
        settle(AppController.LOCK_AFTER.toMillis() + 1_000)
        assertTrue(rig.controller.locked.value)
        return rig to model
    }

    private fun authorize(model: AppModel): Boolean {
        val result = CoroutineScope(dispatcher).async { model.authorize("Transferir 300.00 CUP", null) }
        settle()
        return result.getCompleted()
    }

    @Test fun `with a screen lock, a prompt that cannot be shown neither opens the app nor confirms a send`() {
        val (rig, model) = lockedApp(PhoneLock.FINGERPRINT)
        model.unlock()
        settle()
        assertTrue(rig.controller.locked.value)
        assertIs<LockUi.Cancelled>(model.lockState())
        CoroutineScope(dispatcher).launch { rig.controller.unlock(rig.controller.unlockCipher()) }
        settle()
        assertFalse(authorize(model))
    }

    @Test fun `with no lock at all the app opens and a send goes on to the typed key`() {
        val (rig, model) = lockedApp(PhoneLock.NONE)
        model.unlock()
        settle()
        assertFalse(rig.controller.locked.value)
        assertTrue(authorize(model))
        assertEquals(0, host.prompts)
    }

    @Test fun `after a toggle cut short, turning the code off moves the keys back under the screen lock`() {
        val rig = Rig(vault = FakeVault(PhoneLock.FINGERPRINT), clock = { start.plusMillis(scheduler.currentTime) }, state = dispatcher, io = dispatcher)
        CoroutineScope(dispatcher).launch {
            rig.controller.unlock(null)
            rig.controller.saveKey(rig.controller.keyCipher(), Bank.BPA, "1234".toCharArray())
            rig.controller.createCode("246813".toCharArray())
            rig.controller.protectKeys(true, null)
        }
        settle()
        rig.code.setProtectsKeys(false) // the setting went back, the keys did not
        val passing = PassingHost()
        val model = AppModel(application, rig.controller, dispatcher).also { it.host = passing }
        val disabled = CoroutineScope(dispatcher).async { model.disableCode() }
        settle()
        "246813".forEach { model.codeAsk!!.check.key(KeypadKey.Digit(it - '0')); settle() }
        assertTrue(disabled.getCompleted())
        assertTrue(!rig.vault.underCode() && rig.vault.contains("BPA=1234"))
        assertFalse(rig.controller.code.value.active)
        assertEquals(1, passing.prompts)
    }

    private class PassingHost : Host by UnavailableHost() {
        var prompts = 0
        override suspend fun biometric(title: String, subtitle: String?, cipher: Cipher?, screenLock: Boolean, fallback: Boolean): BiometricResult {
            prompts++
            return BiometricResult.Passed(cipher)
        }
    }

    private class UnavailableHost : Host {
        var prompts = 0
        override suspend fun biometric(title: String, subtitle: String?, cipher: Cipher?, screenLock: Boolean, fallback: Boolean): BiometricResult {
            prompts++
            return BiometricResult.Unavailable
        }
        override suspend fun createDocument(name: String): Uri? = null
        override suspend fun openDocument(): Uri? = null
        override fun displayName(uri: Uri): String? = null
        override fun requestPermissions(permissions: List<String>) = Unit
        override fun shouldExplain(permission: String) = false
        override suspend fun requestPermission(permission: String) = true
        override fun share(text: String) = Unit
        override fun copy(text: String) = Unit
        override fun open(url: String) = Unit
        override fun openContact(key: String) = Unit
        override fun openAppSettings() = Unit
        override fun openNotificationSettings() = Unit
        override fun eraseAppData() = Unit
    }
}
