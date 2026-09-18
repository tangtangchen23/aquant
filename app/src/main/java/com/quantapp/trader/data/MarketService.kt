package com.quantapp.trader.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * A股行情数据层，使用东方财富公开接口（无需后端与令牌）。
 * 支持 K 线、实时行情与股票搜索。
 */
object MarketService {

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    private val UA =
        "Mozilla/5.0 (Linux; Android 13; Kline/1.0) AppleWebKit/537.36 Chrome Mobile Safari/537.36"

    private fun http(url: String): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", UA)
            .header("Referer", "https://quote.eastmoney.com/")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code} for $url")
            return resp.body!!.string()
        }
    }

    /** Resolve "600000" or "000001" style code to Eastmoney secid "1.600000"/"0.000001". */
    fun toSecid(code: String): String {
        val c = code.trim()
        val mkt = when {
            c.startsWith("6") || c.startsWith("9") || c.startsWith("5") -> 1
            else -> 0
        }
        return "$mkt.$c"
    }

    /** Fetch recent daily K-lines (qfq). */
    suspend fun fetchKline(code: String, limit: Int = 160): List<KLine> = withContext(Dispatchers.IO) {
        val secid = toSecid(code)
        val url = "https://push2his.eastmoney.com/api/qt/stock/kline/get?" +
            "secid=$secid&klt=101&fqt=1&lmt=$limit&end=20500101&fields1=f1,f2,f3,f4,f5,f6&" +
            "fields2=f51,f52,f53,f54,f55,f56,f57&ut=fa5fd1943c7b386f172d6893dbfba10b"
        val json = JSONObject(http(url))
        parseKlines(json)
    }

    /** Fetch real-time quote. */
    suspend fun fetchQuote(code: String): Quote = withContext(Dispatchers.IO) {
        val secid = toSecid(code)
        val url = "https://push2.eastmoney.com/api/qt/stock/get?" +
            "secid=$secid&fields=f43,f44,f45,f46,f47,f48,f57,f58,f60,f86,f169,f170&ut=fa5fd1943c7b386f172d6893dbfba10b"
        val json = JSONObject(http(url))
        parseQuote(code, json)
    }

    /** Search stocks by keyword (eastmoney suggest). */
    suspend fun search(keyword: String): List<SearchItem> = withContext(Dispatchers.IO) {
        val url = "https://searchapi.eastmoney.com/api/suggest/get?" +
            "input=${keyword.trim()}&type=14&token=D43BF722C8E33BDC906FB84D85E326E8&count=10"
        val json = JSONObject(http(url))
        val out = mutableListOf<SearchItem>()
        val quark = json.optJSONObject("QuotationCodeTable")
        val arr = quark?.optJSONArray("Data") ?: return@withContext out
        for (i in 0 until arr.length()) {
            val it = arr.getJSONObject(i)
            val code = it.optString("Code", "")
            val name = it.optString("Name", "")
            val mkt = it.optInt("MktNum", it.optInt("MktNum2", 1))
            if (code.isNotEmpty()) out.add(SearchItem(code, name, mkt))
        }
        out
    }

    /**
     * 把用户输入解析成股票代码：纯数字（如 "600000"）直接返回；
     * 否则当作股票名称/关键字，通过东方财富搜索解析为代码（如 "浦发银行" -> "600000"）。
     */
    suspend fun resolveCode(input: String): String = withContext(Dispatchers.IO) {
        val q = input.trim()
        if (q.isEmpty()) return@withContext q
        if (q.all { it.isDigit() }) return@withContext q
        try {
            val list = search(q)
            val hit = list.firstOrNull { it.name == q } ?: list.firstOrNull()
            hit?.code ?: q
        } catch (e: Exception) {
            q
        }
    }
}