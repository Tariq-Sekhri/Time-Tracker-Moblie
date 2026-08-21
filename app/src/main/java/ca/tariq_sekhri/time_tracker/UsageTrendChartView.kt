package ca.tariq_sekhri.time_tracker

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import kotlin.math.max

/** Shared time-of-use line chart; its points can represent hours or days. */
class UsageTrendChartView(context: Context, private val values: List<Long>, private val axisLabels: List<String>) : View(context) {
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(canvas: Canvas) {
        val w=width.toFloat(); val h=height.toFloat(); val left=dp(18f); val top=dp(18f); val bottom=h-dp(30f)
        val maxValue=max(1L,values.maxOrNull() ?: 1L).toFloat()
        paint.style=Paint.Style.STROKE; paint.strokeWidth=dp(1f); paint.color=Color.rgb(88,83,98)
        repeat(4) { i -> val y=top+(bottom-top)*i/3f; canvas.drawLine(left,y,w-dp(12f),y,paint) }
        drawLine(canvas,left,top,w,bottom,maxValue)
    }
    private fun drawLine(canvas:Canvas,left:Float,top:Float,w:Float,bottom:Float,maxValue:Float) {
        if(values.isEmpty()) return; val usable=w-left-dp(12f); val path=Path()
        values.forEachIndexed { i,value -> val x=left+usable*i/max(1,values.size-1); val y=bottom-(bottom-top)*value/maxValue; if(i==0)path.moveTo(x,y) else path.lineTo(x,y) }
        paint.style=Paint.Style.STROKE; paint.strokeWidth=dp(3f); paint.color=Color.rgb(190,157,255); canvas.drawPath(path,canvasPaint())
        paint.style=Paint.Style.FILL; paint.color=Color.rgb(190,157,255); values.forEachIndexed { i,value -> val x=left+usable*i/max(1,values.size-1); val y=bottom-(bottom-top)*value/maxValue; canvas.drawCircle(x,y,dp(3f),paint) }
        labels(canvas,axisLabels,left,w,bottom)
    }
    private fun labels(canvas:Canvas,labels:List<String>,left:Float,w:Float,bottom:Float) { paint.color=Color.rgb(174,177,188); paint.textSize=dp(11f); paint.textAlign=Paint.Align.CENTER; val usable=w-left-dp(12f); labels.forEachIndexed { i,label -> canvas.drawText(label,left+usable*(if(labels.size==1)0f else i.toFloat()/(labels.size-1)),bottom+dp(20f),paint) } }
    private fun canvasPaint()=paint.apply { style=Paint.Style.STROKE; strokeWidth=dp(3f); color=Color.rgb(190,157,255) }
    private fun dp(v:Float)=v*resources.displayMetrics.density
}
