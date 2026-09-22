package com.quantapp.trader.ui

import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.github.mikephil.charting.charts.CombinedChart
import com.github.mikephil.charting.components.AxisBase
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.CandleData
import com.github.mikephil.charting.data.CandleDataSet
import com.github.mikephil.charting.data.CandleEntry
import com.github.mikephil.charting.data.CombinedData
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.ValueFormatter
import com.quantapp.trader.R
import com.quantapp.trader.data.KLine
import com.quantapp.trader.data.MarketService
import com.quantapp.trader.trading.App
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 横屏全屏K线页：由K线图右下角全屏图标进入，沉浸式横屏查看日K。
 * 主图绘制K线 + MA5/10/20，右上角可开关BOLL(20,2)，点“退出”或系统返回键返回。
 */
class FullscreenChartActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SYMBOL = "symbol"
    }

    private lateinit var chart: CombinedChart
    private lateinit var tvBoll: TextView
    private lateinit var tvStatus: TextView
    private var bars: List<KLine> = emptyList()
    private var showBOLL = false

    private var chartTextColor = 0xFF1A1A1A.toInt()
    private var chartGridColor = 0xFFE6E8EB.toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        // 与主界面保持同一主题模式（跟随系统/浅色/深色/红色）
        when (App.appStore.themeMode) {
            3 -> {
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
                setTheme(R.style.Theme_QuantApp_Red)
            }
            1 -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            2 -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            else -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        }
        super.onCreate(savedInstanceState)
        // 沉浸式全屏：隐藏状态栏/导航栏，下滑边缘可短暂唤出
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        setContentView(R.layout.activity_fullscreen_chart)

        chartTextColor = ContextCompat.getColor(this, R.color.text_secondary)
        chartGridColor = ContextCompat.getColor(this, R.color.divider)

        chart = findViewById(R.id.fs_chart)
        tvBoll = findViewById(R.id.tv_fs_boll)
        tvStatus = findViewById(R.id.tv_fs_status)
        findViewById<TextView>(R.id.tv_fs_exit).setOnClickListener { finish() }

        tvBoll.setOnClickListener {
            showBOLL = !showBOLL
            tvBoll.setBackgroundResource(if (showBOLL) R.drawable.bg_indicator_on else R.drawable.bg_chart_overlay)
            tvBoll.setTextColor(if (showBOLL) ContextCompat.getColor(this, R.color.text_primary)
            else android.graphics.Color.WHITE)
            if (bars.isNotEmpty()) render()
        }

        val symbol = intent.getStringExtra(EXTRA_SYMBOL) ?: ""
        if (symbol.isBlank()) {
            tvStatus.text = "未指定股票"
            finish()
            return
        }
        tvStatus.text = "$symbol 加载日K中..."
        lifecycleScope.launch {
            try {
                bars = withContext(Dispatchers.IO) { MarketService.fetchKline(symbol, 240) }
                render()
            } catch (e: Exception) {
                tvStatus.text = "$symbol 加载K线失败：${e.message}"
            }
        }
    }

    private fun render() {
        if (bars.isEmpty()) {
            tvStatus.text = "未获取到K线数据"
            return
        }
        val n = bars.size
        val candleSet = CandleDataSet(
            bars.mapIndexed { i, b -> CandleEntry(i.toFloat(), b.high.toFloat(), b.low.toFloat(), b.open.toFloat(), b.close.toFloat()) },
            "日K"
        ).apply {
            color = Color.GRAY
            shadowColor = Color.DKGRAY
            shadowWidth = 0.7f
            decreasingColor = Color.parseColor("#43A047")
            increasingColor = Color.parseColor("#E53935")
            neutralColor = Color.DKGRAY
            increasingPaintStyle = Paint.Style.FILL
            decreasingPaintStyle = Paint.Style.FILL
            setDrawValues(false)
        }
        val data = CombinedData()
        data.setData(CandleData(candleSet))

        // 均线 MA5/MA10/MA20
        val maSets = listOf(
            Triple(5, "#E8B04A", "MA5"),
            Triple(10, "#4A90E8", "MA10"),
            Triple(20, "#9B59B6", "MA20")
        ).map { (p, c, label) ->
            LineDataSet(maLine(p), label).apply {
                color = Color.parseColor(c)
                lineWidth = 1.4f
                setDrawCircles(false)
                setDrawValues(false)
            }
        }
        val maLineData = LineData(maSets)
        maLineData.setDrawValues(false)
        data.setData(maLineData)

        // BOLL(20,2)
        if (showBOLL) {
            val (mid, up, low) = boll(bars.map { it.close })
            val bollSets = listOf(
                Triple(mid, "#E8B04A", "MID"),
                Triple(up, "#E74C3C", "UP"),
                Triple(low, "#27AE60", "LOW")
            ).map { (v, c, label) ->
                LineDataSet(v.mapIndexed { i, x -> Entry(i.toFloat(), x.toFloat()) }, label).apply {
                    color = Color.parseColor(c)
                    lineWidth = 1.2f
                    setDrawCircles(false)
                    setDrawValues(false)
                }
            }
            val bollLine = LineData(bollSets)
            bollLine.setDrawValues(false)
            data.setData(bollLine)
        }

        chart.apply {
            this.data = data
            description.isEnabled = false
            legend.isEnabled = true
            legend.textSize = 10f
            setScaleEnabled(true)
            setPinchZoom(true)
            styleDateAxis(this, n)
            axisRight.isEnabled = true
            styleChart(this)
            invalidate()
        }
        tvStatus.text = "日K　共$n 根　最新收盘 ${String.format("%.2f", bars.last().close)}"
    }

    private fun maLine(period: Int): List<Entry> {
        val out = ArrayList<Entry>()
        var sum = 0.0
        for (i in bars.indices) {
            sum += bars[i].close
            if (i < period - 1) continue
            if (i >= period) sum -= bars[i - period].close
            out.add(Entry(i.toFloat(), (sum / period).toFloat()))
        }
        return out
    }

    /** 布林带(20,2)：返回 MID/UP/LOW 三序列。 */
    private fun boll(closes: List<Double>): Triple<List<Double>, List<Double>, List<Double>> {
        val n = closes.size
        val mid = ArrayList<Double>(n)
        val up = ArrayList<Double>(n)
        val low = ArrayList<Double>(n)
        var sum = 0.0
        val p = 20
        for (i in 0 until n) {
            sum += closes[i]
            if (i < p - 1) { mid.add(Double.NaN); up.add(Double.NaN); low.add(Double.NaN); continue }
            if (i >= p) sum -= closes[i - p]
            val m = sum / p
            var ss = 0.0
            for (j in (i - p + 1)..i) { val d = closes[j] - m; ss += d * d }
            val sd = Math.sqrt(ss / p)
            mid.add(m); up.add(m + 2 * sd); low.add(m - 2 * sd)
        }
        return Triple(mid, up, low)
    }

    private fun styleDateAxis(c: com.github.mikephil.charting.charts.BarLineChartBase<*>, n: Int) {
        c.xAxis.position = XAxis.XAxisPosition.BOTTOM
        c.xAxis.setGranularityEnabled(true)
        c.xAxis.granularity = 1f
        c.xAxis.setAvoidFirstLastClipping(true)
        c.xAxis.labelCount = if (n > 40) 6 else minOf(5, maxOf(3, n))
        c.xAxis.axisMinimum = -0.5f
        c.xAxis.axisMaximum = (n - 1) + 0.5f
        c.xAxis.valueFormatter = object : ValueFormatter() {
            override fun getAxisLabel(value: Float, axis: AxisBase?): String {
                val idx = value.toInt()
                return if (idx in bars.indices) bars[idx].date.takeLast(5) else ""
            }
        }
        c.xAxis.setLabelCount(c.xAxis.labelCount, false)
    }

    private fun styleChart(c: com.github.mikephil.charting.charts.BarLineChartBase<*>) {
        c.xAxis.textColor = chartTextColor
        c.axisLeft.textColor = chartTextColor
        c.axisRight.textColor = chartTextColor
        c.xAxis.gridColor = chartGridColor
        c.axisLeft.gridColor = chartGridColor
        c.axisRight.gridColor = chartGridColor
        c.axisLeft.axisLineColor = chartGridColor
        c.axisRight.axisLineColor = chartGridColor
        c.xAxis.axisLineColor = chartGridColor
        c.legend.textColor = chartTextColor
        c.legend.form = com.github.mikephil.charting.components.Legend.LegendForm.LINE
    }
}
