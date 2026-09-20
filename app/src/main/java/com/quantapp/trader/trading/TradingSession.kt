package com.quantapp.trader.trading

import java.util.Calendar
import java.util.TimeZone

/**
 * A股交易时段识别。用于让自动交易引擎只在可交易时段评估，盘外降频或暂停，
 * 避免在夜晚/周末/午休对东财行情做无意义轮询（容易触发限流）。
 *
 * - 交易日：周一至周五；周六日不交易。
 * - 时段：上午 09:30–11:30，下午 13:00–15:00；午休 11:30–13:00 视为盘中休息。
 */
object TradingSession {

    private val ZONE = TimeZone.getTimeZone("Asia/Shanghai")

    /** 是否交易日（忽略法定节假日——节假日判定需接口，按周末近似）。 */
    fun isTradingDay(cal: Calendar = now()): Boolean {
        val dow = cal.get(Calendar.DAY_OF_WEEK)
        return dow != Calendar.SATURDAY && dow != Calendar.SUNDAY
    }

    /** 当前是否处于可交易时段（周一至五的交易时间内，含午休判定）。 */
    fun isInTradingHours(cal: Calendar = now()): Boolean {
        if (!isTradingDay(cal)) return false
        val minute = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        return (minute in 9 * 60 + 30 until 11 * 60 + 30) ||
            (minute in 13 * 60 until 15 * 60)
    }

    /**
     * 引擎策略评估是否应当在本轮执行。
     * 提供宽松窗口（早于开盘/晚于收盘各 30 分钟）用于盘前预热与收盘后结算。
     * 返回 true 表示进入正常评估；false 表示应暂停/跳过。
     */
    fun shouldEvaluate(cal: Calendar = now()): Boolean {
        if (!isTradingDay(cal)) return false
        val minute = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        // 宽松窗口：09:00–15:30
        return minute in 9 * 60 until 15 * 60 + 30
    }

    /** 距下一次开盘还需要多少秒（若已在盘内返回0；盘后返回次日开盘倒计时）。 */
    fun secondsUntilOpen(cal: Calendar = now()): Long {
        val n = cal.clone() as Calendar
        if (isInTradingHours(n)) return 0
        // 尝试今日下午段
        n.set(Calendar.HOUR_OF_DAY, 13)
        n.set(Calendar.MINUTE, 0)
        n.set(Calendar.SECOND, 0)
        n.set(Calendar.MILLISECOND, 0)
        if (n.after(cal)) return (n.timeInMillis - cal.timeInMillis) / 1000
        // 尝试今日上午段（一般在凌晨走到这）
        n.set(Calendar.HOUR_OF_DAY, 9)
        n.set(Calendar.MINUTE, 30)
        if (n.after(cal)) return (n.timeInMillis - cal.timeInMillis) / 1000
        // 顺延到下个交易日
        var c = (n.clone() as Calendar).apply {
            add(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 9)
            set(Calendar.MINUTE, 30)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        while (!isTradingDay(c)) c.add(Calendar.DAY_OF_MONTH, 1)
        return (c.timeInMillis - cal.timeInMillis) / 1000
    }

    fun now(): Calendar = Calendar.getInstance(ZONE)
}