package io.github.th3s1nc.osmandhudbridge.limit

import android.content.Context
import java.io.File

/**
 * Speicherort der Kacheln: der normale App-Speicher (wird von Android nicht von selbst geleert, anders als der Cache-Ordner).
 * Kacheln aus dem alten Cache-Ordner werden beim ersten Aufruf verschoben.
 */
object TileStore {
    fun dir(ctx: Context): File {
        val target = File(ctx.filesDir, "limits")
        try {
            val old = File(ctx.cacheDir, "limits")
            if (old.isDirectory) {
                target.mkdirs()
                old.listFiles()?.forEach { f ->
                    val dest = File(target, f.name)
                    if (!dest.exists() && !f.renameTo(dest)) { try { f.copyTo(dest); f.delete() } catch (_: Exception) { } }
                    else if (dest.exists()) f.delete()
                }
                old.delete()
            }
        } catch (_: Exception) { }
        return target
    }
}
