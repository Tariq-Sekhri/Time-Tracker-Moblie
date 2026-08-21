package ca.tariq_sekhri.time_tracker

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class CalendarDayBlock(
    val packageName: String,
    val label: String,
    val startMs: Long,
    val endMs: Long,
    val activeSeconds: Long,
    val color: Int,
    val icon: Drawable?
)

/** Pinch to zoom, then drag vertically to move through the selected tracking day. */
class CalendarDayView(
    context: Context,
    private val dayStartMs: Long,
    private val blocks: List<CalendarDayBlock>,
    initialZoom: Float = 1f,
    initialPanHours: Float = 0f,
    private val onViewportChanged: (Float, Float) -> Unit = { _, _ -> }
) : View(context) {
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private var zoom=initialZoom.coerceIn(1f,96f)
    private var panHours=initialPanHours.coerceIn(0f,24f-24f/zoom)
    private var lastY=0f
    private val scaleDetector=ScaleGestureDetector(context,object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val oldVisible=24f/zoom
            val focusHour=panHours+(detector.focusY-gridTop())/hourHeight(oldVisible)
            // 96x means the 24-hour day can be zoomed down to a 15-minute window.
            zoom=(zoom*detector.scaleFactor).coerceIn(1f,96f)
            val visible=24f/zoom
            panHours=(focusHour-(detector.focusY-gridTop())/hourHeight(visible)).coerceIn(0f,24f-visible)
            saveViewport()
            invalidate()
            return true
        }
    })

    override fun onDraw(canvas: Canvas) {
        val left=dp(42f); val top=gridTop(); val bottom=height-dp(8f); val visibleHours=24f/zoom; val hourHeight=hourHeight(visibleHours)
        paint.strokeWidth=1f; paint.color=Color.rgb(43,50,65); paint.style=Paint.Style.STROKE
        val firstHour=kotlin.math.floor(panHours).toInt().coerceAtLeast(0)
        val lastHour=kotlin.math.ceil(panHours+visibleHours).toInt().coerceAtMost(24)
        (firstHour..lastHour).forEach { hour ->
            val y=top+(hour-panHours)*hourHeight
            canvas.drawLine(left,y,width-dp(8f),y,paint)
            if(hour<24 && hour%2==0) {
                paint.style=Paint.Style.FILL; paint.color=Color.rgb(150,158,172); paint.textSize=dp(10f); paint.textAlign=Paint.Align.RIGHT
                canvas.drawText(hourLabel(hour),left-dp(6f),y+dp(4f),paint)
                paint.style=Paint.Style.STROKE; paint.color=Color.rgb(43,50,65)
            }
        }
        canvas.save(); canvas.clipRect(left,top,width-dp(8f),bottom)
        blocks.forEach { block ->
            val start=(block.startMs-dayStartMs).coerceIn(0L,86_400_000L)/3_600_000f
            val end=(block.endMs-dayStartMs).coerceIn(0L,86_400_000L)/3_600_000f
            val y1=top+(start-panHours)*hourHeight; val y2=(top+(end-panHours)*hourHeight).coerceAtLeast(y1+dp(18f))
            val rect=RectF(left+dp(2f),y1+dp(1f),width-dp(10f),y2-dp(1f))
            paint.style=Paint.Style.FILL; paint.color=block.color; canvas.drawRoundRect(rect,dp(3f),dp(3f),paint)
            paint.color=Color.WHITE; paint.textSize=dp(11f); paint.textAlign=Paint.Align.LEFT
            canvas.drawText("${time(block.startMs)} - ${time(block.endMs)}",left+dp(7f),y1+dp(13f),paint)
            if(y2-y1>dp(32f)) {
                val iconSize=dp(18f); val showIcon=block.icon!=null && y2-y1>dp(48f); val textX=if(showIcon) left+dp(12f)+iconSize else left+dp(7f)
                if(showIcon) block.icon?.let { icon -> icon.setBounds((left+dp(7f)).toInt(),(y1+dp(18f)).toInt(),(left+dp(7f)+iconSize).toInt(),(y1+dp(18f)+iconSize).toInt()); icon.draw(canvas) }
                canvas.drawText("${block.label} (${format(block.activeSeconds)})",textX,y1+dp(28f),paint)
            }
        }
        canvas.restore()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        when(event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { lastY=event.y; parent?.requestDisallowInterceptTouchEvent(true) }
            MotionEvent.ACTION_MOVE -> if(!scaleDetector.isInProgress) {
                val visible=24f/zoom
                panHours=(panHours+(lastY-event.y)/hourHeight(visible)).coerceIn(0f,24f-visible)
                lastY=event.y; invalidate(); parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { saveViewport(); parent?.requestDisallowInterceptTouchEvent(false) }
        }
        return true
    }

    private fun gridTop()=dp(8f)
    private fun saveViewport() { onViewportChanged(zoom,panHours) }
    private fun hourHeight(visibleHours:Float)=((height-dp(8f))-gridTop())/visibleHours
    private fun hourLabel(hour:Int)=String.format(Locale.US,"%02d:00",(CalendarDayView.HOUR_OFFSET+hour)%24)
    private fun time(ms:Long)=SimpleDateFormat("h:mm",Locale.getDefault()).format(Date(ms))
    private fun format(seconds:Long)=if(seconds>=3600)"${seconds/3600}h ${(seconds%3600)/60}m" else "${seconds/60}m"
    private fun dp(v:Float)=v*resources.displayMetrics.density
    companion object { var HOUR_OFFSET=6 }
}
