package io.github.th3s1nc.osmandhudbridge.limit

import java.util.Locale

/** Eine Region aus dem Verzeichnis von Geofabrik (zum Beispiel "Oberpfalz"). [pbfUrl] ist null, wenn es dafür keine eigene Datei gibt. */
class Region(val id: String, val name: String, val parent: String?, val pbfUrl: String?)

/**
 * Verzeichnis der Regionen als Baum (Europa, Deutschland, Bayern, Oberpfalz …), gelesen aus dem JSON von Geofabrik
 * (index-v1-nogeom.json). Reine Logik ohne Android, damit sie getestet werden kann.
 */
class RegionIndex(all: List<Region>) {
    private val byId = all.associateBy { it.id }
    private val kids: Map<String?, List<Region>> =
        all.groupBy { it.parent }.mapValues { e -> e.value.sortedBy { it.name.lowercase(Locale.ROOT) } }

    fun get(id: String): Region? = byId[id]

    /** Unterregionen; [id] = null: oberste Ebene. */
    fun children(id: String?): List<Region> = kids[id] ?: emptyList()

    /** Ausgangspunkt der Auswahl: Europa, falls vorhanden, sonst die oberste Ebene. */
    fun startId(): String? = if (byId.containsKey("europe")) "europe" else null

    /** Weg von der obersten Ebene bis [id] (für die Zeile "Europa › Deutschland › Bayern"). */
    fun path(id: String): List<Region> {
        val out = ArrayList<Region>()
        var cur = byId[id]
        var guard = 0
        while (cur != null && guard++ < 20) {
            out.add(0, cur)
            cur = cur.parent?.let { byId[it] }
        }
        return out
    }

    val size: Int get() = byId.size

    companion object {
        /** Liest das JSON von Geofabrik. Unbrauchbare Einträge werden übersprungen. */
        fun parse(json: String): RegionIndex {
            val root = MiniJson(json).value()
            val features = ((root as? Map<*, *>)?.get("features") as? List<*>) ?: emptyList<Any?>()
            val list = ArrayList<Region>()
            for (f in features) {
                val p = (f as? Map<*, *>)?.get("properties") as? Map<*, *> ?: continue
                val id = p["id"] as? String ?: continue
                val name = p["name"] as? String ?: id
                val parent = (p["parent"] as? String)?.takeIf { it.isNotBlank() }
                val pbf = ((p["urls"] as? Map<*, *>)?.get("pbf") as? String)?.takeIf { it.startsWith("https://") }
                list.add(Region(id, name, parent, pbf))
            }
            return RegionIndex(list)
        }
    }
}

/** Kleiner JSON-Leser (Objekt, Liste, Text, Zahl, true/false/null), damit die Auswertung ohne Android getestet werden kann. */
internal class MiniJson(private val s: String) {
    private var i = 0

    fun value(): Any? {
        ws()
        if (i >= s.length) throw IllegalArgumentException("JSON zu Ende")
        return when (val c = s[i]) {
            '{' -> obj()
            '[' -> arr()
            '"' -> str()
            't' -> { i += 4; true }
            'f' -> { i += 5; false }
            'n' -> { i += 4; null }
            else -> if (c == '-' || c.isDigit()) num() else throw IllegalArgumentException("Unerwartetes Zeichen '$c' bei $i")
        }
    }

    private fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }

    private fun obj(): Map<String, Any?> {
        val m = LinkedHashMap<String, Any?>()
        i++ // {
        ws()
        if (s[i] == '}') { i++; return m }
        while (true) {
            ws()
            val k = str()
            ws()
            if (s[i] != ':') throw IllegalArgumentException("':' fehlt bei $i")
            i++
            m[k] = value()
            ws()
            if (s[i] == ',') { i++; continue }
            if (s[i] == '}') { i++; return m }
            throw IllegalArgumentException("',' oder '}' fehlt bei $i")
        }
    }

    private fun arr(): List<Any?> {
        val l = ArrayList<Any?>()
        i++ // [
        ws()
        if (s[i] == ']') { i++; return l }
        while (true) {
            l.add(value())
            ws()
            if (s[i] == ',') { i++; continue }
            if (s[i] == ']') { i++; return l }
            throw IllegalArgumentException("',' oder ']' fehlt bei $i")
        }
    }

    private fun str(): String {
        if (s[i] != '"') throw IllegalArgumentException("Text erwartet bei $i")
        i++
        val sb = StringBuilder()
        while (i < s.length && s[i] != '"') {
            var c = s[i]
            if (c == '\\') {
                i++
                c = s[i]
                when (c) {
                    'n' -> sb.append('\n')
                    't' -> sb.append('\t')
                    'r' -> sb.append('\r')
                    'b' -> sb.append('\b')
                    'f' -> sb.append('\u000C')
                    'u' -> { sb.append(s.substring(i + 1, i + 5).toInt(16).toChar()); i += 4 }
                    else -> sb.append(c) // " \ /
                }
            } else sb.append(c)
            i++
        }
        i++ // schließendes "
        return sb.toString()
    }

    private fun num(): Double {
        val st = i
        while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
        return s.substring(st, i).toDouble()
    }
}
