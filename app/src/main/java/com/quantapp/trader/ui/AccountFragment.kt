package com.quantapp.trader.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.quantapp.trader.R
import com.quantapp.trader.data.MarketService
import com.quantapp.trader.data.Quote
import com.quantapp.trader.trading.App
import com.quantapp.trader.trading.Position
import com.quantapp.trader.trading.TradingEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
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
    private var refreshJob: Job? = null

    // ------------------------- 生命周期 -------------------------
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val root = inflater.inflate(R.layout.fragment_account, container, false)
        summaryView = root.findViewById(R.id.tv_account_summary)
        btnRefresh = root.findViewById(R.id.btn_refresh_positions)

        // 持仓/成交：动态 LinearLayout 逐行注入，随数量增长并整页滚动
        posContainer = root.findViewById(R.id.list_positions_container)
        tradeContainer = root.findViewById(R.id.list_trades_container)
        reloadPositions()
        renderTrades()
        refreshSummary()

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
        super.onPause()
    }

    /** 页面可见时按轮询间隔自动刷新行情，让持仓市值/权益/浮动盈亏实时跟随涨跌。 */
    private fun startRefreshLoop() {
        refreshJob?.cancel()
        refreshJob = AppScope.launch {
            while (isActive) {
                refreshQuotes()
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
        reloadPositions()
        refreshSummary()
        renderTrades()
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
}

private fun fmt(v: Double) = String.format("%.2f", v)
private fun sign(v: Double) = if (v >= 0) "+" else ""