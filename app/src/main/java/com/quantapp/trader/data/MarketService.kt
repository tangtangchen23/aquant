package com.quantapp.trader.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.nio.charset.Charset
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

    // ---- 多源实时行情：东财 / 新浪 / 腾讯 ----
    private enum class QuoteSource { EASTMONEY, SINA, TENCENT }

    private val sourceHealth = mutableMapOf(
        QuoteSource.EASTMONEY to true,
        QuoteSource.SINA to true,
        QuoteSource.TENCENT to true
    )
    @Volatile private var lastSourceReset = 0L

    /** 定时恢复被标记为不可用的源，避免一次波动长期禁用某个源。 */
    private fun maybeResetSources() {
        val now = System.currentTimeMillis()
        synchronized(sourceHealth) {
            if (now - lastSourceReset > 60_000L) {
                sourceHealth.keys.forEach { sourceHealth[it] = true }
                lastSourceReset = now
            }
        }
    }

    private fun markSource(s: QuoteSource, ok: Boolean) {
        synchronized(sourceHealth) { sourceHealth[s] = ok }
    }

    private fun isHealthy(s: QuoteSource): Boolean = synchronized(sourceHealth) { sourceHealth[s] == true }

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

    private fun http(url: String): String = httpBytes(url, "https://quote.eastmoney.com/").toString(Charsets.UTF_8)

    /** 通用请求：可指定 Referer，返回字节，由调用方决定解码（新浪/腾讯为 GBK）。 */
    private fun httpBytes(url: String, referer: String): ByteArray {
        throttle()
        val req = Request.Builder().url(url)
            .header("User-Agent", UA)
            .header("Referer", referer)
            .build()
        try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    if (resp.code == 429 || resp.code == 52) onThrottle()
                    throw RuntimeException("HTTP ${resp.code} for $url")
                }
                onSuccess()
                return resp.body!!.bytes()
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

    /**
     * 获取实时行情：依次尝试东财 / 新浪 / 腾讯，任一带权随机选源，
     * 失败后自动切换到下一个可用的源。东财被限流（52/429）时自动降级到新浪/腾讯。
     */
    suspend fun fetchQuote(code: String): Quote = withContext(Dispatchers.IO) {
        val secid = toSecid(code)
        // 尝试顺序：优先可用源，东财被标记为健康时就先试东财。
        val attempts = if (isHealthy(QuoteSource.EASTMONEY)) {
            listOf(QuoteSource.EASTMONEY, QuoteSource.SINA, QuoteSource.TENCENT)
        } else {
            listOf(QuoteSource.SINA, QuoteSource.TENCENT, QuoteSource.EASTMONEY)
        }
        var lastError: Exception? = null
        for (i in attempts.indices) {
            val src = attempts[i]
            try {
                val quote = when (src) {
                    QuoteSource.EASTMONEY -> fetchEastmoneyQuote(secid, code)
                    QuoteSource.SINA -> fetchSinaQuote(code)
                    QuoteSource.TENCENT -> fetchTencentQuote(code)
                }
                markSource(src, true) // 成功恢复健康
                return@withContext quote
            } catch (e: Exception) {
                lastError = e
                markSource(src, false)
                // 东财暂时不可用，交由 pickSource 逻辑；重掷后让后续尝试继续
            }
        }
        throw lastError ?: RuntimeException("所有行情源均不可用")
    }

    private fun fetchEastmoneyQuote(secid: String, code: String): Quote {
        val url = "https://push2.eastmoney.com/api/qt/stock/get?" +
            "secid=$secid&fields=f43,f44,f45,f46,f47,f48,f57,f58,f60,f86,f169,f170&ut=fa5fd1943c7b386f172d6893dbfba10b"
        return parseQuote(code, JSONObject(http(url)))
    }

    /**
     * 新浪财经实时行情（GBK 编码）。
     * 字段：0名称 1今开 2昨收 3现价 4最高 5最低 6买一 7卖一 8成交量(股) 9成交额 30日期 31时间
     */
    private fun fetchSinaQuote(code: String): Quote {
        val mktPrefix = if (code.startsWith("6") || code.startsWith("5") || code.startsWith("9")) "sh" else "sz"
        val url = "https://hq.sinajs.cn/list=$mktPrefix$code"
        val body = httpBytes(url, "https://finance.sina.com.cn/")
        val text = String(body, Charset.forName("GBK"))
        if (!text.contains("=\"") ) throw RuntimeException("sina bad response for $code")
        val parts = text.substringAfter("=\"").substringBefore("\";").split(",")
        if (parts.size < 32) throw RuntimeException("sina bad fields for $code")
        val price = parts[3].toDoubleOrNull() ?: throw RuntimeException("sina no price $code")
        val prevClose = parts[2].toDoubleOrNull() ?: 0.0
        return Quote(
            symbol = code,
            name = parts.getOrElse(0) { "" },
            price = price,
            open = parts.getOrElse(1) { "0" }.toDoubleOrNull() ?: 0.0,
            high = parts.getOrElse(4) { "0" }.toDoubleOrNull() ?: 0.0,
            low = parts.getOrElse(5) { "0" }.toDoubleOrNull() ?: 0.0,
            prevClose = prevClose,
            changePct = if (prevClose > 0) (price - prevClose) / prevClose * 100 else 0.0,
            volume = (parts.getOrElse(8) { "0" }.toLongOrNull() ?: 0L) / 100, // 股 → 手
            time = (parts.getOrElse(30) { "" } + " " + parts.getOrElse(31) { "" }).trim()
        )
    }

    /**
     * 腾讯股票实时行情（GBK 编码）。
     * 字段：1名称 2代码 3现价 4昨收 5今开 6成交量(手) 30时间 31日期 33最高 34最低
     */
    private fun fetchTencentQuote(code: String): Quote {
        val mktPrefix = if (code.startsWith("6") || code.startsWith("5") || code.startsWith("9")) "sh" else "sz"
        val url = "https://qt.gtimg.cn/q=$mktPrefix$code"
        val body = httpBytes(url, "https://gu.qq.com/")
        val text = String(body, Charset.forName("GBK"))
        if (!text.contains("=\"")) throw RuntimeException("gtimg bad response for $code")
        val parts = text.substringAfter("=\"").substringBefore("\";").split("~")
        if (parts.size < 35) throw RuntimeException("gtimg bad fields for $code")
        val price = parts[3].toDoubleOrNull() ?: throw RuntimeException("gtimg no price $code")
        val prevClose = parts[4].toDoubleOrNull() ?: 0.0
        return Quote(
            symbol = code,
            name = parts.getOrElse(1) { "" },
            price = price,
            open = parts.getOrElse(5) { "0" }.toDoubleOrNull() ?: 0.0,
            high = parts.getOrElse(33) { "0" }.toDoubleOrNull() ?: 0.0,
            low = parts.getOrElse(34) { "0" }.toDoubleOrNull() ?: 0.0,
            prevClose = prevClose,
            changePct = if (prevClose > 0) (price - prevClose) / prevClose * 100 else 0.0,
            volume = parts.getOrElse(6) { "0" }.toLongOrNull() ?: 0L, // 手
            time = formatTencentTime(parts.getOrElse(30) { "" })
        )
    }

    /** 腾讯返回的字段30形如 "yyyyMMddHHmmss"，转为可读格式；否则原样返回。 */
    private fun formatTencentTime(raw: String): String {
        if (raw.length == 14 && raw.all { it.isDigit() }) {
            return raw.substring(0, 4) + "-" + raw.substring(4, 6) + "-" + raw.substring(6, 8) +
                " " + raw.substring(8, 10) + ":" + raw.substring(10, 12) + ":" + raw.substring(12, 14)
        }
        return raw
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
     * 用东财搜索接口解析股票名称（不依赖 push2 实时行情接口，更稳定）。
     * 传入股票代码返回名称，失败返回空串。
     */
    suspend fun fetchName(code: String): String = withContext(Dispatchers.IO) {
        try {
            search(code).firstOrNull { it.code == code }?.name ?: ""
        } catch (e: Exception) { "" }
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