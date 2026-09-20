package com.quantapp.trader.trading

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * 默认更新入口：仓库内置的版本清单（JSON）。
 * 由 Raw 托管、无 API 限流困扰；清单内给出最新版本号与 APK 直链，
 * 供“检查更新”精确比对当前是否最新版本。
 * 每次发布新版本时需同步更新仓库根目录 latest.json。
 */
const val DEFAULT_UPDATE_URL = "https://raw.githubusercontent.com/tangtangchen23/aquant/main/latest.json"

/** 某标的当前运行中的策略 + 最近一次信号（幂等去重需要）。 */
data class ActiveStrategy(
    val symbol: String,
    val name: String,
    val strategyId: String,
    var lastAction: String = "",     // 最近触发的信号："买入"/"卖出"
    var lastReason: String = "",
    var lastProcessedClose: Double = 0.0
) {
    fun toJson() = JSONObject()
        .put("symbol", symbol).put("name", name).put("strategyId", strategyId)
        .put("lastAction", lastAction).put("lastReason", lastReason)
        .put("lastClose", lastProcessedClose).toString()
    companion object {
        fun fromJson(o: JSONObject) = ActiveStrategy(
            o.getString("symbol"), o.optString("name", ""), o.getString("strategyId"),
            o.optString("lastAction", ""), o.optString("lastReason", ""),
            o.optDouble("lastClose", 0.0)
        )
    }
}

/** 到价提醒：symbol 达到 target 时（above=true 向上突破 / false 向下跌破）推送通知。 */
data class PriceAlert(
    val symbol: String,
    val name: String = "",
    val target: Double,
    val above: Boolean = true,
    val enabled: Boolean = true
) {
    fun key() = "$symbol|${java.math.BigDecimal(target).toPlainString()}&above=$above"
    fun toJson() = JSONObject()
        .put("symbol", symbol).put("name", name).put("target", target)
        .put("above", above).put("enabled", enabled).toString()
    companion object {
        fun fromJson(o: JSONObject) = PriceAlert(
            o.getString("symbol"), o.optString("name", ""), o.optDouble("target", 0.0),
            o.optBoolean("above", true), o.optBoolean("enabled", true))
    }
}

/**
 * 应用级状态仓库：模拟盘账户 + 运行中策略 + 全局设置。
 * 依赖 SharedPreferences 做 JSON 持久化。
 */
class AppStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("quant_store", Context.MODE_PRIVATE)

    val paper = PaperAccount(initialPrefsCapital())

    var mode: String
        get() = prefs.getString("mode", "paper") ?: "paper"   // "paper" | "live"
        set(v) { prefs.edit().putString("mode", v).apply() }

    var liveGateway: String
        get() = prefs.getString("live_gateway", "") ?: ""
        set(v) { prefs.edit().putString("live_gateway", v).apply() }

    var pollSeconds: Int
        get() = prefs.getInt("poll_seconds", 15)
        set(v) { prefs.edit().putInt("poll_seconds", v.coerceIn(5, 300)).apply() }

    var positionPct: Double
        get() = (prefs.getFloat("position_pct", 0.8f)).toDouble()
        set(v) { prefs.edit().putFloat("position_pct", v.toFloat()).apply() }

    // ---------- 风控参数 ----------
    /** 单票止损线（跌到持仓成本的该比例即止损），0 表示关闭。 */
    var stopLossPct: Double
        get() = prefs.getFloat("stop_loss_pct", 0f).toDouble()
        set(v) { prefs.edit().putFloat("stop_loss_pct", v.toFloat().coerceIn(0f, 100f)).apply() }

    /** 单票止盈线（涨到持仓成本的该比例即止盈），0 表示关闭。 */
    var takeProfitPct: Double
        get() = prefs.getFloat("tp_pct", 0f).toDouble()
        set(v) { prefs.edit().putFloat("tp_pct", v.toFloat().coerceIn(0f, 1000f)).apply() }

    /** 账户最大回撤熔断（权益从峰值回撤达到该比例后暂停开仓），0 表示关闭。 */
    var maxDrawdownPct: Double
        get() = prefs.getFloat("max_dd_pct", 0f).toDouble()
        set(v) { prefs.edit().putFloat("max_dd_pct", v.toFloat().coerceIn(0f, 100f)).apply() }

    // ---------- 回测参数 ----------
    /** 单边手续费率（买入卖出各收一次），0 表示不计。 */
    var feeRate: Double
        get() = prefs.getFloat("fee_rate", 0f).toDouble()
        set(v) { prefs.edit().putFloat("fee_rate", v.toFloat().coerceIn(0f, 0.1f)).apply() }

    /** 单边滑点比例，0 表示不计。 */
    var slippagePct: Double
        get() = prefs.getFloat("slippage_pct", 0f).toDouble()
        set(v) { prefs.edit().putFloat("slippage_pct", v.toFloat().coerceIn(0f, 0.1f)).apply() }

    var updateUrl: String
        get() = prefs.getString("update_url", DEFAULT_UPDATE_URL) ?: DEFAULT_UPDATE_URL
        set(v) { prefs.edit().putString("update_url", v.trim()).apply() }

    /** 主题模式：0=跟随系统 1=浅色 2=深色。 */
    var themeMode: Int
        get() = prefs.getInt("theme_mode", 0)
        set(v) { prefs.edit().putInt("theme_mode", v.coerceIn(0, 2)).apply() }

    /** 策略自定义参数：{strategyId -> {key -> value}}，供回测与实盘共同使用。 */
    fun strategyConfig(id: String): MutableMap<String, Double> =
        parseConfig(prefs.getString("strat_cfg", "{}") ?: "{}")[id] ?: mutableMapOf()

    fun setStrategyConfig(id: String, config: Map<String, Double>) {
        val all = parseConfig(prefs.getString("strat_cfg", "{}") ?: "{}")
        all[id] = config.toMutableMap()
        val obj = JSONObject()
        for ((k, v) in all) obj.put(k, JSONObject(v))
        prefs.edit().putString("strat_cfg", obj.toString()).apply()
    }

    private fun parseConfig(json: String): MutableMap<String, MutableMap<String, Double>> {
        val out = mutableMapOf<String, MutableMap<String, Double>>()
        try {
            val root = JSONObject(json)
            val it = root.keys()
            while (it.hasNext()) {
                val id = it.next()
                val c = root.getJSONObject(id)
                val m = mutableMapOf<String, Double>()
                val keys = c.keys()
                while (keys.hasNext()) { val k = keys.next(); m[k] = c.optDouble(k, 0.0) }
                out[id] = m
            }
        } catch (e: Exception) { /* ignore */ }
        return out
    }

    private var strategies = loadActive()

    // ---------- 引擎运行/信号日志 ----------
    private var engineLogs = loadLogs()

    /** 追加一条引擎日志（保留最近 300 条）。 */
    fun addLog(msg: String) {
        engineLogs.add(formatLogTime() + "  " + msg)
        if (engineLogs.size > 300) engineLogs.removeAt(0)
        val arr = JSONArray()
        engineLogs.forEach { arr.put(it) }
        prefs.edit().putString("engine_logs", arr.toString()).apply()
    }

    fun engineLogText(): String = engineLogs.joinToString("\n")

    fun clearLogs() {
        engineLogs.clear()
        prefs.edit().putString("engine_logs", "[]").apply()
    }

    private fun loadLogs(): MutableList<String> {
        val out = mutableListOf<String>()
        try {
            val arr = JSONArray(prefs.getString("engine_logs", "[]") ?: "[]")
            for (i in 0 until arr.length()) out.add(arr.getString(i))
        } catch (e: Exception) { /* ignore */ }
        return out
    }

    private fun formatLogTime(): String {
        val f = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
        return f.format(java.util.Date())
    }

    private var alerts = loadAlerts()

    fun alerts(): List<PriceAlert> = alerts

    fun addOrUpdateAlert(a: PriceAlert) {
        alerts = (alerts.filter { it.key() != a.key() } + a).toMutableList()
        persistAlerts()
    }

    fun removeAlert(a: PriceAlert) {
        alerts = alerts.filter { it.key() != a.key() }.toMutableList()
        persistAlerts()
    }

    fun toggleAlert(a: PriceAlert) {
        addOrUpdateAlert(a.copy(enabled = !a.enabled))
    }

    private fun persistAlerts() {
        val arr = JSONArray()
        for (a in alerts) arr.put(JSONObject(a.toJson()))
        prefs.edit().putString("price_alerts", arr.toString()).apply()
    }

    private fun loadAlerts(): MutableList<PriceAlert> {
        val out = mutableListOf<PriceAlert>()
        try {
            val arr = JSONArray(prefs.getString("price_alerts", "[]") ?: "[]")
            for (i in 0 until arr.length()) out.add(PriceAlert.fromJson(arr.getJSONObject(i)))
        } catch (e: Exception) { /* ignore */ }
        return out
    }

    private fun initialPrefsCapital(): Double {
        val c = prefs.getFloat("paper_capital", 100000f).toDouble()
        prefs.edit().putFloat("paper_capital", c.toFloat()).apply()
        return c
    }

    fun initialCapital(): Double = prefs.getFloat("paper_capital", 100000f).toDouble()

    fun setInitialCapital(v: Double) {
        prefs.edit().putFloat("paper_capital", v.toFloat()).apply()
    }

    fun getAccountJson(): String = prefs.getString("account_json", "{}") ?: "{}"

    fun save() {
        prefs.edit().putString("account_json", paper.toJson())
            .putString("active_strategies", saveActive())
            .apply()
    }

    fun load() {
        paper.fromJson(getAccountJson())
        strategies = loadActive()
    }

    fun activeStrategies(): List<ActiveStrategy> = strategies

    fun addStrategy(s: ActiveStrategy) {
        if (strategies.none { it.symbol == s.symbol }) strategies.add(s)
        else strategies = strategies.map { if (it.symbol == s.symbol) s else it }.toMutableList()
        prefs.edit().putString("active_strategies", saveActive()).apply()
    }

    fun removeStrategy(symbol: String) {
        strategies = strategies.filter { it.symbol != symbol }.toMutableList()
        prefs.edit().putString("active_strategies", saveActive()).apply()
    }

    fun updateStrategy(s: ActiveStrategy) {
        strategies = strategies.map { if (it.symbol == s.symbol) s else it }.toMutableList()
        prefs.edit().putString("active_strategies", saveActive()).apply()
    }

    private fun saveActive(): String {
        val arr = JSONArray()
        for (s in strategies) arr.put(JSONObject(s.toJson()))
        return arr.toString()
    }

    private fun loadActive(): MutableList<ActiveStrategy> {
        val out = mutableListOf<ActiveStrategy>()
        try {
            val arr = JSONArray(prefs.getString("active_strategies", "[]") ?: "[]")
            for (i in 0 until arr.length()) out.add(ActiveStrategy.fromJson(arr.getJSONObject(i)))
        } catch (e: Exception) { /* ignore */ }
        return out
    }
}