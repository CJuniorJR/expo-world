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
