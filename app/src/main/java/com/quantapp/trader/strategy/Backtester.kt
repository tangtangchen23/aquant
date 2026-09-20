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
        initialCapital: Double = 100000.0,
        feeRate: Double = 0.0,
        slippagePct: Double = 0.0
    ): BacktestResult {
        // 逐K线撮合：含手续费(双边各一次)与滑点(不开盘价为 收盘价×(1±滑点) 作为成交价)
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
                    val price = bar.close * (1 + slippagePct) // 买入滑点加价
                    qty = (cash / (price * 100)).toInt() * 100
                    if (qty >= 100) {
                        val cost = qty * price
                        val fee = cost * feeRate
                        if (cost + fee <= cash) {
                            cash -= cost + fee
                            holding = true
                            costPrice = (cost) / qty // 不含手续费的成本，便于对账
                            buyDate = bar.date
                        }
                    }
                }
                Action.SELL -> if (holding) {
                    val price = bar.close * (1 - slippagePct) // 卖出滑点减价
                    val gross = qty * price
                    val fee = gross * feeRate
                    cash += gross - fee
                    val pnlPct = (gross - fee - costPrice * qty) / (costPrice * qty) * 100
                    trades.add(BacktestTrade(buyDate, costPrice, bar.date, price, qty,
                        (gross - fee) - costPrice * qty, pnlPct))
                    if ((gross - fee) > costPrice * qty) wins++
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
            val price = last.close * (1 - slippagePct)
            val gross = qty * price
            val fee = gross * feeRate
            val pnlPct = (gross - fee - costPrice * qty) / (costPrice * qty) * 100
            trades.add(BacktestTrade(buyDate, costPrice, last.date, price, qty,
                (gross - fee) - costPrice * qty, pnlPct))
            if ((gross - fee) > costPrice * qty) wins++
            cash += gross - fee
        }
        equityCurve.add(cash)

        // 基准：始终满仓买入持有（含一端滑点与一次手续费），权益 = 初始本金 × 收盘价/首日收盘价
        val baseClose = bars.first().close * (1 + slippagePct)
        val benchmarkCurve = bars.map { initialCapital * (it.close / baseClose) }
        val benchmark = if (bars.size >= 2 && baseClose > 0)
            (bars.last().close - baseClose) / baseClose * 100 else 0.0

        val finalEquity = cash
        val maxDD = computeMaxDrawdown(equityCurve)

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

    private fun computeMaxDrawdown(curve: List<Double>): Double {
        var peak = Double.MIN_VALUE
        var maxDD = 0.0
        for (v in curve) {
            if (v > peak) peak = v
            if (peak > 0) {
                val dd = (peak - v) / peak * 100
                if (dd > maxDD) maxDD = dd
            }
        }
        return maxDD
    }
}