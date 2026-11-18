package dev.duardo.neotransfer.platform

import android.app.Application
import android.os.Looper
import android.provider.Settings
import android.telephony.ServiceState
import android.telephony.TelephonyManager
import dev.duardo.neotransfer.core.model.Bank
import dev.duardo.neotransfer.core.protocol.BalanceQuery
import dev.duardo.neotransfer.engine.UssdReply
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Airplane mode or no network service: the request fails at once, before anything reaches the modem. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AndroidUssdModemTest {
    private val context = RuntimeEnvironment.getApplication()
    private val command = BalanceQuery(Bank.BPA).commands().single()

    private fun service(state: Int) {
        shadowOf(context.getSystemService(TelephonyManager::class.java)).setServiceState(ServiceState().apply { setState(state) })
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun `airplane mode and no service say so and send nothing`() = runBlocking {
        val modem = AndroidUssdModem(context)
        assertNull(modem.unavailable()) // nothing known yet: nothing is assumed
        Settings.Global.putInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 1)
        assertEquals(UssdReply.Failure("Desactiva el modo avión. No se envió nada."), modem.request(command))
        Settings.Global.putInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0)
        service(ServiceState.STATE_OUT_OF_SERVICE)
        assertEquals(UssdReply.Failure("Sin servicio. No se envió nada."), modem.request(command))
        service(ServiceState.STATE_EMERGENCY_ONLY)
        assertEquals("Sin servicio. No se envió nada.", modem.unavailable())
        service(ServiceState.STATE_IN_SERVICE)
        assertNull(modem.unavailable())
    }

    @Test fun `a request leaves through the SIM of calls when its bank answered on it, otherwise through the newest SIM it answered on`() {
        val inPhone = setOf(3, 5)::contains
        val asks = -1 // the phone asks which SIM on every call
        assertEquals(3, chooseSim(listOf(3), asks, inPhone))
        assertEquals(3, chooseSim(listOf(3), calls = 5, inPhone)) // the SIM of calls is not the one the bank knows
        assertEquals(5, chooseSim(listOf(5, 3), calls = 5, inPhone)) // it knows both: the SIM of calls
        assertEquals(3, chooseSim(listOf(5, 3), asks, inPhone)) // the last it answered on
        assertEquals(5, chooseSim(listOf(5, 2), asks, inPhone)) // SIM 2 left the phone
        assertEquals(5, chooseSim(listOf(2), calls = 5, inPhone))
        assertEquals(asks, chooseSim(emptyList(), asks, inPhone)) // nothing known: the system's own choice
    }
}
