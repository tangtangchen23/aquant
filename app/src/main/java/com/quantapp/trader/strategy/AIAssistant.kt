package com.quantapp.trader.strategy

import com.quantapp.trader.data.KLine

/**
 * P0 本地智能：全部在手机端用确定性算法完成，不联网、不上云。
 * 1) 标的特征识别 + 策略推荐
 * 2) 参数一键寻优（受控网格搜索 × 出厂纪律偏好）
 * 3) 回测结果白话解读
 */

/** 标的行情画像。 */
data class MarketCharacter(
    val trendScore: Double,      // 0~100 趋势效率
    val isTrending: Boolean,     // 是否趋势盘
    val summary: String          // 一句话定调
)

/** 用 Kaufman 效率比判断趋势盘 vs 震荡盘。 */
fun characterize(bars: List<KLine>): MarketCharacter {
    val closes = bars.map { it.close }
    if (closes.size < 40) return MarketCharacter(50.0, false, "数据不足，暂按均衡判定")
    val window = minOf(60, closes.size - 1)
    val start = closes.size - window
    val netMove = kotlin.math.abs(closes.last() - closes[start])
    var sumMove = 0.0
    for (i in start + 1 until closes.size) sumMove += kotlin.math.abs(closes[i] - closes[i - 1])
    val er = if (sumMove > 0) netMove / sumMove else 0.0
    val trendScore = (er * 100).coerceIn(0.0, 100.0)
    val isTrending = trendScore >= 45.0
    val summary = when {
        trendScore >= 60.0 -> "趋势非常强，方向清晰"
        trendScore >= 45.0 -> "趋势较强，走势有方向"
        trendScore >= 30.0 -> "涨跌互现，趋势中性"
        else -> "波动为主，区间震荡"
    }
    return MarketCharacter(trendScore, isTrending, summary)
}

/** 策略推荐结果。 */
data class StrategyRecommendation(
    val strategyId: String,
    val params: Map<String, Double>,
    val reason: String
)

/** 依标的特点推荐策略与一套默认参数。 */
fun recommendStrategy(bars: List<KLine>): StrategyRecommendation {
    val c = characterize(bars)
    return if (c.isTrending) {
        StrategyRecommendation(
            "macd",
            mapOf("fast" to 12.0, "slow" to 26.0, "signal" to 9.0),
            "该标的最新阶段趋势性明显（效率 ${"%.0f".format(c.trendScore)}%），走跟随趋势的 MACD 更合适；建议叠加上方“移动止盈”保护浮盈。"
        )
    } else {
        StrategyRecommendation(
            "boll",
            mapOf("period" to 20.0, "mult" to 2.0),
            "该标的主要在 ${c.summary}的状态里（趋势效率 ${"%.0f".format(c.trendScore)}%），均值回归的布林带更吃香：回踩下轨再接，突破上轨落袋。"
        )
    }
}

/** 参数寻优结果。 */
data class OptimizeResult(
    val strategyId: String,
    val params: Map<String, Double>,
    val paramsText: String,
    val score: Double,
    val tradeCount: Int,
    val returnPct: Double,
    val maxDrawdown: Double
)

/**
 * 受控网格搜索，用回测数据挑选一组合适参数。
 * [mode] 出厂偏好：conservative(保守) / balanced(均衡，默认) / aggressive(激进)。
 */
fun optimizeParams(
    strategyId: String,
    bars: List<KLine>,
    mode: String = "balanced",
    initialCapital: Double = 100000.0,
    feeRate: Double = 0.0,
    slippagePct: Double = 0.0,
    progress: (done: Int, total: Int) -> Unit = { _, _ -> }
): OptimizeResult? {
    val combos = combinationSpace(strategyId)
    var best: OptimizeResult? = null
    for (i in combos.indices) {
        progress(i + 1, combos.size)
        val cfg = combos[i]
        val r = Backtester.run(buildStrategy(strategyId, cfg), bars, initialCapital, feeRate, slippagePct)
        // 成交太少说明这套参数“空转”，容易过拟合，直接跳过
        if (r.tradeCount < 3) continue
        val score = scoreFor(mode, r)
        if (best == null || score > best.score) {
            best = OptimizeResult(strategyId, cfg, paramText(strategyId, cfg), score,
                r.tradeCount, r.returnPct, r.maxDrawdown)
        }
    }
    return best
}

/** 每种策略的一组候选参数组合。 */
private fun combinationSpace(id: String): List<Map<String, Double>> {
    val out = mutableListOf<Map<String, Double>>()
    when (id) {
        "ma" -> for (s in intArrayOf(3, 5, 8)) for (l in intArrayOf(15, 20, 30, 40))
            if (l > s) out.add(mapOf("short" to s.toDouble(), "long" to l.toDouble()))
        "rsi" -> for (p in intArrayOf(7, 14, 21))
            for (o in intArrayOf(20, 25, 30)) for (b in intArrayOf(70, 75, 80))
                if (o < b) out.add(mapOf("period" to p.toDouble(), "oversold" to o.toDouble(), "overbought" to b.toDouble()))
        "macd" -> for (f in intArrayOf(8, 12)) for (s in intArrayOf(21, 26)) for (g in intArrayOf(5, 9))
            out.add(mapOf("fast" to f.toDouble(), "slow" to s.toDouble(), "signal" to g.toDouble()))
        "boll" -> for (p in intArrayOf(10, 15, 20, 26)) for (m in doubleArrayOf(1.5, 2.0, 2.5, 3.0))
            out.add(mapOf("period" to p.toDouble(), "mult" to m))
    }
    return out
}

/** 按风险偏好给一组回测结果打综合分。 */
private fun scoreFor(mode: String, r: BacktestResult): Double {
    val dd = r.maxDrawdown.coerceAtLeast(0.05)
    val rr = r.returnPct / dd                     // 收益回撤比
    return when (mode) {
        "aggressive" -> r.returnPct * 0.7 + rr * 4.0 + r.winRate * 0.20
        "conservative" -> r.winRate * 0.6 + rr * 5.0 - r.maxDrawdown * 1.0 + r.returnPct * 0.10
        else -> r.returnPct * 0.45 + rr * 5.0 + r.winRate * 0.35 - r.maxDrawdown * 0.30
    }
}

private fun paramText(id: String, p: Map<String, Double>): String = when (id) {
    "ma" -> "MA(${p["short"]?.toInt()}/${p["long"]?.toInt()})"
    "rsi" -> "RSI(${p["period"]?.toInt()}, ${"%.0f".format(p["oversold"] ?: 30)}/${"%.0f".format(p["overbought"] ?: 70)})"
    "macd" -> "MACD(${p["fast"]?.toInt()},${p["slow"]?.toInt()},${p["signal"]?.toInt()})"
    "boll" -> "BOLL(${p["period"]?.toInt()},${"%.1f".format(p["mult"] ?: 2.0)})"
    else -> p.toString()
}

/** 把一串回测统计翻译成读得懂的话（含风险提示）。 */
fun interpretBacktest(r: BacktestResult, resultDate: String = ""): String {
    val sb = StringBuilder()
    val beat = r.returnPct - r.benchmarkReturnPct
    sb.append(
        when {
            beat >= 1.0 -> "总体跑赢买入持有 ${"%.1f".format(beat)} 个百分点，策略在方向上是有价值的；"
            beat >= -1.0 -> "与闭眼买入持有基本打平，策略优势不明显；"
            else -> "暂时跑输了买入持有 ${"%.1f".format(-beat)} 个百分点，说明这套规则在多数时间并不具备优势；"
        }
    )

    if (r.tradeCount == 0) {
        sb.append("不过这段区间里几乎没有触发交易信号，样本太稀，结论仅供粗糙参考。")
        return sb.toString()
    }

    sb.append("区间内共 ${r.tradeCount} 笔，胜率 ${"%.0f".format(r.winRate)}%")
    val avgPnl = r.trades.map { it.pnlPct }.average()
    sb.append("，平均每笔 ${if (avgPnl >= 0) "赚" else "亏"} ${"%.2f".format(kotlin.math.abs(avgPnl))}%。")

    if (r.maxDrawdown >= 15.0) {
        sb.append("最大回撤 ${"%.0f".format(r.maxDrawdown)}% 偏大，属于波动较猛的风格，扛不住回撤建议调“保守”档或拉远止损。")
    } else if (r.maxDrawdown >= 6.0) {
        sb.append("最大回撤 ${"%.0f".format(r.maxDrawdown)}%，属于正常范围，但仍有回撤压力。")
    } else {
        sb.append("最大回撤 ${"%.0f".format(r.maxDrawdown)}% 较小，走势相对平稳。")
    }
    return sb.toString()
}