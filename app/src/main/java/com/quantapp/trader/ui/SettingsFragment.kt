package com.quantapp.trader.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.Toast
import android.content.Intent
import android.net.Uri
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.quantapp.trader.BuildConfig
import com.quantapp.trader.R
import com.quantapp.trader.data.LlmClient
import com.quantapp.trader.trading.App
import com.quantapp.trader.trading.PriceAlert
import com.quantapp.trader.update.DEFAULT_UPDATE_MANIFEST_URL
import com.quantapp.trader.update.DownloadHelper
import com.quantapp.trader.update.UpdateChecker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsFragment : Fragment() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val root = inflater.inflate(R.layout.fragment_settings, container, false)
        val rg = root.findViewById<RadioGroup>(R.id.rg_mode)
        val rbPaper = root.findViewById<RadioButton>(R.id.rb_paper)
        val rbLive = root.findViewById<RadioButton>(R.id.rb_live)
        val rgTheme = root.findViewById<RadioGroup>(R.id.rg_theme)
        val rbThemeSys = root.findViewById<RadioButton>(R.id.rb_theme_sys)
        val rbThemeLight = root.findViewById<RadioButton>(R.id.rb_theme_light)
        val rbThemeDark = root.findViewById<RadioButton>(R.id.rb_theme_dark)
        val etAlertSymbol = root.findViewById<EditText>(R.id.et_alert_symbol)
        val etAlertPrice = root.findViewById<EditText>(R.id.et_alert_price)
        val spAlertDir = root.findViewById<Spinner>(R.id.sp_alert_dir)
        val btnAddAlert = root.findViewById<Button>(R.id.btn_add_alert)
        val tvAlerts = root.findViewById<TextView>(R.id.tv_alerts)
        val etCapital = root.findViewById<EditText>(R.id.et_capital)
        val etPoll = root.findViewById<EditText>(R.id.et_poll)
        val etPct = root.findViewById<EditText>(R.id.et_pct)
        val rgTfreq = root.findViewById<RadioGroup>(R.id.rg_tfreq)
        val rbTfreq1min = root.findViewById<RadioButton>(R.id.rb_tfreq_1min)
        val rbTfreq60min = root.findViewById<RadioButton>(R.id.rb_tfreq_60min)
        val etTBase = root.findViewById<EditText>(R.id.et_t_base)
        val etTBand = root.findViewById<EditText>(R.id.et_t_band)
        val etGateway = root.findViewById<EditText>(R.id.et_gateway)
        val etStopLoss = root.findViewById<EditText>(R.id.et_stop_loss)
        val etTakeProfit = root.findViewById<EditText>(R.id.et_take_profit)
        val etMaxDd = root.findViewById<EditText>(R.id.et_max_dd)
        val etTrailActivate = root.findViewById<EditText>(R.id.et_trail_activate)
        val etTrailStop = root.findViewById<EditText>(R.id.et_trail_stop)
        val etBreakEven = root.findViewById<EditText>(R.id.et_breakeven)
        val etAtrEnabled = root.findViewById<EditText>(R.id.et_atr_enabled)
        val etAtrMult = root.findViewById<EditText>(R.id.et_atr_mult)
        val etFirstBuy = root.findViewById<EditText>(R.id.et_first_buy)
        val etAddPct = root.findViewById<EditText>(R.id.et_add_pct)
        val etAddThr = root.findViewById<EditText>(R.id.et_add_thr)
        val tvGatewayStatus = root.findViewById<TextView>(R.id.tv_gateway_status)
        val tvModeHint = root.findViewById<TextView>(R.id.tv_mode_hint)
        val tvInfo = root.findViewById<TextView>(R.id.tv_info)
        val tvVersion = root.findViewById<TextView>(R.id.tv_version)
        val btnCheckUpdate = root.findViewById<Button>(R.id.btn_check_update)
        val btnSave = root.findViewById<Button>(R.id.btn_save)
        val spAiProvider = root.findViewById<Spinner>(R.id.sp_ai_provider)
        val etAiKey = root.findViewById<EditText>(R.id.et_ai_key)
        val etAiModel = root.findViewById<EditText>(R.id.et_ai_model)
        val btnAiTest = root.findViewById<Button>(R.id.btn_ai_test)
        val btnAiSave = root.findViewById<Button>(R.id.btn_ai_save)
        val tvAiStatus = root.findViewById<TextView>(R.id.tv_ai_status)

        val st = App.appStore
        if (st.mode == "live") rbLive.isChecked = true else rbPaper.isChecked = true
        etCapital.setText(st.initialCapital().toLong().toString())
        etPoll.setText(st.pollSeconds.toString())
        etPct.setText(st.positionPct.toString())
        etGateway.setText(st.liveGateway)
        etStopLoss.setText(fmtPct(st.stopLossPct))
        etTakeProfit.setText(fmtPct(st.takeProfitPct))
        etMaxDd.setText(fmtPct(st.maxDrawdownPct))
        etTrailActivate.setText(fmtPct(st.trailingActivatePct))
        etTrailStop.setText(fmtPct(st.trailingStopPct))
        etBreakEven.setText(fmtPct(st.breakEvenPct))
        etAtrEnabled.setText(if (st.atrStopEnabled == 0) "" else st.atrStopEnabled.toString())
        etAtrMult.setText(if (st.atrMultiplier <= 0.1) "" else st.atrMultiplier.toString())
        etFirstBuy.setText(fmtPct(st.firstBuyPct))
        etAddPct.setText(fmtPct(st.addPositionPct))
        etAddThr.setText(fmtPct(st.addThresholdPct))
        if (st.tFrequency == 1) rbTfreq60min.isChecked = true else rbTfreq1min.isChecked = true
        etTBase.setText(st.tBasePct.toString())
        etTBand.setText(st.tBandPct.toString())
        updateHint(rbLive.isChecked, tvModeHint)

        // 外观主题初始化
        when (st.themeMode) { 1 -> rbThemeLight.isChecked = true; 2 -> rbThemeDark.isChecked = true; else -> rbThemeSys.isChecked = true }
        fun applyTheme(mode: Int) {
            if (st.themeMode != mode) st.themeMode = mode
            androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(
                when (mode) { 1 -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
                    2 -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
                    else -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM })
        }
        rgTheme.setOnCheckedChangeListener { _, id ->
            applyTheme(when (id) { R.id.rb_theme_light -> 1; R.id.rb_theme_dark -> 2; else -> 0 })
        }

        // 到价提醒
        spAlertDir.adapter = ArrayAdapter(requireContext(),
            android.R.layout.simple_spinner_item, arrayOf("向上突破目标价", "向下跌破目标价"))
            .apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        fun refreshAlerts() {
            val list = st.alerts()
            tvAlerts.text = if (list.isEmpty()) "（暂无提醒，添加后到价将推送通知）"
            else list.map {
                val dir = if (it.above) "上穿" else "跌破"
                val st_ = if (it.enabled) "" else "  [已停用]"
                "${it.symbol} ${String.format("%.2f", it.target)}($dir)$st_"
            }.joinToString("\n") + "\n\n（点击提醒可在启停间切换，长按删除）"
        }
        refreshAlerts()

        tvAlerts.setOnClickListener {
            val list = st.alerts()
            if (list.isEmpty()) return@setOnClickListener
            AlertDialog.Builder(requireContext())
                .setTitle("删除到价提醒")
                .setItems(list.map { "${it.symbol} ${String.format("%.2f", it.target)} ${if (it.above) "上穿" else "跌破"}" }.toTypedArray()) { _, which ->
                    st.removeAlert(list[which])
                    refreshAlerts()
                }
                .setNeutralButton("取消", null)
                .show()
        }
        tvAlerts.setOnLongClickListener {
            val list = st.alerts()
            if (list.isNotEmpty()) st.toggleAlert(list.last())
            refreshAlerts()
            true
        }

        btnAddAlert.setOnClickListener {
            val sym = etAlertSymbol.text.toString().trim().uppercase()
            val price = etAlertPrice.text.toString().toDoubleOrNull()
            if (sym.isEmpty()) { Toast.makeText(requireContext(), "请输入股票代码", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            if (price == null || price <= 0) { Toast.makeText(requireContext(), "请输入有效目标价", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            st.addOrUpdateAlert(PriceAlert(sym, "", price, above = spAlertDir.selectedItemPosition == 0))
            etAlertSymbol.text.clear(); etAlertPrice.text.clear()
            refreshAlerts()
            Toast.makeText(requireContext(), "已添加 $sym 到价提醒", Toast.LENGTH_SHORT).show()
        }
        tvInfo.text = "免责声明：本App为学习/演示用途。模拟盘不涉及真实资金；实盘信号模式需自行配置网关\n" +
            "（如通过 QMT/EasyTrader 的 HTTP 聚合再下单），风险自担。行情来自东方财富公开接口。"

        // 版本信息
        tvVersion.text = "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"

        btnCheckUpdate.setOnClickListener {
            val url = st.updateUrl
            if (url.isEmpty()) {
                Toast.makeText(requireContext(), "未配置升级地址", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            btnCheckUpdate.isEnabled = false
            btnCheckUpdate.text = "检查中..."
            scope.launch {
                try {
                    val url = st.updateUrl
                    // 兼容旧版内置的 Releases 直链：统一改用版本清单做精确比对
                    val checkUrl = if (url.contains("/releases/")) DEFAULT_UPDATE_MANIFEST_URL else url
                    val info = UpdateChecker.check(checkUrl)
                    val current = BuildConfig.VERSION_NAME
                    val remoteName = info.versionName
                    val remoteCode = info.versionCode

                    val hasRemoteVersion = remoteName != null || remoteCode != null
                    if (!hasRemoteVersion) {
                        // 普通下载/飞书链接：拿不到版本号，直接提供下载入口
                        showDownloadDialog(info.apkUrl)
                        return@launch
                    }
                    // 是否为更新版本：优先用精确 versionCode，否则用版本号字符串比较
                    val newer = when {
                        remoteName != null && remoteCode != null ->
                            remoteCode > BuildConfig.VERSION_CODE ||
                                UpdateChecker.compareVersions(remoteName, current) > 0
                        remoteName != null -> UpdateChecker.compareVersions(remoteName, current) > 0
                        else -> (remoteCode ?: 0) > BuildConfig.VERSION_CODE
                    }
                    val displayVersion = remoteName ?: remoteCode?.toString() ?: "?"
                    if (newer) {
                        showUpdateDialog(info, displayVersion, current)
                    } else {
                        showUpToDate(displayVersion)
                    }
                } catch (e: Exception) {
                    Toast.makeText(requireContext(), "检查更新失败：${e.message}", Toast.LENGTH_LONG).show()
                } finally {
                    btnCheckUpdate.isEnabled = true
                    btnCheckUpdate.text = "检查更新"
                }
            }
        }

        rg.setOnCheckedChangeListener { _, id ->
            updateHint(id == R.id.rb_live, tvModeHint)
            // 切换到实盘信号时主动探测一次网关连通性
            if (id == R.id.rb_live) probeGateway(tvGatewayStatus)
        }

        btnSave.setOnClickListener {
            try {
                val cap = etCapital.text.toString().toDoubleOrNull() ?: 100000.0
                val poll = etPoll.text.toString().toIntOrNull() ?: 15
                val pct = etPct.text.toString().toDoubleOrNull() ?: 0.8
                if (cap < 5000) { Toast.makeText(requireContext(), "初始资金至少5000", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
                if (pct <= 0 || pct > 1) { Toast.makeText(requireContext(), "仓位比例须在0~1", Toast.LENGTH_SHORT).show(); return@setOnClickListener }

                st.mode = if (rbLive.isChecked) "live" else "paper"
                st.pollSeconds = poll
                st.positionPct = pct
                st.liveGateway = etGateway.text.toString().trim()
                st.stopLossPct = etStopLoss.text.toString().toDoubleOrNull() ?: 0.0
                st.takeProfitPct = etTakeProfit.text.toString().toDoubleOrNull() ?: 0.0
                st.maxDrawdownPct = etMaxDd.text.toString().toDoubleOrNull() ?: 0.0
                st.trailingActivatePct = etTrailActivate.text.toString().toDoubleOrNull() ?: 0.0
                st.trailingStopPct = etTrailStop.text.toString().toDoubleOrNull() ?: 0.0
                st.breakEvenPct = etBreakEven.text.toString().toDoubleOrNull() ?: 0.0
                st.atrStopEnabled = etAtrEnabled.text.toString().toIntOrNull()?.coerceIn(0, 1) ?: 0
                st.atrMultiplier = etAtrMult.text.toString().toDoubleOrNull()?.let { it.coerceIn(0.1, 10.0) } ?: 0.0
                st.firstBuyPct = etFirstBuy.text.toString().toDoubleOrNull()?.let { it.coerceIn(0.1, 1.0) } ?: 0.0
                st.addPositionPct = etAddPct.text.toString().toDoubleOrNull()?.let { it.coerceIn(0.0, 1.0) } ?: 0.0
                st.addThresholdPct = etAddThr.text.toString().toDoubleOrNull() ?: 0.0
                st.tFrequency = if (rbTfreq60min.isChecked) 1 else 0
                st.tBasePct = etTBase.text.toString().toDoubleOrNull()?.let { it.coerceIn(0.05, 1.0) } ?: st.tBasePct
                st.tBandPct = etTBand.text.toString().toDoubleOrNull()?.let { it.coerceIn(0.05, 1.0) } ?: st.tBandPct
                // 更换初始资金时重置模拟盘
                if (Math.abs(st.initialCapital() - cap) > 0.01) {
                    st.setInitialCapital(cap)
                    st.paper.reset(cap)
                }
                st.save()
                probeGateway(tvGatewayStatus)
                Toast.makeText(requireContext(), "设置已保存", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "保存失败：${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
        // ---------- AI 大模型配置 ----------
        val aiProviders = LlmClient.providers
        spAiProvider.adapter = ArrayAdapter(requireContext(),
            android.R.layout.simple_spinner_item, aiProviders.map { it.name })
            .apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        var currentProviderId = st.aiProvider
        spAiProvider.setSelection(aiProviders.indexOfFirst { it.id == st.aiProvider }.coerceAtLeast(0))
        etAiKey.setText(st.aiKey)
        etAiModel.setText(st.aiModel)
        fun defaultModelFor(id: String) = aiProviders.firstOrNull { it.id == id }?.defaultModel ?: "deepseek-chat"
        spAiProvider.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) {
                val newId = aiProviders[pos].id
                if (newId != currentProviderId) {
                    val cur = etAiModel.text.toString().trim()
                    if (cur.isEmpty() || cur == defaultModelFor(currentProviderId))
                        etAiModel.setText(defaultModelFor(newId))
                    currentProviderId = newId
                }
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
        fun statusOk(ok: Boolean) = android.graphics.Color.parseColor(if (ok) "#2E7D32" else "#C62828")
        btnAiSave.setOnClickListener {
            val key = etAiKey.text.toString().trim()
            if (key.isEmpty()) {
                tvAiStatus.text = "API Key 不能为空，请先在对应平台控制台获取"
                tvAiStatus.setTextColor(statusOk(false)); return@setOnClickListener
            }
            val pid = aiProviders[spAiProvider.selectedItemPosition.coerceIn(0, aiProviders.size - 1)].id
            st.aiProvider = pid
            st.aiKey = key
            st.aiModel = etAiModel.text.toString().trim().ifEmpty { defaultModelFor(pid) }
            tvAiStatus.text = "已保存：${aiProviders[spAiProvider.selectedItemPosition].name} / ${st.aiModel}"
            tvAiStatus.setTextColor(statusOk(true))
        }
        btnAiTest.setOnClickListener { b ->
            val key = etAiKey.text.toString().trim()
            if (key.isEmpty()) {
                tvAiStatus.text = "请先填写 API Key"
                tvAiStatus.setTextColor(statusOk(false)); return@setOnClickListener
            }
            val pid = aiProviders[spAiProvider.selectedItemPosition.coerceIn(0, aiProviders.size - 1)].id
            val model = etAiModel.text.toString().trim().ifEmpty { defaultModelFor(pid) }
            b.isEnabled = false
            btnAiTest.text = "测试中..."
            tvAiStatus.text = "正在请求 $model ..."
            tvAiStatus.setTextColor(android.graphics.Color.parseColor("#CCB400"))
            scope.launch(Dispatchers.IO) {
                val ok = runCatching {
                    LlmClient.chat(pid, key, model, "你是A股量化助手", "请回复：连接正常，模型可用。")
                }
                withContext(Dispatchers.Main) {
                    ok.onSuccess { rep ->
                        tvAiStatus.text = "连接成功：${rep.take(80)}"
                        tvAiStatus.setTextColor(statusOk(true))
                    }.onFailure { e ->
                        tvAiStatus.text = "连接失败：${e.message}"
                        tvAiStatus.setTextColor(statusOk(false))
                    }
                    b.isEnabled = true
                    btnAiTest.text = "测试连接"
                }
            }
        }
        return root
    }

    private fun showUpToDate(remoteVersion: String) {
        val ctx = requireContext()
        AlertDialog.Builder(ctx)
            .setTitle("已是最新版本")
            .setMessage("当前版本：v${BuildConfig.VERSION_NAME}\n最新版本：v$remoteVersion\n无需更新。")
            .setPositiveButton("确定", null)
            .show()
    }

    private fun showUpdateDialog(info: com.quantapp.trader.update.UpdateInfo, remoteVersion: String, current: String) {
        val ctx = requireContext()
        val changelog = if (info.changelog.isBlank()) "暂无更新说明" else info.changelog
        AlertDialog.Builder(ctx)
            .setTitle("检测到新版本 v$remoteVersion")
            .setMessage("当前版本：v$current\n最新版本：v$remoteVersion\n\n更新内容：\n$changelog")
            .setPositiveButton("立即下载更新") { _, _ -> startInAppDownload(info.apkUrl) }
            .setNegativeButton("稍后", null)
            .show()
    }

    /** 无版本号信息的普通下载/飞书链接：同样提供应用内升级入口。 */
    private fun showDownloadDialog(link: String) {
        val ctx = requireContext()
        AlertDialog.Builder(ctx)
            .setTitle("在线升级")
            .setMessage("当前版本：v${BuildConfig.VERSION_NAME}\n点击“立即下载更新”，将跳转下载最新安装包。")
            .setPositiveButton("立即下载更新") { _, _ -> startInAppDownload(link) }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 发起下载。优先用系统浏览器打开 APK 直链（跟随跳转最可靠、必有下载进度，杜绝“点了却没下载”）；
     * 仅当设备没有可用浏览器时才退化为系统 DownloadManager。
     */
    private fun startInAppDownload(url: String) {
        val ctx = requireContext()
        if (url.isBlank()) {
            Toast.makeText(ctx, "未提供下载链接", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(intent)
            Toast.makeText(ctx, "正在使用浏览器下载更新…", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            // 无浏览器或解析失败：退化为系统下载管理器
            DownloadHelper.start(ctx, url)
        }
    }

    private fun updateHint(live: Boolean, tv: TextView) {
        tv.text = if (live)
            "实盘信号模式：引擎只输出买卖信号并POST到网关，不直接成交，需配置下方网关URL。"
        else "模拟盘模式：使用虚拟资金按真实行情自动成交。"
    }

    private fun fmtPct(v: Double): String =
        if (v == 0.0) "" else v.toString()

    /** 在后台探测实盘网关连通性，并把结果状态显示出来。 */
    private fun probeGateway(tv: TextView) {
        val gw = App.appStore.liveGateway.trim()
        if (gw.isEmpty()) {
            tv.text = "网关：未配置，实盘模式不会发送信号"
            return
        }
        tv.text = "网关检测中..."
        tv.setTextColor(android.graphics.Color.parseColor("#CCB400"))
        scope.launch(Dispatchers.IO) {
            val ok = com.quantapp.trader.trading.TradingEngine.pingGateway()
            kotlinx.coroutines.withContext(Dispatchers.Main) {
                if (ok) {
                    tv.text = "网关：连接正常"
                    tv.setTextColor(android.graphics.Color.parseColor("#2E7D32"))
                } else {
                    tv.text = "网关：连接失败（${com.quantapp.trader.trading.TradingEngine.gatewayLastError}），将自动重试，请检查URL与网关服务"
                    tv.setTextColor(android.graphics.Color.parseColor("#C62828"))
                }
            }
        }
    }
}