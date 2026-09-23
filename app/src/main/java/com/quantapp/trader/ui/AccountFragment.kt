package com.quantapp.trader.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.quantapp.trader.R
import com.quantapp.trader.data.MarketService
import com.quantapp.trader.data.Quote
import com.quantapp.trader.trading.App
import com.quantapp.trader.trading.LiveGateway
import com.quantapp.trader.trading.Position
import com.quantapp.trader.trading.TradingEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat

class AccountFragment : Fragment() {

    /** 实时行情缓存：symbol -> Quote（null 表示已拉取过但失败）。 */
    private val quotes = mutableMapOf<String, Quote?>()
    private val positions = mutableListOf<Position>()
    private var btnRefresh: TextView? = null
    private var summaryView: TextView? = null
    private var filterSide: String? = null // null=全部，"买入"/"卖出"
    private var posContainer: LinearLayout? = null
    private var tradeContainer: LinearLayout? = null
    private var pendingContainer: LinearLayout? = null
    private var refreshJob: Job? = null
    /** "paper" 模拟盘 / "live" 实盘 */
    private var currentScope = "paper"
    private var liveRefreshJob: Job? = null
    private var liveData: JSONObject? = null
    private var tvTitle: TextView? = null
    private var rgScope: RadioGroup? = null

    // ------------------------- 生命周期 -------------------------
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val root = inflater.inflate(R.layout.fragment_account, container, false)
        summaryView = root.findViewById(R.id.tv_account_summary)
        btnRefresh = root.findViewById(R.id.btn_refresh_positions)
        tvTitle = root.findViewById(R.id.tv_account_title)
        rgScope = root.findViewById(R.id.rg_account_scope)

        // 模拟盘 / 实盘 分段切换
        if (App.appStore.mode == "live" && App.appStore.liveGateway.isNotBlank()) {
            currentScope = "live"
            rgScope?.check(R.id.rb_scope_live)
        } else {
            currentScope = "paper"
            rgScope?.check(R.id.rb_scope_paper)
        }
        rgScope?.setOnCheckedChangeListener { _, checkedId ->
            val newScope = if (checkedId == R.id.rb_scope_live) "live" else "paper"
            if (newScope != currentScope) { currentScope = newScope; refreshAll() }
        }

        // 持仓/成交：动态 LinearLayout 逐行注入，随数量增长并整页滚动
        posContainer = root.findViewById(R.id.list_positions_container)
        tradeContainer = root.findViewById(R.id.list_trades_container)
        pendingContainer = root.findViewById(R.id.list_pending_container)
        reloadPositions()
        renderPending()
        renderTrades()
        refreshSummary()
        updateTitle()
        updateSectionVisibility()

        // 一键清仓
        root.findViewById<TextView>(R.id.btn_clear_positions).setOnClickListener {
            val pos = App.appStore.paper.positions.values.toList()
            if (pos.isEmpty()) {
                Toast.makeText(requireContext(), "当前无持仓", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            android.app.AlertDialog.Builder(requireContext())
                .setTitle("一键清仓")
                .setMessage("确定以当前行情价卖出全部 ${pos.size} 只持仓吗？")
                .setPositiveButton("全部卖出") { _, _ ->
                    clearAll()
                    refreshAll()
                }
                .setNegativeButton("取消", null)
                .show()
        }

        // 成交记录筛选
        root.findViewById<Button>(R.id.btn_trade_all).setOnClickListener { updateFilter(null) }
        root.findViewById<Button>(R.id.btn_trade_buy).setOnClickListener { updateFilter("买入") }
        root.findViewById<Button>(R.id.btn_trade_sell).setOnClickListener { updateFilter("卖出") }
        updateFilterUi()

        // 发生交易时：重新拉取行情，让市值/权益/浮动盈亏随最新价刷新
        TradingEngine.onTrade = { _ ->
            activity?.runOnUiThread {
                reloadPositions()
                renderPending()
                refreshQuotes()
            }
        }

        // 手动刷新持仓市值
        btnRefresh?.setOnClickListener {
            btnRefresh?.text = "刷新中..."
            refreshQuotes { btnRefresh?.text = "↻ 刷新市值" }
        }

        return root
    }

    override fun onResume() {
        super.onResume()
        startRefreshLoop()
    }

    override fun onPause() {
        refreshJob?.cancel()
        refreshJob = null
        liveRefreshJob?.cancel()
        liveRefreshJob = null
        super.onPause()
    }

    /** 页面可见时按当前 scope 启动对应轮询。 */
    private fun startRefreshLoop() {
        if (currentScope == "live") startLiveRefreshLoop() else startPaperRefreshLoop()
    }

    private fun startPaperRefreshLoop() {
        refreshJob?.cancel()
        liveRefreshJob?.cancel()
        refreshJob = com.quantapp.trader.ui.AppScope.launch {
            while (isActive) {
                refreshQuotes()
                delay(App.appStore.pollSeconds.coerceIn(5, 600) * 1000L)
            }
        }
    }

    private fun startLiveRefreshLoop() {
        refreshJob?.cancel()
        liveRefreshJob?.cancel()
        liveRefreshJob = com.quantapp.trader.ui.AppScope.launch {
            while (isActive) {
                refreshLive()
                delay(App.appStore.pollSeconds.coerceIn(5, 600) * 1000L)
            }
        }
    }

    // ------------------------- 渲染 -------------------------
    private fun reloadPositions() {
        positions.clear()
        positions.addAll(App.appStore.paper.positions.values)
        val container = posContainer ?: return
        if (container.childCount > 0) container.removeAllViews()
        if (positions.isEmpty()) {
            val empty = TextView(requireContext()).apply {
                text = "（暂无持仓，可在策略页启动自动交易或做T自动建仓）"
                setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                textSize = 13f
                setPadding(paddingStart, dp(10), paddingEnd, dp(10))
            }
            container.addView(empty)
            return
        }
        positions.forEach { container.addView(positionRow(it)) }
    }

    /** 实时行情刷新后：清空并按最新缓存重排所有持仓行。 */
    private fun refreshPositionsRows() {
        reloadPositions()
    }

    private fun positionRow(pos: Position): View {
        val v = LayoutInflater.from(requireContext()).inflate(R.layout.item_position, posContainer, false)
        val q = quotes[pos.symbol]
        val name = v.findViewById<TextView>(R.id.tv_p_name)
        val day = v.findViewById<TextView>(R.id.tv_p_day)
        val meta = v.findViewById<TextView>(R.id.tv_p_meta)
        val pnl = v.findViewById<TextView>(R.id.tv_p_pnl)
        name.text = if (pos.name.isNotBlank()) "${pos.name}  ${pos.symbol}" else pos.symbol
        if (q != null) {
            val price = q.price
            val mv = pos.marketValue(price)
            val pnlV = pos.pnl(price)
            val c = ContextCompat.getColor(requireContext(), if (q.changePct >= 0) R.color.up else R.color.down)
            day.text = "现价 ${fmt(price)}  ${sign(q.changePct)}${fmt(q.changePct)}%"
            day.setTextColor(c)
            meta.text = "数量${pos.qty}  成本${fmt(pos.costPrice)}  市值${fmt(mv)}"
            pnl.text = "${sign(pnlV)}${fmt(pnlV)}（${sign(pos.pnlPct(price))}${fmt(pos.pnlPct(price))}%）"
            pnl.setTextColor(c)
        } else {
            day.text = if (quotes.containsKey(pos.symbol)) "暂无行情" else "市值核算中..."
            day.setTextColor(ContextCompat.getColor(requireContext(), R.color.down))
            meta.text = "数量${pos.qty}  成本${fmt(pos.costPrice)}"
            pnl.text = "--"
            pnl.setTextColor(ContextCompat.getColor(requireContext(), R.color.down))
        }
        v.setOnClickListener { (activity as? MainActivity)?.openChart(pos.symbol) }
        return v
    }

    private fun renderTrades() {
        val container = tradeContainer ?: return
        if (container.childCount > 0) container.removeAllViews()
        val trades = App.appStore.paper.trades
            .asReversed()
            .filter { filterSide == null || it.side == filterSide }
            .take(60)
        if (trades.isEmpty()) {
            val t = TextView(requireContext()).apply {
                text = "（暂无成交记录）"
                setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                textSize = 13f
                setPadding(paddingStart, dp(10), paddingEnd, dp(10))
            }
            container.addView(t)
            return
        }
        val sdf = SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
        trades.forEachIndexed { i, it ->
            val row = TextView(requireContext()).apply {
                text = "${sdf.format(java.util.Date(it.time))}  ${it.name}  ${it.side} ${it.qty}股 @ ${fmt(it.price)}"
                textSize = 14f
                setPadding(dp(10), dp(10), dp(10), dp(10))
                setTextColor(ContextCompat.getColor(context, if (it.side == "买入") R.color.up else R.color.down))
            }
            if (i > 0) {
                val div = View(context).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
                    setBackgroundColor(ContextCompat.getColor(context, R.color.divider))
                }
                container.addView(div)
            }
            container.addView(row)
        }
    }

    // ------------------------- 挂单渲染 -------------------------
    private fun renderPending() {
        val container = pendingContainer ?: return
        if (container.childCount > 0) container.removeAllViews()
        val orders = App.appStore.pendingOrders().reversed()
        if (orders.isEmpty()) {
            val t = TextView(requireContext()).apply {
                text = "（暂无挂单，可在行情页「手动买入/卖出」选挂单等待）"
                setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                textSize = 13f
                setPadding(paddingStart, dp(10), paddingEnd, dp(10))
            }
            container.addView(t)
            return
        }
        val sdf = SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
        orders.forEachIndexed { idx, o ->
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(8), dp(8), dp(8))
            }
            val info = TextView(requireContext()).apply {
                val tn = if (o.name.isNotBlank()) o.name else o.symbol
                text = "$tn 挂单${o.side}\n" +
                    "价格 ${fmt(o.limitPrice)} 元 · ${sdf.format(java.util.Date(o.createdAt))}"
                textSize = 14f
                setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                layoutParams = LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val cancelBtn = TextView(requireContext()).apply {
                text = "撤销"
                textSize = 13f
                setPadding(dp(10), dp(6), dp(10), dp(6))
                themeAttrColor(context, R.attr.onBrand).also { setTextColor(it) }
                setOnClickListener {
                    App.appStore.removePendingOrder(o.symbol, o.createdAt)
                    Toast.makeText(requireContext(), "已撤销挂单", Toast.LENGTH_SHORT).show()
                    renderPending()
                }
            }
            row.addView(info)
            row.addView(cancelBtn)
            container.addView(row)
            if (idx > 0) {
                val div = View(context).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
                    setBackgroundColor(ContextCompat.getColor(context, R.color.divider))
                }
                container.addView(div)
            }
        }
    }

    // ------------------------- 逻辑 -------------------------
    private fun refreshSummary() {
        val tvSummary = summaryView ?: return
        val st = App.appStore
        val pos = st.paper.positions.values
        val cash = st.paper.cash
        var mv = 0.0
        var pnl = 0.0
        for (p in pos) {
            val q = quotes[p.symbol]
            val price = q?.price ?: p.costPrice
            mv += p.marketValue(price)
            pnl += p.pnl(price)
        }
        val equity = cash + mv
        tvSummary.text = buildString {
            append("可用资金：${fmt(cash)} 元\n")
            append("持仓市值：${fmt(mv)} 元\n")
            if (pos.isNotEmpty()) {
                append("账户总权益：${fmt(equity)} 元（浮动盈亏 ${sign(pnl)}${fmt(pnl)} 元）\n")
                tvSummary.setTextColor(if (pnl >= 0)
                    ContextCompat.getColor(requireContext(), R.color.up)
                else ContextCompat.getColor(requireContext(), R.color.down))
            } else {
                append("账户总权益：${fmt(equity)} 元\n")
                tvSummary.setTextColor(ContextCompat.getColor(requireContext(), R.color.down))
            }
            append("持仓数：${pos.size} 只　总成交：${st.paper.trades.size} 笔")
        }
    }

    private fun clearAll() {
        val st = App.appStore
        st.paper.positions.values.toList().forEach { p ->
            val q = quotes[p.symbol]
            st.paper.sell(p.symbol, p.name, q?.price ?: p.costPrice)
        }
        st.save()
        reloadPositions()
    }

    private fun updateFilter(side: String?) {
        filterSide = side
        updateFilterUi()
        renderTrades()
    }

    private fun updateFilterUi() {
        fun style(id: Int, active: Boolean) {
            val btn = view?.findViewById<Button>(id)
            if (btn == null) return
            btn.setTextColor(themeAttrColor(requireContext(), R.attr.onBrand))
            btn.alpha = if (active) 1f else 0.55f
            btn.setTypeface(null, if (active) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        }
        style(R.id.btn_trade_all, filterSide == null)
        style(R.id.btn_trade_buy, filterSide == "买入")
        style(R.id.btn_trade_sell, filterSide == "卖出")
    }

    private fun refreshAll() {
        if (currentScope == "live") {
            updateTitle()
            updateSectionVisibility()
            startRefreshLoop()
            com.quantapp.trader.ui.AppScope.launch { refreshLive() }
        } else {
            updateTitle()
            updateSectionVisibility()
            reloadPositions()
            renderPending()
            renderTrades()
            refreshSummary()
            startRefreshLoop()
        }
    }

    /** 逐条异步拉取持仓实时行情，拉取完成后重写对应行并刷新账户汇总。 */
    private fun refreshQuotes(onDone: (() -> Unit)? = null) {
        val pending = positions.toList()
        if (pending.isEmpty()) {
            onDone?.invoke()
            refreshSummary()
            return
        }
        AppScope.launch {
            pending.forEach { pos ->
                try {
                    quotes[pos.symbol] = withContext(Dispatchers.IO) { MarketService.fetchQuote(pos.symbol) }
                } catch (e: Exception) {
                    quotes[pos.symbol] = null
                }
                activity?.runOnUiThread {
                    refreshPositionsRows()
                    refreshSummary()
                }
            }
            onDone?.invoke()
        }
    }

    private fun dp(v: Int): Int =
        (v * resources.displayMetrics.density).toInt()

    // ------------------------- scope 切换辅助 -------------------------
    private fun updateTitle() {
        val ctx = context ?: return
        val title = tvTitle ?: return
        title.text = if (currentScope == "live") "实盘账户" else "模拟账户"
        val accent = ContextCompat.getColor(ctx, if (currentScope == "live") R.color.up else R.color.text_primary)
        title.setTextColor(accent)
    }

    private fun updateSectionVisibility() {
        val v = view ?: return
        val isLive = currentScope == "live"
        v.findViewById<View>(R.id.section_pos_header).visibility = if (isLive) View.GONE else View.VISIBLE
        v.findViewById<View>(R.id.btn_clear_positions).visibility = if (isLive) View.GONE else View.VISIBLE
        v.findViewById<TextView>(R.id.tv_pending_header).text = if (isLive) "实盘委托（点击撤销）" else "挂单（等待成交）"
        v.findViewById<View>(R.id.tv_trade_header).visibility = if (isLive) View.GONE else View.VISIBLE
        v.findViewById<View>(R.id.btn_trade_bar).visibility = if (isLive) View.GONE else View.VISIBLE
    }

    // ------------------------- 实盘渲染 -------------------------
    private suspend fun refreshLive() {
        if (currentScope != "live") return
        if (App.appStore.liveGateway.isBlank()) {
            withContext(Dispatchers.Main) {
                summaryView?.text = "⚠ 请先在设置 → 实盘设置中配置网关 URL 并连接测试"
                posContainer?.removeAllViews()
                pendingContainer?.removeAllViews()
            }
            return
        }
        val (json, msg) = withContext(Dispatchers.IO) { LiveGateway.account() }
        liveData = json
        withContext(Dispatchers.Main) {
            if (json == null) {
                summaryView?.text = "⚠ 网关返回错误：$msg"
                posContainer?.removeAllViews()
                pendingContainer?.removeAllViews()
                return@withContext
            }
            renderLiveSummary(json)
            renderLivePositions(json)
            renderLiveOrders(json)
        }
    }

    private fun renderLiveSummary(json: JSONObject) {
        val tv = summaryView ?: return
        val asset = json.optJSONObject("asset")
            ?: json.optJSONObject("account")
        if (asset == null) {
            tv.text = "⚠ 网关返回数据缺少 asset 字段"
            tv.setTextColor(ContextCompat.getColor(requireContext(), R.color.down))
            return
        }
        val total = asset.optDouble("total_asset", 0.0)
            .takeIf { it > 0 }
            ?: asset.optDouble("total", 0.0)
            .takeIf { it > 0 }
            ?: asset.optDouble("equity", 0.0)
        val cash = asset.optDouble("available_cash", 0.0)
            .takeIf { it > 0 }
            ?: asset.optDouble("cash", 0.0)
        val marketVal = asset.optDouble("market_value", 0.0)
            .takeIf { it > 0 }
            ?: asset.optDouble("market_value", 0.0)
        val pnl = asset.optDouble("total_pnl", asset.optDouble("pnl", 0.0))
        val pnlColor = ContextCompat.getColor(requireContext(), if (pnl >= 0) R.color.up else R.color.down)
        tv.text = buildString {
            append("账户总资产：${fmt(total)} 元\n")
            append("可用资金：${fmt(cash)} 元")
            if (marketVal > 0) append("　持仓市值：${fmt(marketVal)} 元")
            if (pnl != 0.0) {
                append("\n累计盈亏：${sign(pnl)}${fmt(pnl)} 元")
            }
        }
        tv.setTextColor(pnlColor)
    }

    private fun renderLivePositions(json: JSONObject) {
        val container = posContainer ?: return
        if (container.childCount > 0) container.removeAllViews()
        val arr = json.optJSONArray("positions")
        if (arr == null || arr.length() == 0) {
            val empty = TextView(requireContext()).apply {
                text = "（实盘暂无持仓）"
                setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                textSize = 13f
                setPadding(dp(10), dp(10), dp(10), dp(10))
            }
            container.addView(empty)
            return
        }
        val sdf = SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
        for (i in 0 until arr.length()) {
            val p = arr.getJSONObject(i)
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(10), dp(8), dp(10), dp(8))
            }
            val name = p.optString("name").ifBlank { p.optString("symbol") }
            val symbol = p.optString("symbol")
            val qty = p.optInt("qty", p.optInt("quantity", 0))
            val cost = p.optDouble("cost_price", p.optDouble("avg_cost", 0.0))
            val last = p.optDouble("last_price", p.optDouble("price", 0.0))
            val marketVal = p.optDouble("market_value", qty * last)
            val pnl = p.optDouble("pnl", qty * (last - cost))
            val pct = p.optDouble("pnl_pct", if (cost > 0) (last - cost) / cost * 100 else 0.0)
            val c = ContextCompat.getColor(requireContext(), if (pnl >= 0) R.color.up else R.color.down)
            val line1 = TextView(requireContext()).apply {
                text = "$name  $symbol"
                textSize = 14f
                setTextColor(ContextCompat.getColor(requireContext(), R.color.text_primary))
            }
            val line2 = TextView(requireContext()).apply {
                text = "数量${qty}  成本${fmt(cost)}  现价${fmt(last)}  市值${fmt(marketVal)}"
                textSize = 12f
                setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
            }
            val line3 = TextView(requireContext()).apply {
                text = "盈亏 ${sign(pnl)}${fmt(pnl)}（${sign(pct)}${fmt(pct)}%）"
                textSize = 13f
                setTextColor(c)
            }
            row.addView(line1); row.addView(line2); row.addView(line3)
            row.setOnClickListener { (activity as? MainActivity)?.openChart(symbol) }
            container.addView(row)
            if (i > 0) {
                container.addView(View(context).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
                    setBackgroundColor(ContextCompat.getColor(context, R.color.divider))
                })
            }
        }
    }

    private fun renderLiveOrders(json: JSONObject) {
        val container = pendingContainer ?: return
        if (container.childCount > 0) container.removeAllViews()
        val arr = json.optJSONArray("orders")
        if (arr == null || arr.length() == 0) {
            val empty = TextView(requireContext()).apply {
                text = "（实盘暂无委托）"
                setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                textSize = 13f
                setPadding(dp(10), dp(10), dp(10), dp(10))
            }
            container.addView(empty)
            return
        }
        val sdf = SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val id = o.optString("order_id").ifBlank { o.optString("id") }
            val symbol = o.optString("symbol")
            val name = o.optString("name").ifBlank { symbol }
            val side = o.optString("side")
            val price = o.optDouble("price", 0.0)
            val qty = o.optInt("qty", o.optInt("quantity", 0))
            val filled = o.optInt("filled_qty", o.optInt("filled", 0))
            val status = o.optString("status").ifBlank { o.optString("state") }
            val timeTs = o.optLong("created_at", o.optLong("time", 0))
            val timeStr = if (timeTs > 0) sdf.format(java.util.Date(timeTs)) else ""

            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(8), dp(8), dp(8))
            }
            val info = TextView(requireContext()).apply {
                val sideLabel = when (side.lowercase()) {
                    "buy", "买入", "1" -> "买入"
                    "sell", "卖出", "2" -> "卖出"
                    else -> side
                }
                val sideColor = ContextCompat.getColor(context, if (sideLabel == "买入") R.color.up else R.color.down)
                val statusLabel = when (status.lowercase()) {
                    "pending", "new", "wait", "待成交" -> "待成交"
                    "partial", "partially_filled", "部成" -> "部成"
                    "filled", "done", "已成交" -> "已成交"
                    "cancelled", "canceled", "已撤" -> "已撤"
                    "rejected", "已拒" -> "已拒"
                    else -> status
                }
                val statusColor = when (statusLabel) {
                    "待成交", "部成" -> ContextCompat.getColor(context, R.color.up)
                    "已成交" -> ContextCompat.getColor(context, R.color.text_primary)
                    "已撤", "已拒" -> ContextCompat.getColor(context, R.color.text_secondary)
                    else -> ContextCompat.getColor(context, R.color.text_primary)
                }
                text = buildString {
                    append("$name $sideLabel ")
                    append("@${fmt(price)} 元  数量${qty}  已成${filled}\n")
                    if (timeStr.isNotEmpty()) append("$timeStr　")
                    append("状态：$statusLabel")
                }
                setTextColor(sideColor)
                textSize = 14f
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                // 状态在第二行显示时用状态色
                // 简化处理：side 用 up/down，status 颜色可以单独处理
            }

            val cancelBtn = TextView(requireContext()).apply {
                text = when {
                    status.equals("pending", true) || status.equals("new", true) ||
                    status.equals("待成交", true) || status.equals("wait", true) -> "撤销"
                    status.equals("partial", true) || status.equals("partially_filled", true) ||
                    status.equals("部成", true) -> "撤剩余"
                    else -> "—"
                }
                isClickable = text != "—"
                textSize = 13f
                setPadding(dp(10), dp(6), dp(10), dp(6))
                themeAttrColor(context, R.attr.onBrand).also { setTextColor(it) }
                if (isClickable && id.isNotBlank()) {
                    setOnClickListener {
                        android.app.AlertDialog.Builder(requireContext())
                            .setTitle("确认撤单")
                            .setMessage("撤销委托 $name ${side} @${fmt(price)}，委托号：$id？")
                            .setPositiveButton("撤销") { _, _ -> doCancel(id, name) }
                            .setNegativeButton("取消", null)
                            .show()
                    }
                }
            }

            row.addView(info)
            row.addView(cancelBtn)
            container.addView(row)
            if (i > 0) {
                container.addView(View(context).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
                    setBackgroundColor(ContextCompat.getColor(context, R.color.divider))
                })
            }
        }
    }

    private fun doCancel(orderId: String, orderLabel: String) {
        com.quantapp.trader.ui.AppScope.launch {
            val (ok, msg) = withContext(Dispatchers.IO) { LiveGateway.cancel(orderId) }
            withContext(Dispatchers.Main) {
                if (ok) {
                    Toast.makeText(requireContext(), "已撤单：$orderLabel", Toast.LENGTH_SHORT).show()
                    refreshLive()
                } else {
                    Toast.makeText(requireContext(), "撤单失败：$msg", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}

private fun fmt(v: Double) = String.format("%.2f", v)
private fun sign(v: Double) = if (v >= 0) "+" else ""