package dev.duardo.neotransfer.platform

import android.app.Application
import dev.duardo.neotransfer.BuildConfig
import dev.duardo.neotransfer.core.ledger.ActivityRow
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `/activity` rows go through a real cursor and come back as they were; each variant syncs with its own. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PeerSyncTest {
    @Test fun `release keeps its names, debug and preview carry their suffix in the permission and the peer's provider`() {
        assertEquals("dev.duardo.neotransfer.plus.sync", peerAuthority(""))
        assertEquals("dev.duardo.neotransfer.plus.debug.sync", peerAuthority(".debug"))
        assertEquals("dev.duardo.neotransfer.plus.preview.sync", peerAuthority(".preview"))
        // This variant's merged manifest (unit tests run as debug or preview).
        val suffix = BuildConfig.VARIANT_SUFFIX
        assertTrue(suffix == ".debug" || suffix == ".preview")
        assertEquals("dev.duardo.neotransfer$suffix", BuildConfig.APPLICATION_ID)
        val provider = RuntimeEnvironment.getApplication().packageManager.resolveContentProvider("${BuildConfig.APPLICATION_ID}.sync", 0)
        assertEquals("dev.duardo.neotransfer.permission.SYNC_CONTACTS$suffix", provider?.readPermission)
    }

    @Test fun `activity rows survive the cursor`() {
        val rows = listOf(
            ActivityRow("op-1", "CARD_TRANSFER", "CONFIRMED", "Ana Prueba", 1_792_000_000_123, "6000.00", "CUP", "OUT", "9204000000000001",
                "9204990000000015", "phone:0r1-2A"),
            ActivityRow("op-2", "ELECTRICITY", "NOT_DONE", "Casa", 1_791_999_000_000, "", "", "OUT", "", "", ""),
        )
        assertEquals(rows, activityCursor(rows).use(::activityRows))
    }
}
