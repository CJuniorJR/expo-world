package br.com.expoworld.core

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import java.awt.image.BufferedImage
import java.awt.BasicStroke
import java.awt.Font
import java.awt.Color
import java.io.File

class ExpeditionArtTest {
    @Test fun gifIsIndependentlyDecodedWithExactPixelsInBothFrames() {
        val out = ByteArrayOutputStream(); val gif = ExpeditionGif(out, 33, 19)
        val first = ByteArray(33 * 19) { (it % 16).toByte() }
        val second = ByteArray(first.size) { (15 - it % 16).toByte() }
        gif.frame(first); gif.frame(second); gif.finish()
        ImageIO.createImageInputStream(ByteArrayInputStream(out.toByteArray())).use { input ->
            val reader = ImageIO.getImageReadersByFormatName("gif").next(); reader.input = input
            try {
                assertEquals(2, reader.getNumImages(true))
                listOf(first, second).forEachIndexed { frame, expected ->
                    val image = reader.read(frame)
                    expected.forEachIndexed { i, pixel -> assertEquals(ExpeditionGif.PALETTE[pixel.toInt()], image.getRGB(i % 33, i / 33) and 0xFFFFFF) }
                }
            } finally { reader.dispose() }
        }
    }
    @Test fun mapPaletteGifDecodesAll256Colors() {
        val out = ByteArrayOutputStream()
        val gif = ExpeditionGif(out, 32, 16, ExpeditionGif.MAP_PALETTE)
        val pixels = ByteArray(32 * 16) { (it % 256).toByte() }
        gif.frame(pixels); gif.frame(pixels.reversedArray()); gif.finish()
        ImageIO.createImageInputStream(ByteArrayInputStream(out.toByteArray())).use { input ->
            val reader = ImageIO.getImageReadersByFormatName("gif").next(); reader.input = input
            try {
                assertEquals(2, reader.getNumImages(true))
                listOf(pixels, pixels.reversedArray()).forEachIndexed { frame, expected ->
                    val image = reader.read(frame)
                    expected.forEachIndexed { i, pixel ->
                        assertEquals(ExpeditionGif.MAP_PALETTE[pixel.toInt() and 255], image.getRGB(i % 32, i / 32) and 0xFFFFFF)
                    }
                }
                ExpeditionGif.MAP_PALETTE.forEachIndexed { i, color -> assertEquals(i, ExpeditionGif.mapIndex(color).toInt() and 255) }
            } finally { reader.dispose() }
        }
    }
    @Test fun compressedGifSurvivesCodeWidthChangesAndDictionaryResets() {
        val random = java.util.Random(42)
        for (palette in listOf(ExpeditionGif.PALETTE, ExpeditionGif.MAP_PALETTE)) {
            for (width in listOf(1, 2, 15, 16, 17, 254, 255, 256, 257, 511, 512, 720)) {
                val height = if (width == 720) 180 else 3
                val expected = ByteArray(width * height) { random.nextInt(palette.size).toByte() }
                val out = ByteArrayOutputStream(); val gif = ExpeditionGif(out, width, height, palette)
                gif.frame(expected, 10); gif.finish()
                val image = ImageIO.read(ByteArrayInputStream(out.toByteArray()))
                expected.forEachIndexed { i, value -> assertEquals("width=$width, palette=${palette.size}, pixel=$i",
                    palette[value.toInt() and 255], image.getRGB(i % width, i / width) and 0xFFFFFF) }
            }
        }
        val out = ByteArrayOutputStream(); val gif = ExpeditionGif(out, 720, 960, ExpeditionGif.MAP_PALETTE)
        gif.frame(ByteArray(720 * 960) { 42 }); gif.finish()
        assertTrue("Repeated map areas must compress well", out.size() < 720 * 960 / 20)
    }

    @Test fun fullSizeAnimationHasOneHundredFramesAndCorrectTiming() {
        val width = 720; val height = 960
        val out = ByteArrayOutputStream(); val gif = ExpeditionGif(out, width, height, ExpeditionGif.MAP_PALETTE)
        val pixels = ByteArray(width * height) { ((it / width / 8 + it % width / 12) % 200).toByte() }
        repeat(100) { frame ->
            pixels[frame * width + frame] = 255.toByte()
            gif.frame(pixels, if (frame == 99) 150 else 10)
        }
        gif.finish()
        ImageIO.createImageInputStream(ByteArrayInputStream(out.toByteArray())).use { input ->
            val reader = ImageIO.getImageReadersByFormatName("gif").next(); reader.input = input
            try {
                assertEquals(100, reader.getNumImages(true))
                var duration = 0
                for (frame in 0 until 100) {
                    val tree = reader.getImageMetadata(frame).getAsTree("javax_imageio_gif_image_1.0") as org.w3c.dom.Element
                    val control = tree.getElementsByTagName("GraphicControlExtension").item(0) as org.w3c.dom.Element
                    duration += control.getAttribute("delayTime").toInt()
                }
                assertEquals(1140, duration)
                for (frame in listOf(0, 49, 99)) {
                    val image = reader.read(frame)
                    assertEquals(width, image.width); assertEquals(height, image.height)
                    for (i in pixels.indices) {
                        val row = i / width; val col = i % width
                        val index = if (row == col && row <= frame) 255 else (row / 8 + col / 12) % 200
                        assertEquals(ExpeditionGif.MAP_PALETTE[index], image.getRGB(col, row) and 0xFFFFFF)
                    }
                }
            } finally { reader.dispose() }
        }
        assertTrue("Repeated map tiles must remain bounded", out.size() < 15_000_000)
    }

    @Test fun adaptivePalettePreservesUiColorsAndImprovesMapShades() {
        // Representative muted map tones plus a theme gradient, rather than a uniform RGB cube.
        val pixels = IntArray(32000) { i ->
            val r = 140 + i % 100; val g = 150 + i % 90; val b = 120 + i % 110
            (r shl 16) or (g shl 8) or b
        } + intArrayOf(0, 0xFFFFFF, 0x101C22, 0xFFD166, ExplorerTheme.GREEN.accent)
        val reserved = intArrayOf(0, 0xFFFFFF, 0x101C22, 0xFFD166, ExplorerTheme.GREEN.accent)
        val palette = ExpeditionPalette.from(pixels, reserved)
        val indexed = ByteArray(pixels.size); palette.index(pixels, indexed)
        fun error(a: Int, b: Int): Long = listOf(16, 8, 0).sumOf { shift ->
            val delta = (a ushr shift and 255) - (b ushr shift and 255); (delta * delta).toLong()
        }
        val adaptiveError = pixels.indices.sumOf { error(pixels[it], palette.colors[indexed[it].toInt() and 255]) }
        val previousError = pixels.sumOf { error(it, ExpeditionGif.MAP_PALETTE[ExpeditionGif.mapIndex(it).toInt() and 255]) }
        assertTrue("Adaptive map shades should beat RGB332", adaptiveError < previousError / 2)
        reserved.forEachIndexed { i, color -> assertEquals(color, palette.colors[indexed[pixels.size - reserved.size + i].toInt() and 255]) }
        val flat = ExpeditionPalette.from(IntArray(20) { 0x101C22 })
        assertEquals(256, flat.colors.size)
    }

    @Test fun mapCardUsesMapProjectionAttributionAndBlackStopsWithoutStopSymbols() {
        val samples = listOf(TraceSample(0, -25.43, -49.27, 0.0, 12.0, true, -1, 0.0),
            TraceSample(1, -25.431, -49.27, 5.0, 0.0, false, 0, 5.0),
            TraceSample(2, -25.431, -49.27, 10.0, 0.0, false, 0, 10.0))
        val route = RecordedTrace(0, 10000, samples)
        val commands = ExpeditionArt.scene(route, "Mapa", "16.10.2026", mapPoint = { XY(200.0, 400.0 + it.elapsedSeconds) })
        assertTrue(commands.filterIsInstance<ArtCommand.Text>().any { it.text.contains("MapTiler") && it.text.contains("OpenStreetMap") })
        assertFalse(commands.filterIsInstance<ArtCommand.Text>().any { it.text.contains("SEM MAPA") })
        assertTrue(commands.filterIsInstance<ArtCommand.Line>().any { it.color == "#000000" && it.x1 == 200.0 && it.y1 == 400.0 && it.y2 == 405.0 })
        assertEquals(3, commands.filterIsInstance<ArtCommand.Circle>().size) // Only start and current/end (two concentric rings).
    }
    @Test fun cardLayoutIsBoundedEscapesTextAndCreatesVisualFixture() {
        val filter = FixFilter(); val sampler = TraceSampler()
        val samples = (0..SimulatedRoute.LAST_INDEX).mapNotNull { i ->
            val fix = SimulatedRoute.fix(i, i.toDouble())
            val accepted = filter.accept(fix, fix.monotonicSeconds) as FilterResult.Accepted
            sampler.accept(fix, accepted.previous != null)
        }
        val route = RecordedTrace(1000, 121000, samples, "simulation", true, 88_000.0)
        val commands = ExpeditionArt.scene(route, "Minha Curitiba", "15.10.2026")
        commands.filterIsInstance<ArtCommand.Line>().forEach { line ->
            assertTrue(line.x1 in 0.0..1080.0 && line.x2 in 0.0..1080.0 && line.y1 in 0.0..1440.0 && line.y2 in 0.0..1440.0)
        }
        assertEquals("Minha expedição", ExpeditionArt.title("\n\t"))
        assertTrue(ExpeditionArt.svg(listOf(ArtCommand.Text(0.0, 0.0, "<test>&", "#FFFFFF", 10.0))).contains("&lt;test&gt;&amp;"))
        val folder = File("build/preview").apply { mkdirs() }
        File(folder, "curitiba-card.svg").writeText(ExpeditionArt.svg(commands))
        val image = BufferedImage(1080, 1440, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        try {
            graphics.color = Color.decode(ExpeditionArt.BACKGROUND); graphics.fillRect(0, 0, 1080, 1440)
            commands.forEach { cmd -> when (cmd) {
                is ArtCommand.Line -> { graphics.color = Color.decode(cmd.color); graphics.stroke = BasicStroke(cmd.width.toFloat(), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND); graphics.drawLine(cmd.x1.toInt(), cmd.y1.toInt(), cmd.x2.toInt(), cmd.y2.toInt()) }
                is ArtCommand.Circle -> { graphics.color = Color.decode(cmd.color); graphics.fillOval((cmd.x - cmd.radius).toInt(), (cmd.y - cmd.radius).toInt(), (2 * cmd.radius).toInt(), (2 * cmd.radius).toInt()) }
                is ArtCommand.Text -> { graphics.color = Color.decode(cmd.color); graphics.font = Font("SansSerif", if (cmd.bold) Font.BOLD else Font.PLAIN, cmd.size.toInt()); graphics.drawString(cmd.text, cmd.x.toFloat(), cmd.y.toFloat()) }
            } }
        } finally { graphics.dispose() }
        ImageIO.write(image, "png", File(folder, "curitiba-card.png"))
    }
}
