package dev.duardo.neotransfer.app

import android.app.Application
import android.net.Uri
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureScreenRoboImage
import dev.duardo.neotransfer.core.model.Bank
import dev.duardo.neotransfer.core.model.OwnCard
import dev.duardo.neotransfer.data.WalletCard
import dev.duardo.neotransfer.engine.FakeModem
import dev.duardo.neotransfer.engine.Rig
import dev.duardo.neotransfer.platform.BiometricResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Instant
import javax.crypto.Cipher
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The app code end to end on the JVM, over the real [AppModel] and controller with fakes: set it in Ajustes, keep the
 * keys under it, open the app with it, confirm a send with it, and forget it. Each step is captured to `build/journey-code/`.
 */
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalRoborazziApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-xhdpi", application = Application::class)
class CodeJourneyTest {
    @get:Rule
    val compose = createComposeRule()

    private val scheduler = TestCoroutineScheduler()
    private val dispatcher = StandardTestDispatcher(scheduler)
    private val start = Instant.now()
    private val rig = Rig(clock = { start.plusMillis(scheduler.currentTime) }, state = dispatcher, io = dispatcher, modem = FakeModem(latency = 800))
    private val host = FakeHost()
    private val application = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var model: AppModel
    private var step = 0

    @Test
    fun code() {
        shadowOf(application).grantPermissions(*AppModel.ALL_PERMISSIONS.toTypedArray())
        onController {
            rig.controller.unlock(rig.controller.unlockCipher())
            rig.controller.saveKey(rig.controller.keyCipher(), Bank.BPA, "1234".toCharArray())
            rig.controller.saveCard(WalletCard(OwnCard("9212120123456783", Bank.BPA)))
        }
        model = AppModel(application, rig.controller, dispatcher).also { it.host = host }
        compose.setContent { NeoApp(model) }
        settle()

        // Ajustes › Código de acceso: Desactivado; set it, with a mismatch first.
        node(hasContentDescription("Ajustes")).performClick()
        settle()
        shot("settings")
        text("Código de acceso").performClick()
        settle()
        shot("create")
        type("123456")
        shot("repeat")
        type("654321")
        shot("mismatch")
        type("123456")
        type("123456")
        shot("code-settings")
        assertTrue(rig.controller.code.value.active)

        // The keys go under the code: the code was just typed, so nothing more is asked.
        val prompts = host.prompts.size
        text("Usar también para las claves").performClick()
        settle()
        shot("code-settings-keys")
        assertTrue(rig.vault.underCode() && rig.vault.contains("BPA=1234"))
        assertEquals(prompts, host.prompts.size)

        // Locked: the code (the fingerprint goes up by itself; cancelled here).
        host.next = BiometricResult.Cancelled
        rig.controller.onBackground()
        settle(61_000)
        shot("lock")
        type("000000")
        shot("lock-wrong")
        type("123456")
        assertFalse(rig.controller.locked.value)
        assertFalse(rig.controller.keysLocked.value)
        shot("unlocked")

        // A send is confirmed with the code in a sheet.
        val confirmed = CoroutineScope(dispatcher).async { model.authorize("Transferir 300.00 CUP", null) }
        settle()
        shot("authorize")
        type("123456")
        assertTrue(confirmed.isCompleted && confirmed.getCompleted())

        // Forgotten: the fingerprint leads to a new code; the keys under the old one are gone.
        host.next = BiometricResult.Cancelled
        rig.controller.onBackground()
        settle(61_000)
        text("¿Olvidaste el código?").performClick()
        settle()
        shot("forgot")
        text("Entrar con la huella").performClick()
        settle()
        shot("forgot-create")
        type("111111")
        type("111111")
        assertFalse(rig.controller.locked.value)
        assertFalse(rig.vault.hasKeys())
        shot("forgot-done")
    }

    // ---- Helpers ----

    private fun settle(advance: Long = 0) {
        scheduler.advanceTimeBy(advance)
        repeat(8) {
            scheduler.runCurrent()
            if (::model.isInitialized) compose.waitForIdle()
        }
    }

    private fun onController(block: suspend () -> Unit) {
        CoroutineScope(dispatcher).launch { block() }
        settle()
    }

    private fun type(code: String) = code.forEach {
        node(hasText(it.toString()) and hasClickAction()).performClick()
        settle()
    }

    private fun shot(name: String) {
        compose.waitForIdle()
        captureScreenRoboImage("build/journey-code/${"%02d".format(++step)}-$name.png")
    }

    private fun node(matcher: SemanticsMatcher): SemanticsNodeInteraction = compose.onAllNodes(matcher, useUnmergedTree = false).onFirst()
    private fun text(value: String) = node(hasText(value)).also { runCatching { it.performScrollTo() } }

    private class FakeHost : Host {
        val prompts = mutableListOf<String>()
        var next: BiometricResult? = null
        override suspend fun biometric(title: String, subtitle: String?, cipher: Cipher?, screenLock: Boolean, fallback: Boolean): BiometricResult {
            prompts += title
            return next?.also { next = null } ?: BiometricResult.Passed(cipher)
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
