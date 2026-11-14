package dev.duardo.neotransfer.engine

import dev.duardo.neotransfer.core.ledger.OperationState
import dev.duardo.neotransfer.core.model.Bank
import dev.duardo.neotransfer.core.model.OwnCard
import dev.duardo.neotransfer.data.WalletCard
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The app code through the controller: waits, the erase, keys under the code and back, and phones that cannot save keys. */
@OptIn(ExperimentalCoroutinesApi::class)
class AppCodeControllerTest {
    private val start = Instant.parse("2026-11-14T14:00:00Z")
    private val bpa = WalletCard(OwnCard("9205000000000001", Bank.BPA))

    private fun TestScope.now(): Instant = start.plusMillis(testScheduler.currentTime)
    private fun TestScope.wait(duration: Duration) { advanceTimeBy(duration.toMillis()); runCurrent() }

    private fun TestScope.rig(store: MemoryStore = MemoryStore(), vault: FakeVault = FakeVault(), code: FakeAppCode = FakeAppCode { testScheduler.currentTime }): Rig {
        val dispatcher = StandardTestDispatcher(testScheduler)
        return Rig(store, FakeInbox(), vault, { now() }, dispatcher, dispatcher, code = code)
    }

    private suspend fun Rig.ready() {
        controller.unlock(controller.unlockCipher())
        controller.saveKey(controller.keyCipher(), Bank.BPA, "1234".toCharArray())
        controller.saveCard(bpa)
    }

    private suspend fun Rig.enter(code: String) = controller.enterCode(code.toCharArray())

    private fun TestScope.lock(rig: Rig) {
        rig.controller.onBackground()
        wait(AppController.LOCK_AFTER.plusSeconds(1))
        assertTrue(rig.controller.locked.value)
    }

    @Test fun `waits start at the 5th wrong code in a row, 30 s then doubling, and a right one clears them`() = runTest {
        val rig = rig()
        rig.ready()
        rig.controller.createCode("246813".toCharArray())
        repeat(4) { assertEquals(CodeEntry.Wrong(it + 1, null, null, false), rig.enter("000000")) }
        assertEquals(CodeEntry.Wrong(5, Duration.ofSeconds(30), null, false), rig.enter("000000"))
        // During the wait nothing is checked, not even the right code.
        wait(Duration.ofSeconds(10))
        assertEquals(CodeEntry.Waiting(Duration.ofSeconds(20)), rig.enter("246813"))
        wait(Duration.ofSeconds(20))
        assertEquals(CodeEntry.Wrong(6, Duration.ofSeconds(60), null, false), rig.enter("000000"))
        wait(Duration.ofSeconds(60))
        assertEquals(CodeEntry.Wrong(7, Duration.ofSeconds(120), null, false), rig.enter("000000"))
        wait(Duration.ofSeconds(120))
        assertEquals(CodeEntry.Right(), rig.enter("246813"))
        assertEquals(CodeAttempts(), rig.code.attempts())
        assertEquals(listOf(null, null, null, null, Duration.ofSeconds(30), Duration.ofMinutes(1), Duration.ofMinutes(2), Duration.ofMinutes(4)),
            (1..8).map(AppController::codeWait))
        // Doubling stops at an hour: 32 min at the 11th, then 1 h for good.
        assertEquals(listOf(Duration.ofMinutes(32), Duration.ofHours(1), Duration.ofHours(1)), listOf(11, 12, 40).map(AppController::codeWait))
    }

    @Test fun `without the keys under the code, ten wrong codes only make it wait`() = runTest {
        val vault = FakeVault()
        val rig = rig(vault = vault)
        rig.ready()
        rig.controller.createCode("246813".toCharArray())
        repeat(10) {
            rig.code.attempts().wait?.let { wait(it) }
            val entry = assertIs<CodeEntry.Wrong>(rig.enter("000000"))
            assertNull(entry.left)
            assertFalse(entry.erased)
        }
        assertTrue(vault.contains("BPA=1234"))
    }

    @Test fun `with the keys under the code, the 8th and 9th wrong codes warn and the 10th erases them and nothing else`() = runTest {
        val vault = FakeVault()
        val rig = rig(vault = vault)
        rig.ready()
        rig.controller.createCode("246813".toCharArray())
        rig.controller.protectKeys(true, null)
        val entries = (1..10).map {
            rig.code.attempts().wait?.let { wait(it) }
            assertIs<CodeEntry.Wrong>(rig.enter("000000"))
        }
        assertEquals(listOf(null, null, null, null, null, null, null, 2, 1, null), entries.map { it.left })
        assertEquals(listOf(true), entries.filter { it.erased }.map { it.failures == 10 })
        assertFalse(vault.hasKeys())
        assertTrue(rig.controller.savedKeys.value.isEmpty())
        assertEquals(listOf(bpa.card.number), rig.controller.cards.value.map { it.card.card.number }) // the rest stays
        assertTrue(rig.controller.code.value.active)
    }

    @Test fun `keys go under the code and back without being lost, and the code then opens them`() = runTest {
        val vault = FakeVault()
        val rig = rig(vault = vault)
        rig.ready()
        rig.controller.createCode("246813".toCharArray())
        rig.controller.protectKeys(true, null)
        assertTrue(vault.underCode() && vault.contains("BPA=1234"))
        assertEquals(CodeStatus(active = true, keysUnderCode = true), rig.controller.code.value)
        assertNull(rig.controller.keyCipher()) // saving goes by the code now, no prompt
        rig.controller.saveKey(null, Bank.BANDEC, "12345".toCharArray())
        assertTrue(vault.underCode() && vault.contains("BANDEC=12345"))

        lock(rig)
        assertNull(rig.controller.unlockCipher()) // the fingerprint does not open keys under the code
        assertEquals(CodeEntry.Right(), rig.enter("246813"))
        assertFalse(rig.controller.locked.value)
        assertFalse(rig.controller.keysLocked.value)

        rig.controller.protectKeys(false, rig.controller.phoneKeyCipher())
        assertTrue(!vault.underCode() && vault.contains("BPA=1234") && vault.contains("BANDEC=12345"))
        assertFalse(rig.controller.code.value.keysUnderCode)
    }

    @Test fun `unlocked with the fingerprint, keys under the code wait for it and a queued request says so`() = runTest {
        val vault = FakeVault()
        val rig = rig(vault = vault)
        rig.ready()
        rig.controller.createCode("246813".toCharArray())
        rig.controller.protectKeys(true, null)
        lock(rig)
        assertTrue(rig.controller.unlockWithFingerprint(requireNotNull(rig.controller.fingerprintCipher())))
        assertTrue(rig.controller.keysLocked.value)
        assertIs<Submission.Queued>(rig.controller.refreshBalance(bpa.card.number))
        assertTrue(rig.controller.keysWanted.value)
        wait(Duration.ofSeconds(5))
        assertTrue(rig.modem.sent.isEmpty())
        assertEquals(CodeEntry.Right(), rig.enter("246813"))
        wait(Duration.ofSeconds(5))
        assertEquals(listOf(40, 46), rig.modem.services)
    }

    @Test fun `changing the code moves the keys under the new one`() = runTest {
        val vault = FakeVault()
        val rig = rig(vault = vault)
        rig.ready()
        rig.controller.createCode("246813".toCharArray())
        rig.controller.protectKeys(true, null)
        assertEquals(CodeEntry.Right(), rig.enter("246813"))
        rig.controller.createCode("135792".toCharArray())
        lock(rig)
        assertIs<CodeEntry.Wrong>(rig.enter("246813"))
        assertEquals(CodeEntry.Right(), rig.enter("135792"))
        assertFalse(rig.controller.keysLocked.value)
        assertTrue(vault.contains("BPA=1234"))
    }

    @Test fun `a forgotten code under which the keys were is replaced after the fingerprint, and those keys are gone`() = runTest {
        val vault = FakeVault()
        val rig = rig(vault = vault)
        rig.ready()
        rig.controller.createCode("246813".toCharArray())
        rig.controller.protectKeys(true, null)
        lock(rig)
        assertFailsWith<IllegalStateException> { rig.controller.createCode("111111".toCharArray()) }
        assertTrue(rig.controller.forgotCode(requireNotNull(rig.controller.fingerprintCipher(withKeys = false))))
        assertTrue(rig.controller.createCode("111111".toCharArray()))
        assertFalse(rig.controller.locked.value)
        assertFalse(vault.hasKeys())
        assertTrue(rig.controller.code.value.active)
    }

    @Test fun `turning the code off puts the keys back under the phone's lock, or erases them without one`() = runTest {
        val vault = FakeVault()
        val rig = rig(vault = vault)
        rig.ready()
        rig.controller.createCode("246813".toCharArray())
        rig.controller.protectKeys(true, null)
        assertFalse(rig.controller.disableCode(rig.controller.phoneKeyCipher()))
        assertTrue(!vault.underCode() && vault.contains("BPA=1234"))
        assertFalse(rig.controller.code.value.active)

        val bare = FakeVault()
        val other = rig(vault = bare)
        other.ready()
        other.controller.createCode("246813".toCharArray())
        other.controller.protectKeys(true, null)
        bare.lock = PhoneLock.NONE // the screen lock was removed
        assertTrue(other.controller.disableCode(null))
        assertFalse(bare.hasKeys())
    }

    @Test fun `without a screen lock the keys under the phone cannot stay when the code stops guarding them`() = runTest {
        val vault = FakeVault(PhoneLock.NONE)
        val rig = rig(vault = vault)
        rig.controller.unlock(null)
        rig.controller.createCode("246813".toCharArray())
        rig.controller.protectKeys(true, null)
        rig.controller.saveKey(null, Bank.BPA, "1234".toCharArray()) // under the code, no lock needed
        assertTrue(vault.underCode())
        assertEquals(AppController.NO_LOCK, assertFailsWith<IllegalStateException> { rig.controller.protectKeys(false, null) }.message)
        assertTrue(vault.underCode() && rig.controller.code.value.keysUnderCode)
    }

    @Test fun `without any lock a key typed for a send goes with it and is gone after`() = runTest {
        val vault = FakeVault(PhoneLock.NONE)
        val rig = rig(vault = vault)
        rig.controller.unlock(null)
        rig.controller.saveCard(bpa)
        assertEquals(AppController.NO_LOCK, assertFailsWith<IllegalStateException> { rig.controller.keyCipher() }.message)
        assertEquals(Submission.KeyRequired(Bank.BPA), rig.controller.refreshBalance(bpa.card.number))
        rig.controller.useKey(Bank.BPA, "1234".toCharArray())
        val id = assertIs<Submission.Queued>(rig.controller.refreshBalance(bpa.card.number)).id
        wait(Duration.ofSeconds(5))
        assertEquals(listOf(40, 46), rig.modem.services)
        assertIs<OperationState.Sent>(rig.state(id))
        assertFalse(vault.hasKeys())
        assertTrue(rig.controller.savedKeys.value.isEmpty())
        // The next send needs it typed again.
        assertEquals(Submission.KeyRequired(Bank.BPA), rig.controller.refreshBalance(bpa.card.number))
    }

    @Test fun `a wrong code past the 10th still erases the keys under the code, should the process have died at the 10th`() = runTest {
        val vault = FakeVault()
        val rig = rig(vault = vault)
        rig.ready()
        rig.controller.createCode("246813".toCharArray())
        rig.controller.protectKeys(true, null)
        rig.code.saveAttempts(CodeAttempts(10)) // the 10th was counted, then the process died before erasing
        val entry = assertIs<CodeEntry.Wrong>(rig.enter("000000"))
        assertEquals(11, entry.failures)
        assertTrue(entry.erased)
        assertFalse(vault.hasKeys())
    }

    @Test fun `a right code whose keys no longer open erases them and opens, it never fails every launch`() = runTest {
        val vault = FakeVault()
        val rig = rig(vault = vault)
        rig.ready()
        rig.controller.createCode("246813".toCharArray())
        rig.controller.protectKeys(true, null)
        vault.encrypt(CodeKey("999999".toByteArray()), "BPA=1234".toByteArray()) // under another key: a change cut short
        lock(rig)
        assertEquals(CodeEntry.Right(keysErased = true), rig.enter("246813"))
        assertFalse(rig.controller.locked.value)
        assertFalse(vault.hasKeys())
        assertTrue(rig.controller.savedKeys.value.isEmpty())
    }

    @Test fun `a change cut short before the new code is set leaves the old one opening the keys`() = runTest {
        val vault = FakeVault()
        val code = FakeAppCode { testScheduler.currentTime }
        val rig = rig(vault = vault, code = code)
        rig.ready()
        rig.controller.createCode("246813".toCharArray())
        rig.controller.protectKeys(true, null)
        // Staged under the new code, which was not set yet: the old code still opens what is saved.
        vault.stage(code.prepare("135792".toCharArray()).key, "BPA=1234".toByteArray())
        lock(rig)
        assertEquals(CodeEntry.Right(), rig.enter("246813"))
        assertTrue(vault.underCode() && vault.contains("BPA=1234"))
    }

    @Test fun `a change cut short after the new code was set opens the keys with the new code, nothing is lost`() = runTest {
        val vault = FakeVault()
        val code = FakeAppCode { testScheduler.currentTime }
        val rig = rig(vault = vault, code = code)
        rig.ready()
        rig.controller.createCode("246813".toCharArray())
        rig.controller.protectKeys(true, null)
        // Staged and committed, and the process died before the staged blob became current.
        val prepared = code.prepare("135792".toCharArray())
        vault.stage(prepared.key, BankKeys.encode(mapOf(Bank.BPA to "1234".toCharArray())))
        code.commit(prepared)
        lock(rig)
        assertIs<CodeEntry.Wrong>(rig.enter("246813"))
        assertEquals(CodeEntry.Right(), rig.enter("135792"))
        assertFalse(rig.controller.keysLocked.value)
        assertTrue(vault.contains("BPA=1234"))
        lock(rig)
        assertEquals(CodeEntry.Right(), rig.enter("135792")) // current now
    }

    @Test fun `any other failure opening the keys under the code keeps them, locked`() = runTest {
        val vault = FakeVault()
        val rig = rig(vault = vault)
        rig.ready()
        rig.controller.createCode("246813".toCharArray())
        rig.controller.protectKeys(true, null)
        lock(rig)
        vault.failure = IllegalStateException("No se pudo leer")
        assertEquals(CodeEntry.Right(), rig.enter("246813"))
        assertFalse(rig.controller.locked.value)
        assertTrue(rig.controller.keysLocked.value)
        assertTrue(vault.contains("BPA=1234"))
        lock(rig)
        assertEquals(CodeEntry.Right(), rig.enter("246813"))
        assertFalse(rig.controller.keysLocked.value)
    }

    @Test fun `where the keys are decides how they are saved, not the setting a toggle cut short left behind`() = runTest {
        val vault = FakeVault()
        val code = FakeAppCode { testScheduler.currentTime }
        val rig = rig(vault = vault, code = code)
        rig.ready()
        rig.controller.createCode("246813".toCharArray())
        code.setProtectsKeys(true) // the setting changed, the keys never moved
        assertFalse(rig.controller.code.value.keysUnderCode)
        rig.controller.saveKey(rig.controller.keyCipher(), Bank.BANDEC, "12345".toCharArray())
        assertTrue(!vault.underCode() && vault.contains("BANDEC=12345"))
        // Turning the code off with a screen lock never erases them.
        assertFailsWith<IllegalStateException> {
            rig.controller.protectKeys(true, null)
            rig.controller.disableCode(null)
        }
        assertTrue(vault.hasKeys())
    }
}
