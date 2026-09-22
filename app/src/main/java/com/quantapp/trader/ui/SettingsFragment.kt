package com.quantapp.trader.ui

import android.content.Context
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
import androidx.core.content.ContextCompat
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
        val rgTheme = root.findViewById<RadioGroup>(R.id.rg_theme)
        val rbThemeSys = root.findViewById<RadioButton>(R.id.rb_theme_sys)
        val rbThemeLight = root.findViewById<RadioButton>(R.id.rb_theme_light)
        val rbThemeDark = root.findViewById<RadioButton>(R.id.rb_theme_dark)
        val rbThemeRed = root.findViewById<RadioButton>(R.id.rb_theme_red)
        val etGateway = root.findViewById<EditText>(R.id.et_gateway)
        val tvGatewayStatus = root.findViewById<TextView>(R.id.tv_gateway_status)
        val tvInfo = root.findViewById<TextView>(R.id.tv_info)
        val tvVersion = root.findViewById<TextView>(R.id.tv_version)
        val btnCheckUpdate = root.findViewById<Button>(R.id.btn_check_update)
        val btnSave = root.findViewById<Button>(R.id.btn_save)

        val st = App.appStore
        etGateway.setText(st.liveGateway)

        // ---------- 外观主题初始化/切换 ----------
        when (st.themeMode) {
            1 -> rbThemeLight.isChecked = true
            2 -> rbThemeDark.isChecked = true
            3 -> rbThemeRed.isChecked = true
            else -> rbThemeSys.isChecked = true
        }
        fun applyTheme(mode: Int) {
            val prev = st.themeMode
            if (prev != mode) st.themeMode = mode
            if (mode == 3) {
                androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(
                    androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO)
                requireActivity().recreate()
            } else {
                androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(
                    when (mode) { 1 -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
                        2 -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
                        else -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM })
                if (prev == 3) requireActivity().recreate()
            }
        }
        rgTheme.setOnCheckedChangeListener { _, id ->
            applyTheme(when (id) { R.id.rb_theme_light -> 1; R.id.rb_theme_dark -> 2; R.id.rb_theme_red -> 3; else -> 0 })
        }

        // ---------- 功能设置：二级弹窗入口 ----------
        root.findViewById<View>(R.id.row_trade).setOnClickListener { showTradeDialog(st, etGateway, tvGatewayStatus) }
        root.findViewById<View>(R.id.row_tband).setOnClickListener { showTbandDialog(st) }
        root.findViewById<View>(R.id.row_risk).setOnClickListener { showRiskDialog(st) }
        root.findViewById<View>(R.id.row_alert).setOnClickListener { showAlertDialog() }
        root.findViewById<View>(R.id.row_ai).setOnClickListener { showAiDialog(st) }

        // ---------- 兜底提示 ----------
        tvInfo.text = "免责声明：本App为学习/演示用途。模拟盘不涉及真实资金；实盘信号模式需自行配置网关\n" +
            "（如通过 QMT/EasyTrader 的 HTTP 聚合再下单），风险自担。行情来自东方财富公开接口。"

        // ---------- 版本信息 ----------
        tvVersion.text = "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"

        btnCheckUpdate.setOnClickListener {
            if (st.updateUrl.isEmpty()) {
                Toast.makeText(requireContext(), "未配置升级地址", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            btnCheckUpdate.isEnabled = false
            btnCheckUpdate.text = "检查中..."
            scope.launch {
                try {
                    val url = st.updateUrl
                    val checkUrl = if (url.contains("/releases/")) DEFAULT_UPDATE_MANIFEST_URL else url
                    val info = UpdateChecker.check(checkUrl)
                    val current = BuildConfig.VERSION_NAME
                    val remoteName = info.versionName
                    val remoteCode = info.versionCode
                    val hasRemoteVersion = remoteName != null || remoteCode != null
                    if (!hasRemoteVersion) {
                        showDownloadDialog(info.apkUrl)
                        return@launch
                    }
                    val newer = when {
                        remoteName != null && remoteCode != null ->
                            remoteCode > BuildConfig.VERSION_CODE ||
                                UpdateChecker.compareVersions(remoteName, current) > 0
                        remoteName != null -> UpdateChecker.compareVersions(remoteName, current) > 0
                        else -> (remoteCode ?: 0) > BuildConfig.VERSION_CODE
                    }
                    val displayVersion = remoteName ?: remoteCode?.toString() ?: "?"
                    if (newer) showUpdateDialog(info, displayVersion, current)
                    else showUpToDate(displayVersion)
                } catch (e: Exception) {
                    Toast.makeText(requireContext(), "检查更新失败：${e.message}", Toast.LENGTH_LONG).show()
                } finally {
                    btnCheckUpdate.isEnabled = true
                    btnCheckUpdate.text = "检查更新"
                }
            }
        }

        // 底部“保存设置”：持久化实盘网关并探测连通性
        btnSave.setOnClickListener {
            st.liveGateway = etGateway.text.toString().trim()
            st.save()
            probeGateway(tvGatewayStatus)
            Toast.makeText(requireContext(), "网关已保存", Toast.LENGTH_SHORT).show()
        }
        return root
    }

    // =========================== 二级弹窗 ===========================

    private fun showTradeDialog(st: com.quantapp.trader.trading.AppStore, etGateway: EditText, tvGatewayStatus: TextView) {
        val ctx = requireContext()
        val v = LayoutInflater.from(ctx).inflate(R.layout.dialog_settings_trade, null)
        val rg = v.findViewById<RadioGroup>(R.id.rg_mode_dialog)
        val rbPaper = v.findViewById<RadioButton>(R.id.rb_dialog_paper)
        val rbLive = v.findViewById<RadioButton>(R.id.rb_dialog_live)
        val tvModeHint = v.findViewById<TextView>(R.id.tv_mode_hint)
        val etCapital = v.findViewById<EditText>(R.id.et_capital_dialog)
        val etPoll = v.findViewById<EditText>(R.id.et_poll_dialog)
        val etPct = v.findViewById<EditText>(R.id.et_pct_dialog)
        val btnReset = v.findViewById<Button>(R.id.btn_reset)

        if (st.mode == "live") rbLive.isChecked = true else rbPaper.isChecked = true
        etCapital.setText(st.initialCapital().toLong().toString())
        etPoll.setText(st.pollSeconds.toString())
        etPct.setText(st.positionPct.toString())
        updateHint(rbLive.isChecked, tvModeHint)
        rg.setOnCheckedChangeListener { _, id ->
            updateHint(id == R.id.rb_dialog_live, tvModeHint)
            if (id == R.id.rb_dialog_live) probeGateway(tvGatewayStatus)
        }
        btnReset.setOnClickListener {
            android.app.AlertDialog.Builder(ctx)
                .setTitle("重置模拟盘")
                .setMessage("确定清空当前模拟盘的持仓与成交记录，并重新注入 ${st.initialCapital().toLong()} 元初始资金吗？\n此操作不可撤销。")
                .setPositiveButton("确认重置") { _, _ ->
                    st.paper.reset(st.initialCapital())
                    st.save()
                    com.quantapp.trader.trading.TradingEngine.onTrade?.invoke("")
                    Toast.makeText(ctx, "模拟盘已重置", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("取消", null)
                .show()
        }
        openSaveDialog(v) {
            val cap = etCapital.text.toString().toDoubleOrNull() ?: 100000.0
            val poll = etPoll.text.toString().toIntOrNull() ?: 15
            val pct = etPct.text.toString().toDoubleOrNull() ?: 0.8
            if (cap < 5000) throw RuntimeException("初始资金至少5000")
            if (pct <= 0 || pct > 1) throw RuntimeException("仓位比例须在0~1")
            st.mode = if (rbLive.isChecked) "live" else "paper"
            st.pollSeconds = poll
            st.positionPct = pct
            if (Math.abs(st.initialCapital() - cap) > 0.01) st.setInitialCapital(cap)
            st.save()
        }
    }

    private fun showTbandDialog(st: com.quantapp.trader.trading.AppStore) {
        val ctx = requireContext()
        val v = LayoutInflater.from(ctx).inflate(R.layout.dialog_settings_tband, null)
        val rg = v.findViewById<RadioGroup>(R.id.rg_tfreq_dialog)
        val rb60 = v.findViewById<RadioButton>(R.id.rb_tfreq_60min_dialog)
        val etTBase = v.findViewById<EditText>(R.id.et_t_base_dialog)
        val etTBand = v.findViewById<EditText>(R.id.et_t_band_dialog)
        if (st.tFrequency == 1) rb60.isChecked = true else v.findViewById<RadioButton>(R.id.rb_tfreq_1min_dialog).isChecked = true
        etTBase.setText(st.tBasePct.toString())
        etTBand.setText(st.tBandPct.toString())
        openSaveDialog(v) {
            st.tFrequency = if (rb60.isChecked) 1 else 0
            st.tBasePct = etTBase.text.toString().toDoubleOrNull()?.let { it.coerceIn(0.05, 1.0) } ?: st.tBasePct
            st.tBandPct = etTBand.text.toString().toDoubleOrNull()?.let { it.coerceIn(0.05, 1.0) } ?: st.tBandPct
            st.save()
        }
    }

    private fun showRiskDialog(st: com.quantapp.trader.trading.AppStore) {
        val ctx = requireContext()
        val v = LayoutInflater.from(ctx).inflate(R.layout.dialog_settings_risk, null)
        fun et(id: Int) = v.findViewById<EditText>(id)
        val fields = listOf(
            R.id.et_stop_loss_dialog to st.stopLossPct,
            R.id.et_take_profit_dialog to st.takeProfitPct,
            R.id.et_max_dd_dialog to st.maxDrawdownPct,
            R.id.et_trail_activate_dialog to st.trailingActivatePct,
            R.id.et_trail_stop_dialog to st.trailingStopPct,
            R.id.et_breakeven_dialog to st.breakEvenPct,
            R.id.et_atr_enabled_dialog to if (st.atrStopEnabled == 0) 0.0 else st.atrStopEnabled.toDouble(),
            R.id.et_atr_mult_dialog to st.atrMultiplier,
            R.id.et_first_buy_dialog to st.firstBuyPct,
            R.id.et_add_pct_dialog to st.addPositionPct,
            R.id.et_add_thr_dialog to st.addThresholdPct
        )
        fields.forEach { (id, val_) -> et(id).setText(fmtPct(val_)) }
        openSaveDialog(v) {
            st.stopLossPct = et(R.id.et_stop_loss_dialog).text.toString().toDoubleOrNull() ?: 0.0
            st.takeProfitPct = et(R.id.et_take_profit_dialog).text.toString().toDoubleOrNull() ?: 0.0
            st.maxDrawdownPct = et(R.id.et_max_dd_dialog).text.toString().toDoubleOrNull() ?: 0.0
            st.trailingActivatePct = et(R.id.et_trail_activate_dialog).text.toString().toDoubleOrNull() ?: 0.0
            st.trailingStopPct = et(R.id.et_trail_stop_dialog).text.toString().toDoubleOrNull() ?: 0.0
            st.breakEvenPct = et(R.id.et_breakeven_dialog).text.toString().toDoubleOrNull() ?: 0.0
            st.atrStopEnabled = et(R.id.et_atr_enabled_dialog).text.toString().toIntOrNull()?.coerceIn(0, 1) ?: 0
            st.atrMultiplier = et(R.id.et_atr_mult_dialog).text.toString().toDoubleOrNull()?.let { it.coerceIn(0.1, 10.0) } ?: 0.0
            st.firstBuyPct = et(R.id.et_first_buy_dialog).text.toString().toDoubleOrNull()?.let { it.coerceIn(0.1, 1.0) } ?: 0.0
            st.addPositionPct = et(R.id.et_add_pct_dialog).text.toString().toDoubleOrNull()?.let { it.coerceIn(0.0, 1.0) } ?: 0.0
            st.addThresholdPct = et(R.id.et_add_thr_dialog).text.toString().toDoubleOrNull() ?: 0.0
            st.save()
        }
    }

    private fun showAlertDialog() {
        val ctx = requireContext()
        val st = App.appStore
        val v = LayoutInflater.from(ctx).inflate(R.layout.dialog_settings_alert, null)
        val etSymbol = v.findViewById<EditText>(R.id.et_alert_symbol_dialog)
        val etPrice = v.findViewById<EditText>(R.id.et_alert_price_dialog)
        val spDir = v.findViewById<Spinner>(R.id.sp_alert_dir_dialog)
        val btnAdd = v.findViewById<Button>(R.id.btn_add_alert_dialog)
        val tvAlerts = v.findViewById<TextView>(R.id.tv_alerts_dialog)
        spDir.adapter = ArrayAdapter(ctx,
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
        tvAlerts.setOnClickListener {
            val list = st.alerts()
            if (list.isEmpty()) return@setOnClickListener
            AlertDialog.Builder(ctx)
                .setTitle("删除到价提醒")
                .setItems(list.map { "${it.symbol} ${String.format("%.2f", it.target)} ${if (it.above) "上穿" else "跌破"}" }.toTypedArray()) { _, which ->
                    st.removeAlert(list[which]); refreshAlerts()
                }
                .setNeutralButton("取消", null)
                .show()
        }
        tvAlerts.setOnLongClickListener {
            val list = st.alerts()
            if (list.isNotEmpty()) st.toggleAlert(list.last())
            refreshAlerts(); true
        }
        btnAdd.setOnClickListener {
            val sym = etSymbol.text.toString().trim().uppercase()
            val price = etPrice.text.toString().toDoubleOrNull()
            if (sym.isEmpty()) { Toast.makeText(ctx, "请输入股票代码", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            if (price == null || price <= 0) { Toast.makeText(ctx, "请输入有效目标价", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            st.addOrUpdateAlert(PriceAlert(sym, "", price, above = spDir.selectedItemPosition == 0))
            etSymbol.text.clear(); etPrice.text.clear()
            refreshAlerts()
            Toast.makeText(ctx, "已添加 $sym 到价提醒", Toast.LENGTH_SHORT).show()
        }
        refreshAlerts()
        openSaveDialog(v, saveText = "完成") { }
    }

    private fun showAiDialog(st: com.quantapp.trader.trading.AppStore) {
        val ctx = requireContext()
        val v = LayoutInflater.from(ctx).inflate(R.layout.dialog_settings_ai, null)
        val spProvider = v.findViewById<Spinner>(R.id.sp_ai_provider_dialog)
        val etKey = v.findViewById<EditText>(R.id.et_ai_key_dialog)
        val etModel = v.findViewById<EditText>(R.id.et_ai_model_dialog)
        val btnTest = v.findViewById<Button>(R.id.btn_ai_test_dialog)
        val btnAiSave = v.findViewById<Button>(R.id.btn_ai_save_dialog)
        val tvStatus = v.findViewById<TextView>(R.id.tv_ai_status_dialog)
        val aiProviders = LlmClient.providers
        spProvider.adapter = ArrayAdapter(ctx,
            android.R.layout.simple_spinner_item, aiProviders.map { it.name })
            .apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        var currentProviderId = st.aiProvider
        spProvider.setSelection(aiProviders.indexOfFirst { it.id == st.aiProvider }.coerceAtLeast(0))
        etKey.setText(st.aiKey)
        etModel.setText(st.aiModel)
        fun defaultModelFor(id: String) = aiProviders.firstOrNull { it.id == id }?.defaultModel ?: "deepseek-chat"
        spProvider.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, pos: Int, id: Long) {
                val newId = aiProviders[pos].id
                if (newId != currentProviderId) {
                    val cur = etModel.text.toString().trim()
                    if (cur.isEmpty() || cur == defaultModelFor(currentProviderId)) etModel.setText(defaultModelFor(newId))
                    currentProviderId = newId
                }
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
        fun statusOk(ok: Boolean) = android.graphics.Color.parseColor(if (ok) "#2E7D32" else "#C62828")
        btnAiSave.setOnClickListener {
            val key = etKey.text.toString().trim()
            if (key.isEmpty()) { tvStatus.text = "API Key 不能为空，请先在对应平台控制台获取"; tvStatus.setTextColor(statusOk(false)); return@setOnClickListener }
            val pid = aiProviders[spProvider.selectedItemPosition.coerceIn(0, aiProviders.size - 1)].id
            st.aiProvider = pid
            st.aiKey = key
            st.aiModel = etModel.text.toString().trim().ifEmpty { defaultModelFor(pid) }
            tvStatus.text = "已保存：${aiProviders[spProvider.selectedItemPosition].name} / ${st.aiModel}"
            tvStatus.setTextColor(statusOk(true))
        }
        btnTest.setOnClickListener { b ->
            val key = etKey.text.toString().trim()
            if (key.isEmpty()) { tvStatus.text = "请先填写 API Key"; tvStatus.setTextColor(statusOk(false)); return@setOnClickListener }
            val pid = aiProviders[spProvider.selectedItemPosition.coerceIn(0, aiProviders.size - 1)].id
            val model = etModel.text.toString().trim().ifEmpty { defaultModelFor(pid) }
            b.isEnabled = false
            btnTest.text = "测试中..."
            tvStatus.text = "正在请求 $model ..."
            tvStatus.setTextColor(android.graphics.Color.parseColor("#CCB400"))
            scope.launch(Dispatchers.IO) {
                val ok = runCatching { LlmClient.chat(pid, key, model, "你是A股量化助手", "请回复：连接正常，模型可用。") }
                withContext(Dispatchers.Main) {
                    ok.onSuccess { rep ->
                        tvStatus.text = "连接成功：${rep.take(80)}"; tvStatus.setTextColor(statusOk(true))
                    }.onFailure { e ->
                        tvStatus.text = "连接失败：${e.message}"; tvStatus.setTextColor(statusOk(false))
                    }
                    b.isEnabled = true; btnTest.text = "测试连接"
                }
            }
        }
        openSaveDialog(v, saveText = "完成") { }
    }

    /**
     * 统一的二级设置弹窗：内容（自带头部）+ 保存/取消，圆角卡片容器。
     * width 固定为约屏宽 88%，避免系统默认宽度在平板/大屏上过宽。
     * （colorPrimary 在本应用浅色主题下接近白色，默认按钮文字几乎不可见，故修正按钮文字颜色。）
     */
    private fun openSaveDialog(view: View, saveText: String = "保存", onSave: () -> Unit) {
        val ctx = requireContext()
        AlertDialog.Builder(ctx)
            .setView(view)
            .setPositiveButton(saveText, null)
            .setNegativeButton("取消", null)
            .create().apply {
                window?.setBackgroundDrawableResource(R.drawable.bg_dialog)
                setOnShowListener {
                    getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(ContextCompat.getColor(ctx, R.color.text_primary))
                    getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
                    getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        try {
                            onSave()
                            dismiss()
                        } catch (e: Exception) {
                            Toast.makeText(ctx, "保存失败：${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                show()
            }
    }

    // =========================== 版本更新 ===========================

    private fun showUpToDate(remoteVersion: String) {
        val ctx = requireContext()
        showThemedDialog(AlertDialog.Builder(ctx)
            .setTitle("已是最新版本")
            .setMessage("当前版本：v${BuildConfig.VERSION_NAME}\n最新版本：v$remoteVersion\n无需更新。")
            .setPositiveButton("确定", null))
    }

    /** 截取更新日志中“最新版本（第一段 ## v...）”的内容，避免历史版本堆叠导致提示过长。 */
    private fun latestChangelog(raw: String): String {
        if (raw.isBlank()) return "暂无更新说明"
        val start = raw.indexOf("## v")
        if (start == -1) return raw
        val next = raw.indexOf("## ", start + 2)
        return if (next != -1) raw.substring(start, next).trim()
        else raw.substring(start).trim()
    }

    private fun showUpdateDialog(info: com.quantapp.trader.update.UpdateInfo, remoteVersion: String, current: String) {
        val ctx = requireContext()
        showThemedDialog(AlertDialog.Builder(ctx)
            .setTitle("检测到新版本 v$remoteVersion")
            .setMessage("当前版本：v$current\n最新版本：v$remoteVersion\n\n更新内容：\n${latestChangelog(info.changelog)}")
            .setPositiveButton("立即升级") { _, _ -> startInAppDownload(info.apkUrl) }
            .setNegativeButton("稍后", null))
    }

    /** 无版本号信息的普通下载/飞书链接：同样提供应用内升级入口。 */
    private fun showDownloadDialog(link: String) {
        val ctx = requireContext()
        showThemedDialog(AlertDialog.Builder(ctx)
            .setTitle("在线升级")
            .setMessage("当前版本：v${BuildConfig.VERSION_NAME}\n点击“立即下载更新”，将跳转下载最新安装包。")
            .setPositiveButton("立即下载更新") { _, _ -> startInAppDownload(link) }
            .setNegativeButton("取消", null))
    }

    /** 弹出并统一修正弹窗按钮文字颜色。 */
    private fun showThemedDialog(builder: AlertDialog.Builder) {
        builder.create().apply {
            setOnShowListener {
                getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(
                    ContextCompat.getColor(requireContext(), R.color.text_primary))
                getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(
                    ContextCompat.getColor(requireContext(), R.color.text_secondary))
            }
            show()
        }
    }

    /**
     * 发起下载。优先用系统浏览器打开 APK 直链；仅当设备没有可用浏览器时才退化为系统 DownloadManager。
     */
    private fun startInAppDownload(url: String) {
        val ctx = requireContext()
        if (url.isBlank()) { Toast.makeText(ctx, "未提供下载链接", Toast.LENGTH_SHORT).show(); return }
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(intent)
            Toast.makeText(ctx, "正在使用浏览器下载更新…", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            DownloadHelper.start(ctx, url)
        }
    }

    private fun updateHint(live: Boolean, tv: TextView) {
        tv.text = if (live)
            "实盘信号模式：引擎只输出买卖信号并POST到网关，不直接成交，需配置下方网关URL。"
        else "模拟盘模式：使用虚拟资金按真实行情自动成交。"
    }

    private fun fmtPct(v: Double): String = if (v == 0.0) "" else v.toString()

    /** 在后台探测实盘网关连通性，并把结果状态显示出来。 */
    private fun probeGateway(tv: TextView) {
        val gw = App.appStore.liveGateway.trim()
        if (gw.isEmpty()) { tv.text = "网关：未配置，实盘模式不会发送信号"; return }
        tv.text = "网关检测中..."
        tv.setTextColor(android.graphics.Color.parseColor("#CCB400"))
        scope.launch(Dispatchers.IO) {
            val ok = com.quantapp.trader.trading.TradingEngine.pingGateway()
            kotlinx.coroutines.withContext(Dispatchers.Main) {
                if (ok) {
                    tv.text = "网关：连接正常"; tv.setTextColor(android.graphics.Color.parseColor("#2E7D32"))
                } else {
                    tv.text = "网关：连接失败（${com.quantapp.trader.trading.TradingEngine.gatewayLastError}），将自动重试，请检查URL与网关服务"
                    tv.setTextColor(android.graphics.Color.parseColor("#C62828"))
                }
            }
        }
    }
}