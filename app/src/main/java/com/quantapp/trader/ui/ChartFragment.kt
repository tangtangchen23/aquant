package com.quantapp.trader.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.view.LayoutInflater
import android.view.MotionEvent
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
import com.github.mikephil.charting.charts.BarLineChartBase
import com.github.mikephil.charting.charts.CombinedChart
import com.github.mikephil.charting.components.AxisBase
import com.github.mikephil.charting.components.LimitLine
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.components.YAxis
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
import com.github.mikephil.charting.listener.ChartTouchListener
import com.github.mikephil.charting.listener.OnChartGestureListener
import com.quantapp.trader.R
import com.quantapp.trader.data.KLine
import com.quantapp.trader.data.MarketService
import com.quantapp.trader.data.MarketService.TrendPoint
import com.quantapp.trader.data.WatchStore
import com.quantapp.trader.trading.App
import com.quantapp.trader.trading.PendingOrder
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
    private var showRange = false
    private var signals: List<BuySellSignal> = emptyList()

    private lateinit var chart: CombinedChart
    private lateinit var volChart: BarChart
    private lateinit var macdChart: CombinedChart
    private lateinit var kdjChart: CombinedChart
    private lateinit var tvMA: TextView
    private lateinit var tvVOL: TextView
    private lateinit var tvMACD: TextView
    private lateinit var tvBOLL: TextView
    private lateinit var tvKDJ: TextView
    private lateinit var tvRange: TextView
    private lateinit var tvInfo: TextView

    private val UP_COLOR = "#E53935"
    private val DOWN_COLOR = "#43A047"

    companion object {
        const val BUY = 0
        const val SELL = 1
    }

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
        tvRange = root.findViewById(R.id.tv_range)
        tvInfo = root.findViewById(R.id.tv_chart_info)
        tvInfo.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))

        // 十字光标：点按/拖动主图时显示十字虚线 + 顶部日期/价格浮窗
        chart.marker = CrosshairMarker(
            chart,
            infoOf = { idx -> markerText(idx) },
            isUpAt = { idx -> markerUp(idx) }
        )
        chart.setDrawMarkers(true)
        // 关键修复：CombinedChart 默认 mHighlightFullBarEnabled=true，触摸产生的高亮 dataIndex 恒为 -1，
        // 而绘制十字光标时 CombinedChart.drawMarkers() 会用 dataIndex=-1 去 getDataByIndex(-1) 取数据，
        // 触发 getAllData().get(-1) 的 IndexOutOfBoundsException（发生在 onDraw 主线程，try-catch 拦不住），
        // 导致“点按/滑动图表后切换周期即闪退”。关闭整柱高亮后高亮保留真实 dataSetIndex，绘制安全。
        chart.setHighlightFullBarEnabled(false)

        // 主图缩放/平移时，联动可见成交量/MACD/KDJ子图保持同一X轴范围
        chart.setOnChartGestureListener(object : OnChartGestureListener {
            override fun onChartGestureStart(e: MotionEvent?, gesture: ChartTouchListener.ChartGesture) {}
            override fun onChartGestureEnd(e: MotionEvent?, gesture: ChartTouchListener.ChartGesture) { syncSubCharts() }
            override fun onChartLongPressed(e: MotionEvent?) {}
            override fun onChartDoubleTapped(e: MotionEvent?) {}
            override fun onChartSingleTapped(e: MotionEvent?) {}
            override fun onChartFling(e1: MotionEvent?, e2: MotionEvent?, velocityX: Float, velocityY: Float) { syncSubCharts() }
            override fun onChartScale(e: MotionEvent?, scaleX: Float, scaleY: Float) { syncSubCharts() }
            override fun onChartTranslate(e: MotionEvent?, dX: Float, dY: Float) { syncSubCharts() }
        })

        // 子图禁用独立手势，统一由主图控制缩放联动
        listOf(volChart, macdChart, kdjChart).forEach { disableSubGestures(it) }

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
        tvRange.setOnClickListener { showRange = !showRange; refreshIndicatorAppearance(); if (period != Period.MINUTE) renderCharts() }
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
        // 切换周期前清除旧周期的十字光标高亮，避免旧高亮(陈旧 dataSetIndex)在重绘时引发越界
        chart.highlightValue(null)
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
        val ctx = requireContext()
        btn.text = if (added) "已加自选" else "＋ 自选"
        btn.isEnabled = !added
        btn.backgroundTintList = android.content.res.ColorStateList.valueOf(
            if (added) ContextCompat.getColor(ctx, R.color.text_disabled)
            else themeAttrColor(ctx, R.attr.brand)
        )
        btn.setTextColor(
            if (added) ContextCompat.getColor(ctx, R.color.text_secondary)
            else themeAttrColor(ctx, R.attr.onBrand)
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
                        else {
                            try {
                                renderTrend()
                            } catch (e: Exception) {
                                tvInfo.text = "分时渲染失败：${e.message}"
                                return@launch
                            }
                            tvInfo.text = "$symbol　分时图（最后一分钟 ${String.format("%.2f", trend.last().price)}）"
                        }
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
        try {
            renderCharts()
        } catch (e: Exception) {
            // 兜底：任一子图渲染异常都不应让应用闪退，给出提示
            tvInfo.text = "${p.label}渲染失败：${e.message}"
            return
        }
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

        val v = LayoutInflater.from(ctx).inflate(R.layout.dialog_manual_trade, null)
        val priceEt = v.findViewById<EditText>(R.id.et_price_dialog)
        val qtyEt = v.findViewById<EditText>(R.id.et_qty_dialog)
        val amountEt = v.findViewById<EditText>(R.id.et_amount_dialog)
        val tvLabelQty = v.findViewById<TextView>(R.id.tv_label_qty)
        val tvLabelAmount = v.findViewById<TextView>(R.id.tv_label_amount)
        val hint = v.findViewById<TextView>(R.id.tv_trade_hint)
        val badge = v.findViewById<TextView>(R.id.tv_manual_badge)
        val sub = v.findViewById<TextView>(R.id.tv_manual_sub)
        val section = v.findViewById<TextView>(R.id.tv_manual_section)
        val section2 = v.findViewById<TextView>(R.id.tv_manual_section2)
        val note = v.findViewById<TextView>(R.id.tv_manual_note)

        // 分节/徽标：A股红买绿卖
        val accent = ContextCompat.getColor(ctx, if (isBuy) R.color.up else R.color.down)
        badge.text = if (isBuy) "手动买入 $name" else "手动卖出 $name"
        badge.setBackgroundResource(if (isBuy) R.drawable.chip_buy else R.drawable.chip_sell)
        badge.setTextColor(accent)
        sub.text = if (isBuy)
            "现价已自动填入，可手工修改；仅挂单模式，现价触及后自动成交。"
        else "现价已自动填入，可手工修改；留空股数则卖出全部持仓。"
        section.setTextColor(accent)
        section2.setTextColor(accent)
        note.text = if (isBuy)
            "仅挂单模式：现价触及后自动成交，可在账户页查看或撤销。"
        else "仅挂单模式：现价触及后自动卖出 ${maxQty} 股，可在账户页查看或撤销。"

        // 字段初始化
        priceEt.setHint("挂单价格（可修改）")
        priceEt.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        priceEt.setText(String.format("%.2f", price))
        tvLabelQty.text = if (isBuy) "买入股数（100的整数倍）"
            else "卖出股数（留空=全部，最多 $maxQty 股）"
        qtyEt.inputType = InputType.TYPE_CLASS_NUMBER
        if (!isBuy) {
            tvLabelAmount.visibility = View.GONE
            amountEt.visibility = View.GONE
        } else {
            amountEt.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }

        // 实时换算提示行
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
        refreshHint()

        val dialog = AlertDialog.Builder(ctx)
            .setView(v)
            .setPositiveButton("确认", null) // null: 手动校验后再 dismiss
            .setNegativeButton("取消", null)
            .create().apply {
                window?.setBackgroundDrawableResource(R.drawable.bg_dialog)
                setOnShowListener {
                    val density = ctx.resources.displayMetrics.density
                    // 内容四周留白，避免文字贴着圆角卡片边缘
                    val ins = (12 * density).toInt()
                    window?.decorView?.setPadding(ins, (8 * density).toInt(), ins, (8 * density).toInt())
                    // 确认按钮：买入红色 / 卖出绿色（A股语义）实心胶囊
                    val accent = ContextCompat.getColor(ctx, if (isBuy) R.color.up else R.color.down)
                    getButton(AlertDialog.BUTTON_POSITIVE).apply {
                        background = GradientDrawable().apply {
                            setColor(accent)
                            cornerRadius = (22 * density).toFloat()
                        }
                        setTextColor(Color.WHITE)
                        textSize = 14f
                        setPadding((24 * density).toInt(), 0, (24 * density).toInt(), 0)
                    }
                    // 取消按钮：卡片底 + 描边幽灵胶囊
                    getButton(AlertDialog.BUTTON_NEGATIVE).apply {
                        background = GradientDrawable().apply {
                            setColor(ContextCompat.getColor(ctx, R.color.card_bg))
                            cornerRadius = (22 * density).toFloat()
                            setStroke(density.toInt(), ContextCompat.getColor(ctx, R.color.input_border))
                        }
                        setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
                        textSize = 14f
                        setPadding((24 * density).toInt(), 0, (24 * density).toInt(), 0)
                    }
                    getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val p = priceEt.text.toString().toDoubleOrNull()
                        if (p == null || p <= 0) { toast("价格无效"); return@setOnClickListener }
                        var qty = qtyEt.text.toString().toIntOrNull() ?: 0
                        var buyAmount = 0.0
                        if (isBuy) {
                            if (qty <= 0) {
                                buyAmount = (amountEt?.text?.toString())?.toDoubleOrNull() ?: 0.0
                                if (buyAmount <= 0) { toast("请输入买入股数或金额"); return@setOnClickListener }
                                qty = (buyAmount / p / 100.0).toInt() * 100
                            } else {
                                buyAmount = qty * p
                            }
                            if (qty < 100) { toast("买入股数需为100的整数倍且≥100股"); return@setOnClickListener }
                        } else {
                            if (qty <= 0) qty = maxQty
                            if (qty <= 0) { toast("当前无可卖持仓"); return@setOnClickListener }
                        }
                        // 挂单模式：现价触及挂单价格后才成交
                        val side = if (isBuy) "买入" else "卖出"
                        val order = PendingOrder(code, name, side, p,
                            qtyTarget = if (!isBuy) qty else 0,
                            amountTarget = if (isBuy) buyAmount else 0.0)
                        store.addPendingOrder(order)
                        store.save()
                        toast("已挂单：现价触及 ${"%.2f".format(p)} 后自动$side（可在账户页查看/撤销）")
                        dismiss()
                    }
                }
                show()
            }
    }

    private fun textWatcher(onChanged: () -> Unit): android.text.TextWatcher =
        object : android.text.TextWatcher {
            override fun afterTextChanged(s: Editable?) { onChanged() }
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
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
        tvRange.setBackgroundResource(if (showRange) R.drawable.bg_indicator_on else R.drawable.bg_indicator_off)
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
            // 分时图不显示 B/S 买卖点与横盘箱体
            signals = emptyList()
            axisLeft.removeAllLimitLines()
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
            styleDateAxis(this)
            axisRight.isEnabled = true
            styleChart(this)
            // B/S 买卖点标记（叠加在主图之上）
            signals = detectBuySell()
            renderer = SignalRenderer(this, animator, viewPortHandler) { signals }
            // 横盘区间：识别出震荡箱体后在上沿/下沿画虚线
            axisLeft.removeAllLimitLines()
            if (showRange) {
                val range = detectRange()
                if (range != null) {
                    axisLeft.addLimitLine(range.first)
                    axisLeft.addLimitLine(range.second)
                }
            }
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
            axisRight.isEnabled = false
            axisLeft.axisMinimum = 0f
            styleDateAxis(this)
            styleChart(this)
            invalidate()
        }
    }

    private fun renderMacd() {
        if (!showMACD) { macdChart.visibility = View.GONE; return }
        macdChart.visibility = View.VISIBLE
        // 数据不足一个MACD周期(26)时无法计算，渲染空面板避免 NaN 越界闪退
        if (bars.size < 26) {
            macdChart.data = CombinedData()
            macdChart.description.isEnabled = false
            macdChart.legend.isEnabled = false
            styleDateAxis(macdChart)
            styleChart(macdChart)
            macdChart.invalidate()
            return
        }
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
            axisRight.isEnabled = false
            styleDateAxis(this)
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
            if (bars.isNotEmpty()) styleDateAxis(this)
            else xAxis.valueFormatter = trendFormatter()
            axisRight.isEnabled = false
            axisLeft.axisMinimum = 0f
            axisLeft.axisMaximum = 100f
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
        // 数据不足一个周期时返回全 NaN，避免数组越界崩溃；调用方据此不绘制
        if (values.size < period) return DoubleArray(values.size) { Double.NaN }
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

    /** 主图十字光标顶部浮窗文案：K线显示日期+开高低收，分时显示时间+价格。 */
    private fun markerText(idx: Int): String {
        return if (bars.isNotEmpty() && idx in bars.indices) {
            val b = bars[idx]
            "${b.date}\n开 ${fmt(b.open)}  高 ${fmt(b.high)}  低 ${fmt(b.low)}  收 ${fmt(b.close)}"
        } else if (trend.isNotEmpty() && idx in trend.indices) {
            val t = trend[idx]
            "${t.time}  价格 ${fmt(t.price)}"
        } else ""
    }

    /** 涨跌方向：K线按收盘vs开盘，分时按当前vs上一分钟。 */
    private fun markerUp(idx: Int): Boolean {
        return if (bars.isNotEmpty() && idx in bars.indices) bars[idx].close >= bars[idx].open
        else if (trend.isNotEmpty() && idx in trend.indices && idx > 0) trend[idx].price >= trend[idx - 1].price
        else true
    }

    private fun fmt(v: Double) = String.format("%.2f", v)

    /** 子图（成交量/MACD/KDJ）禁用独立手势，缩放统一由主图驱动联动。 */
    private fun disableSubGestures(c: BarLineChartBase<*>) {
        c.setScaleEnabled(false)
        c.setPinchZoom(false)
        c.setDragEnabled(false)
        c.setDoubleTapToZoomEnabled(false)
        c.isHighlightPerDragEnabled = false
    }

    /**
     * 主图缩放/平移后，把当前可见的X轴数据区间同步给可见子图，
     * 实现成交量/MACD/KDJ 随主图一起放大缩小。
     * 子图始终禁用独立手势（见 disableSubGestures），其 X 轴范围仅由主图驱动，
     * 因此直接改写 axisMinimum/axisMaximum 即可让图形(而非仅坐标文字)跟随缩放。
     */
    private fun syncSubCharts() {
        if (chart == null || chart.data == null) return
        val l = chart.lowestVisibleX.toFloat()
        val r = chart.highestVisibleX.toFloat()
        if (!l.isFinite() || !r.isFinite() || l >= r) return
        listOf(volChart, macdChart, kdjChart)
            .filter { it.visibility == View.VISIBLE }
            .forEach { c ->
                c.xAxis.axisMinimum = l
                c.xAxis.axisMaximum = r
                c.invalidate()
            }
    }

    /**
     * MA5/MA10 金叉/死叉买卖信号：
     * 金叉（MA5上穿MA10）→ 买点B，标记在当根K线低点下方；
     * 死叉（MA5下穿MA10）→ 卖点S，标记在当根K线高点上方。
     */
    private fun detectBuySell(): List<BuySellSignal> {
        val n = bars.size
        if (n < 12) return emptyList()
        val closes = DoubleArray(n) { bars[it].close }
        val ma5 = DoubleArray(n) { Double.NaN }
        val ma10 = DoubleArray(n) { Double.NaN }
        var s5 = 0.0
        var s10 = 0.0
        for (i in 0 until n) {
            s5 += closes[i]; if (i >= 5) s5 -= closes[i - 5]
            s10 += closes[i]; if (i >= 10) s10 -= closes[i - 10]
            if (i >= 4) ma5[i] = s5 / 5
            if (i >= 9) ma10[i] = s10 / 10
        }
        val out = ArrayList<BuySellSignal>()
        for (i in 1 until n) {
            if (ma5[i - 1].isNaN() || ma5[i].isNaN() || ma10[i - 1].isNaN() || ma10[i].isNaN()) continue
            val prev = ma5[i - 1] - ma10[i - 1]
            val cur = ma5[i] - ma10[i]
            when {
                prev <= 0 && cur > 0 -> out.add(BuySellSignal(i, bars[i].low.toFloat(), BUY))
                prev >= 0 && cur < 0 -> out.add(BuySellSignal(i, bars[i].high.toFloat(), SELL))
            }
        }
        return out
    }

    /**
     * 横盘箱体识别：取最近 min(30, n) 根K线，若振幅 (箱顶-箱底)/箱底 < 8% 视为横盘，
     * 返回箱顶/箱底两条虚线 LimitLine；否则返回 null（不画）。
     */
    private fun detectRange(): Pair<LimitLine, LimitLine>? {
        if (bars.size < 10) return null
        val win = minOf(30, bars.size)
        val start = bars.size - win
        var hi = Double.MIN_VALUE
        var lo = Double.MAX_VALUE
        for (i in start until bars.size) {
            if (bars[i].high > hi) hi = bars[i].high
            if (bars[i].low < lo) lo = bars[i].low
        }
        if (lo <= 0 || (hi - lo) / lo >= 0.08) return null
        val top = LimitLine(hi.toFloat(), "箱顶").apply {
            lineColor = Color.parseColor("#7E57C2")
            lineWidth = 1.2f
            enableDashedLine(10f, 8f, 0f)
            textColor = Color.parseColor("#7E57C2")
            textSize = 10f
            labelPosition = LimitLine.LimitLabelPosition.RIGHT_TOP
        }
        val bottom = LimitLine(lo.toFloat(), "箱底").apply {
            lineColor = Color.parseColor("#7E57C2")
            lineWidth = 1.2f
            enableDashedLine(10f, 8f, 0f)
            textColor = Color.parseColor("#7E57C2")
            textSize = 10f
            labelPosition = LimitLine.LimitLabelPosition.RIGHT_BOTTOM
        }
        return Pair(top, bottom)
    }

    private fun dateFormatter() = object : ValueFormatter() {
        override fun getAxisLabel(value: Float, axis: AxisBase?): String {
            val idx = value.toInt()
            return if (idx in bars.indices) bars[idx].date.takeLast(5) else ""
        }
    }

    /**
     * 时间轴统一配置（用于K线主图及成交量/MACD/KDJ 等子图）：
     * - X轴底部、开启整数粒度，标签只落在整根K线上，不重叠、不错位。
     * - 左右各留出半根K线宽的空白（min=-0.5 / max=n-0.5），首末K线不被裁切，能看到最后一天。
     * - 标签数量随可见K线条数自适应，避免过密。
     */
    private fun styleDateAxis(c: com.github.mikephil.charting.charts.BarLineChartBase<*>) {
        val n = bars.size.coerceAtLeast(1)
        c.xAxis.position = XAxis.XAxisPosition.BOTTOM
        c.xAxis.setGranularityEnabled(true)
        c.xAxis.granularity = 1f
        c.xAxis.setAvoidFirstLastClipping(true)
        c.xAxis.labelCount = if (n > 40) 6 else minOf(5, maxOf(3, n))
        c.xAxis.axisMinimum = -0.5f
        c.xAxis.axisMaximum = (n - 1) + 0.5f
        c.xAxis.valueFormatter = dateFormatter()
        c.xAxis.setLabelCount(c.xAxis.labelCount, false)
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