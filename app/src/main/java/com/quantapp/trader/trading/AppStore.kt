package com.quantapp.trader.trading

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** 默认更新/下载链接：GitHub Releases 永久直链（任何人可访问、可直接下载 APK）。 */
const val DEFAULT_UPDATE_URL = "https://github.com/tangtangchen23/aquant/releases/latest/download/aquant-v1.3.0-release.apk"

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

    var updateUrl: String
        get() = prefs.getString("update_url", DEFAULT_UPDATE_URL) ?: DEFAULT_UPDATE_URL
        set(v) { prefs.edit().putString("update_url", v.trim()).apply() }

    private var strategies = loadActive()

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