package com.quantapp.trader.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.TextView
import com.quantapp.trader.R
import com.quantapp.trader.data.MarketService
import com.quantapp.trader.data.Quote
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 自选股列表适配器：每行两组文字——上行「名称 + 代码」，下行「现价 + 涨跌幅」。
 * 红涨绿跌配色（中国习惯：涨红跌绿）。
 * 名称优先取自 nameMap（本地缓存/搜索接口，不依赖行情接口），
 * 价格后台异步从实时行情接口逐条加载，加载完成后刷新当前行。
 */
class WatchAdapter(
    private val ctx: Context,
    private val symbols: MutableList<String>,
    private val nameMap: MutableMap<String, String>
) : BaseAdapter() {

    private val inflater = LayoutInflater.from(ctx)
    private val quotes = mutableMapOf<String, Quote?>()

    override fun getCount(): Int = symbols.size
    override fun getItem(position: Int): Any = symbols[position]
    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val v = convertView ?: inflater.inflate(R.layout.item_watch, parent, false)
        val code = symbols[position]
        val q = quotes[code]
        val name = nameMap[code] ?: ""
        val label = if (name.isNotEmpty()) "$name  $code" else code
        val t1 = v.findViewById<TextView>(R.id.tv_w_name)
        val t2 = v.findViewById<TextView>(R.id.tv_w_price)
        t1.text = label
        if (q != null) {
            val sign = if (q.changePct >= 0) "+" else ""
            t2.text = "${String.format("%.2f", q.price)}\t${sign}${String.format("%.2f", q.changePct)}%"
            val c = ctx.getColor(if (q.changePct >= 0) R.color.up else R.color.down)
            t2.setTextColor(c)
        } else {
            t2.text = if (quotes.containsKey(code)) "价格加载中" else "加载中..."
            t2.setTextColor(ctx.getColor(R.color.down))
        }
        return v
    }

    /** 异步刷新所有自选股：先用搜索接口补全名称，再拉实时行情。完成后回调 onDone（若有）。 */
    fun refresh(onDone: (() -> Unit)? = null) {
        val pending = symbols.toList()
        AppScope.launch {
            pending.forEach { code ->
                try {
                    if (nameMap[code].isNullOrEmpty()) {
                        val n = withContext(Dispatchers.IO) { MarketService.fetchName(code) }
                        if (n.isNotEmpty()) nameMap[code] = n
                    }
                } catch (e: Exception) { }
                try {
                    quotes[code] = withContext(Dispatchers.IO) { MarketService.fetchQuote(code) }
                } catch (e: Exception) {
                    quotes[code] = null
                }
                notifyDataSetChanged()
            }
            onDone?.invoke()
        }
    }
}