package com.quantapp.trader.ui

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.github.mikephil.charting.charts.BarChart
import com.github.mikephil.charting.charts.CombinedChart
import com.github.mikephil.charting.components.AxisBase
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.BarData
import com.github.mikephil.charting.data.BarDataSet
import com.github.mikephil.charting.data.BarEntry
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
import com.quantapp.trader.data.MarketService.TrendPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ChartFragment : Fragment() {

    private enum class Period(val label: String) {
        MINUTE("分时"), DAY("日线"), WEEK("周线"), YEAR("年线"), M120("120分时")
    }

    private var symbol: String = ""

    /** 当前周期数据。K线周期用 bars；分时用 trend。 */
    private var bars: List<KLine> = emptyList()
    private var trend: List<TrendPoint> = emptyList()

    private var period: Period = Period.MINUTE
    private var showMA = true
    private var showVOL = true
    private var showMACD = true

    private lateinit var chart: CombinedChart
    private lateinit var volChart: BarChart
    private lateinit var macdChart: CombinedChart
    private lateinit var tvMA: TextView
    private lateinit var tvVOL: TextView
    private lateinit var tvMACD: TextView
    private lateinit var tvInfo: TextView

    private val UP_COLOR = "#E53935"
    private val DOWN_COLOR = "#43A047"

    private val periodButtons = linkedMapOf<Period, TextView>()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val root = inflater.inflate(R.layout.fragment_chart, container, false)
        chart = root.findViewById(R.id.kline_chart)
        volChart = root.findViewById(R.id.vol_chart)
        macdChart = root.findViewById(R.id.macd_chart)
        tvMA = root.findViewById(R.id.tv_ma)
        tvVOL = root.findViewById(R.id.tv_vol)
        tvMACD = root.findViewById(R.id.tv_macd)
        tvInfo = root.findViewById(R.id.tv_chart_info)

        periodButtons[Period.MINUTE] = root.findViewById(R.id.tv_period_minute)
        periodButtons[Period.DAY] = root.findViewById(R.id.tv_period_day)
        periodButtons[Period.WEEK] = root.findViewById(R.id.tv_period_week)
        periodButtons[Period.YEAR] = root.findViewById(R.id.tv_period_year)
        periodButtons[Period.M120] = root.findViewById(R.id.tv_period_120)

        tvMA.setOnClickListener { showMA = !showMA; refreshIndicatorAppearance(); if (period != Period.MINUTE) renderCharts() }
        tvVOL.setOnClickListener { showVOL = !showVOL; refreshIndicatorAppearance(); if (period != Period.MINUTE) renderCharts() }
        tvMACD.setOnClickListener { showMACD = !showMACD; refreshIndicatorAppearance(); if (period != Period.MINUTE) renderCharts() }
        refreshIndicatorAppearance()

        // 去除了输入股票代码加载——该功能与行情页重复。symbol 由行情页点击自选股/卡片时注入。
        symbol = MainActivity.pendingChartSymbol ?: ""
        MainActivity.pendingChartSymbol = null

        // 周期切换
        periodButtons.forEach { (p, tv) ->
            tv.setOnClickListener { switchPeriod(p) }
        }

        updatePeriodButtonAppearance()

        if (symbol.isBlank()) {
            tvInfo.text = "未指定股票"
        } else {
            loadPeriod(Period.MINUTE, firstLoad = true)
        }

        // 分时图实时刷新
        viewLifecycleOwner.lifecycleScope.launch {
            while (isActive) {
                delay(10_000)
                if (period == Period.MINUTE && symbol.isNotBlank() && isVisible) {
                    loadTrend(silent = true)
                }
            }
        }
        return root
    }

    private fun switchPeriod(p: Period) {
        if (period == p) return
        period = p
        updatePeriodButtonAppearance()
        loadPeriod(p)
    }

    private fun updatePeriodButtonAppearance() {
        periodButtons.forEach { (p, tv) ->
            val active = p == period
            tv.setBackgroundResource(if (active) R.drawable.bg_indicator_on else R.drawable.bg_indicator_off)
            tv.setTypeface(null, if (active) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            tv.alpha = if (active) 1f else 0.72f
        }
    }

    private fun loadPeriod(p: Period, firstLoad: Boolean = false) {
        tvInfo.text = when {
            symbol.isBlank() -> "未指定股票"
            else -> "加载${p.label}中..."
        }
        AppScope.launch {
            try {
                when (p) {
                    Period.MINUTE -> {
                        withContext(Dispatchers.IO) { trend = MarketService.fetchTrend(symbol) }
                        if (trend.isEmpty()) tvInfo.text = "未获取到分时数据"
                        else { renderTrend(); tvInfo.text = "$symbol　分时图（最后一分钟 ${String.format("%.2f", trend.last().price)}）" }
                    }
                    Period.DAY -> {
                        bars = withContext(Dispatchers.IO) { MarketService.fetchKline(symbol, 240) }
                        postCandle(p, "日K")
                    }
                    Period.WEEK -> {
                        bars = withContext(Dispatchers.IO) { MarketService.fetchKlineBy(symbol, klt = 102, limit = 400) }
                        postCandle(p, "周K")
                    }
                    Period.YEAR -> {
                        bars = withContext(Dispatchers.IO) { MarketService.fetchYearKline(symbol) }
                        postCandle(p, "年K")
                    }
                    Period.M120 -> {
                        bars = withContext(Dispatchers.IO) { MarketService.fetch120Kline(symbol) }
                        postCandle(p, "120分K")
                    }
                }
            } catch (e: Exception) {
                tvInfo.text = "加载${p.label}失败：${e.message}"
            }
        }
    }

    private fun postCandle(p: Period, label: String) {
        if (bars.isEmpty()) {
            tvInfo.text = "未获取到${p.label}数据"
            return
        }
        renderCharts()
        val last = bars.last()
        tvInfo.text = "$symbol　$label　共${bars.size}根　最新收盘 ${String.format("%.2f", last.close)}"
    }

    private fun loadTrend(silent: Boolean) {
        AppScope.launch {
            try {
                val t = withContext(Dispatchers.IO) { MarketService.fetchTrend(symbol) }
                if (t.isNotEmpty()) { trend = t; renderTrend() }
            } catch (e: Exception) { /* 静默失败，等待下轮 */ }
        }
    }

    private fun refreshIndicatorAppearance() {
        tvMA.setBackgroundResource(if (showMA) R.drawable.bg_indicator_on else R.drawable.bg_indicator_off)
        tvVOL.setBackgroundResource(if (showVOL) R.drawable.bg_indicator_on else R.drawable.bg_indicator_off)
        tvMACD.setBackgroundResource(if (showMACD) R.drawable.bg_indicator_on else R.drawable.bg_indicator_off)
    }

    /** 分时图：以折线绘制每分钟价格。 */
    private fun renderTrend() {
        val entries = trend.mapIndexed { i, t -> Entry(i.toFloat(), t.price.toFloat()) }
        val set = LineDataSet(entries, "分时").apply {
            color = Color.parseColor("#2196F3")
            lineWidth = 1.6f
            setDrawCircles(false)
            setDrawValues(false)
            mode = LineDataSet.Mode.LINEAR
        }
        val data = CombinedData()
        data.setData(LineData(set))
        chart.apply {
            this.data = data
            description.isEnabled = false
            legend.isEnabled = true
            legend.textSize = 10f
            xAxis.position = XAxis.XAxisPosition.BOTTOM
            xAxis.labelCount = 5
            xAxis.valueFormatter = trendFormatter()
            axisRight.isEnabled = false
            setScaleEnabled(true)
            setPinchZoom(true)
            invalidate()
        }
        volChart.visibility = View.VISIBLE
        macdChart.visibility = View.VISIBLE
        renderTrendVolume()
        renderTrendMacd()
    }

    /** 分时成交量柱状图：现价较上一点上涨显红，下跌显绿。 */
    private fun renderTrendVolume() {
        if (!showVOL) { volChart.visibility = View.GONE; return }
        volChart.visibility = View.VISIBLE
        val colors = ArrayList<Int>()
        val entries = ArrayList<BarEntry>()
        var prev = trend.firstOrNull()?.price ?: 0.0
        trend.forEachIndexed { i, t ->
            val up = if (i == 0) t.price >= prev else t.price >= prev
            prev = t.price
            entries.add(BarEntry(i.toFloat(), (t.volume / 100f).toFloat()))
            colors.add(Color.parseColor(if (up) UP_COLOR else DOWN_COLOR))
        }
        val set = BarDataSet(entries, "成交量").apply {
            setColors(colors)
            setDrawValues(false)
        }
        volChart.apply {
            data = BarData(set)
            description.isEnabled = false
            legend.isEnabled = false
            xAxis.position = XAxis.XAxisPosition.BOTTOM
            xAxis.labelCount = 6
            xAxis.valueFormatter = trendFormatter()
            axisRight.isEnabled = false
            axisLeft.axisMinimum = 0f
            setScaleEnabled(true)
            setPinchZoom(true)
            invalidate()
        }
    }

    /** 分时 MACD：基于分时价格序列计算并绘制。 */
    private fun renderTrendMacd() {
        if (!showMACD) { macdChart.visibility = View.GONE; return }
        // 数据点不足(需>=26以计算EMA)时暂不绘制
        if (trend.size < 26) { macdChart.visibility = View.GONE; return }
        macdChart.visibility = View.VISIBLE
        val (dif, dea, hist) = macd(trend.map { it.price })

        val colors = ArrayList<Int>()
        val histEntries = ArrayList<BarEntry>()
        hist.forEachIndexed { i, v ->
            histEntries.add(BarEntry(i.toFloat(), v.toFloat()))
            colors.add(Color.parseColor(if (v >= 0) UP_COLOR else DOWN_COLOR))
        }
        val histSet = BarDataSet(histEntries, "MACD柱").apply {
            setColors(colors)
            setDrawValues(false)
        }
        val difSet = LineDataSet(LineEntries(dif), "DIF").apply {
            color = Color.parseColor("#F1C40F")
            lineWidth = 1.4f
            setDrawCircles(false)
            setDrawValues(false)
        }
        val deaSet = LineDataSet(LineEntries(dea), "DEA").apply {
            color = Color.parseColor("#E74C3C")
            lineWidth = 1.4f
            setDrawCircles(false)
            setDrawValues(false)
        }
        val data = CombinedData()
        data.setData(BarData(histSet))
        val line = LineData(difSet, deaSet)
        line.setDrawValues(false)
        data.setData(line)

        macdChart.apply {
            this.data = data
            description.isEnabled = false
            legend.isEnabled = true
            legend.textSize = 10f
            xAxis.position = XAxis.XAxisPosition.BOTTOM
            xAxis.labelCount = 6
            xAxis.valueFormatter = trendFormatter()
            axisRight.isEnabled = false
            setScaleEnabled(true)
            setPinchZoom(true)
            invalidate()
        }
    }

    private fun renderCharts() {
        if (bars.isEmpty()) return
        renderPriceChart()
        renderVolume()
        renderMacd()
    }

    private fun renderPriceChart() {
        val candleSet = CandleDataSet(barEntries(), period.label + "K").apply {
            color = Color.GRAY
            shadowColor = Color.DKGRAY
            shadowWidth = 0.7f
            decreasingColor = Color.parseColor(DOWN_COLOR)
            increasingColor = Color.parseColor(UP_COLOR)
            neutralColor = Color.DKGRAY
            increasingPaintStyle = android.graphics.Paint.Style.FILL
            decreasingPaintStyle = android.graphics.Paint.Style.FILL
            setDrawValues(false)
        }
        val data = CombinedData()
        data.setData(CandleData(candleSet))

        if (showMA) {
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
            val lineData = LineData(maSets)
            lineData.setDrawValues(false)
            data.setData(lineData)
        }
        chart.apply {
            this.data = data
            description.isEnabled = false
            legend.isEnabled = true
            legend.textSize = 10f
            setScaleEnabled(true)
            setPinchZoom(true)
            xAxis.position = XAxis.XAxisPosition.BOTTOM
            xAxis.labelCount = 6
            xAxis.valueFormatter = dateFormatter()
            axisRight.isEnabled = true
            invalidate()
        }
    }

    private fun renderVolume() {
        if (!showVOL) { volChart.visibility = View.GONE; return }
        volChart.visibility = View.VISIBLE
        val colors = ArrayList<Int>()
        val entries = ArrayList<BarEntry>()
        bars.forEachIndexed { i, b ->
            entries.add(BarEntry(i.toFloat(), (b.volume / 1_0000_00f)))
            colors.add(Color.parseColor(if (b.close >= b.open) UP_COLOR else DOWN_COLOR))
        }
        val set = BarDataSet(entries, "成交量").apply {
            setColors(colors)
            setDrawValues(false)
        }
        volChart.apply {
            data = BarData(set)
            description.isEnabled = false
            legend.isEnabled = false
            xAxis.position = XAxis.XAxisPosition.BOTTOM
            xAxis.labelCount = 6
            xAxis.valueFormatter = dateFormatter()
            axisRight.isEnabled = false
            axisLeft.axisMinimum = 0f
            setScaleEnabled(true)
            setPinchZoom(true)
            invalidate()
        }
    }

    private fun renderMacd() {
        if (!showMACD) { macdChart.visibility = View.GONE; return }
        macdChart.visibility = View.VISIBLE
        val (dif, dea, hist) = macd(bars.map { it.close })

        val colors = ArrayList<Int>()
        val histEntries = ArrayList<BarEntry>()
        hist.forEachIndexed { i, v ->
            histEntries.add(BarEntry(i.toFloat(), v.toFloat()))
            colors.add(Color.parseColor(if (v >= 0) UP_COLOR else DOWN_COLOR))
        }
        val histSet = BarDataSet(histEntries, "MACD柱").apply {
            setColors(colors)
            setDrawValues(false)
        }
        val difSet = LineDataSet(LineEntries(dif), "DIF").apply {
            color = Color.parseColor("#F1C40F")
            lineWidth = 1.4f
            setDrawCircles(false)
            setDrawValues(false)
        }
        val deaSet = LineDataSet(LineEntries(dea), "DEA").apply {
            color = Color.parseColor("#E74C3C")
            lineWidth = 1.4f
            setDrawCircles(false)
            setDrawValues(false)
        }
        val data = CombinedData()
        data.setData(BarData(histSet))
        val line = LineData(difSet, deaSet)
        line.setDrawValues(false)
        data.setData(line)

        macdChart.apply {
            this.data = data
            description.isEnabled = false
            legend.isEnabled = true
            legend.textSize = 10f
            xAxis.position = XAxis.XAxisPosition.BOTTOM
            xAxis.labelCount = 6
            xAxis.valueFormatter = dateFormatter()
            axisRight.isEnabled = false
            setScaleEnabled(true)
            setPinchZoom(true)
            invalidate()
        }
    }

    // ---------- 指标计算 ----------
    private fun barEntries() = bars.mapIndexed { i, b ->
        CandleEntry(i.toFloat(), b.high.toFloat(), b.low.toFloat(), b.open.toFloat(), b.close.toFloat())
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

    private fun LineEntries(v: List<Double>): List<Entry> =
        v.mapIndexed { i, x -> Entry(i.toFloat(), x.toFloat()) }

    private fun ema(values: DoubleArray, period: Int): DoubleArray {
        val out = DoubleArray(values.size)
        var seed = 0.0
        for (i in 0 until period) seed += values[i]
        out[period - 1] = seed / period
        val k = 2.0 / (period + 1)
        for (i in period until values.size) {
            out[i] = values[i] * k + out[i - 1] * (1 - k)
        }
        return out
    }

    private fun macd(closes: List<Double>): Triple<List<Double>, List<Double>, List<Double>> {
        val c = closes.toDoubleArray()
        val e12 = ema(c, 12)
        val e26 = ema(c, 26)
        val n = c.size
        val dif = DoubleArray(n)
        for (i in 0 until n) dif[i] = e12[i] - e26[i]
        val dea = ema(dif, 9)
        val hist = DoubleArray(n)
        for (i in 0 until n) hist[i] = (dif[i] - dea[i]) * 2
        return Triple(dif.toList(), dea.toList(), hist.toList())
    }

    private fun dateFormatter() = object : ValueFormatter() {
        override fun getAxisLabel(value: Float, axis: AxisBase?): String {
            val idx = value.toInt()
            return if (idx in bars.indices) bars[idx].date.takeLast(5) else ""
        }
    }

    private fun trendFormatter() = object : ValueFormatter() {
        override fun getAxisLabel(value: Float, axis: AxisBase?): String {
            val idx = value.toInt()
            return if (idx in trend.indices) trend[idx].time.substring(11) else ""
        }
    }
}