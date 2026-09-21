package com.quantapp.trader.strategy

import com.quantapp.trader.data.KLine

/** 常用技术指标计算。 */
object Indicators {

    fun sma(values: List<Double>, period: Int): List<Double> {
        val out = ArrayList<Double>(values.size)
        var sum = 0.0
        for (i in values.indices) {
            sum += values[i]
            if (i >= period) sum -= values[i - period]
            out.add(if (i >= period - 1) sum / period else Double.NaN)
        }
        return out
    }

    fun ema(values: List<Double>, period: Int): List<Double> {
        val out = ArrayList<Double>(values.size)
        if (values.isEmpty()) return out
        val k = 2.0 / (period + 1)
        var prev = values[0]
        out.add(prev)
        for (i in 1 until values.size) {
            prev = values[i] * k + prev * (1 - k)
            out.add(prev)
        }
        return out
    }

    /** RSI (Wilder smoothing). */
    fun rsi(values: List<Double>, period: Int = 14): List<Double> {
        val closes = values
        val out = ArrayList<Double>(closes.size)
        if (closes.size < period + 1) {
            repeat(closes.size) { out.add(Double.NaN) }
            return out
        }
        var gain = 0.0
        var loss = 0.0
        for (i in 1..period) {
            val diff = closes[i] - closes[i - 1]
            if (diff >= 0) gain += diff else loss -= diff
        }
        var avgGain = gain / period
        var avgLoss = loss / period
        var r = if (avgLoss == 0.0) 100.0 else 100.0 - 100.0 / (1.0 + avgGain / avgLoss)
        out.add(Double.NaN)
        for (i in 0 until period - 1) out.add(Double.NaN)
        out.add(r)
        for (i in period + 1 until closes.size) {
            val diff = closes[i] - closes[i - 1]
            avgGain = (avgGain * (period - 1) + (if (diff > 0) diff else 0.0)) / period
            avgLoss = (avgLoss * (period - 1) + (if (diff < 0) -diff else 0.0)) / period
            r = if (avgLoss == 0.0) 100.0 else 100.0 - 100.0 / (1.0 + avgGain / avgLoss)
            out.add(r)
        }
        return out
    }

    /** MACD returns (dif, dea, macd). */
    fun macd(closes: List<Double>, fast: Int = 12, slow: Int = 26, signal: Int = 9): Triple<List<Double>, List<Double>, List<Double>> {
        val emaFast = ema(closes, fast)
        val emaSlow = ema(closes, slow)
        val dif = closes.indices.map { emaFast[it] - emaSlow[it] }
        val dea = ema(dif, signal)
        val macdBar = closes.indices.map { (dif[it] - dea[it]) * 2 }
        return Triple(dif, dea, macdBar)
    }

    /** ATR (Average True Range)，返回与 K 线数量等长的序列（前 period-1 个为 NaN）。 */
    fun atr(bars: List<KLine>, period: Int = 14): List<Double> {
        val out = ArrayList<Double>(bars.size)
        if (bars.size < period + 1) {
            repeat(bars.size) { out.add(Double.NaN) }
            return out
        }
        val t = mutableListOf<Double>()
        for (i in bars.indices) {
            if (i == 0) { t.add(bars[i].high - bars[i].low); continue }
            val prevClose = bars[i - 1].close
            val tr = maxOf(
                bars[i].high - bars[i].low,
                kotlin.math.abs(bars[i].high - prevClose),
                kotlin.math.abs(bars[i].low - prevClose)
            )
            t.add(tr)
        }
        // 用简单平均得到首个 ATR，再平滑
        var sum = 0.0
        for (i in 0 until period) sum += t[i]
        var value = sum / period
        for (i in bars.indices) {
            if (i < period) { out.add(Double.NaN) }
            else {
                value = (value * (period - 1) + t[i]) / period
                out.add(value)
            }
        }
        return out
    }
}

/** 策略对某标的给出的交易信号。 */
enum class Action {
    BUY, SELL, HOLD
}

data class Signal(
    val action: Action,
    val reason: String,
    val confidence: Double = 1.0
)

/** 策略接口：基于一段K线序列与最新价判断当前是否触发信号。 */
interface Strategy {
    val id: String
    val name: String
    val description: String

    /** [bars] 为历史收盘K线，[lastPrice] 为当前最新价（日内）。 */
    fun evaluate(bars: List<KLine>, lastPrice: Double): Signal

    /** 参数摘要，用于UI显示。 */
    fun paramsSummary(): String
}

/** 双均线金叉/死叉策略。 */
class MaCrossStrategy(val shortPeriod: Int = 5, val longPeriod: Int = 20) : Strategy {
    override val id = "ma"
    override val name = "双均线策略"
    override val description = "短均线上穿长均线买入，下穿卖出。"
    override fun paramsSummary() = "MA($shortPeriod/$longPeriod)"

    override fun evaluate(bars: List<KLine>, lastPrice: Double): Signal {
        val prices = bars.map { it.close } + lastPrice
        val short = Indicators.sma(prices, shortPeriod)
        val long = Indicators.sma(prices, longPeriod)
        val n = prices.size
        if (n < 3) return Signal(Action.HOLD, "数据不足")
        val prevShort = short[n - 2]
        val prevLong = long[n - 2]
        val curShort = short[n - 1]
        val curLong = long[n - 1]
        return when {
            prevShort <= prevLong && curShort > curLong -> Signal(Action.BUY, "金叉：MA$shortPeriod 上穿 MA$longPeriod")
            prevShort >= prevLong && curShort < curLong -> Signal(Action.SELL, "死叉：MA$shortPeriod 下穿 MA$longPeriod")
            curShort > curLong -> Signal(Action.HOLD, "多头排列，持有")
            else -> Signal(Action.HOLD, "空头排列，观望")
        }
    }
}

/** RSI 超买超卖策略。 */
class RsiStrategy(val period: Int = 14, val oversold: Double = 30.0, val overbought: Double = 70.0) : Strategy {
    override val id = "rsi"
    override val name = "RSI 策略"
    override val description = "RSI 低于超卖线买入，高于超买线卖出。"
    override fun paramsSummary() = "RSI($period, $oversold/$overbought)"

    override fun evaluate(bars: List<KLine>, lastPrice: Double): Signal {
        val closes = bars.map { it.close } + lastPrice
        val rsiList = Indicators.rsi(closes, period)
        val r = rsiList.last()
        if (r.isNaN()) return Signal(Action.HOLD, "数据不足")
        return when {
            r < oversold -> Signal(Action.BUY, "RSI $r 超卖(<$oversold)")
            r > overbought -> Signal(Action.SELL, "RSI $r 超买(>$overbought)")
            else -> Signal(Action.HOLD, "RSI $r 区间内观望")
        }
    }
}

/** MACD 金叉/死叉策略。 */
class MacdStrategy(val fast: Int = 12, val slow: Int = 26, val signal: Int = 9) : Strategy {
    override val id = "macd"
    override val name = "MACD 策略"
    override val description = "DIF 上穿 DEA(金叉)买入，下穿(死叉)卖出。"
    override fun paramsSummary() = "MACD($fast,$slow,$signal)"

    override fun evaluate(bars: List<KLine>, lastPrice: Double): Signal {
        val closes = bars.map { it.close } + lastPrice
        val (dif, dea, _) = Indicators.macd(closes, fast, slow, signal)
        val n = dif.size
        if (n < 3) return Signal(Action.HOLD, "数据不足")
        val prev = dif[n - 2] - dea[n - 2]
        val cur = dif[n - 1] - dea[n - 1]
        return when {
            prev <= 0 && cur > 0 -> Signal(Action.BUY, "MACD 金叉")
            prev >= 0 && cur < 0 -> Signal(Action.SELL, "MACD 死叉")
            cur > 0 -> Signal(Action.HOLD, "多头，持有")
            else -> Signal(Action.HOLD, "空头，观望")
        }
    }
}

/** 布林带策略（均值回归）：跌破下轨买入，突破上轨卖出。 */
class BollStrategy(val period: Int = 20, val mult: Double = 2.0) : Strategy {
    override val id = "boll"
    override val name = "布林带策略"
    override val description = "跌破下轨或回穿下轨买入，突破/跌破上轨卖出。"
    override fun paramsSummary() = "BOLL($period,$mult)"

    override fun evaluate(bars: List<KLine>, lastPrice: Double): Signal {
        val closes = bars.map { it.close } + lastPrice
        val n = closes.size
        if (n < period + 1) return Signal(Action.HOLD, "数据不足")
        val mid = Indicators.sma(closes, period)

        // 返回某根K线的 [中轨, 下轨, 上轨]
        fun band(i: Int): DoubleArray {
            val base = mid[i]
            if (base.isNaN()) return doubleArrayOf(Double.NaN, Double.NaN, Double.NaN)
            var s = 0.0
            for (k in i - period + 1..i) { val d = closes[k] - base; s += d * d }
            val std = kotlin.math.sqrt(s / period)
            return doubleArrayOf(base, base - mult * std, base + mult * std)
        }

        val prevB = band(n - 2)
        val curB = band(n - 1)
        if (prevB[0].isNaN() || curB[0].isNaN()) return Signal(Action.HOLD, "数据不足")
        val prev = closes[n - 2]
        val cur = closes[n - 1]
        val lowerP = prevB[1]; val lower = curB[1]
        val upperP = prevB[2]; val upper = curB[2]

        return when {
            prev <= lowerP && cur > lower -> Signal(Action.BUY, "回穿下轨(${"%.2f".format(lower)})")
            cur < lower -> Signal(Action.BUY, "跌破下轨超卖(${"%.2f".format(lower)})")
            prev >= upperP && cur < upper -> Signal(Action.SELL, "跌破上轨(${"%.2f".format(upper)})")
            cur > upper -> Signal(Action.SELL, "突破上轨超买(${"%.2f".format(upper)})")
            else -> Signal(Action.HOLD, "区间运行，观望")
        }
    }
}

fun strategies(): List<Strategy> = listOf(
    MaCrossStrategy(),
    RsiStrategy(),
    MacdStrategy(),
    BollStrategy(),
    DivideAverageTStrategy(),
    BollGridTStrategy(),
    VwapTStrategy()
)

/** 做T策略标识前缀：命中后引擎走“日内多次高抛低吸”专用逻辑，而非日线单次信号。 */
fun isTStrategy(id: String): Boolean = id.startsWith("t_")

/**
 * 通用做T参数。下轨触发的低吸/高抛基于「日内分时均线或60分周期」计算得到的中枢与偏离阈值。
 */
private fun tParamsSummary(prefix: String, dev: Double) = "$prefix·偏离$dev%"

/** 分时/60分均线做T：价格低于中枢×偏离即低吸买入，高于中枢×偏离即高抛卖出。 */
class DivideAverageTStrategy(val devPct: Double = 5.0) : Strategy {
    override val id = "t_ma"
    override val name = "均线做T"
    override val description = "价格相对日内分时/60分均线偏离超阈值时低吸高抛，日内可多次。"
    override fun paramsSummary() = tParamsSummary("均线做T", devPct)

    override fun evaluate(bars: List<KLine>, lastPrice: Double): Signal {
        // 日内中枢算法由引擎基于分时序列计算，此处提供保守的日线信号用于自动建底仓判断
        val closes = bars.map { it.close } + lastPrice
        val mid = Indicators.sma(closes, 20).lastOrNull() ?: Double.NaN
        if (mid.isNaN() || mid <= 0) return Signal(Action.HOLD, "数据不足")
        val dev = devPct / 100.0
        return when {
            lastPrice < mid * (1 - dev) -> Signal(Action.BUY, "跌破日内均线下轨${String.format("%.2f", mid)}")
            lastPrice > mid * (1 + dev) -> Signal(Action.SELL, "突破日内均线上轨${String.format("%.2f", mid)}")
            else -> Signal(Action.HOLD, "均线区间内观望")
        }
    }
}

/** 日内布林带网格做T：跌破下轨分档低吸，突破上轨分档高抛。 */
class BollGridTStrategy(val period: Int = 20, val mult: Double = 2.0, val devPct: Double = 3.0) : Strategy {
    override val id = "t_boll"
    override val name = "布林网格做T"
    override val description = "日内布林下轨分档低吸、上轨分档高抛，沿网格反复收割波动。"
    override fun paramsSummary() = "布林网格($period,$mult)·$devPct%"

    override fun evaluate(bars: List<KLine>, lastPrice: Double): Signal {
        val closes = bars.map { it.close } + lastPrice
        val n = closes.size
        if (n < period + 1) return Signal(Action.HOLD, "数据不足")
        val mid = Indicators.sma(closes, period)
        val lastMid = mid[n - 1]
        if (lastMid.isNaN()) return Signal(Action.HOLD, "数据不足")
        var s = 0.0
        for (k in n - period until n) { val d = closes[k] - lastMid; s += d * d }
        val std = kotlin.math.sqrt(s / period)
        val lower = lastMid - mult * std
        val upper = lastMid + mult * std
        return when {
            lastPrice < lower -> Signal(Action.BUY, "跌破布林下轨(${String.format("%.2f", lower)})分档低吸")
            lastPrice > upper -> Signal(Action.SELL, "突破布林上轨(${String.format("%.2f", upper)})分档高抛")
            else -> Signal(Action.HOLD, "布林区间内观望")
        }
    }
}

/** 分时VWAP/关键位做T：以当日均价(VWAP)与前收盘为锚，回踩低吸、冲高离场高抛。 */
class VwapTStrategy(val devPct: Double = 2.0) : Strategy {
    override val id = "t_vwap"
    override val name = "VWAP做T"
    override val description = "以日内均价线(VWAP)/前收盘为锚，价格低于锚点回踩低吸，高于锚点冲高出货。"
    override fun paramsSummary() = tParamsSummary("VWAP做T", devPct)

    override fun evaluate(bars: List<KLine>, lastPrice: Double): Signal {
        val closes = bars.map { it.close } + lastPrice
        val n = closes.size
        if (n < 2) return Signal(Action.HOLD, "数据不足")
        val mid = Indicators.sma(closes, 20).lastOrNull()
        val anchor = if (mid != null && !mid.isNaN()) mid else closes.takeLast(20).average()
        val dev = devPct / 100.0
        return when {
            lastPrice < anchor * (1 - dev) -> Signal(Action.BUY, "回踩VWAP下沿(${String.format("%.2f", anchor)})低吸")
            lastPrice > anchor * (1 + dev) -> Signal(Action.SELL, "冲高VWAP上沿(${String.format("%.2f", anchor)})高抛")
            else -> Signal(Action.HOLD, "VWAP附近观望")
        }
    }
}

/** 策略可编辑参数定义：key 供持久化/构建使用，label 用于 UI。 */
data class StrategyParam(
    val key: String,
    val label: String,
    val def: String,
    val int: Boolean
)

/** 返回某策略的可编辑参数（含默认值）。 */
fun strategyParams(id: String): List<StrategyParam> = when (id) {
    "ma" -> listOf(
        StrategyParam("short", "短均线周期", "5", true),
        StrategyParam("long", "长均线周期", "20", true)
    )
    "rsi" -> listOf(
        StrategyParam("period", "RSI周期", "14", true),
        StrategyParam("oversold", "超卖线", "30", false),
        StrategyParam("overbought", "超买线", "70", false)
    )
    "macd" -> listOf(
        StrategyParam("fast", "快线EMA", "12", true),
        StrategyParam("slow", "慢线EMA", "26", true),
        StrategyParam("signal", "信号EMA", "9", true)
    )
    "boll" -> listOf(
        StrategyParam("period", "布林周期", "20", true),
        StrategyParam("mult", "标准差倍数", "2", false)
    )
    "t_ma" -> listOf(
        StrategyParam("devPct", "做T偏离阈值%", "5", false),
        StrategyParam("tfreq", "做T频率(分/60)", "0", true)
    )
    "t_boll" -> listOf(
        StrategyParam("period", "布林周期", "20", true),
        StrategyParam("mult", "标准差倍数", "2", false),
        StrategyParam("devPct", "做T偏离阈值%", "3", false),
        StrategyParam("tfreq", "做T频率(分/60)", "0", true)
    )
    "t_vwap" -> listOf(
        StrategyParam("devPct", "做T偏离阈值%", "2", false),
        StrategyParam("tfreq", "做T频率(分/60)", "0", true)
    )
    else -> emptyList()
}

/** 依据参数 map 构建策略实例；缺失项回退默认值。 */
fun buildStrategy(id: String, v: Map<String, Double>): Strategy = when (id) {
    "ma" -> MaCrossStrategy(v["short"]?.toInt() ?: 5, v["long"]?.toInt() ?: 20)
    "rsi" -> RsiStrategy(
        v["period"]?.toInt() ?: 14, v["oversold"] ?: 30.0, v["overbought"] ?: 70.0)
    "macd" -> MacdStrategy(
        v["fast"]?.toInt() ?: 12, v["slow"]?.toInt() ?: 26, v["signal"]?.toInt() ?: 9)
    "boll" -> BollStrategy(
        v["period"]?.toInt() ?: 20, v["mult"] ?: 2.0)
    "t_ma" -> DivideAverageTStrategy(v["devPct"] ?: 5.0)
    "t_boll" -> BollGridTStrategy(
        v["period"]?.toInt() ?: 20, v["mult"] ?: 2.0, v["devPct"] ?: 3.0)
    "t_vwap" -> VwapTStrategy(v["devPct"] ?: 2.0)
    else -> MaCrossStrategy()
}