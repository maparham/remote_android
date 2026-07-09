package com.example.lanremote.control

class CoordinateMapper(private val widthPx: Int, private val heightPx: Int) {
    fun toPixels(nx: Float, ny: Float): Pair<Int, Int> {
        val cx = nx.coerceIn(0f, 1f)
        val cy = ny.coerceIn(0f, 1f)
        val px = (cx * (widthPx - 1)).toInt()
        val py = (cy * (heightPx - 1)).toInt()
        return Pair(px, py)
    }
}
