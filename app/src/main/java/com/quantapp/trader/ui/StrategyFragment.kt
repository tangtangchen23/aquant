package com.quantapp.trader.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.ValueFormatter
import com.quantapp.trader.R
import com.quantapp.trader.data.MarketService
import com.quantapp.trader.strategy.Backtester
import com.quantapp.trader.strategy.buildStrategy
import com.quantapp.trader.strategy.strategies
import com.quantapp.trader.strategy.strategyParams
import com.quantapp.trader.trading.ActiveStrategy
import com.quantapp.trader.trading.App
import com.quantapp.trader.trading.TradingEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class StrategyFragment : Fragment() {

    private var tvLog: TextView? = null
    private var logScroll: ScrollView? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val root = inflater.inflate(R.layout.fragment_strategy, container, false)
        val etSymbol = root.findViewById<EditText>(R.id.et_strat_symbol)
        val spinner = root.findViewById<Spinner>(R.id.spinner_strategy)
        val btnBacktest = root.findViewById<Button>(R.id.btn_backtest)
        val btnStart = root.findViewById<Button>(R.id.btn_start)
        val btnStop = root.findViewById<Button>(R.id.btn_stop)
        val tvBacktest = root.findViewById<TextView>(R.id.tv_backtest)
        val chartEquity = root.findViewById<LineChart>(R.id.chart_equity)
        val tvStatus = root.findViewById<TextView>(R.id.tv_running_status)
        val listActive = root.findViewById<ListView>(R.id.list_active)
        tvLog = root.findViewById(R.id.tv_engine_log)
        logScroll = tvLog?.parent as? ScrollView

        val strategyList = strategies()
        spinner.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, strategyList.map { "${it.name} (${it.paramsSummary()})" })

        val linearParams = root.findViewById<LinearLayout>(R.id.linear_params)
        val btnApply = root.findViewById<Button>(R.id.btn_apply_params)
        val paramEdits = mutableMapOf<String, EditText>()

        // 根据所选策略构建参数输入框，值优先取已保存配置，其次默认值
        fun buildParamFields(id: String) {
            linearParams.removeAllViews()
            paramEdits.clear()
            val saved = App.appStore.strategyConfig(id)
            val ctx = requireContext()
            val etWidth = (140 * resources.displayMetrics.density).toInt()
            for (p in strategyParams(id)) {
                val value = if (saved.containsKey(p.key)) saved[p.key].toString() else p.def
                val et = EditText(ctx).apply {
                    setText(if (p.int) value.toIntOrNull()?.toString() ?: p.def
                    else String.format("%.2f", value.toDoubleOrNull() ?: p.def.toDouble()))
                    inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                        (if (p.int) android.text.InputType.TYPE_NUMBER_FLAG_SIGNED else android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL)
                }
                val tv = TextView(ctx).apply { text = p.label }
                val row = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    addView(tv, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                    addView(et, LinearLayout.LayoutParams(etWidth, LinearLayout.LayoutParams.WRAP_CONTENT))
                }
                linearParams.addView(row)
                paramEdits[p.key] = et
            }
        }

        fun selectedStrategyId() = strategyList[spinner.selectedItemPosition.coerceIn(0, strategyList.size - 1)].id

        fun readParams(): Map<String, Double> {
            val map = mutableMapOf<String, Double>()
            strategyParams(selectedStrategyId()).forEach { p ->
                paramEdits[p.key]?.text?.toString()?.toDoubleOrNull()?.let { map[p.key] = it }
            }
            return map
        }

        spinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(a: android.widget.AdapterView<*>?, v: View?, pos: Int, l: Long) {
                buildParamFields(strategyList[pos.coerceIn(0, strategyList.size - 1)].id)
            }
            override fun onNothingSelected(a: android.widget.AdapterView<*>?) {}
        }
        buildParamFields(selectedStrategyId())

        // 保存当前策略参数
        btnApply.setOnClickListener {
            val cfg = readParams()
            if (cfg.isEmpty() && strategyParams(selectedStrategyId()).isNotEmpty()) {
                Toast.makeText(requireContext(), "请输入有效参数", Toast.LENGTH_SHORT).show(); return@setOnClickListener
            }
            App.appStore.setStrategyConfig(selectedStrategyId(), cfg)
            Toast.makeText(requireContext(), "参数已保存，回测/实盘将使用该参数", Toast.LENGTH_SHORT).show()
        }

        refreshStatus(tvStatus)
        refreshActive(listActive)
        refreshLog()

        val etFee = root.findViewById<EditText>(R.id.et_fee_rate)
        val etSlip = root.findViewById<EditText>(R.id.et_slippage)
        etFee.setText(if (App.appStore.feeRate == 0.0) "" else App.appStore.feeRate.toString())
        etSlip.setText(if (App.appStore.slippagePct == 0.0) "" else App.appStore.slippagePct.toString())

        root.findViewById<Button>(R.id.btn_clear_log).setOnClickListener {
            App.appStore.clearLogs()
            refreshLog()
        }

        btnBacktest.setOnClickListener { b ->
            val code = etSymbol.text.toString().trim()
            if (code.isEmpty()) return@setOnClickListener
            b.isEnabled = false
            tvBacktest.text = "回测中..."
            AppScope.launch {
                try {
                    val fee = (etFee.text.toString().toDoubleOrNull() ?: 0.0)
                        .coerceIn(0.0, 0.1)
                    val slip = (etSlip.text.toString().toDoubleOrNull() ?: 0.0)
                        .coerceIn(0.0, 0.1)
                    App.appStore.feeRate = fee
                    App.appStore.slippagePct = slip
                    val bars = withContext(Dispatchers.IO) { MarketService.fetchKline(code, 260) }
                    if (bars.size < 40) { tvBacktest.text = "数据不足，无法回测"; return@launch }
                    val result = withContext(Dispatchers.IO) {
                        Backtester.run(buildStrategy(selectedStrategyId(), readParams()), bars,
                            feeRate = fee, slippagePct = slip)
                    }
                    val feeLine = if (fee > 0 || slip > 0)
                        "\n成本假设：手续费 ${String.format("%.4f", fee)} / 滑点 ${String.format("%.4f", slip)}"
                        else ""
                    tvBacktest.text = buildString {
                        append("回测(${result.tradeCount}笔)$feeLine\n")
                        append("策略收益：${String.format("%.2f", result.returnPct)}%\n")
                        append("基准(买入持有)：${String.format("%.2f", result.benchmarkReturnPct)}%\n")
                        append("最终权益：${String.format("%.0f", result.finalEquity)} / 初始 ${String.format("%.0f", result.initialCapital)}\n")
                        append("胜率：${String.format("%.1f", result.winRate)}%\n")
                        append("最大回撤：${String.format("%.1f", result.maxDrawdown)}%\n")
                        val avgPnl = if (result.trades.isEmpty()) 0.0
                        else result.trades.map { it.pnlPct }.average()
                        append("平均单笔收益：${String.format("%.2f", avgPnl)}%")
                    }
                    renderEquity(chartEquity, result, bars)
                } catch (e: Exception) {
                    tvBacktest.text = "回测失败：${e.message}"
                } finally {
                    b.isEnabled = true
                }
            }
        }

        btnStart.setOnClickListener {
            val code = etSymbol.text.toString().trim()
            if (code.isEmpty()) { Toast.makeText(requireContext(), "请输入股票代码", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            val bid = selectedStrategyId()
            val as_ = ActiveStrategy(code, "", bid)
            App.appStore.setStrategyConfig(bid, readParams()) // 让实盘引擎使用同样的自定义参数
            App.appStore.addStrategy(as_)
            TradingEngine.start()
            refreshStatus(tvStatus)
            refreshActive(listActive)
            Toast.makeText(requireContext(), "已启动 $code 的自动化交易", Toast.LENGTH_SHORT).show()
        }

        btnStop.setOnClickListener {
            TradingEngine.stop()
            refreshStatus(tvStatus)
            Toast.makeText(requireContext(), "引擎已停止", Toast.LENGTH_SHORT).show()
        }

        listActive.setOnItemClickListener { _, _, position, _ ->
            val now = App.appStore.activeStrategies()
            // 空列表时显示的占位行 / 下标越界都要兜底，避免闪退
            if (position !in now.indices) return@setOnItemClickListener
            val item = now[position]
            App.appStore.removeStrategy(item.symbol)
            refreshActive(listActive)
            Toast.makeText(requireContext(), "已移除 ${item.symbol}", Toast.LENGTH_SHORT).show()
        }

        TradingEngine.onTicker = { _, _, _, runningCount ->
            activity?.runOnUiThread {
                refreshStatus(tvStatus)
                refreshActive(listActive)
                refreshLog()
            }
        }
        return root
    }

    private fun refreshLog() {
        val tv = tvLog ?: return
        val scroll = logScroll ?: return
        val text = App.appStore.engineLogText()
        tv.text = if (text.isEmpty()) "（暂无引擎日志）" else text
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun refreshStatus(tv: TextView) {
        val running = TradingEngine.isRunning
        val mode = App.appStore.mode
        tv.text = if (running) "引擎状态：运行中（${modeText(mode)}）" else "引擎状态：已停止"
    }

    private fun modeText(mode: String) = if (mode == "live") "实盘信号模式" else "模拟盘模式"

    private fun refreshActive(list: ListView) {
        val list_ = App.appStore.activeStrategies()
        list.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1,
            if (list_.isEmpty()) listOf("（暂无运行中策略，点“启动自动交易”添加）")
            else list_.map { "${it.symbol}  ${it.name}  ${strategyLabel(it.strategyId)}  [${it.lastAction}] ${it.lastReason}" })
    }

    private fun strategyLabel(id: String) = when (id) { "rsi" -> "RSI"; "macd" -> "MACD"; else -> "双均线" }

    /** 绘制策略权益曲线与基准曲线。 */
    private fun renderEquity(chart: LineChart, r: com.quantapp.trader.strategy.BacktestResult, bars: List<com.quantapp.trader.data.KLine>) {
        val strategySet = LineDataSet(
            r.equityCurve.mapIndexed { i, v -> Entry(i.toFloat(), v.toFloat()) },
            "策略")
        strategySet.color = android.graphics.Color.parseColor("#D32F2F")
        strategySet.lineWidth = 2f
        strategySet.setDrawCircles(false)
        strategySet.setDrawValues(false)
        val benchSet = LineDataSet(
            r.benchmarkCurve.mapIndexed { i, v -> Entry(i.toFloat(), v.toFloat()) },
            "买入持有")
        benchSet.color = android.graphics.Color.parseColor("#43A047")
        benchSet.lineWidth = 2f
        benchSet.setDrawCircles(false)
        benchSet.setDrawValues(false)
        val data = LineData(strategySet, benchSet)
        chart.data = data
        chart.description.isEnabled = false
        chart.legend.isEnabled = true
        chart.legend.textSize = 11f
        chart.setScaleEnabled(true)
        chart.setPinchZoom(true)
        chart.xAxis.position = XAxis.XAxisPosition.BOTTOM
        chart.xAxis.labelCount = 4
        chart.xAxis.valueFormatter = object : ValueFormatter() {
            override fun getAxisLabel(value: Float, axis: com.github.mikephil.charting.components.AxisBase?): String {
                val idx = value.toInt()
                return if (idx in bars.indices) bars[idx].date.takeLast(5) else ""
            }
        }
        chart.invalidate()
    }
}