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
import com.github.mikephil.charting.charts.CandleStickChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.CandleData
import com.github.mikephil.charting.data.CandleDataSet
import com.github.mikephil.charting.data.CandleEntry
import com.github.mikephil.charting.components.AxisBase
import com.github.mikephil.charting.formatter.ValueFormatter
import com.quantapp.trader.R
import com.quantapp.trader.data.MarketService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ChartFragment : Fragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val root = inflater.inflate(R.layout.fragment_chart, container, false)
        val etSymbol = root.findViewById<EditText>(R.id.et_symbol)
        val btnLoad = root.findViewById<Button>(R.id.btn_load)
        val chart = root.findViewById<CandleStickChart>(R.id.kline_chart)
        val tvInfo = root.findViewById<TextView>(R.id.tv_chart_info)

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
                    val bars = withContext(Dispatchers.IO) { MarketService.fetchKline(code, 140) }
                    if (bars.isEmpty()) {
                        tvInfo.text = "未获取到数据，请检查代码或名称"
                    } else {
                        renderCandles(chart, bars)
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

    private fun renderCandles(chart: CandleStickChart, bars: List<com.quantapp.trader.data.KLine>) {
        val entries = ArrayList<CandleEntry>()
        bars.forEachIndexed { i, b ->
            val up = b.close >= b.open
            entries.add(CandleEntry(i.toFloat(), b.high.toFloat(), b.low.toFloat(), b.open.toFloat(), b.close.toFloat()))
        }
        val set = CandleDataSet(entries, "日K").apply {
            color = Color.GRAY
            shadowColor = Color.DKGRAY
            shadowWidth = 0.7f
            decreasingColor = Color.parseColor("#43A047")
            increasingColor = Color.parseColor("#E53935")
            neutralColor = Color.DKGRAY
            increasingPaintStyle = android.graphics.Paint.Style.FILL
            decreasingPaintStyle = android.graphics.Paint.Style.FILL
            valueTextSize = 8f
            setDrawValues(false)
        }
        val data = CandleData(set)
        chart.data = data
        chart.description.isEnabled = false
        chart.legend.isEnabled = false
        chart.setScaleEnabled(true)
        chart.setPinchZoom(true)
        val x = chart.xAxis
        x.position = XAxis.XAxisPosition.BOTTOM
        x.labelCount = 8
        x.valueFormatter = object : ValueFormatter() {
            override fun getAxisLabel(value: Float, axis: AxisBase?): String {
                val idx = value.toInt()
                return if (idx in bars.indices) bars[idx].date.takeLast(5) else ""
            }
        }
        chart.invalidate()
    }
}