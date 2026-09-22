package com.quantapp.trader.trading

import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import com.quantapp.trader.data.MarketService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 挂单撮合轮询：读取未成交挂单，定时拉取最新价，
 * 当现价触及挂单价格（买入：现价<=挂单价；卖出：现价>=挂单价）时对模拟盘账户成交，
 * 发送通知并移除该挂单。在交易时段外拉取失败会自动跳过，盘后不误成交。
 */
object PendingOrderWatcher {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = AtomicBoolean(false)
    private var job: Job? = null

    fun start() {
        if (running.getAndSet(true)) return
        job = scope.launch {
            while (isActive) {
                try { check() } catch (e: Exception) { e.printStackTrace() }
                delay(10_000)
            }
        }
    }

    fun stop() {
        running.set(false)
        job?.cancel()
        job = null
    }

    private suspend fun check() {
        val store = App.appStore
        val orders = store.pendingOrders().toList()
        if (orders.isEmpty()) return
        for (o in orders) {
            val quote = try {
                withContext(Dispatchers.IO) { MarketService.fetchQuote(o.symbol) }
            } catch (e: Exception) { continue }
            val price = quote.price
            val hit = if (o.side == "买入") price <= o.limitPrice else price >= o.limitPrice
            if (hit) fill(store, o, price, quote.name)
        }
    }

    private fun fill(store: AppStore, o: PendingOrder, marketPrice: Double, quoteName: String) {
        val fillPrice = o.limitPrice // 以挂单价成交（买入更优/卖出更保守，简单且安全）
        var ok = false
        var fillQty = 0
        if (o.side == "买入") {
            val target = if (o.amountTarget > 0) o.amountTarget else o.qtyTarget * o.limitPrice
            val t = store.paper.buy(o.symbol, o.name, fillPrice, target)
            if (t != null) { ok = true; fillQty = t.qty }
        } else {
            val pos = store.paper.positions[o.symbol]
            if (pos != null) {
                val maxSellable = pos.sellableQty()
                val q = if (o.qtyTarget > 0) o.qtyTarget.coerceIn(1, maxSellable) else maxSellable
                val t = store.paper.sell(o.symbol, o.name, fillPrice, q)
                if (t != null) { ok = true; fillQty = t.qty }
            }
        }
        store.removePendingOrderById(o.id())
        store.save()
        val name = o.name.ifBlank { quoteName.ifBlank { o.symbol } }
        if (ok) {
            fireNotification(name, o.symbol, o.side, fillPrice, fillQty, marketPrice)
        }
        // 触发界面刷新（账户页/策略页监听 onTrade）
        mainThread { TradingEngine.onTrade?.invoke(name) }
    }

    private fun fireNotification(name: String, symbol: String, side: String, price: Double, qty: Int, marketPrice: Double) {
        val ctx = App.context
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notif = NotificationCompat.Builder(ctx, "quant_trade")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("$name($symbol) 挂单成交")
            .setContentText("$side ${qty}股 @ ${String.format("%.2f", price)}（现价 ${String.format("%.2f", marketPrice)}）")
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        try { nm.notify(("pending_${symbol}_$side").hashCode(), notif) } catch (e: Exception) { }
    }

    private fun mainThread(r: () -> Unit) {
        try {
            android.os.Handler(android.os.Looper.getMainLooper()).post { r() }
        } catch (e: Exception) { }
    }
}