package com.quantapp.trader.trading

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 实盘网关 HTTP 客户端。对接 Windows 端 broker 网关（gateway.py），
 * 负责 ping/account/trade/cancel 四个端点的调用与错误收口。
 *
 * 网关 API 契约（与 gateway.py 一致）：
 *   GET  /ping           健康检查，返回 {ok, broker, account?}
 *   GET  /account        账户快照，返回 {asset, positions[], orders[]}
 *   POST /trade          下单，body: {symbol, side, price, qty?, amount?, reason, broker}
 *   POST /cancel/{id}    撤单
 *   Header X-Secret      网关密钥（可选，留空则不发送）
 */
object LiveGateway {

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .writeTimeout(8, TimeUnit.SECONDS)
        .build()

    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

    /** 网关连通性探测。返回 Pair<ok, message>。 */
    fun ping(): Pair<Boolean, String> {
        val gw = App.appStore.liveGateway.trim().trimEnd('/')
        if (gw.isEmpty()) return false to "未配置网关URL"
        val req = Request.Builder().url("$gw/ping").apply {
            val secret = App.appStore.liveGatewaySecret
            if (secret.isNotEmpty()) header("X-Secret", secret)
        }.get().build()
        return try {
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string()?.takeIf { it.isNotEmpty() } ?: ""
                if (resp.isSuccessful) {
                    val ok = runCatching { JSONObject(body).optBoolean("ok", true) }.getOrDefault(true)
                    val broker = runCatching { JSONObject(body).optString("broker", "") }.getOrDefault("")
                    ok to "网关已连接${broker.takeIf { it.isNotEmpty() }?.let { "（$it）" } ?: ""}"
                } else {
                    false to "HTTP ${resp.code} ${resp.message}${body.takeIf { it.isNotEmpty() }?.let { "：${it.take(80)}" } ?: ""}"
                }
            }
        } catch (e: Exception) {
            false to (e.message ?: "网络错误")
        }
    }

    /** 查询账户快照。失败返回 null + message。 */
    fun account(): Pair<JSONObject?, String> {
        val gw = App.appStore.liveGateway.trim().trimEnd('/')
        if (gw.isEmpty()) return null to "未配置网关URL"
        val req = Request.Builder().url("$gw/account").apply {
            val secret = App.appStore.liveGatewaySecret
            if (secret.isNotEmpty()) header("X-Secret", secret)
        }.get().build()
        return try {
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (resp.isSuccessful) {
                    val json = JSONObject(body)
                    if (!json.optBoolean("ok", true)) {
                        null to json.optString("detail", json.optString("message", "网关返回失败"))
                    } else {
                        json to "OK"
                    }
                } else {
                    null to "HTTP ${resp.code}"
                }
            }
        } catch (e: Exception) {
            null to (e.message ?: "网络错误")
        }
    }

    /** 下单。返回 Result 中包含网关返回的 detail/message。 */
    fun trade(
        symbol: String,
        side: String,
        price: Double,
        qty: Int? = null,
        amount: Double? = null,
        reason: String = ""
    ): TradeResult {
        val gw = App.appStore.liveGateway.trim().trimEnd('/')
        if (gw.isEmpty()) return TradeResult(false, message = "未配置网关URL")
        val body = JSONObject().apply {
            put("symbol", symbol)
            put("side", side)
            put("price", price)
            qty?.let { put("qty", it) }
            amount?.let { put("amount", it) }
            put("reason", reason)
            put("broker", App.appStore.broker)
        }.toString().toRequestBody(JSON_MEDIA)
        val req = Request.Builder().url("$gw/trade").apply {
            val secret = App.appStore.liveGatewaySecret
            if (secret.isNotEmpty()) header("X-Secret", secret)
        }.post(body).build()
        return try {
            client.newCall(req).execute().use { resp ->
                val b = resp.body?.string().orEmpty()
                val json = runCatching { JSONObject(b) }.getOrNull()
                if (resp.isSuccessful && json != null) {
                    TradeResult(
                        ok = json.optBoolean("ok", false),
                        orderId = json.optString("order_id").takeIf { it.isNotEmpty() },
                        message = json.optString("message", "")
                            .ifEmpty { if (json.optBoolean("ok")) "下单成功" else "下单失败" }
                    )
                } else {
                    TradeResult(false, message = "HTTP ${resp.code} ${json?.optString("detail") ?: json?.optString("message") ?: ""}".trim())
                }
            }
        } catch (e: Exception) {
            TradeResult(false, message = e.message ?: "网络错误")
        }
    }

    /** 撤单。 */
    fun cancel(orderId: String): Pair<Boolean, String> {
        val gw = App.appStore.liveGateway.trim().trimEnd('/')
        if (gw.isEmpty()) return false to "未配置网关URL"
        val req = Request.Builder().url("$gw/cancel/$orderId").apply {
            val secret = App.appStore.liveGatewaySecret
            if (secret.isNotEmpty()) header("X-Secret", secret)
        }.post("".toRequestBody(JSON_MEDIA)).build()
        return try {
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (resp.isSuccessful) {
                    val ok = runCatching { JSONObject(body).optBoolean("ok", true) }.getOrDefault(true)
                    val msg = runCatching { JSONObject(body).optString("message", "") }.getOrDefault("")
                    ok to msg.ifEmpty { if (ok) "撤单成功" else "撤单失败" }
                } else false to "HTTP ${resp.code}"
            }
        } catch (e: Exception) {
            false to (e.message ?: "网络错误")
        }
    }
}

data class TradeResult(
    val ok: Boolean,
    val orderId: String? = null,
    val message: String,
    val filledPrice: Double? = null
)
