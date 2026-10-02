package io.github.th3s1nc.osmandhudbridge.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import io.github.th3s1nc.osmandhudbridge.BridgeBus
import io.github.th3s1nc.osmandhudbridge.protocol.HudProtocol
import io.github.th3s1nc.osmandhudbridge.protocol.HudMessage
import java.util.ArrayDeque
import java.util.Calendar

/**
 * Robuste BLE-Verbindung zum HUD. Alles läuft auf dem Main-Thread.
 *  - GATT-Operationen laufen strikt nacheinander durch eine Warteschlange
 *  - automatisches Neuverbinden (Backoff, danach Hintergrundverbindung per autoConnect)
 *  - Stop-and-wait: pro Nachricht ein Ack des HUD, 400 ms Timeout, Wiederholung,
 *    nach 5 Fehlversuchen in Folge gilt die Verbindung als tot und wird neu aufgebaut
 */
@SuppressLint("MissingPermission")
class HudClient(private val context: Context, private val listener: Listener) {

    interface Listener {
        fun onStateChanged(state: State, detail: String)
        fun onReady()
        fun onLinkLost()
    }

    enum class State { STOPPED, WAITING, CONNECTING, SETTING_UP, READY }

    private class Item(val msg: HudMessage, val onDone: (() -> Unit)? = null)

    private val handler = Handler(Looper.getMainLooper())
    private val adapter: BluetoothAdapter? =
        context.getSystemService(BluetoothManager::class.java)?.adapter

    var state: State = State.STOPPED
        private set
    val isReady: Boolean get() = state == State.READY

    /** Anzeigemodus, der beim Verbinden aktiviert wird (der Controller hält ihn aktuell). */
    @Volatile var mode: io.github.th3s1nc.osmandhudbridge.protocol.DisplayMode = io.github.th3s1nc.osmandhudbridge.protocol.DisplayMode.NAVIGATOR

    private var address: String? = null
    private var wantRun = false
    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var attempt = 0

    // Beschreiben der CCCDs (strikt nacheinander)
    private val descQueue = ArrayDeque<() -> Boolean>()
    private var descBusy = false

    // Nachrichten (Stop-and-wait)
    private val msgQueue = ArrayDeque<Item>()
    private var inFlight: Item? = null
    private var frames: List<ByteArray> = emptyList()
    private var frameIdx = 0
    private var tid = 0
    private var txCounter = 0
    private var failures = 0
    private var waitingAck = false

    // ---------------- öffentliche API ----------------

    fun start(addr: String) {
        wantRun = true
        address = addr
        attempt = 0
        connectNow()
    }

    fun stop() {
        wantRun = false
        handler.removeCallbacksAndMessages(null)
        teardown()
        setState(State.STOPPED, "gestoppt")
    }

    fun onBluetoothChanged(on: Boolean) {
        if (!wantRun) return
        handler.removeCallbacksAndMessages(null)
        if (on) {
            attempt = 0
            connectNow()
        } else {
            val wasReady = state == State.READY
            teardown()
            setState(State.WAITING, "Bluetooth ist aus")
            if (wasReady) listener.onLinkLost()
        }
    }

    /** Reiht Nachrichten ein. false = nicht gesendet (nicht verbunden / Warteschlange voll). */
    fun send(msgs: List<HudMessage>): Boolean {
        if (state != State.READY || msgs.isEmpty()) return false
        if (msgQueue.size + msgs.size > MAX_QUEUE) {
            BridgeBus.log("HUD-Warteschlange voll, Update zurückgestellt")
            return false
        }
        for (m in msgs) msgQueue.addLast(Item(m))
        pumpMessages()
        return true
    }

    // ---------------- Verbindungsaufbau ----------------

    private val retryRunnable = Runnable { connectNow() }
    private val connectTimeout = Runnable {
        if (state == State.CONNECTING) failAndRetry("Zeitüberschreitung beim Verbinden")
    }

    private fun connectNow() {
        if (!wantRun) return
        val ad = adapter
        if (ad == null || !ad.isEnabled) {
            setState(State.WAITING, "Bluetooth ist aus")
            return
        }
        val addr = address ?: return
        teardown()
        val dev: BluetoothDevice = try {
            ad.getRemoteDevice(addr)
        } catch (e: IllegalArgumentException) {
            setState(State.WAITING, "ungültige HUD-Adresse")
            return
        }
        // Erste Versuche direkt (schnell), danach Hintergrundverbindung: Android verbindet
        // selbst, sobald das HUD wieder in Reichweite / eingeschaltet ist.
        val auto = attempt >= 3
        setState(
            State.CONNECTING,
            if (auto) "warte auf HUD (Hintergrundverbindung)" else "verbinde (Versuch ${attempt + 1})"
        )
        gatt = dev.connectGatt(context, auto, callback, BluetoothDevice.TRANSPORT_LE)
        if (!auto) handler.postDelayed(connectTimeout, CONNECT_TIMEOUT_MS)
    }

    private fun failAndRetry(reason: String) {
        if (!wantRun) return
        val wasReady = state == State.READY
        teardown()
        attempt++
        val delay = RETRY_DELAYS[minOf(attempt - 1, RETRY_DELAYS.size - 1)]
        setState(State.WAITING, "$reason, neuer Versuch in ${delay / 1000}s")
        if (wasReady) listener.onLinkLost()
        handler.removeCallbacks(retryRunnable)
        handler.postDelayed(retryRunnable, delay)
    }

    private fun teardown() {
        handler.removeCallbacks(connectTimeout)
        handler.removeCallbacks(descTimeout)
        handler.removeCallbacks(ackTimeout)
        handler.removeCallbacks(writeTimeout)
        descQueue.clear()
        descBusy = false
        msgQueue.clear()
        inFlight = null
        frames = emptyList()
        frameIdx = 0
        failures = 0
        waitingAck = false
        writeChar = null
        gatt?.let {
            try { it.disconnect() } catch (_: Exception) {}
            try { it.close() } catch (_: Exception) {}
        }
        gatt = null
    }

    private fun setState(s: State, detail: String) {
        state = s
        BridgeBus.hud = "HUD: $detail"
        BridgeBus.log("HUD: $detail")
        listener.onStateChanged(s, detail)
    }

    // ---------------- GATT-Callbacks (Binder-Thread -> Main-Thread) ----------------

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            handler.post {
                if (g !== gatt) {
                    try { g.close() } catch (_: Exception) {}
                    return@post
                }
                if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                    handler.removeCallbacks(connectTimeout)
                    setState(State.SETTING_UP, "verbunden, suche Dienste")
                    // kurze Pause: bei gekoppeltem Gerät startet Android gerade die Verschlüsselung
                    handler.postDelayed({ if (g === gatt) g.discoverServices() }, 500)
                } else {
                    failAndRetry("Verbindung getrennt (status=$status)")
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            handler.post {
                if (g !== gatt) return@post
                val service = g.getService(HudUuids.SERVICE)
                val wc = service?.getCharacteristic(HudUuids.WRITE)
                if (status != BluetoothGatt.GATT_SUCCESS || service == null || wc == null) {
                    refreshCache(g)
                    failAndRetry("HUD-Dienst nicht gefunden (status=$status)")
                    return@post
                }
                writeChar = wc
                beginSetup(g, service)
            }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            handler.post { if (g === gatt) onFrameWritten(status) }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            handler.post {
                if (g !== gatt) return@post
                if (status != BluetoothGatt.GATT_SUCCESS) BridgeBus.log("CCCD-Schreiben status=$status")
                handler.removeCallbacks(descTimeout)
                descBusy = false
                pumpDescriptors()
            }
        }

        // Android 13+
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            handler.post { onNotify(g, value) }
        }

        // Android 12 und älter
        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            val v = c.value ?: return
            handler.post { onNotify(g, v) }
        }
    }

    private fun onNotify(g: BluetoothGatt, v: ByteArray) {
        if (g !== gatt) return
        val ack = HudProtocol.parseAck(v) ?: return
        val cur = inFlight ?: return
        if (!waitingAck || ack.transactionId != tid) return // veraltete Quittung
        handler.removeCallbacks(ackTimeout)
        waitingAck = false
        if (ack.ok) {
            failures = 0
            finishMessage(cur)
        } else {
            BridgeBus.log("HUD: Nachricht abgelehnt (NACK), sende erneut")
            attemptFailed()
        }
    }

    // ---------------- Einrichtung ----------------

    private fun beginSetup(g: BluetoothGatt, service: BluetoothGattService) {
        txCounter = 0
        for (uuid in listOf(HudUuids.NOTIFY, HudUuids.NOTIFY2)) {
            val c = service.getCharacteristic(uuid) ?: continue
            val cccd = c.getDescriptor(HudUuids.CCCD) ?: continue
            descQueue.addLast { enableNotify(g, c, cccd) }
        }
        descQueue.addLast {
            // alle CCCDs fertig: Handshake, danach bereit
            val cal = Calendar.getInstance()
            val hs = HudProtocol.handshake(cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), mode)
            hs.forEachIndexed { i, m ->
                msgQueue.addLast(Item(m, if (i == hs.lastIndex) ({ becomeReady() }) else null))
            }
            pumpMessages()
            true.also { descBusy = false }
        }
        pumpDescriptors()
    }

    private fun pumpDescriptors() {
        while (!descBusy) {
            val op = descQueue.pollFirst() ?: return
            val isLast = descQueue.isEmpty()
            descBusy = true
            val ok = try { op() } catch (e: Exception) {
                BridgeBus.log("BLE-Fehler: ${e.message}"); false
            }
            if (!ok) {
                failAndRetry("Einrichtung fehlgeschlagen")
                return
            }
            if (isLast) return
            handler.postDelayed(descTimeout, DESC_TIMEOUT_MS)
            return
        }
    }

    private val descTimeout = Runnable {
        BridgeBus.log("BLE: CCCD-Antwort ausgeblieben")
        descBusy = false
        pumpDescriptors()
    }

    private fun becomeReady() {
        attempt = 0
        setState(State.READY, "verbunden und bereit")
        handler.post { listener.onReady() }
    }

    // ---------------- Nachrichten: Stop-and-wait ----------------

    private fun pumpMessages() {
        if (inFlight != null) return
        if (state != State.SETTING_UP && state != State.READY) return
        val item = msgQueue.pollFirst() ?: return
        inFlight = item
        tid = txCounter and 0x0f
        txCounter++
        frames = HudProtocol.frame(tid, item.msg)
        failures = 0
        startAttempt()
    }

    private fun startAttempt() {
        frameIdx = 0
        waitingAck = false
        writeNextFrame()
    }

    private fun writeNextFrame() {
        val cur = inFlight ?: return
        if (frameIdx >= frames.size) {
            if (cur.msg.ackRequired) {
                waitingAck = true
                handler.removeCallbacks(ackTimeout)
                handler.postDelayed(ackTimeout, ACK_TIMEOUT_MS)
            } else {
                finishMessage(cur)
            }
            return
        }
        val ok = try { writeFrame(frames[frameIdx]) } catch (e: Exception) { false }
        if (!ok) {
            attemptFailed()
            return
        }
        handler.removeCallbacks(writeTimeout)
        handler.postDelayed(writeTimeout, WRITE_TIMEOUT_MS)
    }

    private fun onFrameWritten(status: Int) {
        if (inFlight == null || waitingAck) return
        handler.removeCallbacks(writeTimeout)
        if (status != BluetoothGatt.GATT_SUCCESS) {
            attemptFailed()
            return
        }
        frameIdx++
        writeNextFrame()
    }

    private val writeTimeout = Runnable {
        BridgeBus.log("BLE: Schreibbestätigung ausgeblieben")
        attemptFailed()
    }

    private val ackTimeout = Runnable {
        if (inFlight != null && waitingAck) {
            waitingAck = false
            attemptFailed()
        }
    }

    private fun attemptFailed() {
        handler.removeCallbacks(ackTimeout)
        handler.removeCallbacks(writeTimeout)
        failures++
        if (failures >= MAX_FAILURES) {
            failAndRetry("HUD antwortet nicht (${MAX_FAILURES} Fehlversuche)")
            return
        }
        startAttempt() // gleiche Transaktions-ID wie beim ersten Versuch
    }

    private fun finishMessage(item: Item) {
        handler.removeCallbacks(ackTimeout)
        handler.removeCallbacks(writeTimeout)
        inFlight = null
        waitingAck = false
        item.onDone?.invoke()
        pumpMessages()
    }

    @Suppress("DEPRECATION")
    private fun writeFrame(bytes: ByteArray): Boolean {
        val g = gatt ?: return false
        val c = writeChar ?: return false
        return if (Build.VERSION.SDK_INT >= 33) {
            g.writeCharacteristic(c, bytes, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) == 0
        } else {
            c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            c.value = bytes
            g.writeCharacteristic(c)
        }
    }

    @Suppress("DEPRECATION")
    private fun enableNotify(g: BluetoothGatt, c: BluetoothGattCharacteristic, d: BluetoothGattDescriptor): Boolean {
        g.setCharacteristicNotification(c, true)
        return if (Build.VERSION.SDK_INT >= 33) {
            g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == 0
        } else {
            d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            g.writeDescriptor(d)
        }
    }

    private fun refreshCache(g: BluetoothGatt) {
        try { g.javaClass.getMethod("refresh").invoke(g) } catch (_: Exception) {}
    }

    private companion object {
        const val MAX_QUEUE = 30
        const val MAX_FAILURES = 5
        const val CONNECT_TIMEOUT_MS = 12_000L
        const val WRITE_TIMEOUT_MS = 1_000L
        const val ACK_TIMEOUT_MS = 400L
        const val DESC_TIMEOUT_MS = 3_000L
        val RETRY_DELAYS = longArrayOf(1_000, 2_000, 3_000, 5_000, 8_000, 10_000)
    }
}
