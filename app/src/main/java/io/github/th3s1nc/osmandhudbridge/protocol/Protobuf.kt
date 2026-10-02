package io.github.th3s1nc.osmandhudbridge.protocol

/** Minimaler Protobuf-Encoder (proto3-Regeln: Standardwert 0 wird weggelassen). */
internal object Protobuf {
    val EMPTY = ByteArray(0)

    fun varint(value: Int): ByteArray {
        var v = value
        val out = ArrayList<Byte>(5)
        while (true) {
            val bits = v and 0x7f
            v = v ushr 7
            if (v == 0) {
                out.add(bits.toByte())
                break
            }
            out.add((bits or 0x80).toByte())
        }
        return out.toByteArray()
    }

    fun key(field: Int, wire: Int): ByteArray = varint((field shl 3) or wire)

    /** Ganzzahl-Feld; 0 wird (proto3) nicht gesendet. */
    fun uint(field: Int, value: Int): ByteArray =
        if (value == 0) EMPTY else key(field, 0) + varint(value)

    /** Eingebettete Nachricht. Auch eine leere Nachricht wird gesendet (Tag + Länge 0) = "leer/löschen". */
    fun message(field: Int, vararg parts: ByteArray): ByteArray = message(field, parts.asList())

    fun message(field: Int, parts: List<ByteArray>): ByteArray {
        val body = concat(parts)
        return key(field, 2) + varint(body.size) + body
    }

    /** Gepackte Liste von Ganzzahlen (repeated enum). */
    fun packed(field: Int, values: List<Int>): ByteArray {
        if (values.isEmpty()) return EMPTY
        val body = concat(values.map { varint(it) })
        return key(field, 2) + varint(body.size) + body
    }

    fun string(field: Int, s: String): ByteArray {
        val b = s.toByteArray(Charsets.US_ASCII)
        return key(field, 2) + varint(b.size) + b
    }

    fun concat(parts: List<ByteArray>): ByteArray {
        val out = ByteArray(parts.sumOf { it.size })
        var i = 0
        for (p in parts) {
            p.copyInto(out, i)
            i += p.size
        }
        return out
    }
}
