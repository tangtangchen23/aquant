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

    fun strategyOf(id: String): Strategy = com.quantapp.trader.strategy.buildStrategy(id, App.appStore.strategyConfig(id))

    fun start() {
        if (running.get()) return
        running.set(true)
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
        running.set(false)
        job?.cancel()
        job = null
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
                if (store.paper.positions[a.symbol] == null && (a.lastAction != "买入" || priceChanged)) {
                    if (store.mode == "paper") {
                        val target = store.paper.cash * store.positionPct
                        store.paper.buy(a.symbol, name, lastPrice, target)?.let {
                            a.lastAction = "买入"; a.lastReason = sig.reason; a.lastProcessedClose = lastClose
                            store.updateStrategy(a); onTrade?.invoke("模拟买入 $name @ ${fmt(lastPrice)}")
                        }
                    } else {
                        a.lastAction = "买入"; a.lastReason = sig.reason; a.lastProcessedClose = lastClose
                        store.updateStrategy(a)
                        pushLiveSignal(a.symbol, name, "BUY", lastPrice, sig.reason)
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
                        }
                    } else if (store.mode == "live") {
                        a.lastAction = "卖出"; a.lastReason = sig.reason; a.lastProcessedClose = lastClose
                        store.updateStrategy(a)
                        pushLiveSignal(a.symbol, name, "SELL", lastPrice, sig.reason)
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
        if (gw.isBlank()) return
        // 以短连接上报信号；网关侧（如 QMT/EasyTrader）据此下单。失败静默。
        try {
            val json = org.json.JSONObject().put("symbol", symbol).put("name", name)
                .put("side", side).put("price", price).put("reason", reason).toString()
            val body = json.toRequestBody("application/json; charset=utf-8".toMediaType())
            okhttp3.OkHttpClient().newCall(
                okhttp3.Request.Builder().url(gw).post(body).build()
            ).execute().close()
        } catch (e: Exception) { }
    }

    fun fmt(v: Double): String = String.format("%.2f", v)
}