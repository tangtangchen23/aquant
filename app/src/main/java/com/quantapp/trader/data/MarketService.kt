package com.quantapp.trader.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
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

    // ---- 多源 K 线容灾：东财 / 腾讯 / 新浪 ----
    private enum class KlineSource { EASTMONEY, TENCENT, SINA }

    private val klineHealth = mutableMapOf(
        KlineSource.EASTMONEY to true,
        KlineSource.TENCENT to true,
        KlineSource.SINA to true
    )
    @Volatile private var lastKlineReset = 0L

    /** 定时恢复被标记为不可用的 K 线源，避免一次波动长期禁用某个源。 */
    private fun maybeResetKlineSources() {
        val now = System.currentTimeMillis()
        synchronized(klineHealth) {
            if (now - lastKlineReset > 60_000L) {
                klineHealth.keys.forEach { klineHealth[it] = true }
                lastKlineReset = now
            }
        }
    }

    private fun markKlineSource(s: KlineSource, ok: Boolean) {
        synchronized(klineHealth) { klineHealth[s] = ok }
    }

    private fun isKlineHealthy(s: KlineSource): Boolean = synchronized(klineHealth) { klineHealth[s] == true }

    /** K 线周期：备用源按周期取数；年线由日线聚合、120分线由60分线聚合，共用本表周期。 */
    private enum class KlinePeriod(val emKlt: Int, val tencentName: String, val sinaScale: Int) {
        DAY(101, "day", 240),
        WEEK(102, "week", 1680),
        M60(60, "m60", 60)
    }

    private fun periodForKlt(klt: Int): KlinePeriod? = when (klt) {
        101 -> KlinePeriod.DAY
        102 -> KlinePeriod.WEEK
        60 -> KlinePeriod.M60
        else -> null
    }

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
     * 101=日线 102=周线 60=60分线（120分线由60分聚合）。
     */
    suspend fun fetchKlineBy(code: String, klt: Int = 101, limit: Int = 200): List<KLine> = withContext(Dispatchers.IO) {
        val period = periodForKlt(klt)
        if (period == null) {
            // 未映射的周期（如月线 103）仅走东财原逻辑
            return@withContext fetchEmKline(code, klt, limit)
        }
        maybeResetKlineSources()
        // 健康源优先，避免对已被限流的接口反复无效重试
        val order = listOf(KlineSource.EASTMONEY, KlineSource.TENCENT, KlineSource.SINA)
            .sortedByDescending { isKlineHealthy(it) }
        for (src in order) {
            val bars = try {
                when (src) {
                    KlineSource.EASTMONEY -> fetchEmKline(code, klt, limit)
                    KlineSource.TENCENT -> fetchTencentKlineBy(code, period, limit)
                    KlineSource.SINA -> fetchSinaKline(code, period, limit)
                }
            } catch (e: Exception) {
                markKlineSource(src, false)
                emptyList()
            }
            if (bars.isNotEmpty()) {
                markKlineSource(src, true)
                return@withContext bars
            }
            markKlineSource(src, false) // 空数据（含东财限流返回空 body）也降级
        }
        emptyList()
    }

    /** 东财 K 线（前复权）。限流/网络异常时抛错或返回空，由 fetchKlineBy 降级到备用源。 */
    private fun fetchEmKline(code: String, klt: Int, limit: Int): List<KLine> {
        val secid = toSecid(code)
        val url = "https://push2his.eastmoney.com/api/qt/stock/kline/get?" +
            "secid=$secid&klt=$klt&fqt=1&lmt=$limit&end=20500101&fields1=f1,f2,f3,f4,f5,f6&" +
            "fields2=f51,f52,f53,f54,f55,f56,f57&ut=fa5fd1943c7b386f172d6893dbfba10b"
        val json = JSONObject(http(url))
        return parseKlines(json)
    }

    // 腾讯K线独立节流：与东财限流退避隔离，作为 K 线备用数据源
    private val tencentLock = Any()
    private var lastTencentReq = 0L

    private fun tencentThrottle() {
        synchronized(tencentLock) {
            val now = System.currentTimeMillis()
            if (now < lastTencentReq + 250L) {
                try { Thread.sleep(lastTencentReq + 250L - now) } catch (_: InterruptedException) {}
            }
            lastTencentReq = System.currentTimeMillis()
        }
    }

    /** 腾讯股票日K线（前复权），作为东财K线被限流时的备用源（日线）。 */
    suspend fun fetchTencentKline(code: String, limit: Int = 80): List<KLine> = withContext(Dispatchers.IO) {
        fetchTencentKlineBy(code, KlinePeriod.DAY, limit)
    }

    /**
     * 腾讯股票 K 线（前复权）备用源：日线/周线走 fqkline（qfq 前复权），60分线走 mkline。
     * 返回 [date, open, close, high, low, volume(手)]，与东财解析保持一致的 KLine 结构。
     * 使用独立的 250ms 节流，不受东财 52 退避影响。
     */
    private fun fetchTencentKlineBy(code: String, period: KlinePeriod, limit: Int): List<KLine> {
        val c = code.trim()
        val prefix = if (c.startsWith("6") || c.startsWith("5") || c.startsWith("9")) "sh" else "sz"
        val sym = "$prefix$c"
        // 腾讯 fqkline 对超大 limit 会返回 param error 或截断数据，clamp 到安全上限
        val capped = limit.coerceIn(1, 640)
        val url = if (period == KlinePeriod.M60) {
            "https://web.ifzq.gtimg.cn/appstock/app/kline/mkline?param=$sym,m60,,$capped"
        } else {
            "https://web.ifzq.gtimg.cn/appstock/app/fqkline/get?param=$sym,${period.tencentName},,,$capped,qfq"
        }
        tencentThrottle()
        val req = Request.Builder().url(url)
            .header("User-Agent", UA)
            .header("Referer", "https://gu.qq.com/")
            .build()
        val body = try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code} for $url")
                resp.body!!.bytes()
            }
        } catch (e: Exception) {
            return emptyList()
        }
        val json = try { JSONObject(String(body, Charsets.UTF_8)) } catch (e: Exception) { return emptyList() }
        val data = json.optJSONObject("data") ?: return emptyList()
        val node = data.optJSONObject(sym) ?: return emptyList()
        val arr = when (period) {
            KlinePeriod.DAY -> node.optJSONArray("qfqday") ?: node.optJSONArray("day")
            KlinePeriod.WEEK -> node.optJSONArray("qfqweek") ?: node.optJSONArray("week")
            KlinePeriod.M60 -> node.optJSONArray("m60")
        } ?: return emptyList()
        val out = mutableListOf<KLine>()
        for (i in 0 until arr.length()) {
            val line = arr.optJSONArray(i) ?: continue
            if (line.length() < 6) continue
            val date = line.optString(0)
            val open = line.optDouble(1, 0.0)
            val close = line.optDouble(2, 0.0)
            val high = line.optDouble(3, 0.0)
            val low = line.optDouble(4, 0.0)
            val vol = (line.optDouble(5, 0.0)).toLong() * 100
            out.add(KLine(date, open, close, high, low, vol, 0.0))
        }
        return out
    }

    // 新浪K线独立节流：与东财限流退避隔离，作为 K 线备用数据源
    private val sinaLock = Any()
    private var lastSinaReq = 0L

    /**
     * 新浪财经 K 线（不复权）备用源：
     * getKLineData?scale=240(日)/1680(周)/60(60分)&datalen=N，
     * 返回 [day, open, high, low, close, volume(股)] 数组。
     * 使用独立的 250ms 节流，不受东财 52 退避影响。
     */
    private fun fetchSinaKline(code: String, period: KlinePeriod, limit: Int): List<KLine> {
        val c = code.trim()
        val prefix = if (c.startsWith("6") || c.startsWith("5") || c.startsWith("9")) "sh" else "sz"
        val sym = "$prefix$c"
        val url = "https://money.finance.sina.com.cn/quotes_service/api/json_v2.php/CN_MarketData.getKLineData?" +
            "symbol=$sym&scale=${period.sinaScale}&ma=no&datalen=$limit"
        synchronized(sinaLock) {
            val now = System.currentTimeMillis()
            if (now < lastSinaReq + 250L) {
                try { Thread.sleep(lastSinaReq + 250L - now) } catch (_: InterruptedException) {}
            }
            lastSinaReq = System.currentTimeMillis()
        }
        val req = Request.Builder().url(url)
            .header("User-Agent", UA)
            .header("Referer", "https://finance.sina.com.cn/")
            .build()
        val body = try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code} for $url")
                resp.body!!.bytes()
            }
        } catch (e: Exception) {
            return emptyList()
        }
        val text = String(body, Charsets.UTF_8)
        // 个别情况下接口会返回 "null[...]" 前缀，做一次容错
        val clean = text.trimStart().removePrefix("null")
        val arr = try { JSONArray(clean) } catch (e: Exception) { return emptyList() }
        val out = mutableListOf<KLine>()
        for (i in 0 until arr.length()) {
            val it = arr.optJSONObject(i) ?: continue
            val day = it.optString("day", "")
            val close = it.optDouble("close", 0.0)
            if (day.isEmpty() || close <= 0.0) continue
            out.add(KLine(
                date = day,
                open = it.optDouble("open", 0.0),
                high = it.optDouble("high", 0.0),
                low = it.optDouble("low", 0.0),
                close = close,
                volume = it.optLong("volume", 0L),
                amount = it.optDouble("amount", 0.0)
            ))
        }
        return out
    }

    /** 年线：优先用周K聚合（单次请求数据量小，东财/新浪可一次取20+年），周K不足时降级用日K聚合。 */
    suspend fun fetchYearKline(code: String): List<KLine> = withContext(Dispatchers.IO) {
        val weekly = fetchKlineBy(code, klt = 102, limit = 1200)
        if (weekly.size >= 52) return@withContext aggregateYearBars(weekly)
        val daily = fetchKlineBy(code, klt = 101, limit = 5000)
        if (daily.isEmpty()) return@withContext emptyList()
        aggregateYearBars(daily)
    }

    /** 按自然年聚合 K 线（date 前4位为年份）。 */
    private fun aggregateYearBars(bars: List<KLine>): List<KLine> {
        val grouped = bars.groupBy { it.date.substring(0, 4) } // "2024-xx" 前4位为年
        return grouped.keys.sorted().map { year ->
            val bs = grouped[year]!!
            KLine(
                date = year,
                open = bs.first().open,
                high = bs.maxOf { it.high },
                low = bs.minOf { it.low },
                close = bs.last().close,
                volume = bs.sumOf { it.volume },
                amount = bs.sumOf { it.amount }
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

    /** 分时数据源：东财 / 腾讯 / 新浪，任一被限流时自动切换下一源。 */
    private enum class TrendSource { EASTMONEY, TENCENT, SINA }

    private val trendHealth = mutableMapOf(
        TrendSource.EASTMONEY to true,
        TrendSource.TENCENT to true,
        TrendSource.SINA to true
    )
    @Volatile private var lastTrendReset = 0L

    private fun maybeResetTrendSources() {
        val now = System.currentTimeMillis()
        synchronized(trendHealth) {
            if (now - lastTrendReset > 60_000L) {
                trendHealth.keys.forEach { trendHealth[it] = true }
                lastTrendReset = now
            }
        }
    }

    private fun markTrendSource(s: TrendSource, ok: Boolean) {
        synchronized(trendHealth) { trendHealth[s] = ok }
    }

    private fun isTrendHealthy(s: TrendSource): Boolean = synchronized(trendHealth) { trendHealth[s] == true }

    /**
     * 当日分时行情。依次尝试东财 / 腾讯 / 新浪，东财被限流（52/429）时自动切换到备用源。
     * 返回当日每分钟的价格序列，时间为 "yyyy-MM-dd HH:mm"，用于分时图。
     */
    suspend fun fetchTrend(code: String): List<TrendPoint> = withContext(Dispatchers.IO) {
        maybeResetTrendSources()
        // 健康源优先，避免对已被限流的源反复无效重试
        val order = listOf(TrendSource.EASTMONEY, TrendSource.TENCENT, TrendSource.SINA)
            .sortedByDescending { isTrendHealthy(it) }
        for (src in order) {
            val pts = try {
                when (src) {
                    TrendSource.EASTMONEY -> fetchEmTrend(code)
                    TrendSource.TENCENT -> fetchTencentTrend(code)
                    TrendSource.SINA -> fetchSinaTrend(code)
                }
            } catch (e: Exception) {
                markTrendSource(src, false)
                emptyList()
            }
            if (pts.isNotEmpty()) {
                markTrendSource(src, true)
                return@withContext pts
            }
            markTrendSource(src, false) // 空数据（含东财限流返回空 body）也降级
        }
        emptyList()
    }

    /** 东财分时（trends2）。限流/网络异常时抛错或返回空，由 fetchTrend 降级到备用源。 */
    private fun fetchEmTrend(code: String): List<TrendPoint> {
        val secid = toSecid(code)
        val url = "https://push2.eastmoney.com/api/qt/stock/trends2/get?" +
            "secid=$secid&fields1=f1,f2,f3,f6,f7,f8&fields2=f51,f53,f56,f58&ndays=1&" +
            "iscr=0&iscca=0&ut=fa5fd1943c7b386f172d6893dbfba10b"
        val json = JSONObject(http(url))
        val data = json.optJSONObject("data") ?: return emptyList()
        val arr = data.optJSONArray("trends") ?: return emptyList()
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
        return out
    }

    /** 腾讯分时（分钟图），时间 "HHmm"、价格、成交量（手）。 */
    private fun fetchTencentTrend(code: String): List<TrendPoint> {
        val c = code.trim()
        val prefix = if (c.startsWith("6") || c.startsWith("5") || c.startsWith("9")) "sh" else "sz"
        val sym = "$prefix$c"
        val url = "https://web.ifzq.gtimg.cn/appstock/app/minute/query?code=$sym"
        tencentThrottle()
        val req = Request.Builder().url(url)
            .header("User-Agent", UA)
            .header("Referer", "https://gu.qq.com/")
            .build()
        val body = try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code} for $url")
                resp.body!!.bytes()
            }
        } catch (e: Exception) { return emptyList() }
        val json = try { JSONObject(String(body, Charsets.UTF_8)) } catch (e: Exception) { return emptyList() }
        val data = json.optJSONObject("data") ?: return emptyList()
        val node = data.optJSONObject(sym) ?: return emptyList()
        val nodeData = node.optJSONObject("data") ?: return emptyList()
        val date = nodeData.optString("date", "")
        val arr = nodeData.optJSONArray("data") ?: return emptyList()
        val out = mutableListOf<TrendPoint>()
        for (i in 0 until arr.length()) {
            val parts = arr.optString(i).split(" ")
            if (parts.size < 2) continue
            val price = parts[1].toDoubleOrNull() ?: continue
            if (price <= 0) continue
            val volume = if (parts.size >= 3) parts[2].toDoubleOrNull() ?: 0.0 else 0.0
            // parts[0] 形如 "0930" -> "HH:mm"
            out.add(TrendPoint("$date $parts[0]".trim(), price, volume))
        }
        return out
    }

    /** 新浪分时（getMinKline），时间 "yyyy-MM-dd HH:mm:00"。 */
    private fun fetchSinaTrend(code: String): List<TrendPoint> {
        val c = code.trim()
        val prefix = if (c.startsWith("6") || c.startsWith("5") || c.startsWith("9")) "sh" else "sz"
        val sym = "$prefix$c"
        val url = "https://quotes.sina.cn/cn/api/json_v2.php/CN_MarketDataService.getMinKline?symbol=$sym"
        synchronized(sinaLock) {
            val now = System.currentTimeMillis()
            if (now < lastSinaReq + 250L) {
                try { Thread.sleep(lastSinaReq + 250L - now) } catch (_: InterruptedException) {}
            }
            lastSinaReq = System.currentTimeMillis()
        }
        val req = Request.Builder().url(url)
            .header("User-Agent", UA)
            .header("Referer", "https://finance.sina.com.cn/")
            .build()
        val body = try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code} for $url")
                resp.body!!.bytes()
            }
        } catch (e: Exception) { return emptyList() }
        val text = String(body, Charsets.UTF_8)
        val clean = text.trimStart().removePrefix("null")
        val arr = try { JSONArray(clean) } catch (e: Exception) { return emptyList() }
        val out = mutableListOf<TrendPoint>()
        for (i in 0 until arr.length()) {
            val it = arr.optJSONObject(i) ?: continue
            val day = it.optString("day", "")
            val price = it.optDouble("close", 0.0)
            if (day.isEmpty() || price <= 0) continue
            // "yyyy-MM-dd HH:mm:00" -> "yyyy-MM-dd HH:mm"
            val t = if (day.length >= 16) day.substring(0, 16) else day
            out.add(TrendPoint(t, price, it.optDouble("volume", 0.0)))
        }
        return out
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

    /** 股票池中的一个标的（东财 clist 返回，已按成交额降序）。 */
    data class StockListItem(
        val code: String,
        val name: String,
        val market: Int,
        val amount: Double,
        val price: Double,
        val changePct: Double
    )

    /**
     * 拉取 A 股股票池（东方财富 clist 接口），按成交额(f6)降序返回 Top N。
     * @param fs 市场过滤串，例如 "m:0+t:6,m:1+t:2"（沪深主板）。
     * @param topN 最多返回的数量。
     */
    suspend fun fetchAStockList(fs: String, topN: Int): List<StockListItem> = withContext(Dispatchers.IO) {
        val out = mutableListOf<StockListItem>()
        var page = 1
        var cursor = 0
        while (cursor < topN) {
            val pz = minOf(200, topN - cursor)
            val url = "https://push2.eastmoney.com/api/qt/clist/get?pn=$page&pz=$pz&po=1&np=1&fltt=2&invt=2&fid=f6&fs=$fs&fields=f2,f3,f6,f12,f13,f14&ut=fa5fd1943c7b386f172d6893dbfba10b"
            val json = try { JSONObject(http(url)) } catch (e: Exception) { break }
            val data = json.optJSONObject("data") ?: break
            val diff = data.optJSONArray("diff") ?: break
            for (i in 0 until diff.length()) {
                val it = diff.optJSONObject(i) ?: continue
                val code = it.optString("f12", "")
                if (code.isEmpty()) continue
                out.add(StockListItem(
                    code = code,
                    name = it.optString("f14", ""),
                    market = it.optInt("f13", if (code.startsWith("6")) 1 else 0),
                    amount = it.optDouble("f6", 0.0),
                    price = it.optDouble("f2", 0.0),
                    changePct = it.optDouble("f3", 0.0)
                ))
            }
            cursor += diff.length()
            if (diff.length() < pz) break
            page++
            if (page > 20) break
        }
        out.take(topN)
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