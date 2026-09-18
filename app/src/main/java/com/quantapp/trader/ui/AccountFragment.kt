package com.quantapp.trader.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.quantapp.trader.R
import com.quantapp.trader.trading.App
import com.quantapp.trader.trading.TradingEngine

class AccountFragment : Fragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val root = inflater.inflate(R.layout.fragment_account, container, false)
        val tvSummary = root.findViewById<TextView>(R.id.tv_account_summary)
        val listPos = root.findViewById<ListView>(R.id.list_positions)
        val listTrades = root.findViewById<ListView>(R.id.list_trades)
        val btnReset = root.findViewById<Button>(R.id.btn_reset)

        refresh(tvSummary, listPos, listTrades)

        TradingEngine.onTicker?.let { }
        TradingEngine.onTrade = { _ ->
            activity?.runOnUiThread { refresh(tvSummary, listPos, listTrades) }
        }

        btnReset.setOnClickListener {
            val capital = App.appStore.initialCapital()
            App.appStore.paper.reset(capital)
            App.appStore.save()
            refresh(tvSummary, listPos, listTrades)
            Toast.makeText(requireContext(), "模拟盘已重置", Toast.LENGTH_SHORT).show()
        }
        return root
    }

    private fun refresh(summary: TextView, listPos: ListView, listTrades: ListView) {
        val st = App.appStore
        val pos = st.paper.positions
        val cash = st.paper.cash
        // 未有实时价时暂以成本价估值
        val equity = cash + pos.values.sumOf { it.qty * it.costPrice }
        summary.text = buildString {
            append("可用资金：${String.format("%.2f", cash)} 元\n")
            append("持仓市值：${String.format("%.2f", equity - cash)} 元\n")
            append("账户总权益：${String.format("%.2f", equity)} 元\n")
            append("持仓数：${pos.size} 只　总成交：${st.paper.trades.size} 笔")
        }

        listPos.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1,
            if (pos.values.isEmpty()) listOf("（暂无持仓）")
            else pos.values.sortedByDescending { it.marketValue(it.costPrice) }
                .map { "  ${it.name}  ${it.symbol}  数量${it.qty}  成本${String.format("%.2f", it.costPrice)}  市值${String.format("%.2f", it.marketValue(it.costPrice))}" })

        listTrades.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1,
            st.paper.trades.takeLast(60).asReversed().map {
                val t = java.text.SimpleDateFormat("MM-dd HH:mm").format(java.util.Date(it.time))
                "$t  ${it.name} ${it.side} ${it.qty}股 @ ${String.format("%.2f", it.price)}"
            })
    }
}