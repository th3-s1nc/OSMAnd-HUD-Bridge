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
                text = "Zeile ${i + 1}"
                textSize = 12f
                alpha = 0.7f
                setPadding(0, (if (i == 0) 0 else 12 * dp).toInt(), 0, 0)
            })
            val sp = Spinner(context)
            sp.adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, values.map { it.title })
            sp.setSelection(values.indexOf(v))
            spinners += sp
            box.addView(sp)
        }
        box.addView(TextView(context).apply {
            text = "Fahrzeit und Strecke starten bei null, sobald du die Anzeige wählst."
            textSize = 12f
            alpha = 0.7f
            setPadding(0, (16 * dp).toInt(), 0, (8 * dp).toInt())
        })

        fun save(list: List<HudValue>) {
            prefs.edit().putString(prefKey(mode), SlotConfig.encode(list)).apply()
            onSaved()
        }

        AlertDialog.Builder(context)
            .setTitle("Felder wählen")
            .setView(ScrollView(context).apply { addView(box) })
            .setPositiveButton("OK") { _, _ -> save(spinners.map { values[it.selectedItemPosition] }) }
            .setNeutralButton("Standard") { _, _ -> save(SlotConfig.defaults(mode)) }
            .setNegativeButton("Abbrechen", null)
            .show()
    }
}
