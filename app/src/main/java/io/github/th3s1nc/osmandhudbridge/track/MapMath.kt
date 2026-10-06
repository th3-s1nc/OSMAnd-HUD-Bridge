package io.github.th3s1nc.osmandhudbridge.track

/** Rechnung für Kartenkacheln (Web-Mercator wie OpenStreetMap). */
object MapMath {
    /** Position auf der Weltkarte in Pixeln bei Zoomstufe [z] und Kachelgröße [tilePx]. */
    fun worldX(lon: Double, z: Int, tilePx: Double): Double = (lon + 180.0) / 360.0 * (1 shl z) * tilePx

    fun worldY(lat: Double, z: Int, tilePx: Double): Double {
        val r = Math.toRadians(lat.coerceIn(-85.0511, 85.0511))
        return (1.0 - Math.log(Math.tan(r) + 1.0 / Math.cos(r)) / Math.PI) / 2.0 * (1 shl z) * tilePx
    }

    fun tileX(lon: Double, z: Int): Int = Math.floor(worldX(lon, z, 1.0)).toInt()

    fun tileY(lat: Double, z: Int): Int = Math.floor(worldY(lat, z, 1.0)).toInt()

    /** Größte Zoomstufe (höchstens [maxZ]), bei der der Ausschnitt samt [fill] (0..1) Rand in [wPx] x [hPx] passt. */
    fun chooseZoom(
        minLat: Double, maxLat: Double, minLon: Double, maxLon: Double,
        wPx: Double, hPx: Double, tilePx: Double, fill: Double = 0.85, maxZ: Int = 16, minZ: Int = 2
    ): Int {
        var z = maxZ
        while (z > minZ) {
            val w = worldX(maxLon, z, tilePx) - worldX(minLon, z, tilePx)
            val h = worldY(minLat, z, tilePx) - worldY(maxLat, z, tilePx)
            if (w <= wPx * fill && h <= hPx * fill) break
            z--
        }
        return z
    }
}
