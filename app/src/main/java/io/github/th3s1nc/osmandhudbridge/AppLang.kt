package io.github.th3s1nc.osmandhudbridge

import android.content.Context
import android.content.res.Configuration
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import io.github.th3s1nc.osmandhudbridge.limit.Tx
import java.util.Locale

/** Sprache der App: "auto" (Handy-Sprache), "de" oder "en". Die Wahl liegt in den Einstellungen der App. */
object AppLang {
    const val KEY = "app_lang"
    const val AUTO = "auto"

    fun get(ctx: Context): String =
        ctx.getSharedPreferences(BridgeService.PREFS, Context.MODE_PRIVATE).getString(KEY, AUTO) ?: AUTO

    /** Für Fenster (Activities): gilt für die ganze App, ein offenes Fenster wird neu aufgebaut. */
    fun apply(ctx: Context) {
        val code = get(ctx)
        setTexts(code)
        AppCompatDelegate.setApplicationLocales(
            if (code == AUTO) LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(code)
        )
    }

    /** Sprache für Texte aus dem Programm (siehe Tx): Englisch bei "en" oder bei Automatisch und englischem Handy. */
    private fun setTexts(code: String) {
        Tx.en =
            code == "en" || (code == AUTO && Locale.getDefault().language == "en")
    }

    fun set(ctx: Context, code: String) {
        ctx.getSharedPreferences(BridgeService.PREFS, Context.MODE_PRIVATE).edit().putString(KEY, code).apply()
        apply(ctx)
    }

    /** Für Dienste (Benachrichtigung, Meldungen): eigener Kontext mit der gewählten Sprache. */
    fun wrap(base: Context): Context {
        val code = get(base)
        setTexts(code)
        if (code == AUTO) return base
        val cfg = Configuration(base.resources.configuration)
        cfg.setLocale(Locale.forLanguageTag(code))
        return base.createConfigurationContext(cfg)
    }
}
