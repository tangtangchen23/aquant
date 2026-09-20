package com.quantapp.trader.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.quantapp.trader.R
import com.quantapp.trader.data.MarketService
import com.quantapp.trader.data.Quote
import com.quantapp.trader.trading.App
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray

class MarketFragment : Fragment() {

    private val watchSymbols = mutableListOf<String>()
    private val queryHistory = mutableListOf<String>()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val root = inflater.inflate(R.layout.fragment_market, container, false)
        val etCode = root.findViewById<AutoCompleteTextView>(R.id.et_code)
        val btnQuery = root.findViewById<Button>(R.id.btn_query)
        val tvQuote = root.findViewById<TextView>(R.id.tv_quote)
        val btnAdd = root.findViewById<Button>(R.id.btn_add_watch)
        val list = root.findViewById<ListView>(R.id.list_watch)
        val btnClearHistory = root.findViewById<TextView>(R.id.btn_clear_history)

        loadWatch()
        loadHistory()

        // 自选股列表：显示名称 + 代码 + 实时行情
        val watchAdapter = WatchAdapter(requireContext(), watchSymbols)
        list.adapter = watchAdapter
        watchAdapter.refresh()

        // 查询历史自动补全
        val historyAdapter = ArrayAdapter(requireContext(),
            android.R.layout.simple_dropdown_item_1line, queryHistory)
        etCode.setAdapter(historyAdapter)
        if (queryHistory.isNotEmpty()) etCode.setText(queryHistory.lastOrNull() ?: "")

        // 弹出历史下拉（直接展示，方便点击重选）
        etCode.setOnFocusChangeListener { v, hasFocus ->
            if (hasFocus && queryHistory.isNotEmpty()) etCode.showDropDown()
        }

        // 清除查询历史
        btnClearHistory.setOnClickListener {
            if (queryHistory.isEmpty()) {
                Toast.makeText(requireContext(), "暂无可清除的查询历史", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            clearHistory()
            etCode.setAdapter(ArrayAdapter(requireContext(),
                android.R.layout.simple_dropdown_item_1line, queryHistory))
            etCode.dismissDropDown()
            etCode.setText("")
            Toast.makeText(requireContext(), "查询历史已清除", Toast.LENGTH_SHORT).show()
        }

        btnQuery.setOnClickListener { q ->
            val raw = etCode.text.toString().trim()
            if (raw.isNotEmpty()) {
                q.isEnabled = false
                tvQuote.text = "解析/查询中..."
                AppScope.launch {
                    try {
                        // 支持名称：先解析为代码再查询
                        val code = MarketService.resolveCode(raw)
                        if (code != raw) etCode.setText(code)
                        val quote = withContext(Dispatchers.IO) { MarketService.fetchQuote(code) }
                        // 查询历史统一存股票名称（查名称或代码都显示为名称）
                        putHistory(quote.name)
                        refreshHistory(etCode)
                        tvQuote.text = formatQuote(quote, code)
                    } catch (e: Exception) {
                        tvQuote.text = "查询失败：${e.message}"
                    } finally {
                        q.isEnabled = true
                    }
                }
            }
        }

        fun addWatch() {
            val raw = etCode.text.toString().trim()
            if (raw.isEmpty()) { Toast.makeText(requireContext(), "请输入代码或名称", Toast.LENGTH_SHORT).show(); return }
            btnAdd.isEnabled = false
            AppScope.launch {
                try {
                    // 解析成代码存储，保证点击跳转K线用代码
                    val code = MarketService.resolveCode(raw)
                    if (code != raw) etCode.setText(code)
                    if (!watchSymbols.contains(code)) {
                        watchSymbols.add(code)
                        saveWatch()
                        watchAdapter.refresh()
                    }
                    // 历史统一存名称；若输入的是代码，则从行情结果取名称
                    val historyName = if (code != raw) raw else
                        withContext(Dispatchers.IO) { MarketService.fetchQuote(code) }.name
                    putHistory(historyName)
                    refreshHistory(etCode)
                    Toast.makeText(requireContext(), "已加入自选：$code（点击自选股查看K线）", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(requireContext(), "加入自选失败：${e.message}", Toast.LENGTH_SHORT).show()
                } finally {
                    btnAdd.isEnabled = true
                }
            }
        }

        btnAdd.setOnClickListener { addWatch() }

        // 点击自选股：自动打开K线图
        list.setOnItemClickListener { _, _, position, _ ->
            if (position !in watchSymbols.indices) return@setOnItemClickListener
            val code = watchSymbols[position]
            (activity as? MainActivity)?.openChart(code)
        }

        // 长按移除自选
        list.setOnItemLongClickListener { _, _, position, _ ->
            if (position in watchSymbols.indices) {
                val code = watchSymbols.removeAt(position)
                saveWatch()
                watchAdapter.refresh()
                Toast.makeText(requireContext(), "已移除自选 $code", Toast.LENGTH_SHORT).show()
            }
            true
        }
        return root
    }

    private fun refreshHistory(etCode: AutoCompleteTextView) {
        etCode.setAdapter(ArrayAdapter(requireContext(),
            android.R.layout.simple_dropdown_item_1line, queryHistory))
    }

    // ---------- 持久化：自选 ----------
    private fun prefs() = App.context.getSharedPreferences("watch", 0)

    private fun loadWatch() {
        try {
            val arr = JSONArray(prefs().getString("list", "[]") ?: "[]")
            watchSymbols.clear()
            for (i in 0 until arr.length()) watchSymbols.add(arr.getString(i))
        } catch (e: Exception) { }
    }

    private fun saveWatch() {
        val arr = JSONArray()
        watchSymbols.forEach { arr.put(it) }
        prefs().edit().putString("list", arr.toString()).apply()
    }

    // ---------- 持久化：查询历史 ----------
    private fun loadHistory() {
        try {
            val arr = JSONArray(prefs().getString("history", "[]") ?: "[]")
            queryHistory.clear()
            for (i in 0 until arr.length()) queryHistory.add(arr.getString(i))
        } catch (e: Exception) { }
    }

    private fun putHistory(code: String) {
        queryHistory.remove(code)
        queryHistory.add(code)
        while (queryHistory.size > 10) queryHistory.removeAt(0)
        val arr = JSONArray()
        queryHistory.forEach { arr.put(it) }
        prefs().edit().putString("history", arr.toString()).apply()
    }

    private fun clearHistory() {
        queryHistory.clear()
        prefs().edit().putString("history", "[]").apply()
    }

    private fun formatQuote(q: Quote, code: String): String {
        val up = q.changePct >= 0
        val sign = if (up) "+" else ""
        return "┌─────────────\n" +
            " ${q.name}  ${q.symbol}\n" +
            " 最新价(元)：${String.format("%.2f", q.price)}\n" +
            " 涨跌幅：${sign}${String.format("%.2f", q.changePct)}%\n" +
            " 今开：${String.format("%.2f", q.open)}  昨收：${String.format("%.2f", q.prevClose)}\n" +
            " 最高：${String.format("%.2f", q.high)}  最低：${String.format("%.2f", q.low)}\n" +
            " 成交量：${q.volume / 1.0} 手\n" +
            " 时间：${q.time}\n└─────────────\n" +
            "提示：加入自选后可点下方自选股查询；长按可移除。可在“图表”页输入代码查看K线。"
    }
}