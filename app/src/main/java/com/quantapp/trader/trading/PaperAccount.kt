package com.quantapp.trader.trading

import org.json.JSONArray
import org.json.JSONObject

/** 单只股票持仓。 */
data class Position(
    val symbol: String,
    val name: String,
    val qty: Int,
    val costPrice: Double,
    /** 自建仓以来跟踪到的最高价（移动止盈/保本止损的依据），随行情每轮更新。 */
    var roundTripHigh: Double = costPrice,
    /** 下一档盈利加仓触发阈值(%)；达标加仓后按增量前移。 */
    var nextAddPct: Double = 0.0,
    /** 已加仓次数。 */
    var addCount: Int = 0,
    /** 当日买入数量（A股 T+1，当日不可卖出）。做T高抛时只能卖 qty-intradayQty。 */
    var intradayQty: Int = 0,
    /** intradayQty 所属交易日（yyyy-MM-dd），跨日后清零并转化为隔日可卖底仓。 */
    var intradayDay: String = "",
    /** 做T最近一次低吸买入价（网格锚点），再低吸需比它更低一档。 */
    var tLastBuy: Double = 0.0,
    /** 做T最近一次高抛卖出价（网格锚点）。 */
    var tLastSell: Double = 0.0
) {
    fun marketValue(price: Double) = qty * price
    fun pnl(price: Double) = (price - costPrice) * qty
    fun pnlPct(price: Double) = if (costPrice > 0) (price - costPrice) / costPrice * 100 else 0.0
    /** 当日可卖出数量（隔日底仓部分），做T高抛的上限。 */
    fun sellableQty() = (qty - intradayQty).coerceAtLeast(0)
}

/** 模拟盘成交记录。 */
data class Trade(
    val time: Long,
    val symbol: String,
    val name: String,
    val side: String, // "买入"/"卖出"
    val price: Double,
    val qty: Int,
    val amount: Double
)

/**
 * 模拟盘账户：现金 + 持仓 + 成交记录。
 * 使用 JSON 持久化到 SharedPreferences。
 */
class PaperAccount(initialCapital: Double = 100000.0) {

    var cash: Double = initialCapital
    val positions = mutableMapOf<String, Position>()
    val trades = mutableListOf<Trade>()

    val holdings: List<Position> get() = positions.values.toList()

    fun equity(realtimePrices: Map<String, Double>): Double {
        var mv = 0.0
        for (p in positions.values) {
            val price = realtimePrices[p.symbol]
            if (price != null) mv += p.marketValue(price)
        }
        return cash + mv
    }

    /** 买入：totalTarget 为要投入的总资金（整取100股）。返回是否成交。 */
    fun buy(symbol: String, name: String, price: Double, totalTarget: Double, intraday: Boolean = false): Trade? {
        if (price <= 0) return null
        val qty = (totalTarget / (price * 100)).toInt() * 100
        if (qty < 100) return null
        val amount = qty * price
        if (amount > cash) return null
        cash -= amount
        val cur = positions[symbol]
        if (cur != null) {
            // 加仓：合并数量与平均成本；跟踪中的 roundTripHigh 取较高者，不清零
            val newQty = cur.qty + qty
            val newCost = (cur.costPrice * cur.qty + amount) / newQty
            val today = todayStr()
            // 当日买入计入 intradayQty（T+1 当日不可卖）
            val iq = if (cur.intradayDay == today) cur.intradayQty + qty else qty
            positions[symbol] = Position(symbol, name, newQty, newCost,
                if (price > cur.roundTripHigh) price else cur.roundTripHigh,
                cur.nextAddPct, cur.addCount, iq, today)
        } else {
            positions[symbol] = Position(symbol, name, qty, price,
                intradayDay = todayStr())
        }
        val t = Trade(System.currentTimeMillis(), symbol, name, "买入", price, qty, amount)
        trades.add(t)
        return t
    }

    /** 卖出持仓。默认卖出全部；传入 [qty] 可部分平仓（不足100股的零股可一次卖出）。 */
    fun sell(symbol: String, name: String, price: Double, qty: Int = Int.MAX_VALUE): Trade? {
        val pos = positions[symbol] ?: return null
        if (price <= 0 || pos.qty <= 0) return null
        val sellQty = qty.coerceIn(1, pos.qty)
        val amount = sellQty * price
        cash += amount
        if (sellQty >= pos.qty) {
            positions.remove(symbol)
        } else {
            // 部分平仓：成本价不变，仅扣减数量；若卖出的是当日仓位，同步扣减 intradayQty
            val today = todayStr()
            val iq = if (pos.intradayDay == today && pos.intradayQty > 0) {
                (pos.intradayQty - sellQty).coerceAtLeast(0)
            } else pos.intradayQty
            positions[symbol] = pos.copy(qty = pos.qty - sellQty, intradayQty = iq)
        }
        val t = Trade(System.currentTimeMillis(), symbol, name, "卖出", price, sellQty, amount)
        trades.add(t)
        return t
    }

    /** 跨交易日时，把昨日当日买入转化为隔日可卖底仓：最新一次下单日期非今日则清零 intraday。 */
    fun rollIntraday() {
        val today = todayStr()
        for ((k, p) in positions) {
            if (p.intradayQty > 0 && p.intradayDay != today) {
                positions[k] = p.copy(intradayQty = 0, intradayDay = today)
            }
        }
    }

    /** 交易日字符串（yyyy-MM-dd）。 */
    fun todayStr(): String {
        return java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date())
    }

    fun reset(initialCapital: Double) {
        cash = initialCapital
        positions.clear()
        trades.clear()
    }

    fun recomputeCashFromTrades(initialCapital: Double) {
        // 用于加载持久化状态后校正：现金 = 初始资金 - 买入总额 + 卖出总额
    }

    fun toJson(): String {
        val pos = JSONArray()
        for (p in positions.values) {
            pos.put(JSONObject().put("symbol", p.symbol).put("name", p.name)
                .put("qty", p.qty).put("cost", p.costPrice)
                .put("high", p.roundTripHigh)
                .put("nextAdd", p.nextAddPct).put("adds", p.addCount)
                .put("iq", p.intradayQty).put("iday", p.intradayDay)
                .put("tBuy", p.tLastBuy).put("tSell", p.tLastSell))
        }
        val tr = JSONArray()
        for (t in trades) {
            tr.put(JSONObject().put("time", t.time).put("symbol", t.symbol).put("name", t.name)
                .put("side", t.side).put("price", t.price).put("qty", t.qty).put("amount", t.amount))
        }
        return JSONObject().put("cash", cash).put("positions", pos).put("trades", tr).toString()
    }

    fun fromJson(s: String) {
        try {
            val o = JSONObject(s)
            cash = o.optDouble("cash", 100000.0)
            positions.clear()
            val pos = o.optJSONArray("positions") ?: JSONArray()
            for (i in 0 until pos.length()) {
                val p = pos.getJSONObject(i)
                positions[p.getString("symbol")] = Position(
                    p.getString("symbol"), p.optString("name", ""),
                    p.optInt("qty", 0), p.optDouble("cost", 0.0)
                ).apply {
                    roundTripHigh = p.optDouble("high", costPrice)
                    nextAddPct = p.optDouble("nextAdd", 0.0)
                    addCount = p.optInt("adds", 0)
                    intradayQty = p.optInt("iq", 0)
                    intradayDay = p.optString("iday", "")
                    tLastBuy = p.optDouble("tBuy", 0.0)
                    tLastSell = p.optDouble("tSell", 0.0)
                }
            }
            trades.clear()
            val tr = o.optJSONArray("trades") ?: JSONArray()
            for (i in 0 until tr.length()) {
                val t = tr.getJSONObject(i)
                trades.add(Trade(t.optLong("time"), t.optString("symbol"), t.optString("name"),
                    t.optString("side"), t.optDouble("price"), t.optInt("qty"), t.optDouble("amount")))
            }
        } catch (e: Exception) {
            // ignore corrupt payload
        }
    }
}