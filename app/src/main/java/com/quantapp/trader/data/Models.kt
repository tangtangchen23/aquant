package com.quantapp.trader.data

import org.json.JSONArray
import org.json.JSONObject

/** Single candlestick / kline bar. */
data class KLine(
    val date: String,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Long,
    val amount: Double
)

/** Real-time snapshot of a symbol. */
data class Quote(
    val symbol: String,
    val name: String,
    val price: Double,
    val open: Double,
    val high: Double,
    val low: Double,
    val prevClose: Double,
    val changePct: Double,
    val volume: Long,
    val time: String
)

/** Build a Quote from the Eastmoney stock/get endpoint response. */
fun parseQuote(symbol: String, obj: JSONObject): Quote {
    val data = obj.optJSONObject("data")
    fun num(key: String) = if (data == null) 0.0 else data.optDouble(key, 0.0)
    fun longv(key: String): Long {
        val v = if (data == null) 0.0 else data.optDouble(key, 0.0)
        return v.toLong()
    }
    // Eastmoney prices are scaled by 100; f47 volume in lots(手).
    return Quote(
        symbol = symbol,
        name = data?.optString("f58", "") ?: "",
        price = num("f43") / 100.0,
        open = num("f46") / 100.0,
        high = num("f44") / 100.0,
        low = num("f45") / 100.0,
        prevClose = num("f60") / 100.0,
        changePct = num("f170") / 100.0,
        volume = longv("f47") * 100,
        time = data?.optString("f86", "") ?: ""
    )
}

/** Parse Eastmoney kline get response (fields2 = f51 date, f52 open, f53 close, f54 high, f55 low, f56 vol(手), f57 amount). */
fun parseKlines(obj: JSONObject): List<KLine> {
    val out = mutableListOf<KLine>()
    val data = obj.optJSONObject("data") ?: return out
    val kl = data.optJSONArray("klines") ?: return out
    for (i in 0 until kl.length()) {
        val line = kl.getString(i).split(",")
        if (line.size < 7) continue
        val date = line[0]
        val open = line[1].toDoubleOrNull() ?: 0.0
        val close = line[2].toDoubleOrNull() ?: 0.0
        val high = line[3].toDoubleOrNull() ?: 0.0
        val low = line[4].toDoubleOrNull() ?: 0.0
        val vol = (line[5].toDoubleOrNull() ?: 0.0).toLong() * 100
        val amount = line[6].toDoubleOrNull() ?: 0.0
        out.add(KLine(date, open, high, low, close, vol, amount))
    }
    return out
}

/** Parse search/autocomplete results array from Eastmoney suggest API. */
data class SearchItem(val code: String, val name: String, val market: Int)

fun parseSearchArray(arr: JSONArray): List<SearchItem> {
    val out = mutableListOf<SearchItem>()
    for (i in 0 until arr.length()) {
        val it = arr.getJSONObject(i)
        val code = it.optString("code", "")
        val name = it.optString("name", "")
        val mkt = it.optInt("mktnum", it.optInt("securityType", 1))
        val secType = it.optInt("securityType", -1)
        val market = if (mkt != 0) mkt else if (secType == -1) 1 else secType
        if (code.isNotEmpty()) out.add(SearchItem(code, name, market))
    }
    return out
}