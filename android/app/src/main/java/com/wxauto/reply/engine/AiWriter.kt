package com.wxauto.reply.engine

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * 生成回复的接口。两种接法：
 *
 *   1. [OpenAiCompatibleWriter] —— 自己的 key。填一个兼容 OpenAI 格式的
 *      接口地址即可，DeepSeek / 通义千问 / 智谱 / Moonshot 都是这个格式，
 *      国内直连没问题。他自己注册一个 key 就能独立使用，不依赖别人。
 *
 *   2. [RelayWriter] —— 用别人给的地址。对接本仓库的 Python 服务
 *      （server/app.py），key 和人设都在对方那边，他这边只填地址和口令。
 *
 * 两者都是阻塞调用，必须在后台线程执行。任何失败都返回 null，
 * 由引擎退化成「不回复」——宁可漏回，不可乱发。
 */
interface AiWriter {
    fun write(message: Message, config: EngineConfig): String?
}

/** 拼系统提示词。和 Python 版 core/persona.py 保持同一套结构。 */
fun buildSystemPrompt(p: PersonaConfig): String {
    val parts = mutableListOf(
        "你在替我回微信消息。你不是助手，你就是我——用我的身份、我的语气说话。"
    )
    if (p.identity.isNotBlank()) parts += "# 我是谁\n${p.identity}"
    if (p.tone.isNotBlank()) parts += "# 我说话的方式\n${p.tone}"
    if (p.playbook.isNotBlank()) parts += "# 遇到各种情况怎么应对\n${p.playbook}"

    val rules = mutableListOf(
        "回复控制在 ${p.maxChars} 个字以内，微信上没人发长段。",
        "只输出要发出去的那句话本身。不要引号、不要解释、不要写「回复：」这种前缀。",
        "不要承诺具体的金额、时间、地点。拿不准就说等我确认了回你。",
        "不答应任何转账、借钱、代付、帮忙付款的请求。",
        "不要自称 AI、助手、机器人，也不要说自己在自动回复。",
        "不知道的事就说不知道或者等我本人回，不要编。",
    )
    rules += p.boundaries.filter { it.isNotBlank() }
    parts += "# 硬性要求\n" + rules.joinToString("\n") { "- $it" }

    if (p.examples.isNotEmpty()) {
        // 示范放最后：模型对靠近末尾的内容模仿得更紧，而语气正是最需要被模仿的
        parts += "# 我平时是这么回的（照着这个语气）\n" +
            p.examples.joinToString("\n") { "\n对方：${it.them}\n我：${it.me}" }
    }
    return parts.joinToString("\n\n")
}

/** 去掉模型偶尔自带的引号，并在过长时截断——宁可短，也别一眼假。 */
fun sanitize(raw: String, maxChars: Int): String? {
    var text = raw.trim().trim('「', '」', '"', '\'', '“', '”').trim()
    if (text.isEmpty()) return null
    if (maxChars > 0 && text.length > maxChars * 2) {
        text = text.take(maxChars * 2).trimEnd('，', '、', '。', ' ') + "…"
    }
    return text
}

// ---------------------------------------------------------------- 自己的 key

class OpenAiCompatibleWriter(
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String,
    private val memory: ConversationMemory = ConversationMemory(),
) : AiWriter {

    override fun write(message: Message, config: EngineConfig): String? {
        val persona = config.persona
        if (!persona.isConfigured()) {
            Log.w(TAG, "没配人设，跳过——空人设只会生成客服腔")
            return null
        }
        if (apiKey.isBlank() || baseUrl.isBlank()) {
            Log.w(TAG, "没填接口地址或 key")
            return null
        }

        memory.remember(message.chatName, Speaker.THEM, message.text)

        val messages = JSONArray().apply {
            put(JSONObject().put("role", "system").put("content", buildSystemPrompt(persona)))
            // 带上最近几轮上下文：对方说「那明天呢」时，
            // 模型看不到前一句就只能瞎猜，这是「像机器人」的主要来源
            memory.recent(message.chatName).forEach { turn ->
                put(
                    JSONObject()
                        .put("role", if (turn.speaker == Speaker.THEM) "user" else "assistant")
                        .put("content", turn.text)
                )
            }
        }

        val payload = JSONObject().apply {
            put("model", model)
            put("messages", messages)
            put("max_tokens", 300)
            put("stream", false)
        }

        var conn: HttpURLConnection? = null
        return try {
            val url = URL(baseUrl.trimEnd('/') + "/chat/completions")
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = TimeUnit.SECONDS.toMillis(10).toInt()
                readTimeout = TimeUnit.SECONDS.toMillis(30).toInt()
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Authorization", "Bearer $apiKey")
            }
            conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }

            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                val err = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                Log.w(TAG, "接口返回 ${conn.responseCode}：${err.take(200)}")
                return null
            }

            val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val content = JSONObject(body)
                .optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("message")
                ?.optString("content")
                .orEmpty()

            sanitize(content, persona.maxChars)?.also {
                memory.remember(message.chatName, Speaker.ME, it)
            }
        } catch (e: IOException) {
            Log.w(TAG, "网络失败，本条跳过：${e.message}")
            null
        } catch (e: org.json.JSONException) {
            Log.w(TAG, "返回内容不是合法 JSON：${e.message}")
            null
        } finally {
            conn?.disconnect()
        }
    }

    companion object {
        private const val TAG = "OpenAiWriter"

        /** 常见接口的预设，填进设置页当提示用。 */
        val PRESETS = listOf(
            Preset("DeepSeek", "https://api.deepseek.com/v1", "deepseek-chat"),
            Preset("通义千问", "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-plus"),
            Preset("智谱 GLM", "https://open.bigmodel.cn/api/paas/v4", "glm-4-flash"),
            Preset("Moonshot", "https://api.moonshot.cn/v1", "moonshot-v1-8k"),
        )
    }

    data class Preset(val name: String, val baseUrl: String, val model: String)
}

// ---------------------------------------------------------------- 用别人的地址

/**
 * 对接本仓库的 Python 服务。人设、key、规则全在对方那边，
 * 这边只把消息发过去、拿回该说的话。
 *
 * 注意：本机的敏感词、限流判断已经在引擎里做过了，这里是第二道；
 * 服务端也会再判一次，重复判断只会更保守，无害。
 */
class RelayWriter(
    private val baseUrl: String,
    private val token: String,
) : AiWriter {

    override fun write(message: Message, config: EngineConfig): String? {
        if (baseUrl.isBlank()) return null

        val payload = JSONObject().apply {
            put("chat_id", "android:${message.chatName}")
            put("chat_name", message.chatName)
            put("text", message.text)
            put("sender_name", message.senderName)
            put("is_group", message.isGroup)
            put("mentioned_me", message.mentionedMe)
            put("platform", "android")
        }

        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(baseUrl.trimEnd('/') + "/reply").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = TimeUnit.SECONDS.toMillis(5).toInt()
                readTimeout = TimeUnit.SECONDS.toMillis(30).toInt()
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Authorization", "Bearer $token")
            }
            conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }

            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                Log.w(TAG, "对方服务返回 ${conn.responseCode}")
                return null
            }

            val json = JSONObject(conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
            if (!json.optBoolean("should_reply", false)) {
                Log.i(TAG, "对方服务判断不回复：${json.optString("reason")}")
                return null
            }
            json.optString("text").takeIf { it.isNotBlank() }
        } catch (e: IOException) {
            Log.w(TAG, "连不上对方服务，本条跳过：${e.message}")
            null
        } catch (e: org.json.JSONException) {
            Log.w(TAG, "对方服务返回了非法 JSON：${e.message}")
            null
        } finally {
            conn?.disconnect()
        }
    }

    companion object {
        private const val TAG = "RelayWriter"
    }
}
