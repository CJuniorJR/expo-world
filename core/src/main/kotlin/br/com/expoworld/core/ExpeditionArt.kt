package br.com.expoworld.core

import java.io.OutputStream
import kotlin.math.*

/** Renderer-neutral drawing instructions. SVG preview and Android Canvas share layout. */
sealed interface ArtCommand {
    data class Line(val x1: Double, val y1: Double, val x2: Double, val y2: Double, val color: String, val width: Double) : ArtCommand
    data class Circle(val x: Double, val y: Double, val radius: Double, val color: String) : ArtCommand
    data class Text(val x: Double, val y: Double, val text: String, val color: String, val size: Double, val bold: Boolean = false) : ArtCommand
}
object ExpeditionArt {
    const val WIDTH = 1080
    const val HEIGHT = 1440
    const val BACKGROUND = "#101C22"
    fun title(input: String) = input.filter { !it.isISOControl() }.trim().take(40).ifBlank { "Minha expedição" }
    private fun area(value: Double?) = value?.let { if (it >= 10_000) String.format(java.util.Locale.forLanguageTag("pt-BR"), "%.2f km²", it / 1_000_000) else "${it.toInt()} m²" } ?: "—"
    fun scene(trace: RecordedTrace, inputTitle: String, dateLabel: String, fraction: Float = 1f, theme: ExplorerTheme = ExplorerTheme.GREEN,
        mapPoint: ((TraceSample) -> XY)? = null): List<ArtCommand> = buildList {
        val mint = ExplorerTheme.hex(theme.accent); val muted = "#AFBFC6"; val gold = "#FFD166"
        add(ArtCommand.Text(80.0, 90.0, "EXPO WORLD / ATLAS PESSOAL", mint, 24.0, true))
        val name = title(inputTitle)
        val split = if (name.length > 20) name.lastIndexOf(' ', 20).takeIf { it >= maxOf(10, name.length - 20) } ?: 20 else name.length
        add(ArtCommand.Text(80.0, 165.0, name.take(split), "#FFFFFF", 44.0, true))
        if (split < name.length) add(ArtCommand.Text(80.0, 220.0, name.drop(split).trim(), "#FFFFFF", 44.0, true))
        add(ArtCommand.Text(80.0, 270.0, dateLabel + if (trace.mode == "simulation") " / SIMULADO" else " / EXPLORAÇÃO", muted, 24.0))
        if (mapPoint == null) for (x in 80..1000 step 80) add(ArtCommand.Line(x.toDouble(), 330.0, x.toDouble(), 1030.0, "#1C3038", 1.0))
        if (mapPoint == null) for (y in 330..1030 step 70) add(ArtCommand.Line(80.0, y.toDouble(), 1000.0, y.toDouble(), "#1C3038", 1.0))
        val all = ReplayTimeline.visualSamples(trace.samples)
        if (all.isNotEmpty()) {
            val origin = Geo.project(all.first().latitude, all.first().longitude).x
            val circumference = 2 * PI * Geo.R
            fun projected(sample: TraceSample): XY {
                val p = Geo.project(sample.latitude, sample.longitude)
                return XY(Math.IEEEremainder(p.x - origin, circumference), p.y)
            }
            val positions = all.map { projected(it) }
            val west = positions.minOf { it.x }; val east = positions.maxOf { it.x }
            val south = positions.minOf { it.y }; val north = positions.maxOf { it.y }
            val scale = min(800 / (east - west).coerceAtLeast(1.0), 540 / (north - south).coerceAtLeast(1.0))
            fun point(sample: TraceSample): XY {
                mapPoint?.let { return it(sample) }
                val p = projected(sample)
                return XY(540 + (p.x - (west + east) / 2) * scale, 680 - (p.y - (south + north) / 2) * scale)
            }
            val frame = ReplayTimeline.frame(all, fraction)
            frame.zipWithNext().filter { !it.second.breakBefore }.forEach { (a, b) ->
                val p = point(a); val q = point(b)
                add(ArtCommand.Line(p.x, p.y, q.x, q.y, "#0A1218", 20.0))
            }
            frame.zipWithNext().filter { !it.second.breakBefore }.forEach { (a, b) ->
                val p = point(a); val q = point(b)
                add(ArtCommand.Line(p.x, p.y, q.x, q.y, TracePresentation.color(b.speedKmh, trace.activity.brightKmh, trace.activity.stopKmh, theme), 10.0))
            }
            // Reuse the presentation rule for zero-length stopped fixes and signal gaps.
            val stopSegments = mutableSetOf<Int>()
            var lastMoving: Int? = null
            for (i in 1 until frame.size) {
                val a = frame[i - 1]; val b = frame[i]
                if (b.breakBefore) { lastMoving = null; continue }
                if (a.latitude != b.latitude || a.longitude != b.longitude) lastMoving = i
                if (b.speedKmh?.let { it <= trace.activity.stopKmh } == true) lastMoving?.let { stopSegments.add(it) }
            }
            stopSegments.forEach { i ->
                val a = point(frame[i - 1]); val b = point(frame[i])
                add(ArtCommand.Line(a.x, a.y, b.x, b.y, "#000000", 10.0))
            }
            val start = point(all.first()); val end = point(frame.last())
            add(ArtCommand.Circle(start.x, start.y, 16.0, gold))
            add(ArtCommand.Circle(end.x, end.y, 23.0, "#FFFFFF"))
            add(ArtCommand.Circle(end.x, end.y, 16.0, mint))
        }
        add(ArtCommand.Text(80.0, 1080.0, if (mapPoint == null) "PRÉVIA DO TRAJETO / AGUARDANDO MAPA" else "© MapTiler · © OpenStreetMap contributors", muted, 20.0))
        add(ArtCommand.Line(80.0, 1120.0, 1000.0, 1120.0, mint, 2.0))
        val summary = TripSummary.from(trace)
        add(ArtCommand.Text(80.0, 1190.0, area(trace.newAreaSquareMeters), mint, 46.0, true))
        add(ArtCommand.Text(80.0, 1225.0, "ÁREA NOVA / ESTIMATIVA", muted, 20.0))
        val distance = String.format(java.util.Locale.forLanguageTag("pt-BR"), "%.2f km", summary.distanceMeters / 1000)
        add(ArtCommand.Text(630.0, 1190.0, distance, "#FFFFFF", 46.0, true))
        add(ArtCommand.Text(630.0, 1225.0, "DISTÂNCIA REGISTRADA", muted, 20.0))
        add(ArtCommand.Text(80.0, 1310.0, "${(summary.recordedSeconds / 60).toInt()} min de registros / ${summary.stops} ${if (summary.stops == 1) "parada" else "paradas"}", muted, 26.0))
        add(ArtCommand.Text(80.0, 1370.0, when {
            !trace.completed -> "PERCURSO INTERROMPIDO / TRECHO RECUPERADO"
            summary.partial -> "RASTRO PARCIAL / AMOSTRAS MAIS RECENTES"
            else -> "CADA CAMINHO, UMA DESCOBERTA."
        }, gold, 20.0, true))
        if (trace.mode == "simulation") add(ArtCommand.Text(80.0, 1410.0,
            "© OpenStreetMap contributors / openstreetmap.org/copyright", muted, 24.0))
    }
    fun svg(commands: List<ArtCommand>): String {
        fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
        return """<svg xmlns="http://www.w3.org/2000/svg" width="$WIDTH" height="$HEIGHT" viewBox="0 0 $WIDTH $HEIGHT"><rect width="100%" height="100%" fill="$BACKGROUND"/>""" + commands.joinToString("") { cmd -> when (cmd) {
            is ArtCommand.Line -> """<line x1="${cmd.x1}" y1="${cmd.y1}" x2="${cmd.x2}" y2="${cmd.y2}" stroke="${cmd.color}" stroke-width="${cmd.width}" stroke-linecap="round"/>"""
            is ArtCommand.Circle -> """<circle cx="${cmd.x}" cy="${cmd.y}" r="${cmd.radius}" fill="${cmd.color}"/>"""
            is ArtCommand.Text -> """<text x="${cmd.x}" y="${cmd.y}" fill="${cmd.color}" font-size="${cmd.size}" font-family="sans-serif" font-weight="${if (cmd.bold) "bold" else "normal"}">${escape(cmd.text)}</text>"""
        } } + "</svg>"
    }
}

/** GIF89a with a fixed 16-color palette. Clear often to bound the LZW dictionary
 * and keep every code at 5 bits. Larger output trades compression for low memory. */
class ExpeditionGif(private val out: OutputStream, val width: Int, val height: Int, private val palette: IntArray = PALETTE) {
    companion object {
        val PALETTE = intArrayOf(0x101C22, 0x1C3038, 0x0A1218, 0xFFFFFF, 0x70E1BA, 0xAFBFC6, 0xFFD166, 0x21404A,
            0x395F62, 0x527F7D, 0x6C9F98, 0x86BFB3, 0xA6DFD2, 0xC9FFF1, 0x8A9298, 0x729ED9)
        val MAP_PALETTE = IntArray(256) { i -> ((i shr 5) * 255 / 7 shl 16) or
            (((i shr 2) and 7) * 255 / 7 shl 8) or ((i and 3) * 255 / 3) }
        fun mapIndex(pixel: Int): Byte = ((((pixel shr 16 and 255) * 7 + 127) / 255 shl 5) or
            (((pixel shr 8 and 255) * 7 + 127) / 255 shl 2) or (((pixel and 255) * 3 + 127) / 255)).toByte()
    }
    private val paletteBits = Integer.numberOfTrailingZeros(palette.size)
    private val minimumCodeSize = maxOf(2, paletteBits)
    private val clearCode = 1 shl minimumCodeSize
    private val codeBits = minimumCodeSize + 1
    private fun word(n: Int) { out.write(n and 255); out.write(n ushr 8 and 255) }
    init {
        require(palette.size == 16 || palette.size == 256)
        require(width in 1..1080 && height in 1..1440)
        out.write("GIF89a".toByteArray(Charsets.US_ASCII)); word(width); word(height)
        out.write(0xF0 or (paletteBits - 1)); out.write(0); out.write(0)
        palette.forEach { out.write(it shr 16 and 255); out.write(it shr 8 and 255); out.write(it and 255) }
        out.write(byteArrayOf(0x21, 0xFF.toByte(), 11)); out.write("NETSCAPE2.0".toByteArray(Charsets.US_ASCII))
        out.write(byteArrayOf(3, 1, 0, 0, 0))
    }
    fun frame(pixels: ByteArray, delayCentiseconds: Int = 25) {
        require(pixels.size == width * height && pixels.all { (it.toInt() and 255) in palette.indices })
        out.write(byteArrayOf(0x21, 0xF9.toByte(), 4, 4)); word(delayCentiseconds.coerceIn(1, 65535)); out.write(0); out.write(0)
        out.write(0x2C); word(0); word(0); word(width); word(height); out.write(0); out.write(minimumCodeSize)
        val block = ByteArray(255); var used = 0; var bits = 0; var accumulator = 0
        fun byte(value: Int) { block[used++] = value.toByte(); if (used == 255) { out.write(255); out.write(block); used = 0 } }
        fun code(value: Int) { accumulator = accumulator or (value shl bits); bits += codeBits
            while (bits >= 8) { byte(accumulator and 255); accumulator = accumulator ushr 8; bits -= 8 }
        }
        var at = 0
        while (at < pixels.size) { code(clearCode); repeat(minOf(12, pixels.size - at)) { code(pixels[at++].toInt() and 255) } }
        code(clearCode + 1)
        if (bits > 0) byte(accumulator and 255)
        if (used > 0) { out.write(used); out.write(block, 0, used) }
        out.write(0)
    }
    fun finish() { out.write(0x3B); out.flush() }
}
