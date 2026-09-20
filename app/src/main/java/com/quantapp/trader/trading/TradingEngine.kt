package com.quantapp.trader.trading

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import com.quantapp.trader.data.MarketService
import com.quantapp.trader.strategy.Strategy
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 自动化交易引擎：按设定周期轮询活跃标的行情，运行策略策略，
 * 模拟盘直接撮合；实盘(信号)模式把信号推送至配置的实盘网关（可对接 QMT/EasyTrader）。
 *
 * 限制说明：本引擎在 App 前台时运行（演示/学习用途）。
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

    fun strategyOf(id: String): Strategy = com.quantapp.trader.strategy.buildStrategy(id, App.appStore.strategyConfig(id))

    fun start() {
        if (running.get()) return
        running.set(true)
        App.appStore.addLog("自动交易引擎启动")
        job = scope.launch {
            while (isActive) {
                try {
                    tick()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                delay(App.appStore.pollSeconds * 1000L)
            }
        }
    }

    fun stop() {
        if (!running.get()) return
        running.set(false)
        job?.cancel()
        job = null
        App.appStore.addLog("自动交易引擎停止")
    }

    val isRunning: Boolean get() = running.get()

    private suspend fun tick() {
        val store = App.appStore
        // 收集实时价用于账户估值
        val active = store.activeStrategies()
        if (active.isEmpty()) return

        val prices = mutableMapOf<String, Double>()
        for (a in active) {
            try {
                val quote = MarketService.fetchQuote(a.symbol)
                prices[a.symbol] = quote.price
            } catch (e: Exception) { }
        }

        // 风控：单票止损/止盈 + 账户回撤熔断
        applyRiskControls(prices)

        // 每个活跃标的评估策略并执行信号
        for (a in active.toList()) {
            try {
                evaluateSymbol(a)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        store.save()
        val longPrice = prices.values.firstOrNull() ?: 0.0
        onTicker?.invoke(store.activeStrategies(), store.paper.equity(prices), store.paper.cash, active.size)
    }

    /** 风控规则：单票止损止盈 + 账户回撤熔断（仅对模拟盘持仓生效）。 */
    private fun applyRiskControls(prices: Map<String, Double>) {
        val store = App.appStore
        val stopLoss = store.stopLossPct / 100.0
        val takeProfit = store.takeProfitPct / 100.0
        val maxDrawdown = store.maxDrawdownPct / 100.0

        // 先处理账户回撤熔断：用实时价的整体权益跟踪峰值
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

        // 单票止损/止盈
        for ((symbol, pos) in store.paper.positions.toList()) {
            val price = prices[symbol] ?: continue
            if (pos.costPrice <= 0) continue
            val pnlPct = (price - pos.costPrice) / pos.costPrice
            val name = pos.name
            if (stopLoss > 0 && pnlPct <= -stopLoss) {
                store.paper.sell(symbol, name, price)?.let {
                    store.addLog("止损：$name($symbol) 亏损 ${String.format("%.1f", pnlPct * 100)}% @ ${String.format("%.2f", price)} 已平仓")
                    onTrade?.invoke("止损 $name @ ${String.format("%.2f", price)}")
                }
            } else if (takeProfit > 0 && pnlPct >= takeProfit) {
                store.paper.sell(symbol, name, price)?.let {
                    store.addLog("止盈：$name($symbol) 盈利 ${String.format("%.1f", pnlPct * 100)}% @ ${String.format("%.2f", price)} 已平仓")
                    onTrade?.invoke("止盈 $name @ ${String.format("%.2f", price)}")
                }
            }
        }
    }

    private suspend fun evaluateSymbol(a: ActiveStrategy) {
        val store = App.appStore
        val bars = MarketService.fetchKline(a.symbol, limit = 120)
        if (bars.isEmpty()) return
        val quote = MarketService.fetchQuote(a.symbol)
        val lastPrice = quote.price
        val lastClose = bars.last().close

        val strategy = strategyOf(a.strategyId)
        val sig = strategy.evaluate(bars, lastPrice)
        val name = if (a.name.isNotEmpty()) a.name else quote.name

        // 只在“最新价较上次处理价明显变化”时才重复开仓/平仓，避免高频重复。
        val priceChanged = Math.abs(lastPrice - a.lastProcessedClose) / (a.lastProcessedClose.takeIf { it > 0 } ?: lastPrice) > 0.0005
        when (sig.action) {
            com.quantapp.trader.strategy.Action.BUY -> {
                if (haltBuying && store.paper.positions[a.symbol] == null) {
                    // 回撤熔断中：不新建仓位，仅更新 lastAction 避免重复
                    a.lastProcessedClose = lastClose; store.updateStrategy(a)
                } else if (store.paper.positions[a.symbol] == null && (a.lastAction != "买入" || priceChanged)) {
                    if (store.mode == "paper") {
                        val target = store.paper.cash * store.positionPct
                        store.paper.buy(a.symbol, name, lastPrice, target)?.let {
                            a.lastAction = "买入"; a.lastReason = sig.reason; a.lastProcessedClose = lastClose
                            store.updateStrategy(a); onTrade?.invoke("模拟买入 $name @ ${fmt(lastPrice)}")
                            store.addLog("买入信号 ${strategyLabel(a.strategyId)} | $name(${a.symbol}) @ ${fmt(lastPrice)} | ${sig.reason}")
                        }
                    } else {
                        a.lastAction = "买入"; a.lastReason = sig.reason; a.lastProcessedClose = lastClose
                        store.updateStrategy(a)
                        pushLiveSignal(a.symbol, name, "BUY", lastPrice, sig.reason)
                        store.addLog("实盘买入信号 ${strategyLabel(a.strategyId)} | $name(${a.symbol}) @ ${fmt(lastPrice)} | ${sig.reason}")
                    }
                }
            }
            com.quantapp.trader.strategy.Action.SELL -> {
                val pos = store.paper.positions[a.symbol]
                if (pos != null || a.lastAction == "买入" && store.mode == "live") {
                    if (store.mode == "paper" && pos != null) {
                        store.paper.sell(a.symbol, name, lastPrice)?.let {
                            a.lastAction = "卖出"; a.lastReason = sig.reason; a.lastProcessedClose = lastClose
                            store.updateStrategy(a); onTrade?.invoke("模拟卖出 $name @ ${fmt(lastPrice)}")
                            store.addLog("卖出信号 ${strategyLabel(a.strategyId)} | $name(${a.symbol}) @ ${fmt(lastPrice)} | ${sig.reason}")
                        }
                    } else if (store.mode == "live") {
                        a.lastAction = "卖出"; a.lastReason = sig.reason; a.lastProcessedClose = lastClose
                        store.updateStrategy(a)
                        pushLiveSignal(a.symbol, name, "SELL", lastPrice, sig.reason)
                        store.addLog("实盘卖出信号 ${strategyLabel(a.strategyId)} | $name(${a.symbol}) @ ${fmt(lastPrice)} | ${sig.reason}")
                    }
                }
            }
            else -> {
                if (priceChanged) { a.lastProcessedClose = lastClose; store.updateStrategy(a) }
            }
        }
    }

    private fun pushLiveSignal(symbol: String, name: String, side: String, price: Double, reason: String) {
        val gw = App.appStore.liveGateway
        if (gw.isBlank()) { gatewayStatus = null; return }
        // 以短连接上报信号；网关侧（如 QMT/EasyTrader）据此下单。失败时记录状态供UI提示。
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

    private fun strategyLabel(id: String) = when (id) { "rsi" -> "RSI"; "macd" -> "MACD"; else -> "双均线" }
}