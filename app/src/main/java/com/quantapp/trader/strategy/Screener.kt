package com.quantapp.trader.strategy

import com.quantapp.trader.data.MarketService
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** 命中策略买入信号的一只股票。 */
data class ScreenHit(
    val code: String,
    val name: String,
    val signal: Signal,
    val price: Double,
    val changePct: Double
)

/**
 * 选股器：对给定股票池逐只拉取近期 K 线、运行目标策略，
 * 收集当前处于“买入信号”的标的，用于在全市场快速筛选出符合量化策略的股票。
 */
object Screener {

    /**
     * 对 [pool] 中的股票逐个执行 [strategy]，返回所有触发买入信号的标的。
     * @param barsPerStock 每只股票拉取的日 K 数量（足够计算 MA/MACD/RSI/BOLL）。
     * @param onProgress 每扫描一只回调一次（done/total/hits），在主线程调用。
     */
    suspend fun hunt(
        strategy: Strategy,
        pool: List<MarketService.StockListItem>,
        barsPerStock: Int = 80,
        onProgress: (done: Int, total: Int, hits: Int) -> Unit = { _, _, _ -> }
    ): List<ScreenHit> {
        val context = currentCoroutineContext()
        val hits = mutableListOf<ScreenHit>()
        val total = pool.size
        var done = 0
        for (item in pool) {
            context.ensureActive() // 支持取消
            val bars = try {
                MarketService.fetchKline(item.code, barsPerStock)
            } catch (e: Exception) {
                emptyList()
            }
            if (bars.size >= 30) {
                val last = bars.last().close
                val sig = try {
                    strategy.evaluate(bars, last)
                } catch (e: Exception) {
                    Signal(Action.HOLD, "")
                }
                if (sig.action == Action.BUY) {
                    val prev = bars[bars.size - 2].close
                    val chg = if (prev > 0) (last / prev - 1) * 100 else 0.0
                    hits.add(ScreenHit(item.code, item.name, sig, last, chg))
                }
            }
            done++
            onProgress(done, total, hits.size)
        }
        // 当前涨幅居前的排前面，便于突出动量
        return hits.sortedByDescending { it.changePct }
    }
}