package com.quantapp.trader.ui

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.fragment.app.Fragment
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ChartFragment : Fragment() {

    private var bars: List<KLine> = emptyList()
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
    private lateinit var etSymbol: EditText
    private lateinit var btnLoad: Button

    private val UP_COLOR = "#E53935"
    private val DOWN_COLOR = "#43A047"

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val root = inflater.inflate(R.layout.fragment_chart, container, false)
        etSymbol = root.findViewById(R.id.et_symbol)
        btnLoad = root.findViewById(R.id.btn_load)
        chart = root.findViewById(R.id.kline_chart)
        volChart = root.findViewById(R.id.vol_chart)
        macdChart = root.findViewById(R.id.macd_chart)
        tvMA = root.findViewById(R.id.tv_ma)
        tvVOL = root.findViewById(R.id.tv_vol)
        tvMACD = root.findViewById(R.id.tv_macd)
        tvInfo = root.findViewById(R.id.tv_chart_info)

        tvMA.setOnClickListener { showMA = !showMA; refreshIndicatorAppearance(); renderCharts() }
        tvVOL.setOnClickListener { showVOL = !showVOL; refreshIndicatorAppearance(); renderCharts() }
        tvMACD.setOnClickListener { showMACD = !showMACD; refreshIndicatorAppearance(); renderCharts() }
        refreshIndicatorAppearance()

        btnLoad.setOnClickListener { b ->
            val raw = etSymbol.text.toString().trim()
            if (raw.isEmpty()) return@setOnClickListener
            b.isEnabled = false
            tvInfo.text = "解析/加载中..."
            AppScope.launch {
                try {
                    // 支持股票名称：先解析为代码，再加载K线
                    val code = MarketService.resolveCode(raw)
                    if (code != raw) etSymbol.setText(code)
                    bars = withContext(Dispatchers.IO) { MarketService.fetchKline(code, 200) }
                    if (bars.isEmpty()) {
                        tvInfo.text = "未获取到数据，请检查代码或名称"
                    } else {
                        renderCharts()
                        val last = bars.last()
                        tvInfo.text = "$code　共${bars.size}根日K　最新收盘 ${String.format("%.2f", last.close)}"
                    }
                } catch (e: Exception) {
                    tvInfo.text = "加载失败：${e.message}"
                } finally {
                    b.isEnabled = true
                }
            }
        }

        // 由行情页点击自选股触达：自动加载该股票K线
        val pendingSymbol = MainActivity.pendingChartSymbol
        if (!pendingSymbol.isNullOrBlank()) {
            MainActivity.pendingChartSymbol = null
            etSymbol.setText(pendingSymbol)
            btnLoad.performClick()
        }
        return root
    }

    private fun refreshIndicatorAppearance() {
        tvMA.setBackgroundResource(if (showMA) R.drawable.bg_indicator_on else R.drawable.bg_indicator_off)
        tvVOL.setBackgroundResource(if (showVOL) R.drawable.bg_indicator_on else R.drawable.bg_indicator_off)
        tvMACD.setBackgroundResource(if (showMACD) R.drawable.bg_indicator_on else R.drawable.bg_indicator_off)
    }

    private fun renderCharts() {
        if (bars.isEmpty()) return
        renderPriceChart()
        renderVolume()
        renderMacd()
    }

    private fun renderPriceChart() {
        val candleSet = CandleDataSet(barEntries(), "日K").apply {
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
            // 以“万手”为展示单位，减小数值量级
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

    /** 均线：从第 period-1 根开始才有完整窗口。 */
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

    /** EMA 序列。seed 取前 period 根 SMA。 */
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

    /** MACD：返回 (DIF, DEA, MACD柱)。柱 = (DIF - DEA) * 2。 */
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
}