package io.github.th3s1nc.osmandhudbridge

import android.content.Context
import android.content.SharedPreferences
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import io.github.th3s1nc.osmandhudbridge.protocol.DisplayMode
import io.github.th3s1nc.osmandhudbridge.protocol.HudValue
import io.github.th3s1nc.osmandhudbridge.protocol.SlotConfig
import io.github.th3s1nc.osmandhudbridge.limit.Tx

/** Fenster "Felder wählen": je Zeile ein Auswahlfeld mit den Werten für Guide (2 Zeilen) bzw. Cruiser (4 Zeilen). */
object FieldPickerDialog {
    fun prefKey(mode: DisplayMode) =
        if (mode == DisplayMode.GUIDE) BridgeService.KEY_SLOTS_GUIDE else BridgeService.KEY_SLOTS_CRUISER

    /** Aktuell gespeicherte Auswahl dieses Modus. */
    fun current(prefs: SharedPreferences, mode: DisplayMode): List<HudValue> =
        SlotConfig.parse(mode, prefs.getString(prefKey(mode), null))

    fun show(context: Context, prefs: SharedPreferences, mode: DisplayMode, onSaved: () -> Unit) {
        val dp = context.resources.displayMetrics.density
        val values = HudValue.values().toList()
        val start = current(prefs, mode)
        val spinners = ArrayList<Spinner>()

        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((24 * dp).toInt(), (8 * dp).toInt(), (24 * dp).toInt(), 0)
        }
        start.forEachIndexed { i, v ->
            box.addView(TextView(context).apply {
                text = Tx.t("Zeile ${i + 1}", "Line ${i + 1}")
                textSize = 12f
                alpha = 0.7f
                setPadding(0, (if (i == 0) 0 else 12 * dp).toInt(), 0, 0)
            })
            val sp = Spinner(context)
            sp.adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, values.map { it.displayTitle })
            sp.setSelection(values.indexOf(v))
            spinners += sp
            box.addView(sp)
        }
        box.addView(TextView(context).apply {
            text = Tx.t("Fahrzeit und Strecke starten bei null, sobald du die Anzeige wählst.", "Ride time and distance start at zero as soon as you choose the display.")
            textSize = 12f
            alpha = 0.7f
            setPadding(0, (16 * dp).toInt(), 0, (8 * dp).toInt())
        })

        fun save(list: List<HudValue>) {
            prefs.edit().putString(prefKey(mode), SlotConfig.encode(list)).apply()
            onSaved()
        }

        AlertDialog.Builder(context)
            .setTitle(Tx.t("Felder wählen", "Choose fields"))
            .setView(ScrollView(context).apply { addView(box) })
            .setPositiveButton("OK") { _, _ -> save(spinners.map { values[it.selectedItemPosition] }) }
            .setNeutralButton(Tx.t("Standard", "Default")) { _, _ -> save(SlotConfig.defaults(mode)) }
            .setNegativeButton(Tx.t("Abbrechen", "Cancel"), null)
            .show()
    }
}
