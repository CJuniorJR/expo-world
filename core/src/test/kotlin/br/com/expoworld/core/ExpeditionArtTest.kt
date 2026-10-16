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
