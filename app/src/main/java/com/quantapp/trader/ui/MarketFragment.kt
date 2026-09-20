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
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.quantapp.trader.R
import com.quantapp.trader.data.MarketService
import com.quantapp.trader.data.Quote
import com.quantapp.trader.trading.App
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray

class MarketFragment : Fragment() {

    private val watchSymbols = mutableListOf<String>()
    private val queryHistory = mutableListOf<String>()
    private var quoteSymbol: String? = null // 当前报价卡片展示的股票代码

    private var tvQName: TextView? = null
    private var tvQChange: TextView? = null
    private var tvQPrice: TextView? = null
    private var tvQOpen: TextView? = null
    private var tvQPrev: TextView? = null
    private var tvQHigh: TextView? = null
    private var tvQLow: TextView? = null
    private var tvQVolume: TextView? = null
    private var tvQTime: TextView? = null
    private var cardQuote: View? = null
    private var btnAddWatch: Button? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val root = inflater.inflate(R.layout.fragment_market, container, false)
        val etCode = root.findViewById<AutoCompleteTextView>(R.id.et_code)
        val btnQuery = root.findViewById<Button>(R.id.btn_query)
        val btnAdd = root.findViewById<Button>(R.id.btn_add_watch)
        val btnRefresh = root.findViewById<TextView>(R.id.btn_refresh_watch)
        val list = root.findViewById<ListView>(R.id.list_watch)
        val btnClearHistory = root.findViewById<TextView>(R.id.btn_clear_history)
        btnAddWatch = btnAdd
        cardQuote = root.findViewById(R.id.card_quote)

        // 报价卡片字段
        tvQName = root.findViewById(R.id.tv_q_name)
        tvQChange = root.findViewById(R.id.tv_q_change)
        tvQPrice = root.findViewById(R.id.tv_q_price)
        tvQOpen = root.findViewById(R.id.tv_q_open)
        tvQPrev = root.findViewById(R.id.tv_q_prev)
        tvQHigh = root.findViewById(R.id.tv_q_high)
        tvQLow = root.findViewById(R.id.tv_q_low)
        tvQVolume = root.findViewById(R.id.tv_q_volume)
        tvQTime = root.findViewById(R.id.tv_q_time)

        // 点击查询股票卡片：自动跳转到K线图
        root.findViewById<View>(R.id.card_quote).setOnClickListener {
            val code = quoteSymbol
            if (!code.isNullOrBlank()) (activity as? MainActivity)?.openChart(code)
        }

        loadWatch()
        loadHistory()

        // 自选股列表：显示名称 + 代码 + 实时行情（红涨绿跌）
        val watchAdapter = WatchAdapter(requireContext(), watchSymbols)
        list.adapter = watchAdapter
        list.emptyView = root.findViewById(R.id.tv_watch_empty)
        watchAdapter.refresh()
        startAutoRefresh(list)

        // 查询历史自动补全
        val historyAdapter = ArrayAdapter(requireContext(),
            android.R.layout.simple_dropdown_item_1line, queryHistory)
        etCode.setAdapter(historyAdapter)
        if (queryHistory.isNotEmpty()) etCode.setText(queryHistory.lastOrNull() ?: "")

        // 弹出历史下拉（直接展示，方便点击重选）
        etCode.setOnFocusChangeListener { v, hasFocus ->
            if (hasFocus && queryHistory.isNotEmpty() && v is AutoCompleteTextView) v.showDropDown()
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

        // 自选股手动刷新
        btnRefresh.setOnClickListener {
            btnRefresh.text = "刷新中..."
            watchAdapter.refresh { btnRefresh.text = "↻ 刷新" }
            Toast.makeText(requireContext(), "已请求刷新自选股行情", Toast.LENGTH_SHORT).show()
        }

        btnQuery.setOnClickListener { q ->
            val raw = etCode.text.toString().trim()
            if (raw.isNotEmpty()) {
                q.isEnabled = false
                setQuoteLoading()
                AppScope.launch {
                    try {
                        // 支持名称：先解析为代码再查询
                        val code = MarketService.resolveCode(raw)
                        if (code != raw) etCode.setText(code)
                        val quote = withContext(Dispatchers.IO) { MarketService.fetchQuote(code) }
                        // 查询历史统一存股票名称（查名称或代码都显示为名称）
                        putHistory(quote.name)
                        refreshHistory(etCode)
                        fillQuoteCard(quote)
                    } catch (e: Exception) {
                        resetQuoteCard()
                        tvQName?.text = "查询失败"
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

        // 长按：弹出 置顶 / 删除 操作
        list.setOnItemLongClickListener { _, _, position, _ ->
            if (position in watchSymbols.indices) {
                showWatchActions(position)
            }
            true
        }
        return root
    }

    /** 长按自选股的菜单：置顶 / 删除。 */
    private fun showWatchActions(position: Int) {
        val code = watchSymbols[position]
        val options = arrayOf("置顶", "删除")
        AlertDialog.Builder(requireContext())
            .setTitle(code)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        watchSymbols.removeAt(position)
                        watchSymbols.add(0, code)
                        saveWatch()
                        val adapter = requireWatchAdapter()
                        adapter.refresh()
                    }
                    1 -> {
                        watchSymbols.removeAt(position)
                        saveWatch()
                        requireWatchAdapter().refresh()
                        Toast.makeText(requireContext(), "已移除自选 $code", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .show()
    }

    private fun requireWatchAdapter(): WatchAdapter {
        val list = view?.findViewById<ListView>(R.id.list_watch) ?: return WatchAdapter(requireContext(), watchSymbols)
        return list.adapter as WatchAdapter
    }

    private fun setQuoteLoading() {
        cardQuote?.visibility = View.VISIBLE
        tvQName?.text = "加载中..."
        tvQChange?.text = "--"
        tvQPrice?.text = "--"
    }

    private fun resetQuoteCard() {
        quoteSymbol = null
        cardQuote?.visibility = View.GONE
        btnAddWatch?.visibility = View.GONE
        tvQName?.text = "未查询"
        tvQChange?.text = ""
        tvQPrice?.text = "--"
        tvQOpen?.text = "--"
        tvQPrev?.text = "--"
        tvQHigh?.text = "--"
        tvQLow?.text = "--"
        tvQVolume?.text = "--"
        tvQTime?.text = ""
    }

    /** 用实时行情填充报价卡片，价格与涨跌幅红涨绿跌。 */
    private fun fillQuoteCard(q: Quote) {
        quoteSymbol = q.symbol
        cardQuote?.visibility = View.VISIBLE
        btnAddWatch?.visibility = View.VISIBLE
        val textColor = if (q.changePct >= 0) R.color.up else R.color.down
        val changeText = "${if (q.changePct >= 0) "+" else ""}${String.format("%.2f", q.changePct)}%"
        tvQName?.text = "${q.name}  ${q.symbol}"
        tvQChange?.text = changeText
        tvQPrice?.text = String.format("%.2f", q.price)
        tvQChange?.setTextColor(ContextCompat.getColor(requireContext(), textColor))
        tvQPrice?.setTextColor(ContextCompat.getColor(requireContext(), textColor))
        tvQOpen?.text = String.format("%.2f", q.open)
        tvQPrev?.text = String.format("%.2f", q.prevClose)
        tvQHigh?.text = String.format("%.2f", q.high)
        tvQLow?.text = String.format("%.2f", q.low)
        tvQVolume?.text = "成交量 ${q.volume / 100.0} 手"
        tvQTime?.text = q.time
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

    /**
     * 自选股实时行情自动刷新：有自选股且页面对用户可见时，每 10 秒刷新一轮，
     * 无需手工点击刷新。
     */
    private fun startAutoRefresh(list: ListView) {
        viewLifecycleOwner.lifecycleScope.launch {
            while (isActive) {
                delay(10_000)
                if (watchSymbols.isNotEmpty()) {
                    (list.adapter as? WatchAdapter)?.refresh()
                }
            }
        }
    }
}