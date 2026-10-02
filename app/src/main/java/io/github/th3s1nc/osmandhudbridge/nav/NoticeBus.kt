package io.github.th3s1nc.osmandhudbridge.nav

enum class NoticeKind { CALL, MESSAGE }

/** Meldung fürs HUD: eingehender Anruf oder Nachricht. [clear] = Anzeige wieder wegnehmen. */
class Notice(val kind: NoticeKind, val name: String? = null, val clear: Boolean = false)

/** Verbindung Listener-Dienst -> Bridge-Dienst (beide laufen im selben Prozess). */
object NoticeBus {
    @Volatile var listener: ((Notice) -> Unit)? = null

    private val COUNT_SUFFIX = Regex("""\s*\(\d+[^)]*\)\s*$""")

    /** Absendername aus dem Benachrichtigungstitel; "Mum (3 Nachrichten)" -> "Mum". Leer -> null. */
    fun cleanSender(title: String?): String? =
        title?.replace(COUNT_SUFFIX, "")?.trim()?.takeIf { it.isNotEmpty() }
}
