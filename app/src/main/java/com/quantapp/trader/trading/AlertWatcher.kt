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
 * 到价提醒轮询：读取启用的到价提醒，定时拉取最新价，
 * 首次跨越目标价推送通知；价格回退到阈值内自动重新武装，避免重复告警。
 */
object AlertWatcher {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val fired = hashSetOf<String>()
    private val running = AtomicBoolean(false)
    private var job: Job? = null

    fun start() {
        if (running.getAndSet(true)) return
        job = scope.launch {
            while (isActive) {
                try { check() } catch (e: Exception) { e.printStackTrace() }
                delay(20_000)
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
        for (a in store.alerts().filter { it.enabled }) {
            val quote = try {
                withContext(Dispatchers.IO) { MarketService.fetchQuote(a.symbol) }
            } catch (e: Exception) { continue }
            val price = quote.price
            val key = a.key()
            val crossed = if (a.above) price >= a.target else price <= a.target
            if (crossed) {
                if (fired.add(key)) {
                    val name = if (a.name.isNotBlank()) a.name else quote.name
                    fireNotification(name, a.symbol, a.above, a.target, price)
                }
            } else {
                fired.remove(key)
            }
        }
    }

    private fun fireNotification(name: String, symbol: String, above: Boolean, target: Double, price: Double) {
        val ctx = App.context
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val text = if (above)
            "现价 ${String.format("%.2f", price)} 已上穿提醒价 ${String.format("%.2f", target)}"
        else
            "现价 ${String.format("%.2f", price)} 已跌破提醒价 ${String.format("%.2f", target)}"
        val notif = NotificationCompat.Builder(ctx, "quant_alert")
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("$name($symbol) 到价提醒")
            .setContentText(text)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        try { nm.notify(("alert_$symbol").hashCode(), notif) } catch (e: Exception) { }
    }
}