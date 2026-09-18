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
        val channel = NotificationChannel(
            "quant_trade", "交易通知", NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
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