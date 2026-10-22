package br.com.expoworld.storage

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import br.com.expoworld.core.*

/** The mask only depicts cells whose discovery time was recorded for this actual trip. */
internal class ExpeditionVideoFog(discoveries: List<TripDiscovery>, private val map: ExpeditionMapImage) {
    private val rects = discoveries.filter { it.elapsedSeconds >= 0 }.map { discovery ->
        val cell = discovery.cell
        val step = ExplorationConfig.CELL_PROJECTED_METERS
        fun point(x: Double, y: Double) = map.point(TraceSample(0, Geo.latitude(y), Geo.longitude(x), 0.0, null, true, 0, 0.0))
        val a = point(cell.x * step, cell.y * step)
        val b = point((cell.x + 1) * step, (cell.y + 1) * step)
        discovery.elapsedSeconds to RectF(minOf(a.x, b.x).toFloat(), minOf(a.y, b.y).toFloat(), maxOf(a.x, b.x).toFloat(), maxOf(a.y, b.y).toFloat())
    }
    private val paint = Paint().apply { color = 0x990A1218.toInt() }
    fun draw(canvas: Canvas, elapsed: Double) {
        val path = Path().apply {
            fillType = Path.FillType.EVEN_ODD
            addRect(80f, 330f, 1000f, 1030f, Path.Direction.CW)
            rects.forEach { (time, rect) -> if (time <= elapsed) addRect(rect, Path.Direction.CW) }
        }
        canvas.save(); canvas.clipRect(80f, 330f, 1000f, 1030f)
        canvas.drawPath(path, paint)
        canvas.restore()
    }
}

/** Same overhead Palio silhouette and activity colors as the map; one tiny reused sprite. */
internal class ExpeditionVideoMarker(activity: ActivityKind, theme: ExplorerTheme) : AutoCloseable {
    private val sprite: Bitmap
    private val paint = Paint().apply { isFilterBitmap = false; isAntiAlias = false }
    init {
        val rows = when (activity) {
            ActivityKind.CAR -> listOf(
                "........DDDD........", "......DDDDDDDD......", ".....DDGGGGGGDD.....", "....DGGGYYYYGGGD....",
                "...DGGGGGGGGGGGGD...", "..DDGGGGGGGGGGGGDD..", ".DDGGGGGGGGGGGGGGDD.", "DDGGGGGGGGGGGGGGGGDD",
                "DDTTGGGGGGGGGGGGTTDD", "..TTDGGGGGGGGGGDTT..", "....DGGGGGGGGGGD....", "....DWWWWWWWWWWD....",
                "....DWWWWKKWWWWD....", "....DWWWWKKWWWWD....", "....DWWWWWWWWWWD....", "....DGGGGGGGGGGD....",
                ".....GGGGgGGGG.....", ".DD.DGGGGGGGGGGD.DD.", "DDGGDGGGGGGGGGGDGGDD", "DDGGDGGGGGGGGGGDGGDD",
                ".DD.DGGGGGGGGGGD.DD.", "....DGGGGGGGGGGD....", "....DGGGGGGGGGGD....", "....DTTTTTTTTTTD....",
                "....DTTTTTTTTTTD....", "...DDTTTTTTTTTTDD...", "..DDDDDDSSSSDDDDDD..", "..DDD......DDD...")
            ActivityKind.WALK -> listOf(
                "......DDDD......", ".....DSSSSD.....", ".....DSSSSD.....", "......DDDD......",
                "....DDGGGGDD....", "...DGGGGGGGGD...", "..DGGGGGGGGGGD..", "..DGGGGGGGGGGD..",
                "..DGGGGGGGGGGD..", "...DGGGGGGGGD...", "....DGGGGGGD....", "....DDDDDDDD....",
                "....DD....DD....", "....DD....DD....", "...DDD....DDD...")
            ActivityKind.BIKE -> listOf(
                ".......DD.......", ".......DD.......", ".......DD.......", "...SSSSSSSSSS...",
                ".......DD.......", ".......GG.......", "......GGGG......", "......GDDG......",
                "......GDDG......", "......GGGG......", ".......GG.......", "......DDDD......",
                "......DDDD......", ".......DD.......", ".......DD.......", ".......DD.......", ".......DD.......")
        }
        val colors = mapOf('D' to Color.rgb(29,39,38), 'G' to (0xFF000000.toInt() or if (activity == ActivityKind.CAR) theme.carBody else if (theme == ExplorerTheme.GREEN) 0x21B38B else theme.carHighlight),
            'g' to (0xFF000000.toInt() or theme.carHighlight), 'Y' to Color.rgb(246,177,48), 'W' to Color.rgb(122,192,199),
            'K' to Color.rgb(40,81,84), 'T' to Color.rgb(19,28,28), 'S' to Color.rgb(161,180,172))
        sprite = Bitmap.createBitmap(rows.maxOf { it.length }, rows.size, Bitmap.Config.ARGB_8888)
        rows.forEachIndexed { y, row -> row.forEachIndexed { x, pixel -> sprite.setPixel((sprite.width - row.length) / 2 + x, y, colors[pixel] ?: Color.TRANSPARENT) } }
    }
    fun draw(canvas: Canvas, point: XY, heading: Double, width: Float, height: Float) {
        canvas.save()
        canvas.scale(width / ExpeditionArt.WIDTH, height / ExpeditionArt.HEIGHT)
        canvas.clipRect(80f, 330f, 1000f, 1030f)
        canvas.translate(point.x.toFloat(), point.y.toFloat())
        canvas.rotate(heading.toFloat())
        val scale = 2.6f
        canvas.drawBitmap(sprite, null, RectF(-sprite.width * scale / 2, -sprite.height * scale / 2, sprite.width * scale / 2, sprite.height * scale / 2), paint)
        canvas.restore()
    }
    override fun close() { sprite.recycle() }
}
