package com.quantapp.trader.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 大模型客户端：兼容 OpenAI 的 chat/completions 协议。
 * 预置两个平台——DeepSeek 与 千问(通义 Qwen)，模型名可在设置里改。
 * 密钥只保存在本机，AI 仅做“解释与建议”，从不接触或修改任何交易。
 */
object LlmClient {

    data class Provider(val id: String, val name: String, val baseUrl: String, val defaultModel: String)

    /** 平台预设。默认模型用官方常用 ID，若你的账号网关用自定义名（如 “DeepSeek V4.1 flash”），在设置里改模型名即可。 */
    val providers = listOf(
        Provider("deepseek", "DeepSeek", "https://api.deepseek.com/v1", "deepseek-chat"),
        Provider("qwen", "千问 Qwen", "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-flash")
    )

    fun providerOf(id: String): Provider = providers.firstOrNull { it.id == id } ?: providers[0]

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(90, TimeUnit.SECONDS)
        .build()

    /** 调用 OpenAI 兼容的 chat/completions，返回纯文本回复。 */
    fun chat(providerId: String, apiKey: String, model: String, system: String, user: String): String {
        val p = providerOf(providerId)
        val body = JSONObject()
            .put("model", model)
            .put("temperature", 0.3)
            .put("max_tokens", 900)
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", system))
                .put(JSONObject().put("role", "user").put("content", user)))
        val req = Request.Builder()
            .url("${p.baseUrl}/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(req).execute().use { resp ->
            val txt = resp.body?.string() ?: ""
            if (resp.code !in 200..299 || txt.isBlank()) {
                throw Exception("模型请求失败(${resp.code})：${trimError(txt)}")
            }
            val obj = JSONObject(txt)
            val choices = obj.optJSONArray("choices")
            val content = choices
                ?.takeIf { it.length() > 0 }
                ?.getJSONObject(0)
                ?.optJSONObject("message")
                ?.optString("content")
                ?.trim()
                ?: throw Exception("模型返回为空或格式异常")
            if (content.isEmpty()) throw Exception("模型返回为空")
            return content
        }
    }

    private fun trimError(txt: String): String {
        val t = txt.take(300)
        return try {
            JSONObject(t).optJSONObject("error")?.optString("message") ?: t
        } catch (e: Exception) { t }
    }
}