package br.com.expoworld.ui

import android.graphics.Canvas
import android.graphics.Bitmap
import android.graphics.RectF
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import br.com.expoworld.core.*

object ExpeditionRenderer {
    fun draw(canvas: Canvas, commands: List<ArtCommand>, width: Float, height: Float, antialias: Boolean = true, map: Bitmap? = null, mapOverlay: ((Canvas) -> Unit)? = null) {
        canvas.save()
        canvas.clipRect(0f, 0f, width, height)
        canvas.scale(width / ExpeditionArt.WIDTH, height / ExpeditionArt.HEIGHT)
        canvas.drawColor(Color.parseColor(ExpeditionArt.BACKGROUND))
        map?.let { canvas.drawBitmap(it, null, RectF(80f, 330f, 1000f, 1030f), Paint(Paint.FILTER_BITMAP_FLAG)) }
        mapOverlay?.invoke(canvas)
        val paint = Paint().apply { isAntiAlias = antialias; strokeCap = Paint.Cap.ROUND }
        for (cmd in commands) when (cmd) {
            is ArtCommand.Line -> {
                paint.color = Color.parseColor(cmd.color); paint.strokeWidth = cmd.width.toFloat()
                canvas.drawLine(cmd.x1.toFloat(), cmd.y1.toFloat(), cmd.x2.toFloat(), cmd.y2.toFloat(), paint)
            }
            is ArtCommand.Circle -> {
                paint.color = Color.parseColor(cmd.color)
                canvas.drawCircle(cmd.x.toFloat(), cmd.y.toFloat(), cmd.radius.toFloat(), paint)
            }
            is ArtCommand.Text -> {
                paint.color = Color.parseColor(cmd.color); paint.textSize = cmd.size.toFloat()
                paint.typeface = if (cmd.bold) Typeface.create("sans-serif", Typeface.BOLD) else Typeface.create("sans-serif", Typeface.NORMAL)
                canvas.drawText(cmd.text, cmd.x.toFloat(), cmd.y.toFloat(), paint)
            }
        }
        canvas.restore()
    }
}
