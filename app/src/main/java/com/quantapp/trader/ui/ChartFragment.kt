package com.quantapp.trader.ui

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
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
import com.quantapp.trader.data.WatchStore
import com.quantapp.trader.trading.App
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
    private var showBOLL = false
    private var showKDJ = false

    private lateinit var chart: CombinedChart
    private lateinit var volChart: BarChart
    private lateinit var macdChart: CombinedChart
    private lateinit var kdjChart: CombinedChart
    private lateinit var tvMA: TextView
    private lateinit var tvVOL: TextView
    private lateinit var tvMACD: TextView
    private lateinit var tvBOLL: TextView
    private lateinit var tvKDJ: TextView
    private lateinit var tvInfo: TextView

    private val UP_COLOR = "#E53935"
    private val DOWN_COLOR = "#43A047"

    // 图表文字/网格颜色：随主题解析，避免深色主题下黑字看不清。
    private var chartTextColor = 0xFF1A1A1A.toInt()
    private var chartGridColor = 0xFFE6E8EB.toInt()

    private fun resolveThemeColors() {
        chartTextColor = ContextCompat.getColor(requireContext(), R.color.text_secondary)
        chartGridColor = ContextCompat.getColor(requireContext(), R.color.divider)
    }

    private val periodButtons = linkedMapOf<Period, TextView>()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val root = inflater.inflate(R.layout.fragment_chart, container, false)
        resolveThemeColors()
        chart = root.findViewById(R.id.kline_chart)
        volChart = root.findViewById(R.id.vol_chart)
        macdChart = root.findViewById(R.id.macd_chart)
        kdjChart = root.findViewById(R.id.kdj_chart)
        tvMA = root.findViewById(R.id.tv_ma)
        tvVOL = root.findViewById(R.id.tv_vol)
        tvMACD = root.findViewById(R.id.tv_macd)
        tvBOLL = root.findViewById(R.id.tv_boll)
        tvKDJ = root.findViewById(R.id.tv_kdj)
        tvInfo = root.findViewById(R.id.tv_chart_info)
        tvInfo.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))

        periodButtons[Period.MINUTE] = root.findViewById(R.id.tv_period_minute)
        periodButtons[Period.DAY] = root.findViewById(R.id.tv_period_day)
        periodButtons[Period.WEEK] = root.findViewById(R.id.tv_period_week)
        periodButtons[Period.YEAR] = root.findViewById(R.id.tv_period_year)
        periodButtons[Period.M120] = root.findViewById(R.id.tv_period_120)

        tvMA.setOnClickListener { showMA = !showMA; refreshIndicatorAppearance(); if (period != Period.MINUTE) renderCharts() }
        tvVOL.setOnClickListener { showVOL = !showVOL; refreshIndicatorAppearance(); if (period != Period.MINUTE) renderCharts() else renderTrendVolume() }
        tvMACD.setOnClickListener { showMACD = !showMACD; refreshIndicatorAppearance(); if (period != Period.MINUTE) renderCharts() else renderTrendMacd() }
        tvBOLL.setOnClickListener { showBOLL = !showBOLL; refreshIndicatorAppearance(); if (period != Period.MINUTE) renderCharts() }
        tvKDJ.setOnClickListener { showKDJ = !showKDJ; refreshIndicatorAppearance(); if (period != Period.MINUTE) renderCharts() else renderTrendKdj() }
        refreshIndicatorAppearance()

        // 去除了输入股票代码加载——该功能与行情页重复。symbol 由行情页点击自选股/卡片时注入。
        symbol = MainActivity.pendingChartSymbol ?: ""
        MainActivity.pendingChartSymbol = null

        // 未加入自选时显示“＋ 自选”按钮，点击后写入自选
        val btnAddWatch = root.findViewById<Button>(R.id.btn_add_watch)
        updateWatchButton(btnAddWatch)
        btnAddWatch.setOnClickListener { addToWatch(btnAddWatch) }

        // 周期切换
        periodButtons.forEach { (p, tv) ->
            tv.setOnClickListener { switchPeriod(p) }
        }

        updatePeriodButtonAppearance()

        // 手动买入/卖出（操作模拟盘账户）
        root.findViewById<Button>(R.id.btn_manual_buy).setOnClickListener { manualTrade(isBuy = true) }
        root.findViewById<Button>(R.id.btn_manual_sell).setOnClickListener { manualTrade(isBuy = false) }

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

    /** 刷新“＋自选”按钮：已加入自选时置灰禁用，未加入时高亮可点击。 */
    private fun updateWatchButton(btn: Button) {
        val added = symbol.isNotBlank() && WatchStore.contains(symbol)
        btn.text = if (added) "已加自选" else "＋ 自选"
        btn.isEnabled = !added
        btn.backgroundTintList = android.content.res.ColorStateList.valueOf(
            ContextCompat.getColor(requireContext(), if (added) R.color.text_secondary else R.color.brand)
        )
    }

    /** 加入自选：先取名称（行情接口失败则回退代码），再写入自选存储。 */
    private fun addToWatch(btn: Button) {
        if (symbol.isBlank()) return
        btn.isEnabled = false
        AppScope.launch {
            val name = try {
                withContext(Dispatchers.IO) { MarketService.fetchName(symbol) }
            } catch (e: Exception) { "" }
            val added = WatchStore.add(symbol, name)
            toast(if (added) "已加入自选：${name.ifEmpty { symbol }}（$symbol）" else "该股票已在自选中")
            updateWatchButton(btn)
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

    /**
     * 手动买入 / 卖出：弹出输入框，可填写成交价、金额、股数，对模拟盘账户下单。
     * 买入：填写股数（100整数倍）或金额其一；卖出：可输入股数部分平仓，留空=全部。
     */
    private fun manualTrade(isBuy: Boolean) {
        if (symbol.isBlank()) { toast("未指定股票"); return }
        val code = symbol
        AppScope.launch {
            val quote = try {
                withContext(Dispatchers.IO) { MarketService.fetchQuote(code) }
            } catch (e: Exception) { null }
            if (quote == null || quote.price <= 0) { toast("获取行情失败，请稍后重试"); return@launch }
            showManualDialog(isBuy, code, quote.price, quote.name)
        }
    }

    private fun showManualDialog(isBuy: Boolean, code: String, price: Double, quoteName: String) {
        val store = App.appStore
        val pos = store.paper.positions[code]
        val name = quoteName.ifEmpty { code }
        val ctx = requireContext()
        val maxQty = pos?.qty ?: 0

        val priceEt = tradeField(ctx, value = String.format("%.2f", price), inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL, hint = "成交价，可修改")
        val qtyEt = tradeField(ctx, value = "", inputType = InputType.TYPE_CLASS_NUMBER,
            hint = if (isBuy) "100的整数倍，留空则按金额算" else "留空=全部，最多 $maxQty 股")
        val amountEt = if (isBuy) tradeField(ctx, value = "",
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL, hint = "输入金额自动换算股数") else null

        // 实时换算提示行
        val hint = TextView(ctx).apply {
            textSize = 12f
            setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
            setPadding(8, 6, 0, 0)
        }

        var suppress = false
        fun refreshHint() {
            val p = priceEt.text.toString().toDoubleOrNull() ?: 0.0
            if (isBuy) {
                val q = qtyEt.text.toString().toIntOrNull() ?: 0
                hint.text = if (q > 0 && p > 0)
                    "约需资金 ¥${"%.0f".format(q * p)}（${q / 100} 手），可用 ¥${"%.0f".format(store.paper.cash)}"
                else "可用资金 ¥${"%.0f".format(store.paper.cash)}"
            } else {
                val q = qtyEt.text.toString().toIntOrNull()?.coerceIn(0, maxQty) ?: maxQty
                hint.text = if (p > 0)
                    "可得约 ¥${"%.0f".format(q * p)}（当前可卖 $maxQty 股）"
                else "当前可卖 $maxQty 股"
            }
        }
        fun setQty(q: Int) { suppress = true; qtyEt.setText(if (q > 0) "$q" else ""); suppress = false; refreshHint() }
        fun setAmount(a: Double) { suppress = true; amountEt?.setText("${"%.0f".format(a)}"); suppress = false; refreshHint() }

        // 联动换算：价 ↔ 金额 ↔ 股数
        priceEt.addTextChangedListener(textWatcher { refreshHint() })
        qtyEt.addTextChangedListener(textWatcher {
            if (suppress) return@textWatcher
            val q = qtyEt.text.toString().toIntOrNull() ?: 0
            if (isBuy) {
                val p = priceEt.text.toString().toDoubleOrNull() ?: 0.0
                if (q > 0 && p > 0) setAmount(q * p) else refreshHint()
            } else refreshHint()
        })
        amountEt?.addTextChangedListener(textWatcher {
            if (suppress) return@textWatcher
            val a = amountEt?.text?.toString()?.toDoubleOrNull() ?: 0.0
            val p = priceEt.text.toString().toDoubleOrNull() ?: 0.0
            if (a > 0 && p > 0) {
                val q = (a / p / 100.0).toInt() * 100
                if (q >= 100) setQty(q) else refreshHint()
            } else refreshHint()
        })

        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 4, 48, 0) }
        appendTradeField(col, "成交价", priceEt)
        appendTradeField(col, if (isBuy) "买入股数" else "卖出股数", qtyEt)
        if (amountEt != null) appendTradeField(col, "买入金额", amountEt)
        col.addView(hint)
        refreshHint()

        val title = if (isBuy) "手动买入 $name"
            else "手动卖出 $name" + if (!isBuy && pos != null) "（持仓 $maxQty 股）" else ""
        val dialog = AlertDialog.Builder(ctx)
            .setTitle(title)
            .setView(col)
            .setPositiveButton("确认", null) // null: 手动校验后再 dismiss
            .setNegativeButton("取消", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(ContextCompat.getColor(ctx, R.color.on_brand))
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val p = priceEt.text.toString().toDoubleOrNull()
                if (p == null || p <= 0) { toast("成交价无效"); return@setOnClickListener }
                var qty = qtyEt.text.toString().toIntOrNull() ?: 0
                if (isBuy) {
                    if (qty <= 0) {
                        val amt = (amountEt?.text?.toString())?.toDoubleOrNull() ?: 0.0
                        if (amt <= 0) { toast("请输入买入股数或金额"); return@setOnClickListener }
                        qty = (amt / p / 100.0).toInt() * 100
                    }
                    if (qty < 100) { toast("买入股数需为100的整数倍且≥100股"); return@setOnClickListener }
                } else {
                    if (qty <= 0) qty = maxQty // 留空=全部
                }
                executeManual(isBuy, code, name, p, qty)
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun tradeField(ctx: Context, value: String, inputType: Int, hint: String): EditText =
        EditText(ctx).apply {
            this.inputType = inputType
            this.hint = hint
            textSize = 14f
            setHintTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
            if (value.isNotEmpty()) setText(value)
        }

    private fun appendTradeField(col: LinearLayout, label: String, et: EditText) {
        val ctx = col.context
        val tv = TextView(ctx).apply {
            text = label
            setTextSize(13f)
            setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = if (col.childCount == 0) 0 else 18 }
        }
        col.addView(tv)
        et.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = 4 }
        col.addView(et)
    }

    private fun textWatcher(onChanged: () -> Unit): android.text.TextWatcher =
        object : android.text.TextWatcher {
            override fun afterTextChanged(s: Editable?) { onChanged() }
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
        }

    private fun executeManual(isBuy: Boolean, code: String, name: String, price: Double, qty: Int) {
        val store = App.appStore
        if (isBuy) {
            val q = (qty.coerceAtLeast(0))
            val target = q * price
            val t = store.paper.buy(code, name, price, target)
            if (t == null) toast("买入失败：现金不足或不足一手")
            else toast("已买入 $name ${t.qty}股 @ ${"%.2f".format(price)}")
        } else {
            val pos = store.paper.positions[code]
            if (pos == null) { toast("当前无 $code 持仓"); return }
            val q = if (pos.qty == 0) 0 else qty.coerceIn(1, pos.qty)
            val t = store.paper.sell(code, name, price, q)
            if (t == null) toast("卖出失败")
            else toast("已卖出 $name ${t.qty}股 @ ${"%.2f".format(price)}")
        }
        store.save()
    }

    private fun toast(msg: String) {
        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
    }

    private fun refreshIndicatorAppearance() {
        tvMA.setBackgroundResource(if (showMA) R.drawable.bg_indicator_on else R.drawable.bg_indicator_off)
        tvVOL.setBackgroundResource(if (showVOL) R.drawable.bg_indicator_on else R.drawable.bg_indicator_off)
        tvMACD.setBackgroundResource(if (showMACD) R.drawable.bg_indicator_on else R.drawable.bg_indicator_off)
        tvBOLL.setBackgroundResource(if (showBOLL) R.drawable.bg_indicator_on else R.drawable.bg_indicator_off)
        tvKDJ.setBackgroundResource(if (showKDJ) R.drawable.bg_indicator_on else R.drawable.bg_indicator_off)
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
            styleChart(this)
            invalidate()
        }
        volChart.visibility = View.VISIBLE
        macdChart.visibility = View.VISIBLE
        renderTrendVolume()
        renderTrendMacd()
        renderTrendKdj()
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
            styleChart(this)
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
            styleChart(this)
            invalidate()
        }
    }

    private fun renderCharts() {
        if (bars.isEmpty()) return
        renderPriceChart()
        renderVolume()
        renderMacd()
        renderKdj()
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
        if (showBOLL) {
            val (mid, up, low) = boll(bars.map { it.close })
            val bollSets = listOf(
                Triple(mid, "#E8B04A", "MID"),
                Triple(up, "#E74C3C", "UP"),
                Triple(low, "#27AE60", "LOW")
            ).map { (v, c, label) ->
                LineDataSet(LineEntries(v), label).apply {
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
            xAxis.position = XAxis.XAxisPosition.BOTTOM
            xAxis.labelCount = 6
            xAxis.valueFormatter = dateFormatter()
            axisRight.isEnabled = true
            styleChart(this)
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
            styleChart(this)
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
            styleChart(this)
            invalidate()
        }
    }

    // ---------- 指标计算 ----------
    private fun barEntries() = bars.mapIndexed { i, b ->
        CandleEntry(i.toFloat(), b.high.toFloat(), b.low.toFloat(), b.open.toFloat(), b.close.toFloat())
    }

    private fun renderKdj() {
        if (!showKDJ) { kdjChart.visibility = View.GONE; return }
        kdjChart.visibility = View.VISIBLE
        val (k, d, j) = kdj(bars.map { it.high }.toDoubleArray(), bars.map { it.low }.toDoubleArray(), bars.map { it.close }.toDoubleArray())
        renderKdjChart(k, d, j)
    }

    private fun renderTrendKdj() {
        if (!showKDJ) { kdjChart.visibility = View.GONE; return }
        kdjChart.visibility = View.VISIBLE
        val highs = trend.map { it.price }.toDoubleArray()
        val lows = trend.map { it.price }.toDoubleArray()
        val closes = trend.map { it.price }.toDoubleArray()
        val (k, d, j) = kdj(highs, lows, closes)
        renderKdjChart(k, d, j)
    }

    private fun renderKdjChart(k: List<Double>, d: List<Double>, j: List<Double>) {
        val kSet = LineDataSet(LineEntries(k), "K").apply {
            color = Color.parseColor("#F1C40F"); lineWidth = 1.4f; setDrawCircles(false); setDrawValues(false)
        }
        val dSet = LineDataSet(LineEntries(d), "D").apply {
            color = Color.parseColor("#4A90E8"); lineWidth = 1.4f; setDrawCircles(false); setDrawValues(false)
        }
        val jSet = LineDataSet(LineEntries(j), "J").apply {
            color = Color.parseColor("#9B59B6"); lineWidth = 1.2f; setDrawCircles(false); setDrawValues(false)
        }
        val data = CombinedData()
        val line = LineData(kSet, dSet, jSet)
        line.setDrawValues(false)
        data.setData(line)
        kdjChart.apply {
            this.data = data
            description.isEnabled = false
            legend.isEnabled = true
            legend.textSize = 10f
            xAxis.position = XAxis.XAxisPosition.BOTTOM
            xAxis.labelCount = 6
            xAxis.valueFormatter = if (bars.isNotEmpty()) dateFormatter() else trendFormatter()
            axisRight.isEnabled = false
            axisLeft.axisMinimum = 0f
            axisLeft.axisMaximum = 100f
            setScaleEnabled(true)
            setPinchZoom(true)
            styleChart(this)
            invalidate()
        }
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

    /** KDJ(9,3,3)：返回 K/D/J 三序列(0~100)。 */
    private fun kdj(h: DoubleArray, l: DoubleArray, c: DoubleArray): Triple<List<Double>, List<Double>, List<Double>> {
        val n = c.size
        val p = 9
        val k = ArrayList<Double>(n)
        val d = ArrayList<Double>(n)
        val j = ArrayList<Double>(n)
        var prevK = 50.0
        var prevD = 50.0
        for (i in 0 until n) {
            val start = (i - p + 1).coerceAtLeast(0)
            var hh = Double.MIN_VALUE
            var ll = Double.MAX_VALUE
            for (x in start..i) { if (h[x] > hh) hh = h[x]; if (l[x] < ll) ll = l[x] }
            val rsv = if (hh == ll) 50.0 else (c[i] - ll) / (hh - ll) * 100
            val curK = (2.0 / 3) * prevK + (1.0 / 3) * rsv
            val curD = (2.0 / 3) * prevD + (1.0 / 3) * curK
            prevK = curK
            prevD = curD
            k.add(curK); d.add(curD); j.add(3 * curK - 2 * curD)
        }
        return Triple(k, d, j)
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

    /** 统一图表文字/轴/网格颜色，保证深浅主题下可读。 */
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

    private fun trendFormatter() = object : ValueFormatter() {
        override fun getAxisLabel(value: Float, axis: AxisBase?): String {
            val idx = value.toInt()
            return if (idx in trend.indices) trend[idx].time.substring(11) else ""
        }
    }
}