package com.quantapp.trader.ui

import android.os.Build
import android.os.Bundle
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.quantapp.trader.R
import com.quantapp.trader.trading.EngineService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    /** 待加载K线的股票代码：由行情页点击自选股设置，图表页读取后消费。 */
    companion object {
        @Volatile var pendingChartSymbol: String? = null
    }

    private lateinit var nav: BottomNavigationView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppScope.attach(lifecycleScope)
        // 应用主题模式（跟随系统/浅色/深色），需在 setContentView 前调用
        androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(
            when (com.quantapp.trader.trading.App.appStore.themeMode) {
                1 -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
                2 -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
                else -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
        )
        setContentView(R.layout.activity_main)
        // 启动常驻引擎服务：自动交易与到价提醒在退到后台/锁屏后仍继续运行
        EngineService.start(this)
        requestNotificationPermission()

        nav = findViewById(R.id.bottom_nav)

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.container, MarketFragment())
                .commit()
        }

        nav.setOnItemSelectedListener { item ->
            val f: Fragment = when (item.itemId) {
                R.id.nav_screener -> ScreenerFragment()
                R.id.nav_strategy -> StrategyFragment()
                R.id.nav_account -> AccountFragment()
                R.id.nav_settings -> SettingsFragment()
                else -> MarketFragment()
            }
            supportFragmentManager.beginTransaction().replace(R.id.container, f).commit()
            true
        }
    }

    /** 打开K线图并加载指定股票（由行情页点击自选股/查询卡片触发，无需底部导航页签）。 */
    fun openChart(symbol: String) {
        pendingChartSymbol = symbol
        supportFragmentManager.beginTransaction()
            .replace(R.id.container, ChartFragment())
            .commit()
    }

    /** 切换到底部导航页签（供其他页面跳转，如未配置AI时跳设置）。 */
    fun switchTab(itemId: Int) {
        nav.selectedItemId = itemId
    }

    /** Android 13+ 需要动态申请通知权限，后台引擎/到价提醒通知才可见。 */
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1001)
        }
    }
}

/** Activity 生命周期绑定的协程作用域，供各 Fragment 使用。 */
object AppScope {
    @Volatile private var s: CoroutineScope? = null
    fun attach(scope: CoroutineScope) { s = scope }
    fun launch(block: suspend CoroutineScope.() -> Unit) = s?.launch { block() }
}