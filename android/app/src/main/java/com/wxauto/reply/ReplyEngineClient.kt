package com.wxauto.reply

import android.util.Log
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * 和本地规则服务通信。
 *
 * 刻意不引第三方 HTTP 库：这个 App 权限极大（能读所有通知），
 * 依赖越少，审查起来越容易。
 */
class ReplyEngineClient(
    private val baseUrl: String,
    private val token: String,
    /**
     * 这一端驱动的是哪个微信号。限流和去重按账号隔离，不是按平台：
     * 两个不同的号各跑一端就填不同的值（或都留空），同一个号多端登录
     * 则两端填相同的值以避免重复回复。留空时服务端回退成 platform。
     */
    private val account: String = "",
) {

    data class Decision(
        val shouldReply: Boolean,
        val reason: String,
        val text: String?,
        val delayMillis: Long,
    )

    /**
     * 阻塞式请求，必须在后台线程调用。
     * 任何失败都返回「不回复」——宁可漏回，不可乱发。
     */
    fun decide(
        chatId: String,
        chatName: String,
        text: String,
        senderName: String,
        isGroup: Boolean,
        mentionedMe: Boolean,
    ): Decision {
        val payload = JSONObject().apply {
            put("chat_id", chatId)
            put("chat_name", chatName)
            put("text", text)
            put("sender_name", senderName)
            put("is_group", isGroup)
            put("mentioned_me", mentionedMe)
            put("platform", "android")
            put("account", account)
        }

        var conn: HttpURLConnection? = null
        return try {
            conn = (URL("${baseUrl.trimEnd('/')}/reply").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = TimeUnit.SECONDS.toMillis(5).toInt()
                readTimeout = TimeUnit.SECONDS.toMillis(15).toInt()
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Authorization", "Bearer $token")
            }

            conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }

            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                Log.w(TAG, "规则服务返回 ${conn.responseCode}，跳过本条")
                return DENY
            }

            val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val json = JSONObject(body)
            Decision(
                shouldReply = json.optBoolean("should_reply", false),
                reason = json.optString("reason", ""),
                text = json.optString("text").takeIf { it.isNotEmpty() },
                delayMillis = (json.optDouble("delay_seconds", 0.0) * 1000).toLong(),
            )
        } catch (e: IOException) {
            Log.w(TAG, "规则服务不可达，跳过本条: ${e.message}")
            DENY
        } catch (e: org.json.JSONException) {
            Log.w(TAG, "规则服务返回了非法 JSON，跳过本条: ${e.message}")
            DENY
        } finally {
            conn?.disconnect()
        }
    }

    companion object {
        private const val TAG = "ReplyEngineClient"
        private val DENY = Decision(false, "规则服务不可用", null, 0)
    }
}
