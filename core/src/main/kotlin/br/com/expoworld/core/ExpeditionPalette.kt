package br.com.expoworld.core

/** One palette learned from the final card, shared by every frame to avoid color flicker.
 * A 5-bit RGB histogram bounds memory; weighted median cuts retain actual map colors.
 * Lookup is prepared once, so animation frames only require one lookup per pixel.
 */
class ExpeditionPalette private constructor(val colors: IntArray, private val lookup: ByteArray) {
    fun index(pixels: IntArray, target: ByteArray) {
        require(pixels.size == target.size)
        for (i in pixels.indices) target[i] = lookup[bin(pixels[i])]
    }

    companion object {
        private fun bin(rgb: Int) = ((rgb ushr 19 and 31) shl 10) or
            ((rgb ushr 11 and 31) shl 5) or (rgb ushr 3 and 31)
        private fun channel(bin: Int, shift: Int) = bin ushr shift and 31

        fun from(pixels: IntArray, reserved: IntArray = intArrayOf(0x000000, 0xFFFFFF, 0x101C22, 0xFFD166)): ExpeditionPalette {
            require(pixels.isNotEmpty())
            val fixed = reserved.map { it and 0xFFFFFF }.distinct().take(32)
            val counts = IntArray(32768)
            val red = LongArray(32768); val green = LongArray(32768); val blue = LongArray(32768)
            for (pixel in pixels) {
                val b = bin(pixel); counts[b]++
                red[b] += pixel ushr 16 and 255; green[b] += pixel ushr 8 and 255; blue[b] += pixel and 255
            }
            class Box(val bins: List<Int>) {
                val weight = bins.sumOf { counts[it].toLong() }
                val shift = listOf(10, 5, 0).maxBy { sh -> bins.maxOf { channel(it, sh) } - bins.minOf { channel(it, sh) } }
                val range = bins.maxOf { channel(it, shift) } - bins.minOf { channel(it, shift) }
                val score = range * kotlin.math.sqrt(weight.toDouble())
            }
            val boxes = mutableListOf(Box(counts.indices.filter { counts[it] > 0 }))
            while (boxes.size < 256 - fixed.size) {
                val box = boxes.filter { it.bins.size > 1 }.maxByOrNull { it.score } ?: break
                val sorted = box.bins.sortedBy { channel(it, box.shift) }
                var sum = 0L; var cut = 1
                for (i in 0 until sorted.lastIndex) {
                    sum += counts[sorted[i]]; cut = i + 1
                    if (sum * 2 >= box.weight) break
                }
                boxes.remove(box); boxes.add(Box(sorted.take(cut))); boxes.add(Box(sorted.drop(cut)))
            }
            val palette = (fixed + boxes.map { box ->
                val r = (box.bins.sumOf { red[it] } / box.weight).toInt()
                val g = (box.bins.sumOf { green[it] } / box.weight).toInt()
                val b = (box.bins.sumOf { blue[it] } / box.weight).toInt()
                (r shl 16) or (g shl 8) or b
            }).toMutableList()
            while (palette.size < 256) palette.add(palette.last())
            val colors = palette.toIntArray()
            val lookup = ByteArray(32768) { key ->
                val r = channel(key, 10) * 8 + 4; val g = channel(key, 5) * 8 + 4; val b = channel(key, 0) * 8 + 4
                var best = 0; var distance = Int.MAX_VALUE
                for (i in colors.indices) {
                    val dr = r - (colors[i] ushr 16 and 255)
                    val dg = g - (colors[i] ushr 8 and 255); val db = b - (colors[i] and 255)
                    val d = dr * dr + dg * dg + db * db
                    if (d < distance) { distance = d; best = i }
                }
                best.toByte()
            }
            // Exact UI/trace colors win in their bins, including stationary black.
            fixed.forEachIndexed { i, color -> lookup[bin(color)] = i.toByte() }
            return ExpeditionPalette(colors, lookup)
        }
    }
}
