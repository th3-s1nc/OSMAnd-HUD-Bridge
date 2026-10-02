package io.github.th3s1nc.osmandhudbridge.nav

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import io.github.th3s1nc.osmandhudbridge.BridgeBus
import io.github.th3s1nc.osmandhudbridge.BridgeService

/**
 * Liest die Navigations-Benachrichtigung von OsmAnd mit (Restweg, Restzeit, evtl. Ausfahrt).
 * Braucht die Freigabe "Benachrichtigungszugriff" in den Android-Einstellungen.
 * Der Inhalt wird ins Log geschrieben und ausgewertet (Restweg, Restzeit, Kreisverkehr-Ausfahrt).
 */
class OsmAndNotificationListener : NotificationListenerService() {

    private var last = ""

    override fun onListenerConnected() {
        BridgeBus.init(applicationContext)
        BridgeBus.log("Benachrichtigungszugriff aktiv")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val n = sbn ?: return
        // Schalter "Bridge aktiv" aus: Benachrichtigungen werden weder gelesen noch geloggt
        if (!getSharedPreferences(BridgeService.PREFS, MODE_PRIVATE).getBoolean(BridgeService.KEY_ENABLED, true)) return
        if (n.packageName in WHATSAPP_PACKAGES) { handleMessage(n); return }
        if (n.notification.category == Notification.CATEGORY_CALL) { handleCall(n); return }
        if (n.packageName !in OSMAND_PACKAGES) return
        val e = n.notification.extras ?: return
        val parts = listOf(
            Notification.EXTRA_TITLE, Notification.EXTRA_TEXT, Notification.EXTRA_SUB_TEXT,
            Notification.EXTRA_BIG_TEXT, Notification.EXTRA_INFO_TEXT
        ).mapNotNull { key -> e.getCharSequence(key)?.toString()?.takeIf { it.isNotBlank() }?.let { "${key.substringAfterLast('.')}=$it" } }
        val line = parts.joinToString(" | ")
        if (line.isEmpty() || line == last) return
        last = line
        BridgeBus.log("OsmAnd-Notification (${n.id}): $line")
        val body = ArrayList<String>()
        e.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.let { body += it.split('\n') }
        e.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.let { body += it.split('\n') }
        e.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()?.let { body += it.split('\n') }
        val info = OsmAndNotificationParser.parse(e.getCharSequence(Notification.EXTRA_TITLE)?.toString(), body)
        OsmAndNotificationBus.listener?.invoke(info)
    }

    private val lastText = HashMap<String, String>()
    private var callKey: String? = null
    private var lastCallName: String? = null

    /** WhatsApp-Nachricht: nur Absender (Titel), Sammelmeldungen und Dauermeldungen werden übergangen. */
    private fun handleMessage(n: StatusBarNotification) {
        val no = n.notification
        BridgeBus.log("WhatsApp-Meldung (${n.packageName}), Flags ${no.flags}, Kategorie ${no.category}")
        if (no.flags and Notification.FLAG_GROUP_SUMMARY != 0) { BridgeBus.log("WhatsApp: Sammelmeldung übergangen"); return }
        if (no.flags and Notification.FLAG_ONGOING_EVENT != 0) { BridgeBus.log("WhatsApp: Dauermeldung übergangen"); return }
        if (no.category == Notification.CATEGORY_CALL) { handleCall(n); return }
        val e = no.extras ?: return
        val title = e.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val text = e.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val name = NoticeBus.cleanSender(title)
        if (name == null) { BridgeBus.log("WhatsApp: Meldung ohne Titel übergangen"); return }
        val sig = "$title|$text|${no.`when`}"
        if (lastText[n.key] == sig) { BridgeBus.log("WhatsApp: doppelte Meldung übergangen"); return } // Neuveröffentlichung
        lastText[n.key] = sig
        if (lastText.size > 50) lastText.clear()
        BridgeBus.log("Nachricht von $name (${n.packageName})")
        NoticeBus.listener?.invoke(Notice(NoticeKind.MESSAGE, name))
    }

    /** Anruf (eingehend): über die Benachrichtigung der Telefon-App, deshalb ohne Telefon-/Kontaktberechtigung. */
    private fun handleCall(n: StatusBarNotification) {
        val no = n.notification
        val e = no.extras ?: return
        // EXTRA_CALL_TYPE (Android 12+): 1 eingehend, 2 laufend, 3 Prüfung
        val type = e.getInt("android.callType", 0)
        val incoming = type == 1 || (type == 0 && no.fullScreenIntent != null)
        if (!incoming) {
            if (callKey == n.key) {
                callKey = null
                NoticeBus.listener?.invoke(Notice(NoticeKind.CALL, clear = true))
            }
            return
        }
        val name = NoticeBus.cleanSender(e.getCharSequence(Notification.EXTRA_TITLE)?.toString())
        if (callKey == n.key && name == lastCallName) return // Neuveröffentlichung derselben Anrufmeldung
        callKey = n.key
        lastCallName = name
        BridgeBus.log("Eingehender Anruf (${n.packageName})")
        NoticeBus.listener?.invoke(Notice(NoticeKind.CALL, name))
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        val n = sbn ?: return
        if (n.key == callKey) {
            callKey = null
            NoticeBus.listener?.invoke(Notice(NoticeKind.CALL, clear = true))
        }
        if (n.packageName in OSMAND_PACKAGES) {
            last = ""
            OsmAndNotificationBus.listener?.invoke(OsmAndNotificationInfo(null, null, null, null))
        }
    }

    private companion object {
        val OSMAND_PACKAGES = setOf("net.osmand", "net.osmand.plus", "net.osmand.dev")
        val WHATSAPP_PACKAGES = setOf("com.whatsapp", "com.whatsapp.w4b")
    }
}

/** Verbindung Listener-Dienst -> Bridge-Dienst (beide laufen im selben Prozess). */
object OsmAndNotificationBus {
    @Volatile var listener: ((OsmAndNotificationInfo) -> Unit)? = null
}
