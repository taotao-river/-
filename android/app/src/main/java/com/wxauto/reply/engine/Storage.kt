package com.wxauto.reply.engine

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * 配置与状态的落盘。用 SharedPreferences + JSON，不引任何第三方库——
 * 这个 App 权限很大（能读所有通知），依赖越少越容易被审查。
 */
object Storage {

    private const val PREFS = "wxauto_engine"

    private const val KEY_CONFIG = "config_json"
    private const val KEY_STATE = "state_json"

    fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ------------------------------------------------------------------ 配置

    fun loadConfig(context: Context): EngineConfig {
        val raw = prefs(context).getString(KEY_CONFIG, null)
            ?: return defaultConfig()
        return runCatching { parseConfig(JSONObject(raw)) }.getOrElse {
            // 配置损坏时回到默认值而不是崩溃；总开关默认是关的，所以安全
            defaultConfig()
        }
    }

    fun saveConfig(context: Context, config: EngineConfig) {
        prefs(context).edit().putString(KEY_CONFIG, serializeConfig(config).toString()).apply()
    }

    /** 只翻总开关，不动其他配置。给快捷开关用。 */
    fun setEnabled(context: Context, enabled: Boolean) {
        saveConfig(context, loadConfig(context).copy(enabled = enabled))
    }

    fun defaultConfig(): EngineConfig = EngineConfig(
        // 默认关闭：装完不会立刻开始替用户发消息，
        // 必须由用户主动打开开关。
        enabled = false,
        signature = "（自动回复）",
        activeFromMinute = -1,
        activeToMinute = -1,
        replyToPrivate = true,
        groupPolicy = GroupPolicy.NEVER,   // 群消息默认全关，避免刷屏
        cooldownSeconds = 1800,
        maxPerChatPerDay = 5,
        maxPerHour = 30,
        minDelaySeconds = 3,
        maxDelaySeconds = 12,
        rules = listOf(
            Rule(
                name = "问在不在",
                keywords = listOf("在吗", "在么", "在不在", "忙吗"),
                replies = listOf(
                    "在的，我这会儿有点事，稍后回你",
                    "在，手上忙着，等下详细说",
                ),
            ),
        ),
        fallbackText = "我现在不方便，看到会尽快回你",
    )

    private fun serializeConfig(c: EngineConfig): JSONObject = JSONObject().apply {
        put("enabled", c.enabled)
        put("signature", c.signature)
        put("activeFromMinute", c.activeFromMinute)
        put("activeToMinute", c.activeToMinute)
        put("replyToPrivate", c.replyToPrivate)
        put("groupPolicy", c.groupPolicy.name)
        put("allowContacts", JSONArray(c.allowContacts))
        put("blockContacts", JSONArray(c.blockContacts))
        put("blockKeywords", JSONArray(c.blockKeywords))
        put("cooldownSeconds", c.cooldownSeconds)
        put("maxPerChatPerDay", c.maxPerChatPerDay)
        put("maxPerHour", c.maxPerHour)
        put("minDelaySeconds", c.minDelaySeconds)
        put("maxDelaySeconds", c.maxDelaySeconds)
        put("fallbackText", c.fallbackText)
        put("rules", JSONArray().apply {
            c.rules.forEach { r ->
                put(JSONObject().apply {
                    put("name", r.name)
                    put("keywords", JSONArray(r.keywords))
                    put("pattern", r.pattern ?: "")
                    put("replies", JSONArray(r.replies))
                })
            }
        })
    }

    private fun parseConfig(o: JSONObject): EngineConfig {
        val fallback = defaultConfig()
        return EngineConfig(
            enabled = o.optBoolean("enabled", false),
            signature = o.optString("signature", fallback.signature),
            activeFromMinute = o.optInt("activeFromMinute", -1),
            activeToMinute = o.optInt("activeToMinute", -1),
            replyToPrivate = o.optBoolean("replyToPrivate", true),
            groupPolicy = GroupPolicy.from(o.optString("groupPolicy")),
            allowContacts = o.optJSONArray("allowContacts").toStringList(),
            blockContacts = o.optJSONArray("blockContacts").toStringList(),
            blockKeywords = o.optJSONArray("blockKeywords").toStringList(),
            cooldownSeconds = o.optInt("cooldownSeconds", fallback.cooldownSeconds),
            maxPerChatPerDay = o.optInt("maxPerChatPerDay", fallback.maxPerChatPerDay),
            maxPerHour = o.optInt("maxPerHour", fallback.maxPerHour),
            minDelaySeconds = o.optInt("minDelaySeconds", fallback.minDelaySeconds),
            maxDelaySeconds = o.optInt("maxDelaySeconds", fallback.maxDelaySeconds),
            fallbackText = o.optString("fallbackText", fallback.fallbackText),
            rules = o.optJSONArray("rules").let { arr ->
                if (arr == null) fallback.rules
                else (0 until arr.length()).mapNotNull { i ->
                    arr.optJSONObject(i)?.let { r ->
                        Rule(
                            name = r.optString("name", "规则"),
                            keywords = r.optJSONArray("keywords").toStringList(),
                            pattern = r.optString("pattern").takeIf { it.isNotBlank() },
                            replies = r.optJSONArray("replies").toStringList(),
                        )
                    }
                }
            },
        )
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { optString(it).takeIf { s -> s.isNotBlank() } }
    }

    // ------------------------------------------------------------------ 状态

    fun stateStore(context: Context): EngineStateStore = PrefsStateStore(prefs(context))

    private class PrefsStateStore(private val prefs: SharedPreferences) : EngineStateStore {
        private val last = HashMap<String, Long>()
        private val perChat = HashMap<String, List<Long>>()
        private var recent: List<Long> = emptyList()
        private val rotation = HashMap<String, Int>()

        init {
            runCatching {
                val o = JSONObject(prefs.getString(KEY_STATE, "{}") ?: "{}")
                o.optJSONObject("last")?.let { j ->
                    j.keys().forEach { k -> last[k] = j.optLong(k) }
                }
                o.optJSONObject("perChat")?.let { j ->
                    j.keys().forEach { k -> perChat[k] = j.optJSONArray(k).toLongList() }
                }
                recent = o.optJSONArray("recent").toLongList()
                o.optJSONObject("rotation")?.let { j ->
                    j.keys().forEach { k -> rotation[k] = j.optInt(k) }
                }
            }  // 状态损坏就从空开始，代价只是冷却计数清零
        }

        override fun lastReplyAt(identity: String) = last[identity]
        override fun setLastReplyAt(identity: String, at: Long) { last[identity] = at }
        override fun chatReplyTimes(identity: String) = perChat[identity] ?: emptyList()
        override fun setChatReplyTimes(identity: String, times: List<Long>) {
            perChat[identity] = times
        }
        override fun recentReplyTimes() = recent
        override fun setRecentReplyTimes(times: List<Long>) { recent = times }
        override fun rotationIndex(key: String) = rotation[key] ?: -1
        override fun setRotationIndex(key: String, index: Int) { rotation[key] = index }

        override fun flush() {
            val o = JSONObject().apply {
                put("last", JSONObject().apply { last.forEach { (k, v) -> put(k, v) } })
                put("perChat", JSONObject().apply {
                    perChat.forEach { (k, v) -> put(k, JSONArray().apply { v.forEach { put(it) } }) }
                })
                put("recent", JSONArray().apply { recent.forEach { put(it) } })
                put("rotation", JSONObject().apply { rotation.forEach { (k, v) -> put(k, v) } })
            }
            prefs.edit().putString(KEY_STATE, o.toString()).apply()
        }

        private fun JSONArray?.toLongList(): List<Long> {
            if (this == null) return emptyList()
            return (0 until length()).map { optLong(it) }
        }
    }
}
