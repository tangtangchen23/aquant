package com.quantapp.trader.trading

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

class QuantApp : Application() {

    override fun onCreate() {
        super.onCreate()
        App.attach(this)
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        getSystemService(NotificationManager::class.java).apply {
            createNotificationChannel(NotificationChannel(
                "quant_trade", "交易通知", NotificationManager.IMPORTANCE_LOW))
            createNotificationChannel(NotificationChannel(
                "quant_alert", "行情提醒", NotificationManager.IMPORTANCE_HIGH))
        }
    }
}

/** 全局可访问的 App 引用。 */
object App {
    lateinit var context: android.content.Context
        private set
    lateinit var appStore: AppStore
        private set

    fun attach(app: Application) {
        context = app.applicationContext
        appStore = AppStore(context)
        appStore.load()
    }
}