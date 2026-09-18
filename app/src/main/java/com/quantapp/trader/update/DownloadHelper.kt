package com.quantapp.trader.update

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Environment
import android.widget.Toast

/**
 * 应用内下载 + 安装安装包（APK）。
 * 使用系统 DownloadManager 后台下载并在通知栏显示进度；
 * 下载完成后自动拉起系统安装界面，无需跳转浏览器。
 */
object DownloadHelper {

    private var lastId: Long = -1
    private var receiver: BroadcastReceiver? = null

    /** 开始下载并注入完成后自动安装的接收器（仅首次注册一次）。 */
    fun start(context: Context, url: String) {
        val ctx = context.applicationContext
        val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val req = DownloadManager.Request(Uri.parse(url))
            .setTitle("A股量化机器人·升级")
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
                ctx.registerReceiver(receiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE))
            }
            Toast.makeText(ctx, "已开始下载，请留意通知栏进度", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(ctx, "发起下载失败：${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

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