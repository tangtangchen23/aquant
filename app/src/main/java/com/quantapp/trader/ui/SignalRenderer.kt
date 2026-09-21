package com.quantapp.trader.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import com.github.mikephil.charting.animation.ChartAnimator
import com.github.mikephil.charting.charts.CombinedChart
import com.github.mikephil.charting.components.YAxis
import com.github.mikephil.charting.renderer.CombinedChartRenderer
import com.github.mikephil.charting.utils.ViewPortHandler

/** K线买卖信号（B/S）：index 为K线下标，price 为标记锚点价格，type 为 BUY/SELL。 */
data class BuySellSignal(val index: Int, val price: Float, val type: Int)

/**
 * 在K线主图上叠加 B(买)/S(卖) 信号标记（同花顺风格）：
 * 买入点在K线下方画红色圆标“B”，卖出点在K线上方画绿色圆标“S”。
 */
class SignalRenderer(
    chart: CombinedChart,
    animator: ChartAnimator,
    viewPortHandler: ViewPortHandler,
    private val signals: () -> List<BuySellSignal>
) : CombinedChartRenderer(chart, animator, viewPortHandler) {

    private val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 26f
        color = android.graphics.Color.WHITE
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }

    override fun drawData(c: Canvas) {
        super.drawData(c)
        val ch = mChart as CombinedChart
        val transformer = ch.getTransformer(YAxis.AxisDependency.LEFT)
        for (s in signals()) {
            val pt = transformer.getPixelForValues(s.index.toFloat(), s.price)
            val x = pt.x.toFloat()
            val isBuy = s.type == ChartFragment.BUY
            circlePaint.color = if (isBuy) 0xFFE53935.toInt() else 0xFF43A047.toInt()
            val radius = 13f
            // 买入标在K线下方，卖出标在K线上方，避免遮挡实体
            val cy = if (isBuy) pt.y.toFloat() + radius + 8f else pt.y.toFloat() - radius - 8f
            c.drawCircle(x, cy, radius, circlePaint)
            c.drawText(if (isBuy) "B" else "S", x, cy + 9f, textPaint)
        }
    }
}
