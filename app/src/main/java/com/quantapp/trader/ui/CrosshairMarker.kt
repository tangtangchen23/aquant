package com.quantapp.trader.ui

import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import com.github.mikephil.charting.charts.BarLineChartBase
import com.github.mikephil.charting.components.IMarker
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.highlight.Highlight
import com.github.mikephil.charting.utils.MPPointF

/**
 * 同花顺式十字光标：手指在K线/分时图上点按或拖动时，
 * 显示贯穿图表的十字虚线、选中点圆点，以及顶部“日期/价格浮窗”。
 */
class CrosshairMarker(
    private val chart: BarLineChartBase<*>,
    private val infoOf: (Int) -> String,
    private val isUpAt: (Int) -> Boolean
) : IMarker {

    private var entry: Entry? = null

    private val crossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.2f
        color = 0xCC9E9E9E.toInt()
    }
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xE61F2329.toInt() }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 30f
        color = android.graphics.Color.WHITE
        typeface = Typeface.DEFAULT_BOLD
    }
    private val detailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 26f
        color = android.graphics.Color.WHITE
    }
    private val upColor = 0xFFE53935.toInt()
    private val downColor = 0xFF43A047.toInt()
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    override fun getOffset(): MPPointF = MPPointF.getInstance(0f, 0f)

    override fun getOffsetForDrawingAtPoint(x: Float, y: Float): MPPointF =
        MPPointF.getInstance(0f, 0f)

    override fun refreshContent(e: Entry?, highlight: Highlight?) {
        entry = e
    }

    override fun draw(canvas: Canvas, posx: Float, posy: Float) {
        val e = entry ?: return
        val vp = chart.viewPortHandler
        val left = vp.contentLeft()
        val top = vp.contentTop()
        val right = vp.contentRight()
        val bottom = vp.contentBottom()
        if (left >= right || top >= bottom) return

        // 十字虚线
        crossPaint.pathEffect = DashPathEffect(floatArrayOf(8f, 6f), 0f)
        canvas.drawLine(posx, top, posx, bottom, crossPaint)
        canvas.drawLine(left, posy, right, posy, crossPaint)
        crossPaint.pathEffect = null

        // 选中点圆点（涨红跌绿）
        val idx = e.x.toInt()
        dotPaint.color = if (isUpAt(idx)) upColor else downColor
        canvas.drawCircle(posx, posy, 6f, dotPaint)

        // 顶部浮窗：第一行日期，后续行OHLC明细
        val lines = infoOf(idx).split('\n').filter { it.isNotBlank() }
        if (lines.isEmpty()) return
        val w = lines.maxOf { if (it == lines[0]) titlePaint.measureText(it) else detailPaint.measureText(it) } + 28f
        val cx = (left + right) / 2f
        val pad = 14f
        val h = pad * 2 + 24f + (lines.size - 1) * 34f
        val rect = RectF(cx - w / 2f, top + 4f, cx + w / 2f, top + 4f + h)
        canvas.drawRoundRect(rect, 12f, 12f, bgPaint)
        var ty = rect.top + pad + 24f
        lines.forEachIndexed { i, line ->
            val paint = if (i == 0) titlePaint else detailPaint
            val old = paint.color
            // 明细行用涨红跌绿着色，日期行保持白色
            if (i > 0) paint.color = if (isUpAt(idx)) upColor else downColor
            canvas.drawText(line, cx - paint.measureText(line) / 2f, ty, paint)
            paint.color = old
            ty += if (i == 0) 30f else 34f
        }
    }
}
