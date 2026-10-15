package com.quantapp.trader

import com.quantapp.trader.data.KLine
import com.quantapp.trader.strategy.Backtester
import com.quantapp.trader.strategy.buildStrategy
import com.quantapp.trader.trading.PaperAccount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 交易内核回归测试：覆盖此前修复的 T+1 卖出约束、参数钳制与回测口径。
 * 均为纯 JVM 逻辑，不依赖 Android 运行时。
 */
class TradingCoreTest {

    // ------------------------- 模拟盘 T+1 -------------------------

    @Test
    fun sameDaySellBlockedByT1() {
        val acc = PaperAccount(100000.0)
        val buy = acc.buy("600000", "浦发银行", 10.0, 10000.0)
        assertNotNull(buy)
        // 当日买入（intradayQty）当日不可卖
        assertNull(acc.sell("600000", "浦发银行", 10.5))
    }

    @Test
    fun sellAllowedAfterRollIntraday() {
        val acc = PaperAccount(100000.0)
        acc.buy("600000", "浦发银行", 10.0, 10000.0)
        assertNull(acc.sell("600000", "浦发银行", 10.5)) // 当日不可卖
        acc.settle("600000")                             // 模拟跨日：当日仓转为隔日可卖底仓
        val sell = acc.sell("600000", "浦发银行", 10.5)
        assertNotNull(sell)
        assertNull(acc.positions["600000"])              // 全部卖出后持仓清空
    }

    @Test
    fun sellCappedAtSellableQty() {
        val acc = PaperAccount(100000.0)
        acc.buy("600000", "浦发银行", 10.0, 10000.0) // 当日买入 1000 股
        acc.settle("600000")                          // 转为可卖底仓 1000 股
        acc.buy("600000", "浦发银行", 10.0, 5000.0)  // 当日再加仓 500 股
        val pos = acc.positions["600000"]!!
        assertEquals(1500, pos.qty)
        assertEquals(1000, pos.sellableQty())
        val sell = acc.sell("600000", "浦发银行", 11.0) // 请求全卖
        assertNotNull(sell)
        assertEquals(1000, sell!!.qty)                  // 只能卖出可卖底仓部分
        assertEquals(500, acc.positions["600000"]!!.qty)
    }

    /** 模拟跨日：把当日买入的 intradayQty 转为隔日可卖底仓。 */
    private fun PaperAccount.settle(symbol: String) {
        val p = positions[symbol] ?: return
        positions[symbol] = p.copy(intradayDay = "2000-01-01") // 假装是昨日建仓
        rollIntraday()
    }

    @Test
    fun buyRejectedWhenCashInsufficient() {
        val acc = PaperAccount(1000.0)
        assertNull(acc.buy("600000", "浦发银行", 100.0, 10000.0))
        assertEquals(1000.0, acc.cash, 1e-6)
    }

    /** 加仓/低吸时须保留做T网格锚点（tLastBuy/tLastSell），否则日内网格失效。 */
    @Test
    fun addOnBuyPreservesTGridAnchors() {
        val acc = PaperAccount(100000.0)
        acc.buy("600000", "浦发银行", 10.0, 10000.0, intraday = true)
        val p0 = acc.positions["600000"]!!
        acc.positions["600000"] = p0.copy(tLastBuy = 9.5, tLastSell = 10.5)
        acc.settle("600000") // 转为可卖底仓后再加仓
        acc.buy("600000", "浦发银行", 10.2, 5000.0, intraday = true)
        val p1 = acc.positions["600000"]!!
        assertEquals(9.5, p1.tLastBuy, 1e-9)
        assertEquals(10.5, p1.tLastSell, 1e-9)
    }

    /** 缺少实时价时 equity 应回退成本价，避免单只行情失败导致权益被剔除而误判回撤。 */
    @Test
    fun equityFallsBackToCostPriceWhenQuoteMissing() {
        val acc = PaperAccount(100000.0)
        acc.buy("600000", "浦发银行", 10.0, 10000.0)
        assertEquals(100000.0, acc.equity(emptyMap()), 1e-6)
    }

    // ------------------------- 回测 -------------------------

    private fun bars(closes: List<Double>): List<KLine> =
        closes.mapIndexed { i, c ->
            KLine("2024-01-${(i % 28) + 1}", c, c, c, c, 0L, 0.0)
        }

    @Test
    fun backtestMaxDrawdownInRange() {
        val closes = (0 until 120).map { 10.0 + (it % 7) - 3 }
        val r = Backtester.run(
            buildStrategy("ma", mapOf("short" to 5.0, "long" to 20.0)),
            bars(closes), feeRate = 0.0003)
        assertTrue(r.maxDrawdown >= 0.0)
        assertTrue(r.maxDrawdown <= 100.0)
    }

    @Test
    fun feesAndSlippageNeverIncreaseEquity() {
        val closes = (0 until 120).map { 10.0 + Math.sin(it / 5.0) * 2 }
        val noCost = Backtester.run(
            buildStrategy("ma", mapOf("short" to 5.0, "long" to 10.0)), bars(closes))
        val withCost = Backtester.run(
            buildStrategy("ma", mapOf("short" to 5.0, "long" to 10.0)), bars(closes),
            feeRate = 0.003, slippagePct = 0.002)
        assertTrue(withCost.finalEquity <= noCost.finalEquity + 1e-6)
    }

    // ------------------------- 参数钳制 -------------------------

    @Test
    fun buildStrategyClampsInvalidPeriodsWithoutCrash() {
        // 负周期/极端值不应抛异常，也不应在回测中越界
        val closes = (0 until 80).map { 10.0 + (it % 5) }
        val s = buildStrategy("ma", mapOf("short" to -5.0, "long" to 0.0))
        val r = Backtester.run(s, bars(closes))
        assertTrue(r.returnPct.isFinite())
    }
}
