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
        // 优先请求 GitHub API 的 raw-media 端点（无 CDN 缓存、始终返回最新内容）。
        // raw.githubusercontent.com 对该路径存在顽固边缘缓存，会长期返回旧版清单，
        // 导致“检查更新永远报已是最新”，这里统一将 raw 地址实时地映射到 API 读取。
        val freshUrl = toFreshUrl(link) ?: link
        try {
            fetch(freshUrl, link)
        } catch (e: Exception) {
            // API 被匿名限流(403)等场景：回退到原始 raw 地址再试一次
            if (freshUrl != link) {
                try { return@withContext fetch(link, link) } catch (e2: Exception) { /* 继续走兜底 */ }
            }
            // 仍无法作为文档获取时，退化为直接下载链接（版本号从链接字符串猜测）
            UpdateInfo(
                versionName = guessVersion(link),
                versionCode = null,
                apkUrl = link,
                changelog = ""
            )
        }
    }

    /** 拉取并解析。isSuccessful 失败即抛异常，便于上层回退。 */
    private suspend fun fetch(url: String, displayLink: String): UpdateInfo {
        val req = okhttp3.Request.Builder().url(url)
            .header("User-Agent", "AQuantUpdater/1.2")
            .header("Accept", "application/vnd.github.raw") // 对 API 返回原内容；对 raw 地址无害
            .build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string() ?: ""
            if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}")
            return parse(body, displayLink)
        }
    }

    /**
     * 把 `raw.githubusercontent.com/{owner}/{repo}/{ref}/{path}` 实时映射为
     * `api.github.com/repos/{owner}/{repo}/contents/{path}?ref={ref}`，
     * 借助 API 绕开 raw CDN 的陈旧缓存。非 raw 地址原样返回。
     */
    private fun toFreshUrl(raw: String): String? {
        val m = Regex("raw.githubusercontent\\.com/([^/]+)/([^/]+)/([^/]+)/(.+)").find(raw) ?: return null
        val (owner, repo, ref, path) = m.destructured
        return "https://api.github.com/repos/$owner/$repo/contents/$path?ref=$ref"
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