package com.quantapp.trader.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.quantapp.trader.BuildConfig
import com.quantapp.trader.R
import com.quantapp.trader.trading.App
import com.quantapp.trader.update.DownloadHelper
import com.quantapp.trader.update.UpdateChecker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class SettingsFragment : Fragment() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val root = inflater.inflate(R.layout.fragment_settings, container, false)
        val rg = root.findViewById<RadioGroup>(R.id.rg_mode)
        val rbPaper = root.findViewById<RadioButton>(R.id.rb_paper)
        val rbLive = root.findViewById<RadioButton>(R.id.rb_live)
        val etCapital = root.findViewById<EditText>(R.id.et_capital)
        val etPoll = root.findViewById<EditText>(R.id.et_poll)
        val etPct = root.findViewById<EditText>(R.id.et_pct)
        val etGateway = root.findViewById<EditText>(R.id.et_gateway)
        val tvModeHint = root.findViewById<TextView>(R.id.tv_mode_hint)
        val tvInfo = root.findViewById<TextView>(R.id.tv_info)
        val tvVersion = root.findViewById<TextView>(R.id.tv_version)
        val btnCheckUpdate = root.findViewById<Button>(R.id.btn_check_update)
        val btnSave = root.findViewById<Button>(R.id.btn_save)

        val st = App.appStore
        if (st.mode == "live") rbLive.isChecked = true else rbPaper.isChecked = true
        etCapital.setText(st.initialCapital().toLong().toString())
        etPoll.setText(st.pollSeconds.toString())
        etPct.setText(st.positionPct.toString())
        etGateway.setText(st.liveGateway)
        updateHint(rbLive.isChecked, tvModeHint)
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
                    val info = UpdateChecker.check(url)
                    val remoteCode = info.versionCode
                    if (remoteCode != null && remoteCode > BuildConfig.VERSION_CODE) {
                        showUpdateDialog(info)
                    } else if (remoteCode != null) {
                        Toast.makeText(requireContext(), "已是最新版本 v${BuildConfig.VERSION_NAME}", Toast.LENGTH_SHORT).show()
                    } else {
                        // 普通下载/飞书链接：无版本号信息，直接提供下载入口
                        showDownloadDialog(url, info.versionName)
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
                // 更换初始资金时重置模拟盘
                if (Math.abs(st.initialCapital() - cap) > 0.01) {
                    st.setInitialCapital(cap)
                    st.paper.reset(cap)
                }
                st.save()
                Toast.makeText(requireContext(), "设置已保存", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "保存失败：${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
        return root
    }

    private fun showUpdateDialog(info: com.quantapp.trader.update.UpdateInfo) {
        val ctx = requireContext()
        val changelog = if (info.changelog.isBlank()) "暂无更新说明" else info.changelog
        AlertDialog.Builder(ctx)
            .setTitle("发现新版本 v${info.versionName}")
            .setMessage("当前版本：v${BuildConfig.VERSION_NAME}\n更新日志：\n$changelog")
            .setPositiveButton("立即升级") { _, _ -> startInAppDownload(info.apkUrl) }
            .setNegativeButton("稍后", null)
            .show()
    }

    /** 无版本号信息的普通下载/飞书链接：同样提供应用内升级入口。 */
    private fun showDownloadDialog(link: String, guessVersion: String?) {
        val ctx = requireContext()
        val versionLine = guessVersion?.let { "链接指向版本：v$it\n" } ?: ""
        AlertDialog.Builder(ctx)
            .setTitle("在线升级")
            .setMessage("当前版本：v${BuildConfig.VERSION_NAME}\n${versionLine}点击“立即升级”，App 将在后台下载最新安装包并自动进入安装。")
            .setPositiveButton("立即升级") { _, _ -> startInAppDownload(link) }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 发起应用内下载。需保证链接为可直接下载的 APK 直链。 */
    private fun startInAppDownload(url: String) {
        val ctx = requireContext()
        if (url.isBlank()) {
            Toast.makeText(ctx, "未提供下载链接", Toast.LENGTH_SHORT).show()
            return
        }
        // Android 13+ 需要通知权限才能显示下载进度
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(requireActivity(),
                arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
        }
        DownloadHelper.start(ctx, url)
    }

    private fun updateHint(live: Boolean, tv: TextView) {
        tv.text = if (live)
            "实盘信号模式：引擎只输出买卖信号并POST到网关，不直接成交，需配置下方网关URL。"
        else "模拟盘模式：使用虚拟资金按真实行情自动成交。"
    }
}