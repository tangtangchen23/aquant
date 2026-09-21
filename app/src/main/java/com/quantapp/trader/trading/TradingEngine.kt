package com.quantapp.trader.trading

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import com.quantapp.trader.data.KLine
import com.quantapp.trader.data.MarketService
import com.quantapp.trader.strategy.Action
import com.quantapp.trader.strategy.Indicators
import com.quantapp.trader.strategy.Strategy
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 自动化交易引擎：只在 A 股交易时段内按周期轮询活跃标的行情，运行策略，
 * 模拟盘直接撮合；实盘(信号)模式把信号推送至配置的实盘网关（可对接 QMT/EasyTrader）。
 *
 * 稳定性设计：
 * - 交易时段识别（TradingSession）：盘外与节假日不评估，避免无效请求与限流。
 * - 每轮每个标的只拉取一次行情数据（tickBundle），估值与评估共用。
 * - 信号去抖：趋势过滤 + 每根K线只交易一次，避免盘中价格抖动造成的反复开平仓。
 */
object TradingEngine {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var running = AtomicBoolean(false)
    @Volatile var onTicker: ((List<ActiveStrategy>, Double, Double, Int) -> Unit)? = null
    @Volatile var onTrade: ((String) -> Unit)? = null // 资金/持仓变化回调
    private var job: Job? = null

    // 风控状态：权益峰值 + 回撤熔断标记（仅模拟盘开仓受影响）
    @Volatile private var peakEquity = 0.0
    @Volatile private var haltBuying = false

    /** 实盘网关最近一次连接状态：null=未知/未配置  true=成功  false=失败。 */
    @Volatile var gatewayStatus: Boolean? = null
    @Volatile var gatewayLastError: String = ""
    @Volatile var gatewayPingCount: Int = 0

    // 趋势过滤缓存（上证指数日线），避免每个标的都重复拉大盘
    @Volatile private var shTrendOk: Boolean = true
    @Volatile private var shTrendCheckedAt: Long = 0

    fun strategyOf(id: String): Strategy = com.quantapp.trader.strategy.buildStrategy(id, App.appStore.strategyConfig(id))

    fun start() {
        if (running.get()) return
        running.set(true)
        val active = App.appStore.activeStrategies()
        if (active.isEmpty()) {
            App.appStore.addLog("自动交易引擎启动")
        } else {
            val list = active.joinToString(" ") { "${it.name.ifEmpty { it.symbol }}(${it.symbol})" }
            App.appStore.addLog("自动交易引擎启动 · 运行中 ${active.size} 支：$list")
        }
        job = scope.launch {
            while (isActive) {
                try {
                    tick()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                delay(loopDelayMs())
            }
        }
    }

    /** 盘内正常轮询；盘外拉长间隔以省资源。 */
    private suspend fun loopDelayMs(): Long {
        if (TradingSession.isInTradingHours()) return App.appStore.pollSeconds * 1000L
        if (TradingSession.isTradingDay()) {
            // 盘中休息（午休）或盘前后：放慢到 60s，不退出
            return 60_000L
        }
        // 非交易日：每隔一段时间醒一次检查是否进入可评估窗口
        return 5 * 60_000L
    }

    fun stop() {
        if (!running.get()) return
        running.set(false)
        job?.cancel()
        job = null
        App.appStore.addLog("自动交易引擎停止")
    }

    val isRunning: Boolean get() = running.get()

    /** 每轮数据：只拉取一次，估值与策略评估共用。 */
    private class TickData(
        val price: Double,
        val bars: List<KLine>,
        val lastBarKey: String,
        /** 做T用：日内价格序列（分时或60分，由 tfreq 决定），非做T标的下为 null。 */
        val intraday: List<Double>? = null,
        /** 做T用：日内成交量序列（与 intraday 等长，VWAP 需要）。 */
        val intradayVol: List<Double>? = null
    )

    private suspend fun tick() {
        val store = App.appStore
        val active = store.activeStrategies()
        if (active.isEmpty()) return

        // 盘外：不要做无意义的行情请求（只保留极低频的唤醒探测）
        if (!TradingSession.isInTradingHours()) {
            // 仍在盘前后（9:00-9:30 或 15:00-15:30）可做一次收盘结算/盘前暖场，其余直接跳过
            if (!TradingSession.shouldEvaluate()) return
        }

        // 跨交易日：把隔日隔日的当日买入转为可卖底仓
        store.paper.rollIntraday()

        // 1) 统一拉取所有活跃标的的行情 + K线（做T标的额外拉分时/60分日内序列）
        val bundle = mutableMapOf<String, TickData>()
        for (a in active) {
            try {
                val quote = MarketService.fetchQuote(a.symbol)
                val bars = MarketService.fetchKline(a.symbol, limit = 120)
                if (bars.isNotEmpty()) {
                    val key = bars.last().date
                    val isT = com.quantapp.trader.strategy.isTStrategy(a.strategyId)
                    var intra: List<Double>? = null
                    var intraVol: List<Double>? = null
                    if (isT) {
                        try {
                            val cfg = store.strategyConfig(a.strategyId)
                            val tfreq = cfg["tfreq"]?.toInt() ?: 0
                            if (tfreq == 1) {
                                // 60分周期做T：用分时序列做中枢更贴近日内波动
                                val m = MarketService.fetchKlineBy(a.symbol, klt = 60, limit = 40)
                                intra = m.map { it.close }
                                intraVol = m.map { it.volume.toDouble() }
                            } else {
                                // 分时做T：默认 240 分钟数据（或当日分钟）
                                val t = MarketService.fetchTrend(a.symbol)
                                intra = t.map { it.price }
                                intraVol = t.map { it.volume }
                            }
                        } catch (e: Exception) { /* 日内数据失败则仅用日线 */ }
                    }
                    bundle[a.symbol] = TickData(quote.price, bars, key, intra, intraVol)
                }
            } catch (e: Exception) { /* 静默，等待下轮重试 */ }
        }

        // 2) 风控：回撤熔断 + 单票移动止盈/保本/ATR止损/止盈 + 单笔最大亏损兜底
        applyRiskControls(bundle.mapValues { it.value.price }, bundle.mapValues { it.value.bars })

        // 3) 盈利加仓纪律（独立于策略信号，基于盈利档位）
        applyAdditions(bundle)

        // 4) 评估每个标的并执行信号
        for (a in active.toList()) {
            val d = bundle[a.symbol] ?: continue
            try {
                evaluateSymbol(a, d)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        store.save()
        val longPrice = bundle.values.firstOrNull()?.price ?: 0.0
        onTicker?.invoke(store.activeStrategies(), store.paper.equity(bundle.mapValues { it.value.price }),
            store.paper.cash, active.size)
    }

    /** 出场纪律：账户回撤熔断 + 单票移动止盈/保本止损/ATR止损/固定止损止盈 + 单笔最大亏损兜底。 */
    private fun applyRiskControls(prices: Map<String, Double>, barsBySymbol: Map<String, List<KLine>>) {
        val store = App.appStore
        val stopLoss = store.stopLossPct / 100.0
        val takeProfit = store.takeProfitPct / 100.0
        val maxDrawdown = store.maxDrawdownPct / 100.0
        val trailingActivate = store.trailingActivatePct / 100.0
        val trailingStop = store.trailingStopPct / 100.0
        val breakEven = store.breakEvenPct / 100.0
        val risk = store.riskPerTradePct
        val initial = store.initialCapital()

        // 先处理账户回撤熔断
        val equity = store.paper.equity(prices)
        if (equity > peakEquity) peakEquity = equity
        if (maxDrawdown > 0) {
            val dd = if (peakEquity > 0) (peakEquity - equity) / peakEquity else 0.0
            if (dd >= maxDrawdown && store.paper.positions.isNotEmpty()) {
                if (!haltBuying) {
                    haltBuying = true
                    store.addLog("风控：回撤已达 ${String.format("%.1f", dd * 100)}%，暂停自动开仓")
                }
            } else if (haltBuying && dd < maxDrawdown) {
                haltBuying = false
                store.addLog("风控：回撤恢复到 ${String.format("%.1f", dd * 100)}%，恢复自动开仓")
            }
        }

        // 单票离场判断 + 单笔最大亏损兜底
        for ((symbol, pos) in store.paper.positions.toList()) {
            val price = prices[symbol] ?: continue
            if (pos.costPrice <= 0) continue
            val pnlPct = (price - pos.costPrice) / pos.costPrice

            // 1) 更新跟踪最高价
            if (price > pos.roundTripHigh) {
                pos.roundTripHigh = price
                store.paper.positions[symbol] = pos
            }

            // 2) 计算离场价：取各类止损线中的最高者
            var stopPrice = Double.NEGATIVE_INFINITY
            var stopWhy = ""
            if (stopLoss > 0) {
                stopPrice = pos.costPrice * (1 - stopLoss)
                stopWhy = "止损"
            }
            // ATR 自适应止损：成本价下方 ATR×mult 处，适配不同波动率
            if (store.atrStopEnabled == 1) {
                val atrVal = barsBySymbol[symbol]?.let { Indicators.atr(it, store.atrPeriod).lastOrNull() }
                    ?.takeIf { !it.isNaN() && it > 0 }
                if (atrVal != null) {
                    val atrStop = pos.costPrice - atrVal * store.atrMultiplier
                    if (atrStop > stopPrice) { stopPrice = atrStop; stopWhy = "ATR止损" }
                }
            }
            // 保本止损：盈利达阈值后止损线抬至成本价，锁定免受亏损
            if (breakEven > 0 && pnlPct >= breakEven && pos.costPrice > stopPrice) {
                stopPrice = pos.costPrice; stopWhy = "保本止损"
            }
            // 移动止盈：盈利达阈值后跟踪最高价回撤，锁住浮盈
            if (trailingActivate > 0 && pnlPct >= trailingActivate && pos.roundTripHigh > 0) {
                val trail = pos.roundTripHigh * (1 - trailingStop)
                if (trail > stopPrice) { stopPrice = trail; stopWhy = "移动止盈" }
            }

            // 单笔最大亏损兜底（与该笔占用初始资金比）
            val lossRatio = (pos.costPrice - price) * pos.qty / initial
            val hitStop = price <= stopPrice
            val hitRiskCap = risk > 0 && lossRatio >= risk
            val hitTP = takeProfit > 0 && pnlPct >= takeProfit

            if (hitStop || hitRiskCap || hitTP) {
                val name = pos.name
                val why = when {
                    hitTP -> "止盈(盈利 ${String.format("%.1f", pnlPct * 100)}%)"
                    hitRiskCap -> "单笔亏损上限(亏损 ${String.format("%.2f", pnlPct * 100)}%)"
                    hitStop && stopWhy.isNotEmpty() -> "$stopWhy(现价 ${String.format("%.2f", price)} 触发)"
                    else -> "止损"
                }
                store.paper.sell(symbol, name, price)?.let {
                    store.addLog("$why：$name($symbol) @ ${String.format("%.2f", price)} 已平仓")
                    onTrade?.invoke("$why $name @ ${String.format("%.2f", price)}")
                }
            }
        }
    }

    /** 盈利加仓纪律：持仓盈利达到档位后按比例加仓，独立于策略信号。 */
    private suspend fun applyAdditions(bundle: Map<String, TickData>) {
        val store = App.appStore
        val addPct = store.addPositionPct
        val threshold = store.addThresholdPct / 100.0
        if (addPct <= 0 || threshold <= 0) return

        for ((symbol, d) in bundle) {
            val pos = store.paper.positions[symbol] ?: continue
            val price = d.price
            if (pos.costPrice <= 0) continue
            val pnl = (price - pos.costPrice) / pos.costPrice
            // 触发档位：新仓未设档位则按首次阈值；已有则按上次+阈值
            val trigger = if (pos.nextAddPct > 0) pos.nextAddPct / 100.0 else threshold
            if (pnl < trigger) continue
            if (store.maxAdds > 0 && pos.addCount >= store.maxAdds) continue
            if (pos.qty * price >= store.initialCapital() * (store.maxPositionPct.takeIf { it > 0 } ?: 1.0)) continue

            val name = if (pos.name.isNotEmpty()) pos.name else resolveName(symbol)
            val target = positionTarget(store) * addPct
            if (store.mode == "paper") {
                store.paper.buy(symbol, name, price, target)?.let {
                    // 下一档 = 当前盈利 + 阈值
                    val updated = store.paper.positions[symbol]!!.copy(
                        nextAddPct = (pnl + threshold) * 100.0,
                        addCount = store.paper.positions[symbol]!!.addCount + 1)
                    store.paper.positions[symbol] = updated
                    store.addLog("盈利加仓：$name($symbol) 盈利 ${String.format("%.1f", pnl * 100)}% @ ${fmt(price)}")
                    onTrade?.invoke("盈利加仓 $name @ ${fmt(price)}")
                }
            } else {
                pushLiveSignal(symbol, name, "ADD", price, "盈利加仓 ${String.format("%.1f", pnl * 100)}%")
                store.addLog("实盘加仓信号 $name($symbol) @ ${fmt(price)}")
            }
        }
    }

    private suspend fun evaluateSymbol(a: ActiveStrategy, d: TickData) {
        val store = App.appStore
        val bars = d.bars
        val lastPrice = d.price
        val lastClose = bars.last().close

        // 做T策略：走日内多次高抛低吸专用路径
        if (com.quantapp.trader.strategy.isTStrategy(a.strategyId)) {
            executeTrading(a, d)
            return
        }

        val strategy = strategyOf(a.strategyId)
        val name = if (a.name.isNotEmpty()) a.name else resolveName(a.symbol)

        // 趋势过滤：允许开多前检查大盘/个股趋势
        val trendOk = if (store.requireTrend > 0) confirmTrend(store, bars) else true

        val sig = strategy.evaluate(bars, lastPrice)

        when (sig.action) {
            Action.BUY -> {
                // 趋势不过滤禁止开多
                if (store.requireTrend > 0 && !trendOk) {
                    a.lastReason = "趋势过滤：暂不买入（${sig.reason}）"
                    a.lastProcessedClose = lastClose; store.updateStrategy(a)
                    return
                }
                // 每根K线只允许买入一次，避免盘中同一信号反复触发
                if (a.lastBarKey == d.lastBarKey && a.lastAction == "买入") return
                val pos = store.paper.positions[a.symbol]
                if (haltBuying && pos == null) {
                    a.lastProcessedClose = lastClose; store.updateStrategy(a); return
                }
                if (pos == null && canOpen(store, a.symbol)) {
                    // 分批建仓：首仓按比例投入，剩余留待盈利加仓
                    val target = positionTarget(store) * store.firstBuyPct
                    executeBuy(a, name, lastPrice, d.lastBarKey, target, sig.reason)
                    // 启用盈利加仓时写入首档阈值，否则新建仓默认不触发加仓
                    if (store.addPositionPct > 0 && store.addThresholdPct > 0) {
                        val p0 = store.paper.positions[a.symbol]
                        if (p0 != null && p0.nextAddPct <= 0)
                            store.paper.positions[a.symbol] = p0.copy(nextAddPct = store.addThresholdPct)
                    }
                } else {
                    a.lastProcessedClose = lastClose; store.updateStrategy(a)
                }
            }
            Action.SELL -> {
                val pos = store.paper.positions[a.symbol]
                if (pos != null || a.lastAction == "买入" && store.mode == "live") {
                    if (store.mode == "paper" && pos != null) {
                        store.paper.sell(a.symbol, name, lastPrice)?.let {
                            a.lastAction = "卖出"; a.lastReason = sig.reason
                            a.lastProcessedClose = lastClose; a.lastBarKey = d.lastBarKey
                            store.updateStrategy(a); onTrade?.invoke("模拟卖出 $name @ ${fmt(lastPrice)}")
                            store.addLog("卖出信号 ${strategyLabel(a.strategyId)} | $name(${a.symbol}) @ ${fmt(lastPrice)} | ${sig.reason}")
                        }
                    } else if (store.mode == "live") {
                        a.lastAction = "卖出"; a.lastReason = sig.reason
                        a.lastProcessedClose = lastClose; a.lastBarKey = d.lastBarKey
                        store.updateStrategy(a)
                        pushLiveSignal(a.symbol, name, "SELL", lastPrice, sig.reason)
                        store.addLog("实盘卖出信号 ${strategyLabel(a.strategyId)} | $name(${a.symbol}) @ ${fmt(lastPrice)} | ${sig.reason}")
                    }
                }
            }
            else -> {
                // HOLD：仅在K线变化时推进 lastProcessedClose，避免无谓写入
                if (a.lastBarKey != d.lastBarKey) { a.lastProcessedClose = lastClose; store.updateStrategy(a) }
            }
        }
    }

    /** 组合层开仓约束：仓位上限占用、最大持仓数。仅控制“是否允许新建仓”。 */
    private fun canOpen(store: AppStore, symbol: String): Boolean {
        if (store.paper.positions.containsKey(symbol)) return true // 加仓场景（当前不加，仅判断新仓）
        val maxHoldings = store.maxHoldings
        if (maxHoldings > 0 && store.paper.positions.size >= maxHoldings) {
            store.addLog("组合风控：已达最大持仓数 $maxHoldings，暂不新建 ${symbol}")
            return false
        }
        val cap = store.maxPositionPct
        if (cap > 0) {
            val initial = store.initialCapital()
            // 统计已占用比例（按市价权重近似，用当前持仓成本做占用）
            val used = store.paper.cash
            val totalTarget = positionTarget(store)
            if (used < totalTarget * (1 - cap)) return false // 不够放置单票导致明显超限，直接拒绝
        }
        // 现金不足由 buy() 自行判定
        return store.paper.cash > 0
    }

    /** 单票目标仓位金额：按全局 positionPct 确定投入资金。 */
    private fun positionTarget(store: AppStore): Double {
        val base = store.paper.cash * store.positionPct
        val cap = store.maxPositionPct
        return if (cap > 0) base.coerceAtMost(store.initialCapital() * cap) else base
    }

    /**
     * 做T主逻辑：日内多次高抛低吸。
     * - 数据：d.intraday 日内价格序列（分时或60分，全局 tFrequency 选择）。
     * - 中枢：按策略类型计算分时/60分均线（或自身布林/VWAP 近似），生成上下轨。
     * - 方向：价格跌破下轨→低吸买入；突破上轨→高抛卖出；回到中枢→观望。
     * - T+1：买入当日记入 intradayQty，高抛只卖可卖底仓（sellableQty）。
     * - 网格锚价：tLastBuy / tLastSell 使每次触发都要求价格比上次更极端一档，实现日内多档而不抖动。
     */
    private suspend fun executeTrading(a: ActiveStrategy, d: TickData) {
        val store = App.appStore
        val bars = d.bars
        val price = d.price
        val name = if (a.name.isNotEmpty()) a.name else resolveName(a.symbol)

        // 计算日内中枢与上下轨
        val band = computeTIntraband(a.strategyId, d)
        if (band == null) {
            a.lastReason = "做T：日内数据不足，等待下轮"; a.lastProcessedClose = bars.last().close
            store.updateStrategy(a)
            return
        }
        val (pivot, lower, upper) = band

        val devPct = store.strategyConfig(a.strategyId)["devPct"]
            ?: if (a.strategyId == "t_boll") 3.0 else if (a.strategyId == "t_vwap") 2.0 else 5.0
        val dev = devPct / 100.0
        val bandQtyTarget = positionTarget(store) * store.tBandPct

        val pos = store.paper.positions[a.symbol]

        // A) 无持仓 → 自动建底仓：仅当价格下探到下轨下方才介入（避免追高）
        if (pos == null || pos.qty <= 0) {
            if (price < lower) {
                val target = positionTarget(store) * store.tBasePct
                doTBuy(a, name, price, target, "做T建仓：跌破下轨${fmt(lower)}")
            } else {
                a.lastReason = "做T：等待跌破下轨${fmt(lower)}建仓（现价${fmt(price)}）"
                a.lastProcessedClose = bars.last().close; store.updateStrategy(a)
            }
            return
        }

        // B) 有底仓 → 高抛 / 低吸
        val sellable = pos.sellableQty()
        // 高抛：突破上轨，且比上次卖出价高一档；只卖可卖的隔日底仓
        if (price >= upper && sellable >= 100 &&
            (pos.tLastSell <= 0 || price >= pos.tLastSell * (1 + dev))) {
            val qty = ((bandQtyTarget / (price * 100)).toInt().coerceAtLeast(1) * 100)
                .coerceAtMost(((sellable / 100).toInt() * 100).coerceAtLeast(0))
            if (qty >= 100) {
                doTSell(a, name, price, qty, "做T高抛：突破上轨${fmt(upper)}")
            } else {
                a.lastReason = "做T：上轨${fmt(upper)}可卖底仓不足"; a.lastProcessedClose = bars.last().close
                store.updateStrategy(a)
            }
            return
        }
        // 低吸：跌破下轨，且比上次买入价低一档；用现金买（计入当日不可卖）
        if (price <= lower &&
            (pos.tLastBuy <= 0 || price <= pos.tLastBuy * (1 - dev)) &&
            store.paper.cash >= price * 100) {
            val target = bandQtyTarget
            doTBuy(a, name, price, target, "做T低吸：跌破下轨${fmt(lower)}")
            return
        }

        // C) 区间内观望
        a.lastReason = "做T：中枢${fmt(pivot)}附近观望（下轨${fmt(lower)}上轨${fmt(upper)}）"
        a.lastProcessedClose = bars.last().close; store.updateStrategy(a)
    }

    /** 计算做T中枢与上下轨：[pivot, lower, upper]；返回空表示数据不足。 */
    private fun computeTIntraband(id: String, d: TickData): Triple<Double, Double, Double>? {
        val store = App.appStore
        val series = d.intraday ?: return null
        if (series.size < 3) return null
        val last = d.price
        val closes = series + last
        return when (id) {
            "t_vwap" -> {
                val vol = d.intradayVol
                // VWAP：成交额/成交量 加权，用日内价格做近似 ±2%
                val mv = if (vol != null && vol.isNotEmpty() && series.isNotEmpty()) {
                    var sum = 0.0; var w = 0.0
                    for (i in series.indices) { sum += series[i] * vol[i]; w += vol[i] }
                    if (w > 0) sum / w else series.average()
                } else series.average()
                val dev = (store.strategyConfig("t_vwap")["devPct"] ?: 2.0) / 100.0
                Triple(mv, mv * (1 - dev), mv * (1 + dev))
            }
            "t_boll" -> {
                val period = (store.strategyConfig("t_boll")["period"]?.toInt() ?: 20).coerceAtLeast(3)
                val mult = store.strategyConfig("t_boll")["mult"] ?: 2.0
                val mid = Indicators.sma(closes, period).lastOrNull() ?: return null
                if (mid.isNaN()) return null
                val start = (closes.size - period).coerceAtLeast(0)
                var s = 0.0
                for (k in start until closes.size) { val dd = closes[k] - mid; s += dd * dd }
                val std = kotlin.math.sqrt(s / period)
                Triple(mid, mid - mult * std, mid + mult * std)
            }
            else -> { // t_ma
                val period = (store.strategyConfig("t_ma")["devPct"] ?: 5.0) / 100.0
                val mid = Indicators.sma(closes, 20).lastOrNull() ?: return null
                if (mid.isNaN()) return null
                Triple(mid, mid * (1 - period), mid * (1 + period))
            }
        }
    }

    private fun doTBuy(a: ActiveStrategy, name: String, price: Double, target: Double, reason: String) {
        val store = App.appStore
        if (store.mode == "paper") {
            store.paper.buy(a.symbol, name, price, target, intraday = true)?.let {
                val cur = store.paper.positions[a.symbol]
                if (cur != null) store.paper.positions[a.symbol] = cur.copy(tLastBuy = price)
                a.lastAction = "低吸"; a.lastReason = reason
                a.lastProcessedClose = price
                store.updateStrategy(a); onTrade?.invoke("模拟低吸 $name @ ${fmt(price)}")
                store.addLog("$reason | $name(${a.symbol}) @ ${fmt(price)}")
            }
        } else {
            a.lastAction = "低吸"; a.lastReason = reason
            a.lastProcessedClose = price; store.updateStrategy(a)
            pushLiveSignal(a.symbol, name, "BUY", price, reason)
            store.addLog("实盘低吸信号 $name(${a.symbol}) @ ${fmt(price)} | $reason")
        }
    }

    private fun doTSell(a: ActiveStrategy, name: String, price: Double, qty: Int, reason: String) {
        val store = App.appStore
        if (store.mode == "paper") {
            store.paper.sell(a.symbol, name, price, qty)?.let {
                val cur = store.paper.positions[a.symbol]
                if (cur != null) store.paper.positions[a.symbol] = cur.copy(tLastSell = price)
                a.lastAction = "高抛"; a.lastReason = reason
                a.lastProcessedClose = price
                store.updateStrategy(a); onTrade?.invoke("模拟高抛 $name @ ${fmt(price)}")
                store.addLog("$reason | $name(${a.symbol}) @ ${fmt(price)}")
            }
        } else {
            a.lastAction = "高抛"; a.lastReason = reason
            a.lastProcessedClose = price; store.updateStrategy(a)
            pushLiveSignal(a.symbol, name, "SELL", price, reason)
            store.addLog("实盘高抛信号 $name(${a.symbol}) @ ${fmt(price)} | $reason")
        }
    }

    private fun executeBuy(a: ActiveStrategy, name: String, price: Double, barKey: String, target: Double, reason: String) {
        val store = App.appStore
        if (store.mode == "paper") {
            store.paper.buy(a.symbol, name, price, target)?.let {
                a.lastAction = "买入"; a.lastReason = reason
                a.lastProcessedClose = price; a.lastBarKey = barKey
                store.updateStrategy(a); onTrade?.invoke("模拟买入 $name @ ${fmt(price)}")
                store.addLog("买入信号 ${strategyLabel(a.strategyId)} | $name(${a.symbol}) @ ${fmt(price)} | $reason")
            }
        } else {
            a.lastAction = "买入"; a.lastReason = reason
            a.lastProcessedClose = price; a.lastBarKey = barKey
            store.updateStrategy(a)
            pushLiveSignal(a.symbol, name, "BUY", price, reason)
            store.addLog("实盘买入信号 ${strategyLabel(a.strategyId)} | $name(${a.symbol}) @ ${fmt(price)} | $reason")
        }
    }

    /** 趋势过滤：1=个股价格高于其自身 MA40 才允许开多；2=进一步要求上证指数趋势向上。 */
    private fun confirmTrend(store: AppStore, bars: List<KLine>): Boolean {
        val mode = store.requireTrend
        if (mode == 0) return true
        val closes = bars.map { it.close }
        if (closes.size >= 42) {
            val ma = Indicators.sma(closes, 40)
            val lastMa = ma.lastOrNull()
            if (lastMa != null && !lastMa.isNaN() && closes.last() < lastMa) return false
        }
        if (mode == 2) return shIndexTrendOk()
        return true
    }

    /** 上证指数趋势：最新收盘 > MA20 视为多头。带缓存，5 分钟刷新一次。 */
    private fun shIndexTrendOk(): Boolean {
        val now = System.currentTimeMillis()
        if (now - shTrendCheckedAt < 5 * 60_000L) return shTrendOk
        shTrendCheckedAt = now
        return try {
            val bars = kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
                com.quantapp.trader.data.MarketService.fetchKlineBy("000001", klt = 101, limit = 60).let {
                    // 上证指数 secid 需走 1.000001
                    it
                }.let { it.ifEmpty { com.quantapp.trader.data.MarketService.fetchKlineBy("000001", klt = 101, limit = 60).let { x -> x } } }
            }
            val idx = bars.lastIndex
            if (idx < 20) { shTrendOk = true; true }
            else {
                val ma = Indicators.sma(bars.map { it.close }, 20)
                shTrendOk = !ma[idx].isNaN() && bars[idx].close > ma[idx]
                shTrendOk
            }
        } catch (e: Exception) {
            shTrendOk = true // 大盘数据获取失败时放行，避免误阻断
            true
        }
    }

    private fun pushLiveSignal(symbol: String, name: String, side: String, price: Double, reason: String) {
        val gw = App.appStore.liveGateway
        if (gw.isBlank()) { gatewayStatus = null; return }
        try {
            val json = org.json.JSONObject().put("symbol", symbol).put("name", name)
                .put("side", side).put("price", price).put("reason", reason).toString()
            val body = json.toRequestBody("application/json; charset=utf-8".toMediaType())
            val resp = okhttp3.OkHttpClient().newCall(
                okhttp3.Request.Builder().url(gw).post(body).build()
            ).execute()
            resp.close()
            gatewayStatus = true
            gatewayLastError = ""
        } catch (e: Exception) {
            gatewayStatus = false
            gatewayLastError = e.message ?: "连接失败"
        }
    }

    /** 上报网关连接状态：由 Settings 页在保存/查看时主动探测一遍。 */
    fun pingGateway(): Boolean {
        val gw = App.appStore.liveGateway
        if (gw.isBlank()) { gatewayStatus = null; gatewayLastError = "未配置网关URL"; return true }
        return try {
            val resp = okhttp3.OkHttpClient().newCall(
                okhttp3.Request.Builder().url(gw).head().build()
            ).execute()
            resp.close()
            gatewayStatus = true
            gatewayLastError = ""
            true
        } catch (e: Exception) {
            gatewayStatus = false
            gatewayLastError = e.message ?: "连接失败"
            false
        }
    }

    fun fmt(v: Double): String = String.format("%.2f", v)

    /** 从行情取股票名称作为兜底；失败返回空串。 */
    private suspend fun resolveName(symbol: String): String = try {
        App.appStore.activeStrategies().firstOrNull { it.symbol == symbol }?.name
            ?.takeIf { it.isNotEmpty() }
            ?: com.quantapp.trader.data.MarketService.fetchQuote(symbol).name
    } catch (e: Exception) { "" }

    private fun strategyLabel(id: String) = when (id) {
        "rsi" -> "RSI"; "macd" -> "MACD"; "boll" -> "布林带"; "t_ma" -> "均线做T"
        "t_boll" -> "布林做T"; "t_vwap" -> "VWAP做T"; else -> "双均线"
    }
}