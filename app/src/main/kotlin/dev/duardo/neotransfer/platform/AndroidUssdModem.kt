package dev.duardo.neotransfer.platform

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telephony.ServiceState
import android.telephony.SubscriptionManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import dev.duardo.neotransfer.core.protocol.UssdCommand
import dev.duardo.neotransfer.core.protocol.UssdTransport
import dev.duardo.neotransfer.engine.UssdModem
import dev.duardo.neotransfer.engine.UssdReply
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

/**
 * One request at a time is the controller's job; this class only talks to the modem. A reply arriving after the caller
 * stopped waiting is dropped.
 *
 * The SIM ([sim]). A bank answers only the line registered with it, and the phone may hold two: a request leaves
 * through the SIM of calls (the default voice subscription) when the bank answered on it before, otherwise through the
 * newest SIM it answered on that is still in the phone. Knowing none, through the SIM of calls; when the phone asks
 * which SIM on every call, that is the one the system picks for a request, and its own question for a dialed one.
 *
 * Nothing goes out in airplane mode or without network service: the request fails at once, sent to no one. Both are
 * read without permissions: `Settings.Global.AIRPLANE_MODE_ON`, and the voice service state the system pushes to a
 * [TelephonyCallback.ServiceStateListener] (without location permissions it comes with its location fields cleared,
 * which are not read). A SIM is followed from its first use; the SIM of calls of when the app started, from then.
 */
class AndroidUssdModem(context: Context) : UssdModem {
    private val context = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    /** The voice service state the system last pushed for each SIM followed; [UNKNOWN] until it pushes one, and then nothing is assumed. */
    private val service = ConcurrentHashMap<Int, Int>()

    init {
        watch(sim(emptyList()))
    }

    override suspend fun request(command: UssdCommand, lines: List<Int>): UssdReply {
        require(command.transport == UssdTransport.REQUEST)
        val sim = sim(lines)
        watch(sim)
        unavailable(sim)?.let { return UssdReply.Failure(it) }
        return send(command, sim)
    }

    /**
     * The subscription a request goes through, given the SIMs its bank answered on ([lines], newest last). An invalid
     * id when the phone names no SIM of calls and none of [lines] is in it: the system's own choice then.
     */
    internal fun sim(lines: List<Int>): Int =
        chooseSim(lines, SubscriptionManager.getDefaultVoiceSubscriptionId()) { SubscriptionManager.getSlotIndex(it) != SubscriptionManager.INVALID_SIM_SLOT_INDEX }

    private fun watch(sim: Int) {
        if (service.putIfAbsent(sim, UNKNOWN) != null) return
        telephony(sim).registerTelephonyCallback(context.mainExecutor, object : TelephonyCallback(), TelephonyCallback.ServiceStateListener {
            override fun onServiceStateChanged(serviceState: ServiceState) {
                service[sim] = serviceState.state
            }
        })
    }

    /** Why nothing can be sent through [sim] now, ready to show; null when it has service or the phone does not say. */
    internal fun unavailable(sim: Int = sim(emptyList())): String? = when {
        Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) != 0 -> "Desactiva el modo avión. No se envió nada."
        service[sim].let { it == ServiceState.STATE_OUT_OF_SERVICE || it == ServiceState.STATE_EMERGENCY_ONLY || it == ServiceState.STATE_POWER_OFF } ->
            "Sin servicio. No se envió nada."
        else -> null
    }

    private suspend fun send(command: UssdCommand, sim: Int): UssdReply = suspendCancellableCoroutine { continuation ->
        fun answer(reply: UssdReply) { if (continuation.isActive) continuation.resume(reply) }
        try {
            telephony(sim).sendUssdRequest(command.valueForTransport(), object : TelephonyManager.UssdResponseCallback() {
                override fun onReceiveUssdResponse(manager: TelephonyManager, request: String, response: CharSequence) =
                    answer(UssdReply.Text(response.toString()))

                override fun onReceiveUssdResponseFailed(manager: TelephonyManager, request: String, failureCode: Int) =
                    answer(UssdReply.Failure(if (failureCode == TelephonyManager.USSD_ERROR_SERVICE_UNAVAIL) "El servicio USSD no está disponible" else "La red no aceptó la solicitud"))
            }, handler)
        } catch (_: SecurityException) {
            answer(UssdReply.Failure("Falta el permiso de llamadas"))
        } catch (_: RuntimeException) {
            answer(UssdReply.Failure("El teléfono no pudo enviar la solicitud"))
        }
    }

    /**
     * `ACTION_CALL` with `#` encoded; it opens the system's USSD dialog, so call it with the app in front. A SIM the
     * bank answered on is named to the system, which then dials through it without asking.
     */
    override fun dial(command: UssdCommand, lines: List<Int>): String? {
        require(command.transport == UssdTransport.DIAL)
        val sim = sim(lines)
        watch(sim)
        unavailable(sim)?.let { return it }
        val uri = Uri.parse("tel:" + command.valueForTransport().replace("#", Uri.encode("#")))
        val intent = Intent(Intent.ACTION_CALL, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (sim in lines) intent.putExtra(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, PhoneAccountHandle(TELEPHONY, sim.toString()))
        return try {
            context.startActivity(intent)
            null
        } catch (_: SecurityException) {
            "Falta el permiso de llamadas"
        } catch (_: RuntimeException) {
            "El teléfono no pudo enviar la solicitud"
        }
    }

    private fun telephony(sim: Int): TelephonyManager {
        val manager = context.getSystemService(TelephonyManager::class.java)
        return if (SubscriptionManager.isValidSubscriptionId(sim)) manager.createForSubscriptionId(sim) else manager
    }

    private companion object {
        /** No state pushed yet; none of [ServiceState]'s. */
        const val UNKNOWN = -1

        /**
         * The system's telephony, whose phone account of each SIM is named by its subscription id (seen with
         * `dumpsys telecom` on Android 16). Listing the accounts instead would take `READ_PHONE_STATE`; an account the
         * system does not know is ignored, and it asks or uses the SIM of calls as without it.
         */
        val TELEPHONY = ComponentName("com.android.phone", "com.android.services.telephony.TelephonyConnectionService")
    }
}

/**
 * The SIM for a request whose bank answered on [lines] (newest last): the SIM of [calls] when it is one of them,
 * otherwise the newest of them [inPhone]; none, the SIM of calls, an invalid id when the phone asks on every call.
 */
internal fun chooseSim(lines: List<Int>, calls: Int, inPhone: (Int) -> Boolean): Int = if (calls in lines) calls else lines.lastOrNull(inPhone) ?: calls
