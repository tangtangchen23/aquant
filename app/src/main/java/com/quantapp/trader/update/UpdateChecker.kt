package com.quantapp.trader.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** 远端更新信息。当无法从链接解析出版本号（如直接指向 APK/飞书分享链接）时，versionName/versionCode 为 null。 */
data class UpdateInfo(
    val versionName: String?,
    val versionCode: Int?,
    val apkUrl: String,
    val changelog: String
)

const val VERSION_PATTERN = """v?(\d+\.\d+(\.\d+)?)(?:-|\s|\.apk|$|_)"""

/**
 * 内置版本清单地址（仓库根目录 latest.json）。
 * 使用 Raw 托管避免 GitHub API 的匿名限流，设备能稳定读取。
 */
const val DEFAULT_UPDATE_MANIFEST_URL =
    "https://raw.githubusercontent.com/tangtangchen23/aquant/main/latest.json"

/**
 * 检查新版本。兼容两种来源：
 * 1. JSON 版本清单：`{ "latest": { "version":"1.1.0", "versionCode":2, "apkUrl":"...", "changelog":"..." } }`
 *    可提供精确版本号用于自动比对。
 * 2. 直接指向安装包（APK 直链 / 飞书分享链接等）：解析不出版本号时，
 *    返回 apkUrl=该链接、版本字段为 null，由界面提供“打开链接/下载”入口。
 */
object UpdateChecker {

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun check(link: String): UpdateInfo = withContext(Dispatchers.IO) {
        if (link.isBlank()) throw RuntimeException("链接为空")
        val req = okhttp3.Request.Builder().url(link)
            .header("User-Agent", "AQuantUpdater/1.2")
            .build()
        try {
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string() ?: ""
                if (!resp.isSuccessful && body.isBlank()) throw RuntimeException("HTTP ${resp.code}")
                parse(body, link)
            }
        } catch (e: Exception) {
            // 无法作为文档获取时，退化为直接下载链接（版本号从链接字符串猜测）
            UpdateInfo(
                versionName = guessVersion(link),
                versionCode = null,
                apkUrl = link,
                changelog = ""
            )
        }
    }

    private fun parse(body: String, link: String): UpdateInfo {
        val latest = try {
            val o = JSONObject(body)
            o.optJSONObject("latest") ?: JSONObject()
        } catch (e: Exception) {
            JSONObject()
        }
        val apkUrl = latest.optString("apkUrl", "")
        // 有完整版本字段则走 JSON；否则当普通链接处理
        if (latest.has("version") || latest.has("versionCode")) {
            val cl = latest.optString("changelog", "")
            val cl2 = try {
                val arr = latest.optJSONArray("changes") ?: JSONArray()
                buildString {
                    for (i in 0 until arr.length()) append("· ").append(arr.getString(i)).append("\n")
                }
            } catch (e: Exception) { "" }
            return UpdateInfo(
                versionName = latest.optString("version", "").ifBlank { null },
                versionCode = if (latest.has("versionCode")) latest.optInt("versionCode") else null,
                apkUrl = apkUrl.ifBlank { link },
                changelog = if (cl.isNotEmpty()) cl else cl2
            )
        }
        return UpdateInfo(
            versionName = guessVersion(link),
            versionCode = null,
            apkUrl = link,
            changelog = ""
        )
    }

    /** 从链接/文件名中猜测版本号，如 "xxx-v1.2.0-release.apk" -> 1.2.0。 */
    private fun guessVersion(s: String): String? {
        val m = Regex(VERSION_PATTERN).find(s) ?: return null
        return m.groupValues[1]
    }

    /**
     * 比较两个版本号字符串（如 "1.4.1"、"1.4.1"）。
     * >0 表示 a 更新；==0 表示相同；<0 表示 a 更旧。
     */
    fun compareVersions(a: String, b: String): Int {
        val pa = a.trimStart('v', 'V').split('.', '-', '_').mapNotNull { it.toIntOrNull() }
        val pb = b.trimStart('v', 'V').split('.', '-', '_').mapNotNull { it.toIntOrNull() }
        val n = maxOf(pa.size, pb.size)
        for (i in 0 until n) {
            val x = pa.getOrElse(i) { 0 }
            val y = pb.getOrElse(i) { 0 }
            if (x != y) return x - y
        }
        return 0
    }
}