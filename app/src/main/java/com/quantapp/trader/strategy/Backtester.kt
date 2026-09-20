package com.quantapp.trader.strategy

import com.quantapp.trader.data.KLine

/** 回测结果统计。 */
data class BacktestResult(
    val initialCapital: Double,
    val finalEquity: Double,
    val returnPct: Double,
    val benchmarkReturnPct: Double,
    val tradeCount: Int,
    val winRate: Double,
    val maxDrawdown: Double,
    val trades: List<BacktestTrade>,
    /** 逐K线的策略权益曲线（与 bars 一一对应）。 */
    val equityCurve: List<Double>,
    /** 逐K线的买入持有基准曲线（与 bars 一一对应）。 */
    val benchmarkCurve: List<Double>
)

data class BacktestTrade(
    val buyDate: String,
    val buyPrice: Double,
    val sellDate: String,
    val sellPrice: Double,
    val qty: Int,
    val pnl: Double,
    val pnlPct: Double
)

/**
 * 简单逐K线回测：在每个K线收益价评估策略，按收盘价撮合（全仓买卖）。
 */
object Backtester {

    fun run(
        strategy: Strategy,
        bars: List<KLine>,
        initialCapital: Double = 100000.0
    ): BacktestResult {
        // 使用不含最后一根的序列回测所有权信号，最后一根仅作最终估值。
        val history = bars.dropLast(1)
        var cash = initialCapital
        var holding = false
        var qty = 0
        var costPrice = 0.0
        var buyDate = ""
        val trades = mutableListOf<BacktestTrade>()
        var wins = 0
        val equityCurve = ArrayList<Double>(bars.size)

        for (i in history.indices) {
            val bar = history[i]
            val prev = history.subList(0, i)
            val sig = strategy.evaluate(prev, bar.close)
            when (sig.action) {
                Action.BUY -> if (!holding) {
                    qty = (cash / (bar.close * 100)).toInt() * 100
                    if (qty >= 100) {
                        cash -= qty * bar.close
                        holding = true
                        costPrice = bar.close
                        buyDate = bar.date
                    }
                }
                Action.SELL -> if (holding) {
                    cash += qty * bar.close
                    val pnlPct = (bar.close - costPrice) / costPrice * 100
                    trades.add(BacktestTrade(buyDate, costPrice, bar.date, bar.close, qty,
                        (bar.close - costPrice) * qty, pnlPct))
                    if (bar.close > costPrice) wins++
                    qty = 0
                    holding = false
                }
                Action.HOLD -> {}
            }
            // 记录逐K线权益（未持仓时 qty==0，equity==现金）
            equityCurve.add(if (holding) cash + qty * bar.close else cash)
        }

        // 未平仓部分按最后一根K线市价平仓估值
        if (holding) {
            val last = bars.last()
            val pnlPct = (last.close - costPrice) / costPrice * 100
            trades.add(BacktestTrade(buyDate, costPrice, last.date, last.close, qty,
                (last.close - costPrice) * qty, pnlPct))
            if (last.close > costPrice) wins++
            cash += qty * last.close
        }
        equityCurve.add(cash)

        // 基准：始终满仓买入持有，权益 = 初始本金 × 收盘价/首日收盘价
        val baseClose = bars.first().close
        val benchmarkCurve = bars.map { initialCapital * (it.close / baseClose) }

        val finalEquity = cash
        val benchmark = if (bars.size >= 2)
            (bars.last().close - bars.first().close) / bars.first().close * 100 else 0.0

        // 基于日K的粗略最大回撤（用最终仓位敞口近似权益曲线）
        var peakE = initialCapital
        var maxDD = 0.0
        var curCash = initialCapital
        var curQty = 0
        for (b in bars) {
            if (curQty > 0) {
                val eq = curCash + curQty * b.close
                if (eq > peakE) peakE = eq
                val dd = (peakE - eq) / peakE * 100
                if (dd > maxDD) maxDD = dd
            }
        }

        val winRate = if (trades.isEmpty()) 0.0 else wins.toDouble() / trades.size * 100
        return BacktestResult(
            initialCapital = initialCapital,
            finalEquity = finalEquity,
            returnPct = (finalEquity - initialCapital) / initialCapital * 100,
            benchmarkReturnPct = benchmark,
            tradeCount = trades.size,
            winRate = winRate,
            maxDrawdown = maxDD,
            trades = trades,
            equityCurve = equityCurve,
            benchmarkCurve = benchmarkCurve
        )
    }
}