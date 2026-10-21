package io.github.th3s1nc.osmandhudbridge

import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import io.github.th3s1nc.osmandhudbridge.protocol.DisplayMode
import io.github.th3s1nc.osmandhudbridge.protocol.ElementTest
import io.github.th3s1nc.osmandhudbridge.protocol.TestElement
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import io.github.th3s1nc.osmandhudbridge.limit.Tx

/**
 * Element-Test (Werkzeuge → HUD): Bildschirm wählen, Code antippen, das HUD zeigt ihn 5 Sekunden, dann "gesehen" oder
 * "nicht gesehen". Das Protokoll gibt es nur hier (kein automatischer Durchlauf).
 */
class ElementTestDialog(private val ctx: Context) {
    private val handler = Handler(Looper.getMainLooper())
    private val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private var mode = DisplayMode.GUIDE
    /** Gefahren-Bildschirm 22 des HUD: kein Anzeigemodus, nur zum Ausprobieren. */
    private var danger = false
    private val screenTitle get() = if (danger) Tx.t("Gefahr", "Danger") else mode.title
    private val screenId get() = if (danger) BridgeService.TEST_DANGER_SCREEN else mode.screenId
    private val modeKey get() = if (danger) BridgeService.TEST_DANGER else mode.name
    private lateinit var dialog: AlertDialog
    private val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    private var running = false
    private var animator: ValueAnimator? = null
    private val endRunnable = Runnable { onShowOver() }
    private var seenAnswerPending: TestElement? = null

    fun show() {
        val pad = dp(20)
        root.setPadding(pad, dp(8), pad, 0)
        dialog = AlertDialog.Builder(ctx)
            .setTitle(Tx.t("Element-Test", "Element test"))
            .setView(root)
            .setNegativeButton(Tx.t("Schließen", "Close"), null)
            .setNeutralButton(Tx.t("Protokoll", "Log"), null)
            .create()
        dialog.setOnDismissListener { stopShow(); sendToService(modeKey, BridgeService.TEST_END) }
        dialog.show()
        // "Protokoll" soll das Fenster nicht schließen
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { showLog() }
        sendToService(modeKey, BridgeService.TEST_SHOW_MODE)
        showList()
    }

    // ---------------- Liste ----------------

    private fun showList() {
        root.removeAllViews()
        root.addView(text(Tx.t("Das HUD zeigt den gewählten Bildschirm. Tippe einen Code: er blinkt ${ElementTest.SHOW_SECONDS} Sekunden.", "The HUD shows the chosen screen. Tap a code: it blinks for ${ElementTest.SHOW_SECONDS} seconds."), 13f, dim = true))
        val pick = MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = Tx.t("Bildschirm: ", "Screen: ") + "$screenTitle ($screenId) ▾"
            isAllCaps = false
            setOnClickListener { pickScreen() }
        }
        root.addView(pick, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10); bottomMargin = dp(6) })
        if (danger) root.addView(text(Tx.t("Bildschirm 22 ist ein Gefahren-Bildschirm des HUD. Was er zeichnet, ist unbekannt: probiere die Codes aus und trage ein.", "Screen 22 is a danger screen of the HUD. What it draws is unknown: try the codes and record the result."), 12f, dim = true))
        val seen = lastAnswers(screenTitle)
        val list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        for (e in if (danger) ElementTest.catalog else ElementTest.listFor(mode)) {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(4), dp(11), dp(4), dp(11))
                isClickable = true
                val tv = android.util.TypedValue()
                ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
                setBackgroundResource(tv.resourceId)
                setOnClickListener { begin(e) }
            }
            row.addView(text(e.code.toString(), 15f, bold = true, accent = true), LinearLayout.LayoutParams(dp(48), ViewGroup.LayoutParams.WRAP_CONTENT))
            row.addView(text(ElementTest.tr(e.name), 15f), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            seen[e.code]?.let { ok ->
                row.addView(text(if (ok) "✓" else "✗", 16f, bold = true).apply { setTextColor(if (ok) 0xFF4CAF50.toInt() else 0xFFE05050.toInt()) })
            }
            list.addView(row)
        }
        val scroll = ScrollView(ctx).apply { addView(list) }
        val h = (ctx.resources.displayMetrics.heightPixels * 0.5f).toInt()
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h))
    }

    private fun pickScreen() {
        val modes = DisplayMode.values()
        val names = modes.map { "${it.title} (${it.screenId})" } + Tx.t("Gefahr (${BridgeService.TEST_DANGER_SCREEN}), nur Test", "Danger (${BridgeService.TEST_DANGER_SCREEN}), test only")
        AlertDialog.Builder(ctx)
            .setTitle(Tx.t("Bildschirm", "Screen"))
            .setSingleChoiceItems(names.toTypedArray(), if (danger) modes.size else modes.indexOf(mode)) { d, i ->
                danger = i >= modes.size
                if (!danger) mode = modes[i]
                sendToService(modeKey, BridgeService.TEST_SHOW_MODE)
                d.dismiss()
                showList()
            }
            .show()
    }

    // ---------------- Anzeige am HUD ----------------

    private fun begin(e: TestElement) {
        if (!BridgeService.running) {
            Toast.makeText(ctx, Tx.t("Erst den Dienst starten", "Start the service first"), Toast.LENGTH_SHORT).show()
            return
        }
        sendToService(modeKey, e.code)
        running = true
        seenAnswerPending = e
        root.removeAllViews()
        root.addView(text(Tx.t("$screenTitle ($screenId) · Code ${e.code} „${ElementTest.tr(e.name)}“", "$screenTitle ($screenId) · code ${e.code} “${ElementTest.tr(e.name)}”"), 15f, bold = true, accent = true))
        val bar = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply { max = ElementTest.SHOW_SECONDS * 1000 }
        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(14)).apply { topMargin = dp(14) })
        val info = text(Tx.t("Der Code blinkt am HUD. Danach ist der Bildschirm wieder normal.", "The code blinks on the HUD. Afterwards the screen is normal again."), 13f, dim = true)
        root.addView(info, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        animator = ValueAnimator.ofInt(0, ElementTest.SHOW_SECONDS * 1000).apply {
            duration = ElementTest.SHOW_SECONDS * 1000L
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener { bar.progress = it.animatedValue as Int }
            start()
        }
        val buttons = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(answerButton(Tx.t("Gesehen", "Seen"), true, e), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) })
        buttons.addView(answerButton(Tx.t("Nicht gesehen", "Not seen"), false, e), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(6) })
        root.addView(buttons, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(18) })
        handler.postDelayed(endRunnable, ElementTest.SHOW_SECONDS * 1000L)
    }

    private fun answerButton(label: String, ok: Boolean, e: TestElement) =
        MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = label
            isAllCaps = false
            setOnClickListener {
                stopShow()
                addLog(screenTitle, e, ok)
                showList()
            }
        }

    /** Nach 5 Sekunden: HUD wieder normal, die Antwort kann noch gegeben werden. */
    private fun onShowOver() {
        if (!running) return
        running = false
        animator?.cancel()
    }

    private fun stopShow() {
        handler.removeCallbacks(endRunnable)
        animator?.cancel()
        animator = null
        running = false
        seenAnswerPending = null
    }

    private fun sendToService(key: String, code: Int) {
        if (!BridgeService.running) return
        val i = Intent(ctx, BridgeService::class.java).setAction(BridgeService.ACTION_ELEMENT_TEST)
            .putExtra(BridgeService.EXTRA_MODE, key).putExtra(BridgeService.EXTRA_CODE, code)
        ContextCompat.startForegroundService(ctx, i)
    }

    // ---------------- Protokoll (nur hier) ----------------

    private class Entry(val time: String, val screen: String, val code: Int, val name: String, val ok: Boolean)

    private fun entries(): List<Entry> =
        (prefs.getString(KEY_LOG, "") ?: "").lines().filter { it.isNotBlank() }.mapNotNull {
            val p = it.split("|")
            if (p.size < 5) null else Entry(p[0], p[1], p[2].toIntOrNull() ?: return@mapNotNull null, p[3], p[4] == "1")
        }

    private fun addLog(screen: String, e: TestElement, ok: Boolean) {
        val t = SimpleDateFormat("dd.MM. HH:mm", Locale.GERMANY).format(Date())
        val line = "$t|$screen|${e.code}|${e.name}|${if (ok) 1 else 0}"
        val all = ((prefs.getString(KEY_LOG, "") ?: "").lines().filter { it.isNotBlank() } + line).takeLast(300)
        prefs.edit().putString(KEY_LOG, all.joinToString("\n")).apply()
    }

    /** Letzte Antwort je Code für diesen Bildschirm (gesehen = true). */
    private fun lastAnswers(screen: String): Map<Int, Boolean> {
        val last = HashMap<Int, Boolean>()
        for (e in entries()) if (e.screen == screen) last[e.code] = e.ok
        return last
    }

    private fun logText(): String =
        entries().joinToString("\n") { "${it.time}  ${it.screen}  ${it.code} ${ElementTest.tr(it.name)}: ${if (it.ok) Tx.t("gesehen", "seen") else Tx.t("nicht gesehen", "not seen")}" }

    private fun showLog() {
        val tv = text(logText().ifEmpty { Tx.t("Noch keine Einträge.", "No entries yet.") }, 13f)
        val scroll = ScrollView(ctx).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(tv) }
        AlertDialog.Builder(ctx)
            .setTitle(Tx.t("Protokoll", "Log"))
            .setView(scroll)
            .setPositiveButton(Tx.t("Teilen", "Share")) { _, _ ->
                val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, "Element-Test (OSMAnd HUD Bridge)\n" + logText())
                ctx.startActivity(Intent.createChooser(send, Tx.t("Protokoll teilen", "Share log")))
            }
            .setNeutralButton(Tx.t("Leeren", "Clear")) { _, _ ->
                prefs.edit().remove(KEY_LOG).apply()
                if (running.not() && seenAnswerPending == null) showList()
            }
            .setNegativeButton(Tx.t("Schließen", "Close"), null)
            .show()
    }

    // ---------------- Hilfen ----------------

    private fun text(s: String, sp: Float, bold: Boolean = false, dim: Boolean = false, accent: Boolean = false) = TextView(ctx).apply {
        text = s
        textSize = sp
        if (bold) setTypeface(typeface, Typeface.BOLD)
        val tv = android.util.TypedValue()
        val attr = when {
            accent -> androidx.appcompat.R.attr.colorPrimary
            dim -> android.R.attr.textColorSecondary
            else -> android.R.attr.textColorPrimary
        }
        if (ctx.theme.resolveAttribute(attr, tv, true)) {
            if (tv.resourceId != 0) setTextColor(ContextCompat.getColor(ctx, tv.resourceId)) else setTextColor(tv.data)
        }
    }

    private fun dp(v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    private companion object {
        const val PREFS = "element_test"
        const val KEY_LOG = "log"
    }
}
