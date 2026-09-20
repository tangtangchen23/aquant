package com.quantapp.trader.trading

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.quantapp.trader.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 常驻前台服务：让模拟盘自动交易、到价提醒在 App 退到后台/锁屏后仍按轮询周期运行。
 *
 * 通过常驻通知展示引擎状态，并提供“停止后台”操作。START_STICKY 保证被系统回收后自动复活。
 */
class EngineService : Service() {

    companion object {
        const val ACTION_START = "com.quantapp.trader.START_ENGINE"
        const val ACTION_STOP = "com.quantapp.trader.STOP_ENGINE"
        const val CHANNEL_ID = "quant_engine"
        const val NOTIF_ID = 1

        /** 启动常驻引擎服务（自动交易+提醒退后台仍生效）。 */
        fun start(context: Context) {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(Intent(context, EngineService::class.java).apply { action = ACTION_START })
            } else {
                context.startService(Intent(context, EngineService::class.java).apply { action = ACTION_START })
            }
        }

        /** 关闭常驻服务（自动交易与提醒一并停止）。 */
        fun stop(context: Context) {
            context.startService(Intent(context, EngineService::class.java).apply { action = ACTION_STOP })
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private var scope: CoroutineScope? = null
    private var updateJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    override fun onDestroy() {
        scope?.cancel()
        scope = null
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            AlertWatcher.stop()
            TradingEngine.stop()
            updateJob?.cancel()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIF_ID, buildNotification("引擎启动中..."))
        AlertWatcher.start()
        if (App.appStore.activeStrategies().isNotEmpty()) {
            TradingEngine.start()
        }

        if (updateJob == null || !updateJob!!.isActive) {
            updateJob = scope?.launch {
                while (isActive) {
                    val text = statusText()
                    main.post { updateStatus(text) }
                    delay(5_000)
                }
            }
        }
        return START_STICKY
    }

    private fun statusText(): String = buildString {
        if (TradingEngine.isRunning) append("引擎运行中")
        else append("引擎已停止")
        append(" · 策略 ${App.appStore.activeStrategies().size} 个")
        append(" · 提醒 ${App.appStore.alerts().count { it.enabled }} 条")
    }

    private fun updateStatus(text: String) {
        getSystemService(NotificationManager::class.java)?.notify(NOTIF_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val openPi = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stopPi = PendingIntent.getService(this, 1,
            Intent(this, EngineService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("A股量化引擎")
            .setContentText(text)
            .setContentIntent(openPi)
            .setOngoing(true)
            .addAction(0, "停止后台", stopPi)
            .build()
    }
}