package io.github.th3s1nc.osmandhudbridge

import android.app.Application

/** Setzt beim Start der App die gewählte Sprache. */
class HudApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLang.apply(this)
    }
}
