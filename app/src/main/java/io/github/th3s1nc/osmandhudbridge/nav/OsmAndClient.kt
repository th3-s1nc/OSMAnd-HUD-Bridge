package io.github.th3s1nc.osmandhudbridge.nav

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import io.github.th3s1nc.osmandhudbridge.protocol.NavCommand
import net.osmand.aidlapi.IOsmAndAidlInterface
import net.osmand.aidlapi.OsmandAidlConstants
import net.osmand.aidlapi.navigation.ANavigationUpdateParams
import net.osmand.aidlapi.navigation.ANavigationVoiceRouterMessageParams

/** Bindet an die OSMAnd-AIDL-Schnittstelle und meldet Distanz + Abbiegetyp. Verbindet sich selbständig neu. */
class OsmAndClient(
    private val context: Context,
    private val handler: Handler,
    private val onNav: (meters: Int, turnType: Int) -> Unit,
    private val onStatus: (String) -> Unit,
    private val onArrived: () -> Unit = {},
    private val onVia: () -> Unit = {}
) {
    private var api: IOsmAndAidlInterface? = null
    private var wantRun = false
    private var bound = false
    private var callbackId = -1L

    private val callback = OsmAndCallback(
        OsmAndCallback.Listener { meters, turn -> handler.post { onNav(meters, turn) } },
        OsmAndCallback.VoiceListener { cmds, played ->
            // Zum Prüfen, ob OSMAnd hier die Kreisverkehr-Ausfahrt mitliefert
            handler.post { onStatus("OSMAnd-Ansage: cmds=$cmds played=$played") }
            if (OsmAndVoice.isDestinationReached(cmds)) handler.post { onArrived() }
            else if (OsmAndVoice.isViaReached(cmds)) handler.post { onVia() }
        }
    )

    private val retry = Runnable { tryBind() }
    private val bindCheck = Runnable {
        if (wantRun && bound && api == null) {
            onStatus("OSMAnd: keine Antwort, versuche erneut")
            rebind()
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            handler.removeCallbacks(bindCheck)
            api = IOsmAndAidlInterface.Stub.asInterface(service)
            try {
                callbackId = api?.registerForNavigationUpdates(ANavigationUpdateParams(), callback) ?: -1L
                try {
                    api?.registerForVoiceRouterMessages(ANavigationVoiceRouterMessageParams(), callback)
                } catch (e: Exception) {
                    onStatus("OSMAnd: Ansagen nicht verfügbar (${e.message})")
                }
                if (callbackId < 0) {
                    onStatus("OSMAnd: Bridge ist in OSMAnd nicht freigegeben (Callback-ID $callbackId). Pfeil kommt ersatzweise aus der Benachrichtigung")
                } else {
                    onStatus("OSMAnd: verbunden (Callback-ID $callbackId), Navigation in OSMAnd starten")
                }
            } catch (e: Exception) {
                onStatus("OSMAnd: Anmeldung fehlgeschlagen (${e.message})")
                rebind()
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            api = null
            onStatus("OSMAnd: Verbindung verloren")
            rebind()
        }

        override fun onBindingDied(name: ComponentName?) {
            api = null
            onStatus("OSMAnd: Binding beendet")
            rebind()
        }

        override fun onNullBinding(name: ComponentName?) {
            onStatus("OSMAnd: Schnittstelle liefert nichts")
            rebind()
        }
    }

    fun start() {
        wantRun = true
        tryBind()
    }

    fun stop() {
        wantRun = false
        handler.removeCallbacks(retry)
        handler.removeCallbacks(bindCheck)
        try {
            val p = ANavigationUpdateParams()
            p.setSubscribeToUpdates(false)
            p.setCallbackId(callbackId)
            api?.registerForNavigationUpdates(p, callback)
        } catch (_: Exception) {}
        unbind()
    }

    private fun tryBind() {
        if (!wantRun || bound) return
        for (pkg in PACKAGES) {
            for (action in ACTIONS) {
                val ok = try {
                    context.bindService(
                        Intent(action).setPackage(pkg), connection, Context.BIND_AUTO_CREATE
                    )
                } catch (e: Exception) {
                    false
                }
                if (ok) {
                    bound = true
                    onStatus("OSMAnd: verbinde ($pkg)")
                    handler.postDelayed(bindCheck, 10_000)
                    return
                }
                try { context.unbindService(connection) } catch (_: Exception) {}
            }
        }
        onStatus("OSMAnd nicht erreichbar, neuer Versuch in 10 s")
        handler.postDelayed(retry, 10_000)
    }

    private fun unbind() {
        if (bound) {
            try { context.unbindService(connection) } catch (_: Exception) {}
            bound = false
        }
        api = null
    }

    private fun rebind() {
        unbind()
        handler.removeCallbacks(retry)
        if (wantRun) handler.postDelayed(retry, 5_000)
    }

    private companion object {
        val PACKAGES = listOf("net.osmand.plus", "net.osmand", "net.osmand.dev")
        val ACTIONS = listOf("net.osmand.aidl.OsmandAidlServiceV2", "net.osmand.aidl.OsmandAidlService")
    }
}

/** OSMAnd-Abbiegetyp -> HUD-Pfeil. null = Pfeil leeren. Zuordnung der leichten Kurven ist eine Annahme, am Gerät prüfen. */
object OsmAndTurns {
    fun toHudCommand(type: Int): NavCommand? = when (type) {
        OsmandAidlConstants.TURN_TYPE_C -> NavCommand.STRAIGHT
        OsmandAidlConstants.TURN_TYPE_TL -> NavCommand.TURN_LEFT
        // "halb/leicht links" (OSMAnd Typ 3): sanfte Richtungsänderung, das HUD-Symbol "links halten" passt besser als ein Abbiegepfeil
        OsmandAidlConstants.TURN_TYPE_TSLL -> NavCommand.KEEP_LEFT
        OsmandAidlConstants.TURN_TYPE_TSHL -> NavCommand.SHARP_LEFT
        OsmandAidlConstants.TURN_TYPE_TR -> NavCommand.TURN_RIGHT
        // "halb/leicht rechts" (OSMAnd Typ 6, Ansage right_sl)
        OsmandAidlConstants.TURN_TYPE_TSLR -> NavCommand.KEEP_RIGHT
        OsmandAidlConstants.TURN_TYPE_TSHR -> NavCommand.SHARP_RIGHT
        OsmandAidlConstants.TURN_TYPE_KL -> NavCommand.KEEP_LEFT
        OsmandAidlConstants.TURN_TYPE_KR -> NavCommand.KEEP_RIGHT
        OsmandAidlConstants.TURN_TYPE_TU -> NavCommand.UTURN_LEFT
        OsmandAidlConstants.TURN_TYPE_TRU -> NavCommand.UTURN_RIGHT
        OsmandAidlConstants.TURN_TYPE_RNDB -> NavCommand.ROUNDABOUT
        OsmandAidlConstants.TURN_TYPE_RNLB -> NavCommand.ROUNDABOUT_LEFT
        else -> null
    }
}
