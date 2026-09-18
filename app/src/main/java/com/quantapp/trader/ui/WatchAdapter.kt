package com.quantapp.trader.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.TextView
import com.quantapp.trader.data.MarketService
import com.quantapp.trader.data.Quote
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 自选股列表适配器：每行显示「股票名称 + 代码」与「现价 + 涨跌幅」。
 * 后台异步拉取每只自选股的实时行情；未加载完成时先显示代码 + 加载中。
 */
class WatchAdapter(private val ctx: Context, private val symbols: MutableList<String>) :
    BaseAdapter() {

    private val inflater = LayoutInflater.from(ctx)
    private val quotes = mutableMapOf<String, Quote?>()

    override fun getCount(): Int = symbols.size
    override fun getItem(position: Int): Any = symbols[position]
    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val v = convertView ?: inflater.inflate(android.R.layout.simple_list_item_2, parent, false)
        val code = symbols[position]
        val q = quotes[code]
        val t1 = v.findViewById<TextView>(android.R.id.text1)
        val t2 = v.findViewById<TextView>(android.R.id.text2)
        if (q != null) {
            val sign = if (q.changePct >= 0) "+" else ""
            t1.text = "${q.name}  $code"
            t2.text = "现价 ${String.format("%.2f", q.price)}    涨跌 ${sign}${String.format("%.2f", q.changePct)}%"
        } else {
            t1.text = code
            t2.text = if (quotes.containsKey(code)) "暂无行情" else "加载中..."
        }
        return v
    }

    /** 异步刷新所有自选股的实时行情。 */
    fun refresh() {
        val pending = symbols.toList()
        AppScope.launch {
            pending.forEach { code ->
                try {
                    quotes[code] = withContext(Dispatchers.IO) { MarketService.fetchQuote(code) }
                } catch (e: Exception) {
                    quotes[code] = null
                }
                notifyDataSetChanged()
            }
        }
    }
}