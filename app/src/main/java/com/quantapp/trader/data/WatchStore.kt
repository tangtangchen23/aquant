package com.quantapp.trader.data

import com.quantapp.trader.trading.App
import org.json.JSONArray
import org.json.JSONObject

/**
 * 自选股持久化：与行情页共用 SharedPreferences("watch") 的 list/names，
 * 供选股结果页、K线图页快捷加入自选，保持各页数据一致。
 */
object WatchStore {

    private fun prefs() = App.context.getSharedPreferences("watch", 0)

    /** 自选股代码列表（保持添加顺序）。 */
    fun symbols(): List<String> {
        return try {
            val arr = JSONArray(prefs().getString("list", "[]") ?: "[]")
            (0 until arr.length()).map { arr.getString(it) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun contains(code: String): Boolean = symbols().contains(code)

    /** 加入自选（已存在则忽略），返回是否新增。名称非空时写入名称缓存。 */
    fun add(code: String, name: String = ""): Boolean {
        val list = symbols().toMutableList()
        if (list.contains(code)) return false
        list.add(code)
        val arr = JSONArray()
        list.forEach { arr.put(it) }
        prefs().edit().putString("list", arr.toString()).apply()
        if (name.isNotEmpty()) {
            val obj = JSONObject(prefs().getString("names", "{}") ?: "{}")
            obj.put(code, name)
            prefs().edit().putString("names", obj.toString()).apply()
        }
        return true
    }
}
