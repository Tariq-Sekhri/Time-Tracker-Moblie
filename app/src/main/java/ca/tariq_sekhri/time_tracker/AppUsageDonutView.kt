package ca.tariq_sekhri.time_tracker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.View
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

data class UsageDonutSlice(
    val packageName: String,
    val label: String,
    val seconds: Long,
    val icon: Drawable?,
    val color: Int
)

/** Draws actual usage proportions and the installed app icons around their own segments. */
class AppUsageDonutView(context: Context, private val slices: List<UsageDonutSlice>) : View(context) {
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.BUTT }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER }
    private val total = slices.sumOf { it.seconds }.coerceAtLeast(1L)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val size = min(width, height).toFloat()
        val cx = width / 2f
        val cy = height / 2f
        val radius = size * .28f
        ringPaint.strokeWidth = size * .105f
        val oval = RectF(cx - radius, cy - radius, cx + radius, cy + radius)
        var angle = -90f
        slices.forEach { slice ->
            val sweep = (slice.seconds.toDouble() / total * 360.0).toFloat()
            ringPaint.color = slice.color
            canvas.drawArc(oval, angle, sweep, false, ringPaint)
            val iconAngle = Math.toRadians((angle + sweep / 2f).toDouble())
            val iconSize = (size * .115f).toInt().coerceAtLeast(24)
            val iconRadius = radius + ringPaint.strokeWidth / 2f + iconSize * .62f
            val ix = (cx + cos(iconAngle).toFloat() * iconRadius).toInt()
            val iy = (cy + sin(iconAngle).toFloat() * iconRadius).toInt()
            slice.icon?.let { drawable ->
                drawable.bounds = Rect(ix - iconSize / 2, iy - iconSize / 2, ix + iconSize / 2, iy + iconSize / 2)
                drawable.draw(canvas)
            }
            angle += sweep
        }
        textPaint.textSize = size * .095f
        textPaint.isFakeBoldText = true
        canvas.drawText(format(total), cx, cy + textPaint.textSize * .16f, textPaint)
        textPaint.textSize = size * .04f
        textPaint.isFakeBoldText = false
        textPaint.color = Color.rgb(157, 164, 178)
        canvas.drawText("Total usage", cx, cy + size * .11f, textPaint)
    }

    private fun format(seconds: Long): String {
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
    }

    companion object {
        fun colorFromIcon(drawable: Drawable?): Int {
            if (drawable == null) return Color.rgb(177, 139, 255)
            return runCatching {
                val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                drawable.bounds = Rect(0, 0, 32, 32)
                drawable.draw(canvas)
                var red = 0L; var green = 0L; var blue = 0L; var count = 0L
                for (y in 0 until 32 step 2) for (x in 0 until 32 step 2) {
                    val pixel = bitmap.getPixel(x, y)
                    val hsv = FloatArray(3)
                    Color.colorToHSV(pixel, hsv)
                    if (Color.alpha(pixel) > 128 && hsv[1] > .18f) {
                        red += Color.red(pixel); green += Color.green(pixel); blue += Color.blue(pixel); count++
                    }
                }
                bitmap.recycle()
                if (count == 0L) Color.rgb(177, 139, 255) else Color.rgb((red / count).toInt(), (green / count).toInt(), (blue / count).toInt())
            }.getOrDefault(Color.rgb(177, 139, 255))
        }
    }
}
