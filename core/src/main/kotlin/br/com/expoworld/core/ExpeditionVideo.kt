package br.com.expoworld.core

/** Deterministic MP4 timeline: ten seconds of travel, then two seconds to read the result. */
object ExpeditionVideo {
    const val WIDTH = 720
    const val HEIGHT = 960
    const val FRAME_RATE = 24
    const val TRAVEL_FRAMES = FRAME_RATE * 10
    const val FRAME_COUNT = FRAME_RATE * 12
    fun fraction(frame: Int): Float = (frame.coerceIn(0, FRAME_COUNT - 1).toFloat() / (TRAVEL_FRAMES - 1)).coerceAtMost(1f)
    fun timestampUs(frame: Int): Long = frame.toLong() * 1_000_000L / FRAME_RATE
}

/** BT.601 limited-range I420, with 2x2 averaged chroma. No Android or codec layout assumptions. */
object ExpeditionYuv {
    fun encode(argb: IntArray, width: Int, height: Int, target: ByteArray) {
        require(width > 0 && height > 0 && width % 2 == 0 && height % 2 == 0)
        val size = width.toLong() * height
        require(size <= Int.MAX_VALUE / 2 && argb.size == size.toInt() && target.size == (size * 3 / 2).toInt())
        val pixels = size.toInt()
        var chroma = 0
        for (y in 0 until height step 2) for (x in 0 until width step 2) {
            var sumR = 0; var sumG = 0; var sumB = 0
            for (dy in 0..1) for (dx in 0..1) {
                val at = (y + dy) * width + x + dx
                val color = argb[at]
                val r = color ushr 16 and 255; val g = color ushr 8 and 255; val b = color and 255
                target[at] = (((66 * r + 129 * g + 25 * b + 128) shr 8) + 16).coerceIn(16, 235).toByte()
                sumR += r; sumG += g; sumB += b
            }
            val r = (sumR + 2) / 4; val g = (sumG + 2) / 4; val b = (sumB + 2) / 4
            target[pixels + chroma] = (((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128).coerceIn(16, 240).toByte()
            target[pixels + pixels / 4 + chroma] = (((112 * r - 94 * g - 18 * b + 128) shr 8) + 128).coerceIn(16, 240).toByte()
            chroma++
        }
    }
}
