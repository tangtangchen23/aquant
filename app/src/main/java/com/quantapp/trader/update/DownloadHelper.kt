package com.quantapp.trader.update

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Environment
import android.widget.Toast
import androidx.core.content.ContextCompat
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 应用内下载 + 安装安装包（APK）。
 * 使用系统 DownloadManager 后台下载并在通知栏显示进度；
 * 下载完成后自动拉起系统安装界面，无需跳转浏览器。
 */
object DownloadHelper {

    /**
     * 国内可用的 GitHub 加速镜像前缀（追加在原始 github 链接之前）。
     * 当设备直连 github.com 解析失败/缓慢时，退化为这些镜像回退下载。
     * 说明：此类公共镜像偶有变更，若失效可将区间往后移除或替换为更新的镜像。
     */
    private val ACCELERATORS = listOf(
        "https://ghproxy.net/",
        "https://gh-proxy.com/",
        "https://ghfast.top/"
    )

    private var lastId: Long = -1
    private var receiver: BroadcastReceiver? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** 开始下载并注入完成后自动安装的接收器（仅首次注册一次）。 */
    fun start(context: Context, url: String) {
        val ctx = context.applicationContext
        scope.launch {
            // 网络请求放到 IO 线程，避免主线程 NetworkOnMainThreadException
            val downloadUrl = withContext(Dispatchers.IO) { resolveBestUrl(url) }
            val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val req = DownloadManager.Request(Uri.parse(downloadUrl))
                .setTitle("AI量化·升级")
                .setDescription("正在下载最新版本，请稍候…")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setMimeType("application/vnd.android.package-archive")
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "aquant-upgrade.apk")
                .setAllowedOverMetered(true)
            try {
                lastId = dm.enqueue(req)
                if (receiver == null) {
                    receiver = object : BroadcastReceiver() {
                        override fun onReceive(c: Context, i: Intent) {
                            val id = i.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
                            if (id != lastId) return
                            install(c, dm, id)
                        }
                    }
                    // Android 13+ 必须显式声明 exported 标志，否则抛 SecurityException
                    ContextCompat.registerReceiver(
                        ctx, receiver,
                        IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
                        ContextCompat.RECEIVER_NOT_EXPORTED
                    )
                }
                Toast.makeText(ctx, "已开始下载，请留意通知栏进度", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(ctx, "发起下载失败：${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * 选择一条可用的下载直链（返回给 DownloadManager 的最终 CDN 地址）。
     *
     * 顺序：
     * 1. 直接跟随原链接(通常 github.com 的 /releases/latest/download/xxx.apk)的 302，
     *    解析出对象存储 CDN 直链（如 releases-assets.githubusercontent.com）。
     * 2. 若 github.com 直连超时/失败，依次尝试国内可访问的 GitHub 加速镜像，
     *    由镜像在境外拉取后回送给用户，避免设备直连被劣化的 github 域名。
     * 3. 全部失败则退回原链接，交由系统 DownloadManager 兜底。
     */
    fun resolveBestUrl(link: String): String {
        val direct = followRedirects(link)
        if (isUsable(direct)) return direct!!

        for (mirror in ACCELERATORS) {
            val candidate = followRedirects(mirror + link)
            if (isUsable(candidate)) return candidate!!
        }
        return link
    }

    /** 展开 302 跳转，拿到跳转后的最终稳定地址。 */
    private fun followRedirects(link: String): String? {
        return try {
            val conn = URL(link).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "AQuantUpdater/1.2")
            conn.connectTimeout = 10000
            conn.readTimeout = 20000
            val code = conn.responseCode
            val finalUrl = conn.url.toString()
            // 立刻丢弃响应体，避免把整个 APK 在这里下载一遍
            try { conn.inputStream.close() } catch (_: Exception) {}
            conn.disconnect()
            if (code in 200..399) finalUrl else null
        } catch (e: Exception) {
            null
        }
    }

    private fun isUsable(url: String?): Boolean = !url.isNullOrBlank() && url.startsWith("http")

    private fun install(ctx: Context, dm: DownloadManager, id: Long) {
        try {
            val uri: Uri? = try {
                dm.getUriForDownloadedFile(id)
            } catch (e: Exception) {
                Uri.fromFile(java.io.File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "aquant-upgrade.apk"))
            }
            if (uri == null) {
                Toast.makeText(ctx, "下载完成，但未找到安装包文件", Toast.LENGTH_LONG).show()
                return
            }
            val intent = Intent(Intent.ACTION_VIEW)
            intent.setDataAndType(uri, "application/vnd.android.package-archive")
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            ctx.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(ctx, "下载完成，自动安装失败：${e.message}（请到下载目录手动安装）", Toast.LENGTH_LONG).show()
        }
    }
}