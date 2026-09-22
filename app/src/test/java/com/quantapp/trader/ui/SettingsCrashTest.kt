package com.quantapp.trader.ui

import androidx.appcompat.app.AppCompatActivity
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = com.quantapp.trader.trading.QuantApp::class)
class SettingsCrashTest {

    class Host : AppCompatActivity()

    private fun openSettings() {
        val c = Robolectric.buildActivity(Host::class.java).setup().get()
        val f = SettingsFragment()
        c.supportFragmentManager.beginTransaction()
            .add(android.R.id.content, f)
            .commitNow()
    }

    @Test
    fun settingsPageOpensWithoutCrash() {
        openSettings()
    }

    @Test
    fun settingsTradeDialogOpens() {
        val c = Robolectric.buildActivity(Host::class.java).setup().get()
        val f = SettingsFragment()
        c.supportFragmentManager.beginTransaction().add(android.R.id.content, f).commitNow()
        val root = f.requireView()
        root.findViewById<android.view.View>(com.quantapp.trader.R.id.row_trade).performClick()
    }

    @Test
    fun settingsWindowsInDarkThemeOpen() {
        val app = com.quantapp.trader.trading.App
        // force dark themeMode so dark resources are used at inflation
        app.appStore.themeMode = 2
        openSettings()
    }
}