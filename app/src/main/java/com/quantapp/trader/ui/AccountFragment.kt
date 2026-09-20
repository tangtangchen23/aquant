package com.quantapp.trader.ui

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.quantapp.trader.R
import com.quantapp.trader.data.MarketService
import com.quantapp.trader.trading.App
import com.quantapp.trader.trading.Position
import com.quantapp.trader.trading.TradingEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat

class AccountFragment : Fragment() {

    private var adapter: PositionAdapter? = null
    private var btnRefresh: TextView? = null
    private var filterSide: String? = null // null=全部，"买入"/"卖出"

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val root = inflater.inflate(R.layout.fragment_account, container, false)
        val tvSummary = root.findViewById<TextView>(R.id.tv_account_summary)
        val listPos = root.findViewById<ListView>(R.id.list_positions)
        val listTrades = root.findViewById<ListView>(R.id.list_trades)
        val btnReset = root.findViewById<Button>(R.id.btn_reset)
        btnRefresh = root.findViewById(R.id.btn_refresh_positions)

        // 持仓列表：实时估值 + 红涨绿跌，点击跳转K线图
        adapter = PositionAdapter(requireContext(), App.appStore.paper.positions.values.toList())
        listPos.adapter = adapter
        adapter?.refresh()
        listPos.setOnItemClickListener { _, _, p, _ ->
            val pos = adapter?.getItem(p) as? Position
            pos?.let { (activity as? MainActivity)?.openChart(it.symbol) }
        }

        // 一键清仓：按当前行情价卖出全部持仓（未拉到行情时按成本价）
        root.findViewById<Button>(R.id.btn_clear_positions).setOnClickListener {
            val positions = App.appStore.paper.positions.values.toList()
            if (positions.isEmpty()) {
                Toast.makeText(requireContext(), "当前无持仓", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            android.app.AlertDialog.Builder(requireContext())
                .setTitle("一键清仓")
                .setMessage("确定以当前行情价卖出全部 ${positions.size} 只持仓吗？")
                .setPositiveButton("全部卖出") { _, _ ->
                    clearAll()
                    refreshSummary(tvSummary)
                    refreshTrades(listTrades)
                }
                .setNegativeButton("取消", null)
                .show()
        }

        // 成交记录筛选（全部/买入/卖出）
        root.findViewById<Button>(R.id.btn_trade_all).setOnClickListener { updateFilter(null, listTrades) }
        root.findViewById<Button>(R.id.btn_trade_buy).setOnClickListener { updateFilter("买入", listTrades) }
        root.findViewById<Button>(R.id.btn_trade_sell).setOnClickListener { updateFilter("卖出", listTrades) }
        updateFilterUi(listTrades)

        refreshSummary(tvSummary)
        refreshTrades(listTrades)

        TradingEngine.onTrade = { _ ->
            activity?.runOnUiThread {
                adapter?.reload(App.appStore.paper.positions.values.toList())
                adapter?.refresh()
                refreshSummary(tvSummary)
                refreshTrades(listTrades)
            }
        }

        // 手动刷新持仓市值
        btnRefresh?.setOnClickListener {
            btnRefresh?.text = "刷新中..."
            adapter?.refresh { btnRefresh?.text = "↻ 刷新市值" }
        }

        btnReset.setOnClickListener {
            val capital = App.appStore.initialCapital()
            App.appStore.paper.reset(capital)
            App.appStore.save()
            adapter?.reload(App.appStore.paper.positions.values.toList())
            adapter?.refresh()
            refreshSummary(tvSummary)
            refreshTrades(listTrades)
            Toast.makeText(requireContext(), "模拟盘已重置", Toast.LENGTH_SHORT).show()
        }
        return root
    }

    private fun refreshSummary(tvSummary: TextView) {
        val st = App.appStore
        val pos = st.paper.positions.values
        val cash = st.paper.cash
        // 用适配器已缓存的实时价估算总权益；未拉到的持仓按成本价兜底
        var mv = 0.0
        var pnl = 0.0
        for (p in pos) {
            val q = adapter?.quoteOf(p.symbol)
            val price = q?.price ?: p.costPrice
            mv += p.marketValue(price)
            pnl += p.pnl(price)
        }
        val equity = cash + mv
        val tv = tvSummary
        tv.text = buildString {
            append("可用资金：${fmt(cash)} 元\n")
            append("持仓市值：${fmt(mv)} 元\n")
            if (pos.isNotEmpty()) {
                append("账户总权益：${fmt(equity)} 元（浮动盈亏 ${sign(pnl)}${fmt(pnl)} 元）\n")
                tv.setTextColor(if (pnl >= 0)
                    ContextCompat.getColor(requireContext(), R.color.up)
                else ContextCompat.getColor(requireContext(), R.color.down))
            } else {
                append("账户总权益：${fmt(equity)} 元\n")
                tv.setTextColor(ContextCompat.getColor(requireContext(), R.color.down))
            }
            append("持仓数：${pos.size} 只　总成交：${st.paper.trades.size} 笔")
        }
    }

    /** 一键清仓：按当前行情价卖出全部持仓（未拉到行情时按成本价兜底）。 */
    private fun clearAll() {
        val st = App.appStore
        st.paper.positions.values.toList().forEach { p ->
            val q = adapter?.quoteOf(p.symbol)
            st.paper.sell(p.symbol, p.name, q?.price ?: p.costPrice)
        }
        st.save()
        adapter?.reload(st.paper.positions.values.toList())
        adapter?.refresh()
        Toast.makeText(requireContext(), "已全部清仓", Toast.LENGTH_SHORT).show()
    }

    private fun updateFilter(side: String?, listTrades: ListView) {
        filterSide = side
        updateFilterUi(listTrades)
        refreshTrades(listTrades)
    }

    private fun updateFilterUi(listTrades: ListView) {
        fun style(id: Int, active: Boolean) {
            val btn = view?.findViewById<Button>(id) ?: listTrades.rootView.findViewById(id)
            btn.setTextColor(if (active)
                ContextCompat.getColor(requireContext(), R.color.brand)
            else ContextCompat.getColor(requireContext(), R.color.text_secondary))
            btn.setTypeface(null, if (active) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        }
        style(R.id.btn_trade_all, filterSide == null)
        style(R.id.btn_trade_buy, filterSide == "买入")
        style(R.id.btn_trade_sell, filterSide == "卖出")
    }

    private fun refreshTrades(listTrades: ListView) {
        val trades = App.appStore.paper.trades
            .asReversed()
            .filter { filterSide == null || it.side == filterSide }
            .take(60)
        listTrades.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1,
            trades.takeIf { it.isNotEmpty() }
                ?.map {
                    val t = SimpleDateFormat("MM-dd HH:mm").format(java.util.Date(it.time))
                    "$t  ${it.name} ${it.side} ${it.qty}股 @ ${fmt(it.price)}"
                } ?: listOf("（暂无成交记录）"))
    }

    /** 持仓列表适配器：实时估值，红涨绿跌，逐条异步拉行情。 */
    private class PositionAdapter(ctx: Context, initial: List<Position>) : BaseAdapter() {
        private val list = initial.toMutableList()
        private val quotes = mutableMapOf<String, com.quantapp.trader.data.Quote?>()
        private val ctx = ctx.applicationContext
        override fun getCount() = list.size
        override fun getItem(p: Int): Any = list[p]
        override fun getItemId(p: Int): Long = p.toLong()
        fun reload(p: List<Position>) { list.clear(); list.addAll(p); notifyDataSetChanged() }
        fun quoteOf(symbol: String) = quotes[symbol]

        override fun getView(p: Int, cv: View?, parent: ViewGroup): View {
            val v = cv ?: LayoutInflater.from(ctx).inflate(R.layout.item_position, parent, false)
            val pos = list[p]
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
                val c = ctx.getColor(if (q.changePct >= 0) R.color.up else R.color.down)
                day.text = "现价 ${fmt(price)}  ${sign(q.changePct)}${fmt(q.changePct)}%"
                day.setTextColor(c)
                meta.text = "数量${pos.qty}  成本${fmt(pos.costPrice)}  市值${fmt(mv)}"
                pnl.text = "${sign(pnlV)}${fmt(pnlV)}（${sign(pos.pnlPct(price))}${fmt(pos.pnlPct(price))}%）"
                pnl.setTextColor(c)
            } else {
                day.text = if (quotes.containsKey(pos.symbol)) "暂无行情" else "市值核算中..."
                day.setTextColor(ctx.getColor(R.color.down))
                meta.text = "数量${pos.qty}  成本${fmt(pos.costPrice)}"
                pnl.text = "--"
                pnl.setTextColor(ctx.getColor(R.color.down))
            }
            return v
        }

        fun refresh(onDone: (() -> Unit)? = null) {
            val pending = list.toList()
            AppScope.launch {
                pending.forEach { pos ->
                    try {
                        quotes[pos.symbol] = withContext(Dispatchers.IO) { MarketService.fetchQuote(pos.symbol) }
                    } catch (e: Exception) {
                        quotes[pos.symbol] = null
                    }
                    notifyDataSetChanged()
                }
                onDone?.invoke()
            }
        }
    }
}

private fun fmt(v: Double) = String.format("%.2f", v)
private fun sign(v: Double) = if (v >= 0) "+" else ""