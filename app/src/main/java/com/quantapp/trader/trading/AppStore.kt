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
    var lastProcessedClose: Double = 0.0,
    /** 已执行信号的K线标识（用于“每根K线只交易一次”避免盘中抖动重复交易）。 */
    var lastBarKey: String = ""
) {
    fun toJson() = JSONObject()
        .put("symbol", symbol).put("name", name).put("strategyId", strategyId)
        .put("lastAction", lastAction).put("lastReason", lastReason)
        .put("lastClose", lastProcessedClose).put("lastBar", lastBarKey).toString()
    companion object {
        fun fromJson(o: JSONObject) = ActiveStrategy(
            o.getString("symbol"), o.optString("name", ""), o.getString("strategyId"),
            o.optString("lastAction", ""), o.optString("lastReason", ""),
            o.optDouble("lastClose", 0.0), o.optString("lastBar", "")
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

    // ---------- 组合层参数 ----------
    /** 单票最大资金占用比例（相对初始资金），0 表示不限。 */
    var maxPositionPct: Double
        get() = prefs.getFloat("max_pos_pct", 0f).toDouble()
        set(v) { prefs.edit().putFloat("max_pos_pct", v.toFloat().coerceIn(0f, 1f)).apply() }

    /** 最大同时持仓数量，0 表示不限。 */
    var maxHoldings: Int
        get() = prefs.getInt("max_holdings", 0)
        set(v) { prefs.edit().putInt("max_holdings", v.coerceIn(0, 100)).apply() }

    /** 单笔最大可承受亏损金额（相对初始资金比，如 0.02 表示2%），0 表示关闭。 */
    var riskPerTradePct: Double
        get() = prefs.getFloat("risk_per_trade_pct", 0f).toDouble()
        set(v) { prefs.edit().putFloat("risk_per_trade_pct", v.toFloat().coerceIn(0f, 1f)).apply() }

    // ---------- 出场纪律：移动止盈 / 保本 / ATR 止损 ----------
    /** 启动移动止盈的盈利阈值(%)：持仓盈利达到该比例后开始跟踪回撤离场，0 关闭。 */
    var trailingActivatePct: Double
        get() = prefs.getFloat("trailing_activate_pct", 0f).toDouble()
        set(v) { prefs.edit().putFloat("trailing_activate_pct", v.toFloat().coerceIn(0f, 100f)).apply() }

    /** 移动止盈回撤止损比例(%)：启动后从持仓最高价回撤该比例即离场。 */
    var trailingStopPct: Double
        get() = prefs.getFloat("trailing_stop_pct", 8f).toDouble()
        set(v) { prefs.edit().putFloat("trailing_stop_pct", v.toFloat().coerceIn(0f, 100f)).apply() }

    /** 保本止损触发阈值(%)：盈利达到该比例后止损线抬至成本价，0 关闭。 */
    var breakEvenPct: Double
        get() = prefs.getFloat("break_even_pct", 0f).toDouble()
        set(v) { prefs.edit().putFloat("break_even_pct", v.toFloat().coerceIn(0f, 100f)).apply() }

    /** ATR 止损开关：1 启用 0 关闭。 */
    var atrStopEnabled: Int
        get() = prefs.getInt("atr_stop_enabled", 0)
        set(v) { prefs.edit().putInt("atr_stop_enabled", v.coerceIn(0, 1)).apply() }

    /** ATR 计算周期。 */
    var atrPeriod: Int
        get() = prefs.getInt("atr_period", 14)
        set(v) { prefs.edit().putInt("atr_period", v.coerceIn(2, 60)).apply() }

    /** ATR 止损倍数（止损距离 = ATR × 该倍数）。 */
    var atrMultiplier: Double
        get() = prefs.getFloat("atr_multiplier", 2f).toDouble()
        set(v) { prefs.edit().putFloat("atr_multiplier", v.toFloat().coerceIn(0.1f, 10f)).apply() }

    // ---------- 分批建仓 / 盈利加仓 ----------
    /** 首仓占目标仓位的比例(0~1)：<1 表示拆分建仓，1 为一次满仓。 */
    var firstBuyPct: Double
        get() = prefs.getFloat("first_buy_pct", 1f).toDouble()
        set(v) { prefs.edit().putFloat("first_buy_pct", v.toFloat().coerceIn(0.1f, 1f)).apply() }

    /** 每档加仓占目标仓位的比例(0~1)，0 表示关闭加仓。 */
    var addPositionPct: Double
        get() = prefs.getFloat("add_position_pct", 0f).toDouble()
        set(v) { prefs.edit().putFloat("add_position_pct", v.toFloat().coerceIn(0f, 1f)).apply() }

    /** 盈利加仓档位(%)：持仓盈利每达到一个该档位加仓一次。 */
    var addThresholdPct: Double
        get() = prefs.getFloat("add_threshold_pct", 0f).toDouble()
        set(v) { prefs.edit().putFloat("add_threshold_pct", v.toFloat().coerceIn(0f, 100f)).apply() }

    /** 最大加仓次数，0 表示无数量限制（需配合 addPositionPct>0 生效）。 */
    var maxAdds: Int
        get() = prefs.getInt("max_adds", 0)
        set(v) { prefs.edit().putInt("max_adds", v.coerceIn(0, 20)).apply() }

    // ---------- 信号确认 / 趋势过滤 ----------
    /** 仅当大盘或个股趋势向上时才允许开多（0 关闭；1 用个股均线过滤；2 用上证趋势过滤）。 */
    var requireTrend: Int
        get() = prefs.getInt("require_trend", 0)
        set(v) { prefs.edit().putInt("require_trend", v.coerceIn(0, 2)).apply() }

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

    // ---------- AI 大模型 ----------
    var aiProvider: String
        get() = prefs.getString("ai_provider", "deepseek") ?: "deepseek"   // "deepseek" | "qwen"
        set(v) { prefs.edit().putString("ai_provider", v).apply() }

    var aiKey: String
        get() = prefs.getString("ai_key", "") ?: ""
        set(v) { prefs.edit().putString("ai_key", v.trim()).apply() }

    var aiModel: String
        get() = prefs.getString("ai_model", "deepseek-chat") ?: "deepseek-chat"
        set(v) { prefs.edit().putString("ai_model", v.trim()).apply() }

    /** 配置了有效 Key 即视为开启。 */
    val aiEnabled: Boolean
        get() = prefs.getString("ai_key", "")?.trim()?.isNotEmpty() == true

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