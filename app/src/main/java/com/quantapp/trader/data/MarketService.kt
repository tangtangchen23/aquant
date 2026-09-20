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

    // 简易令牌桶：控制东财接口调用频率，避免触发 52 限流。
    /** 两次请求的最小间隔（毫秒）。 */
    @Volatile private var minIntervalMs = 200L
    /** 连续失败后退避，最长退避 60s。 */
    @Volatile private var backoffMs = 0L
    private val lock = Any()

    private fun throttle() {
        synchronized(lock) {
            if (backoffMs > 0) {
                sleepQuietly(backoffMs)
                backoffMs = 0
            }
            // 距上次请求的最小间隔
            val now = System.currentTimeMillis()
            if (now < lastReq + minIntervalMs) {
                sleepQuietly(lastReq + minIntervalMs - now)
            }
            lastReq = System.currentTimeMillis()
        }
    }

    private var lastReq = 0L

    private fun sleepQuietly(ms: Long) {
        try { Thread.sleep(ms.coerceAtMost(60_000)) } catch (_: InterruptedException) { }
    }

    private fun onSuccess() {
        synchronized(lock) { minIntervalMs = 150L } // 命中后恢复较快节奏
    }

    private fun onThrottle() {
        synchronized(lock) {
            backoffMs = (backoffMs + 3000L).coerceAtMost(60_000L) // 指数退避
        }
    }

    private val UA =
        "Mozilla/5.0 (Linux; Android 13; Kline/1.0) AppleWebKit/537.36 Chrome Mobile Safari/537.36"

    private fun http(url: String): String {
        throttle()
        val req = Request.Builder().url(url)
            .header("User-Agent", UA)
            .header("Referer", "https://quote.eastmoney.com/")
            .build()
        try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    if (resp.code == 429 || resp.code == 52) onThrottle()
                    throw RuntimeException("HTTP ${resp.code} for $url")
                }
                onSuccess()
                return resp.body!!.string()
            }
        } catch (e: Exception) {
            // 网络/限流错误也退避
            if (e is RuntimeException && e.message?.contains("HTTP 52") == true) onThrottle()
            throw e
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
    suspend fun fetchKline(code: String, limit: Int = 160): List<KLine> = fetchKlineBy(code, klt = 101, limit = limit)

    /**
     * Fetch K-lines of a given period by Eastmoney klt code:
     * 101=日线 102=周线 103=月线.
     */
    suspend fun fetchKlineBy(code: String, klt: Int = 101, limit: Int = 200): List<KLine> = withContext(Dispatchers.IO) {
        val secid = toSecid(code)
        val url = "https://push2his.eastmoney.com/api/qt/stock/kline/get?" +
            "secid=$secid&klt=$klt&fqt=1&lmt=$limit&end=20500101&fields1=f1,f2,f3,f4,f5,f6&" +
            "fields2=f51,f52,f53,f54,f55,f56,f57&ut=fa5fd1943c7b386f172d6893dbfba10b"
        val json = JSONObject(http(url))
        parseKlines(json)
    }

    /** 年线：拉取足够长的日线，按自然年聚合成年度K线。 */
    suspend fun fetchYearKline(code: String): List<KLine> = withContext(Dispatchers.IO) {
        val daily = fetchKlineBy(code, klt = 101, limit = 5000)
        if (daily.isEmpty()) return@withContext emptyList()
        val grouped = daily.groupBy { it.date.substring(0, 4) } // "2024-xx" 前4位为年
        grouped.keys.sorted().map { year ->
            val bars = grouped[year]!!
            KLine(
                date = year,
                open = bars.first().open,
                high = bars.maxOf { it.high },
                low = bars.minOf { it.low },
                close = bars.last().close,
                volume = bars.sumOf { it.volume },
                amount = bars.sumOf { it.amount }
            )
        }
    }

    /**
     * 120分时线：拉取60分K线后，把相邻两根合成一根120分钟K线；
     * 末根若为单根则单独保留。open=第一根open, close=最后一根close, 高低取合并区间的极值。
     */
    suspend fun fetch120Kline(code: String): List<KLine> = withContext(Dispatchers.IO) {
        val m60 = fetchKlineBy(code, klt = 60, limit = 400)
        if (m60.isEmpty()) return@withContext emptyList()
        val out = mutableListOf<KLine>()
        var i = 0
        while (i < m60.size) {
            val a = m60[i]
            val b = m60.getOrNull(i + 1)
            if (b != null) {
                out.add(KLine(
                    date = a.date,
                    open = a.open,
                    high = maxOf(a.high, b.high),
                    low = minOf(a.low, b.low),
                    close = b.close,
                    volume = a.volume + b.volume,
                    amount = a.amount + b.amount
                ))
                i += 2
            } else {
                out.add(a)
                i += 1
            }
        }
        out
    }

    /** 分时数据点：时间 + 现价 + 成交量。 */
    data class TrendPoint(val time: String, val price: Double, val volume: Double = 0.0)

    /**
     * 当日分时行情（trends2 接口）。返回当日每分钟的价格序列，用于分时图。
     */
    suspend fun fetchTrend(code: String): List<TrendPoint> = withContext(Dispatchers.IO) {
        val secid = toSecid(code)
        val url = "https://push2.eastmoney.com/api/qt/stock/trends2/get?" +
            "secid=$secid&fields1=f1,f2,f3,f6,f7,f8&fields2=f51,f53,f56,f58&ndays=1&" +
            "iscr=0&iscca=0&ut=fa5fd1943c7b386f172d6893dbfba10b"
        val json = JSONObject(http(url))
        val data = json.optJSONObject("data") ?: return@withContext emptyList()
        val arr = data.optJSONArray("trends") ?: return@withContext emptyList()
        val out = mutableListOf<TrendPoint>()
        for (i in 0 until arr.length()) {
            val parts = arr.getString(i).split(",")
            if (parts.size >= 2) {
                val price = parts[1].toDoubleOrNull() ?: continue
                if (price > 0) {
                    val volume = if (parts.size >= 3) parts[2].toDoubleOrNull() ?: 0.0 else 0.0
                    out.add(TrendPoint(parts[0], price, volume))
                }
            }
        }
        out
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