package com.quantapp.trader.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.ValueFormatter
import com.quantapp.trader.R
import com.quantapp.trader.data.KLine
import com.quantapp.trader.data.LlmClient
import com.quantapp.trader.data.MarketService
import com.quantapp.trader.strategy.Backtester
import com.quantapp.trader.strategy.buildStrategy
import com.quantapp.trader.strategy.characterize
import com.quantapp.trader.strategy.interpretBacktest
import com.quantapp.trader.strategy.optimizeParams
import com.quantapp.trader.strategy.recommendStrategy
import com.quantapp.trader.strategy.strategies
import com.quantapp.trader.strategy.strategyParams
import com.quantapp.trader.trading.ActiveStrategy
import com.quantapp.trader.trading.App
import com.quantapp.trader.trading.TradingEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class StrategyFragment : Fragment() {

    private var tvLog: TextView? = null
    private var logScroll: ScrollView? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val root = inflater.inflate(R.layout.fragment_strategy, container, false)
        val etSymbol = root.findViewById<AutoCompleteTextView>(R.id.et_strat_symbol)
        val btnPickWatch = root.findViewById<Button>(R.id.btn_pick_watch)
        val spinner = root.findViewById<Spinner>(R.id.spinner_strategy)

        // 股票名称/代码联想搜索
        val searchHandler = Handler(Looper.getMainLooper())
        val searchCodes = mutableListOf<String>()
        val searchTask = object : Runnable {
            override fun run() {
                val q = etSymbol.text.toString().trim()
                if (q.isEmpty()) return
                AppScope.launch {
                    val res = try { withContext(Dispatchers.IO) { MarketService.search(q) } }
                        catch (e: Exception) { emptyList() }
                    if (etSymbol.text.toString().trim() == q) {
                        searchCodes.clear()
                        res.forEach { searchCodes.add(it.code) }
                        etSymbol.setAdapter(ArrayAdapter(requireContext(),
                            android.R.layout.simple_dropdown_item_1line,
                            res.map { "${it.name}  ${it.code}" }))
                        if (res.isNotEmpty()) etSymbol.showDropDown()
                    }
                }
            }
        }
        etSymbol.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                searchHandler.removeCallbacks(searchTask)
                if (!s.isNullOrBlank()) searchHandler.postDelayed(searchTask, 250)
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        etSymbol.setOnItemClickListener { _, _, pos, _ ->
            if (pos in searchCodes.indices) {
                etSymbol.setText(searchCodes[pos])
                etSymbol.dismissDropDown()
            }
        }

        // 从自选股列表选择
        btnPickWatch.setOnClickListener {
            val p = App.context.getSharedPreferences("watch", 0)
            val list = try { JSONArray(p.getString("list", "[]") ?: "[]") }
                catch (e: Exception) { JSONArray() }
            val names = try { JSONObject(p.getString("names", "{}") ?: "{}") }
                catch (e: Exception) { JSONObject() }
            val codes = mutableListOf<String>()
            for (i in 0 until list.length()) codes.add(list.optString(i, ""))
            if (codes.isEmpty()) {
                Toast.makeText(requireContext(), "自选股为空", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val labels = codes.map { c ->
                val n = names.optString(c, "")
                if (n.isEmpty()) c else "$n  $c"
            }
            AlertDialog.Builder(requireContext())
                .setTitle("从自选股选择")
                .setItems(labels.toTypedArray()) { _, pos -> etSymbol.setText(codes[pos]) }
                .show()
        }
        val btnBacktest = root.findViewById<Button>(R.id.btn_backtest)
        val btnStart = root.findViewById<Button>(R.id.btn_start)
        val btnStop = root.findViewById<Button>(R.id.btn_stop)
        val tvBacktest = root.findViewById<TextView>(R.id.tv_backtest)
        val chartEquity = root.findViewById<LineChart>(R.id.chart_equity)
        val tvStatus = root.findViewById<TextView>(R.id.tv_running_status)
        val listActive = root.findViewById<LinearLayout>(R.id.list_active)
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

        // ---------- 通用：回测结果展示（含 AI 白话解读） ----------
        fun backtestText(result: com.quantapp.trader.strategy.BacktestResult, fee: Double, slip: Double): String {
            val feeLine = if (fee > 0 || slip > 0)
                "\n成本假设：手续费 ${String.format("%.4f", fee)} / 滑点 ${String.format("%.4f", slip)}"
                else ""
            return buildString {
                append("回测(${result.tradeCount}笔)$feeLine\n")
                append("策略收益：${String.format("%.2f", result.returnPct)}%\n")
                append("基准(买入持有)：${String.format("%.2f", result.benchmarkReturnPct)}%\n")
                append("最终权益：${String.format("%.0f", result.finalEquity)} / 初始 ${String.format("%.0f", result.initialCapital)}\n")
                append("胜率：${String.format("%.1f", result.winRate)}%\n")
                append("最大回撤：${String.format("%.1f", result.maxDrawdown)}%\n")
                val avgPnl = if (result.trades.isEmpty()) 0.0
                else result.trades.map { it.pnlPct }.average()
                append("平均单笔收益：${String.format("%.2f", avgPnl)}%\n")
                append("\n【AI 解读】\n")
                append(interpretBacktest(result))
            }
        }

        // 同一标的在页面会话内只拉一次 K 线
        var cachedCode: String? = null
        var cachedBars: List<KLine>? = null
        suspend fun fetchBars(raw: String): Pair<String, List<KLine>>? {
            val code = try { withContext(Dispatchers.IO) { MarketService.resolveCode(raw) } }
                catch (e: Exception) { raw }
            if (code == cachedCode && cachedBars != null) return code to cachedBars!!
            val bars = withContext(Dispatchers.IO) { MarketService.fetchKline(code, 260) }
            cachedCode = code; cachedBars = bars
            return code to bars
        }

        suspend fun showBacktestResult(strategyId: String, cfg: Map<String, Double>,
                                       bars: List<KLine>, fee: Double, slip: Double,
                                       header: String? = null) {
            val result = withContext(Dispatchers.IO) {
                Backtester.run(buildStrategy(strategyId, cfg), bars, feeRate = fee, slippagePct = slip)
            }
            val base = backtestText(result, fee, slip)
            tvBacktest.text = if (header != null) "$header\n\n$base" else base
            renderEquity(chartEquity, result, bars)
        }

        // 切换策略选择（会触发参数区重建）
        fun selectStrategy(id: String) {
            val idx = strategyList.indexOfFirst { it.id == id }
            if (idx >= 0) spinner.setSelection(idx)
        }

        // 把一组参数写进参数输入框
        fun fillParams(params: Map<String, Double>) {
            strategyParams(selectedStrategyId()).forEach { p ->
                val v = params[p.key]
                if (v != null) {
                    paramEdits[p.key]?.setText(
                        if (p.int) v.toInt().toString() else String.format("%.2f", v))
                }
            }
        }

        btnBacktest.setOnClickListener { b ->
            val raw = etSymbol.text.toString().trim()
            if (raw.isEmpty()) return@setOnClickListener
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
                    val (_, bars) = fetchBars(raw) ?: return@launch
                    if (bars.size < 40) { tvBacktest.text = "数据不足，无法回测"; return@launch }
                    showBacktestResult(selectedStrategyId(), readParams(), bars, fee, slip)
                } catch (e: Exception) {
                    tvBacktest.text = "回测失败：${e.message}"
                } finally {
                    b.isEnabled = true
                }
            }
        }

        // ---------- AI 智能推荐：识别标的特征 → 推荐策略并填入参数 ----------
        root.findViewById<Button>(R.id.btn_ai_recommend).setOnClickListener { b ->
            val raw = etSymbol.text.toString().trim()
            if (raw.isEmpty()) {
                Toast.makeText(requireContext(), "请先输入股票代码或名称", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            b.isEnabled = false
            tvBacktest.text = "AI 识别走势中..."
            AppScope.launch {
                try {
                    val (_, bars) = fetchBars(raw) ?: return@launch
                    if (bars.size < 40) { tvBacktest.text = "数据不足，无法分析"; return@launch }
                    val c = characterize(bars)
                    val rec = recommendStrategy(bars)
                    selectStrategy(rec.strategyId)
                    fillParams(rec.params)
                    App.appStore.setStrategyConfig(rec.strategyId, rec.params)
                    tvBacktest.text = "【AI 智能推荐 · ${c.summary}】\n${rec.reason}\n\n已自动切换到对应策略并填入参数，可点“回测”验证，或再点“AI 一键配参”微调。"
                } catch (e: Exception) {
                    tvBacktest.text = "AI 分析失败：${e.message}"
                } finally {
                    b.isEnabled = true
                }
            }
        }

        // ---------- AI 一键配参：按风险偏好寻优参数并自动回测 ----------
        root.findViewById<Button>(R.id.btn_ai_optimize).setOnClickListener { b ->
            val raw = etSymbol.text.toString().trim()
            if (raw.isEmpty()) {
                Toast.makeText(requireContext(), "请先输入股票代码或名称", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val sid = selectedStrategyId()
            val modes = arrayOf("保守", "均衡", "激进")
            android.app.AlertDialog.Builder(requireContext())
                .setTitle("AI 一键配参 · 选择风险偏好")
                .setSingleChoiceItems(modes, 1) { d, which ->
                    d.dismiss()
                    b.isEnabled = false
                    tvBacktest.text = "AI 寻优中（网格搜索）..."
                    AppScope.launch {
                        try {
                            val fee = (etFee.text.toString().toDoubleOrNull() ?: 0.0)
                                .coerceIn(0.0, 0.1)
                            val slip = (etSlip.text.toString().toDoubleOrNull() ?: 0.0)
                                .coerceIn(0.0, 0.1)
                            val (_, bars) = fetchBars(raw) ?: return@launch
                            if (bars.size < 40) { tvBacktest.text = "数据不足，无法寻优"; return@launch }
                            val modeKey = arrayOf("conservative", "balanced", "aggressive")[which]
                            val best = optimizeParams(sid, bars, modeKey, feeRate = fee,
                                slippagePct = slip) { done, total ->
                                activity?.runOnUiThread { tvBacktest.text = "AI 寻优中... $done/$total" }
                            }
                            if (best == null) {
                                tvBacktest.text = "该策略在此区间没有找到合格参数（交易次数偏少）"
                                return@launch
                            }
                            fillParams(best.params)
                            App.appStore.setStrategyConfig(sid, best.params)
                            showBacktestResult(sid, best.params, bars, fee, slip,
                                "【AI 一键配参 · ${modes[which]}】已选 ${best.paramsText}（综合分 ${String.format("%.1f", best.score)}）")
                        } catch (e: Exception) {
                            tvBacktest.text = "AI 配参失败：${e.message}"
                        } finally {
                            b.isEnabled = true
                        }
                    }
                }
                .setNegativeButton("取消", null)
                .show()
        }

        // ---------- AI 深度解读：调大模型解读当前回测 ----------
        root.findViewById<Button>(R.id.btn_ai_explain).setOnClickListener { b ->
            val raw = etSymbol.text.toString().trim()
            if (raw.isEmpty()) {
                Toast.makeText(requireContext(), "请先输入股票代码或名称", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val st = App.appStore
            if (!st.aiEnabled) {
                android.app.AlertDialog.Builder(requireContext())
                    .setTitle("未配置 AI 大模型")
                    .setMessage("请先在「设置 → AI 大模型」填入 API Key 并保存，再回来使用深度解读。")
                    .setPositiveButton("去设置") { _, _ ->
                        (requireActivity() as? MainActivity)?.switchTab(R.id.nav_settings)
                    }
                    .setNegativeButton("取消", null)
                    .show()
                return@setOnClickListener
            }
            b.isEnabled = false
            tvBacktest.text = "AI 深度解读中（${st.aiModel}）..."
            AppScope.launch {
                try {
                    val fee = (etFee.text.toString().toDoubleOrNull() ?: 0.0).coerceIn(0.0, 0.1)
                    val slip = (etSlip.text.toString().toDoubleOrNull() ?: 0.0).coerceIn(0.0, 0.1)
                    val (code, bars) = fetchBars(raw) ?: return@launch
                    if (bars.size < 40) { tvBacktest.text = "数据不足，无法解读"; return@launch }
                    val sid = selectedStrategyId()
                    val cfg = readParams()
                    val result = withContext(Dispatchers.IO) {
                        Backtester.run(buildStrategy(sid, cfg), bars, feeRate = fee, slippagePct = slip)
                    }
                    fun fmtNum(v: Double) =
                        if (v == v.toLong().toDouble()) v.toLong().toString() else String.format("%.2f", v)
                    val paramsStr = cfg.entries.joinToString(" ") { "${it.key}=${fmtNum(it.value)}" }
                    val strategyName = strategyList[spinner.selectedItemPosition.coerceIn(0, strategyList.size - 1)].name
                    val sys = "你是资深A股量化分析师。请针对用户给出的回测统计，用简明中文给出一句话结论、主要优点与风险、以及具体的改进建议（参数、风控或出场纪律）。不要复读原始数字表格，聚焦专业判断。篇幅控制在200字内。"
                    val user = buildString {
                        append("标的代码：$code\n")
                        append("策略：$strategyName\n")
                        append("参数：$paramsStr\n")
                        append("成本：手续费${fee} 滑点${slip}\n")
                        append("回测结果（近一年约260根K线）：\n")
                        append(backtestText(result, fee, slip))
                    }
                    val reply = withContext(Dispatchers.IO) {
                        LlmClient.chat(st.aiProvider, st.aiKey, st.aiModel, sys, user)
                    }
                    tvBacktest.text = "【AI 深度解读】\n$reply"
                } catch (e: Exception) {
                    tvBacktest.text = "AI 解读失败：${e.message}\n\n（若是模型名或密钥不对，请到「设置 → AI 大模型」检查）"
                } finally {
                    b.isEnabled = true
                }
            }
        }

        btnStart.setOnClickListener {
            val raw = etSymbol.text.toString().trim()
            if (raw.isEmpty()) {
                Toast.makeText(requireContext(), "请输入股票代码或名称（多支用逗号/空格分隔）", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val bid = selectedStrategyId()
            App.appStore.setStrategyConfig(bid, readParams()) // 让实盘引擎使用同样的自定义参数
            // 支持一次提交多支：按逗号/空格/分号/换行拆分
            val inputs = raw.split(',', '，', ';', '；', ' ', '\t', '\n')
                .map { it.trim() }.filter { it.isNotEmpty() }
            if (inputs.isEmpty()) return@setOnClickListener
            AppScope.launch {
                val added = mutableListOf<Pair<String, String>>() // code to name
                for (input in inputs) {
                    val code = try { withContext(Dispatchers.IO) { MarketService.resolveCode(input) } }
                        catch (e: Exception) { input }
                    if (code.isBlank()) continue
                    val name = try { withContext(Dispatchers.IO) { MarketService.fetchQuote(code).name } }
                        catch (e: Exception) { "" }
                    App.appStore.addStrategy(ActiveStrategy(code, name, bid))
                    added.add(code to name)
                }
                if (added.isEmpty()) return@launch
                TradingEngine.start()
                refreshStatus(tvStatus)
                refreshActive(listActive)
                App.appStore.addLog("运行中标的（${added.size}）：" +
                    added.joinToString(" ") { "${it.second.ifEmpty { it.first }}(${it.first})" })
                Toast.makeText(requireContext(),
                    "已启动 ${added.size} 支：${added.joinToString(" ") { it.first }}", Toast.LENGTH_SHORT).show()
            }
        }

        btnStop.setOnClickListener {
            TradingEngine.stop()
            refreshStatus(tvStatus)
            Toast.makeText(requireContext(), "引擎已停止", Toast.LENGTH_SHORT).show()
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

    /** 运行中策略列表：动态构建行（外层 ScrollView 内强制撑开），每行可点击移除。 */
    private fun refreshActive(container: LinearLayout) {
        container.removeAllViews()
        val list_ = App.appStore.activeStrategies()
        if (list_.isEmpty()) {
            container.addView(activeRow(requireContext(), "", "（暂无运行中策略，点“启动自动交易”添加）", null))
            return
        }
        for (s in list_) {
            val text = "${s.symbol}  ${s.name}  ${strategyLabel(s.strategyId)}  [${s.lastAction.ifEmpty { "未触发" }}] ${s.lastReason}"
            container.addView(activeRow(requireContext(), s.symbol, text) {
                App.appStore.removeStrategy(it)
                refreshActive(container)
                Toast.makeText(requireContext(), "已移除 $it", Toast.LENGTH_SHORT).show()
            })
        }
    }

    private fun activeRow(ctx: android.content.Context, symbol: String, text: String, onClick: ((String) -> Unit)?): TextView {
        val tv = TextView(ctx)
        tv.text = text
        tv.textSize = 14f
        tv.setPadding(dp2(8), dp2(8), dp2(8), dp2(8))
        if (onClick != null) {
            tv.setBackgroundResource(R.drawable.bg_card)
            tv.setOnClickListener { onClick(symbol) }
        }
        return tv
    }

    private fun dp2(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun strategyLabel(id: String) = when (id) {
        "rsi" -> "RSI"; "macd" -> "MACD"; "boll" -> "布林带"; "t_ma" -> "均线做T"
        "t_boll" -> "布林做T"; "t_vwap" -> "VWAP做T"; else -> "双均线"
    }

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