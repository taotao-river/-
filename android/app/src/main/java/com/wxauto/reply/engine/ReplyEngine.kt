package com.wxauto.reply.engine

import java.time.LocalTime
import kotlin.random.Random

/**
 * 内嵌规则引擎 —— core/engine.py 的 Kotlin 版。
 *
 * 为什么要有这一份：手机上跑不了 Python 服务，而要求普通用户去装
 * Termux 敲命令是不现实的。把引擎搬进 App，装完 APK 打开开关就能用，
 * 不需要服务器、不需要电脑、不需要局域网。
 *
 * 行为必须和 Python 版保持一致，尤其是**判断顺序**：
 * 安全类判断（敏感词、黑名单）永远排在频率限制之前，
 * 这样即使把冷却调成 0 也不会误回转账类消息。
 *
 * 线程安全：通知回调可能并发进来，decide() 整体加锁。
 */
class ReplyEngine(
    private val store: EngineStateStore,
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: Random = Random.Default,
) {

    companion object {
        private const val DAY_MILLIS = 24 * 60 * 60 * 1000L
        private const val HOUR_MILLIS = 60 * 60 * 1000L

        /**
         * 命中即永不自动回复，不受任何配置影响。
         * 自动回一句「好的」给转账或验证码消息的代价，
         * 远高于漏回一条正常消息。
         */
        val HARD_BLOCK_KEYWORDS = listOf(
            "转账", "红包", "验证码", "银行卡", "身份证",
            "密码", "借钱", "急用钱", "汇款", "付款码",
        )
    }

    @Synchronized
    fun decide(config: EngineConfig, message: Message): Decision {
        val now = clock()
        val text = message.text.trim()

        if (!config.enabled) return Decision.skip("自动回复已关闭")
        if (text.isEmpty()) return Decision.skip("空消息")

        // ---- 安全类判断（永远优先） ----
        HARD_BLOCK_KEYWORDS.firstOrNull { text.contains(it) }?.let {
            return Decision.skip("含敏感词「$it」，交给你本人处理")
        }

        if (config.blockContacts.any { it.isNotBlank() && it == message.chatName }) {
            return Decision.skip("${message.chatName} 在不回复名单里")
        }

        config.blockKeywords.firstOrNull { it.isNotBlank() && text.contains(it) }?.let {
            return Decision.skip("含屏蔽词「$it」")
        }

        val allow = config.allowContacts.filter { it.isNotBlank() }
        if (allow.isNotEmpty() && message.chatName !in allow) {
            return Decision.skip("${message.chatName} 不在指定名单里")
        }

        // ---- 会话类型 ----
        if (message.isGroup) {
            when (config.groupPolicy) {
                GroupPolicy.NEVER -> return Decision.skip("群消息不回")
                GroupPolicy.ONLY_AT_ME ->
                    if (!message.mentionedMe) return Decision.skip("群消息没 @ 我")
                GroupPolicy.ALWAYS -> Unit
            }
        } else if (!config.replyToPrivate) {
            return Decision.skip("私聊不回")
        }

        // ---- 时段 ----
        if (!withinActiveHours(config, now)) {
            return Decision.skip("不在自动回复时段内")
        }

        val identity = identityOf(message)

        // ---- 频率限制 ----
        rateLimitReason(config, identity, now)?.let { return Decision.skip(it) }

        // ---- 命中规则 ----
        for (rule in config.rules) {
            if (rule.replies.none { it.isNotBlank() }) continue
            if (rule.matches(text)) {
                val reply = pickReply(identity, rule)
                return commit(config, identity, reply, "命中规则「${rule.name}」", rule.name, now)
            }
        }

        // ---- 兜底 ----
        if (config.fallbackText.isBlank()) {
            return Decision.skip("没有匹配的规则")
        }
        return commit(config, identity, config.fallbackText, "默认回复", null, now)
    }

    // ------------------------------------------------------------------ 内部

    /**
     * 会话身份。用归一化的会话名而不是通知给的 key：
     * 同一个人的通知 key 可能因为消息类型而变，用名字更稳。
     * 群名后面的成员数会变，不能算进身份。
     */
    private fun identityOf(message: Message): String {
        val name = message.chatName
            .replace(Regex("""[（(]\s*\d+\s*[)）]\s*$"""), "")
            .trim()
            .lowercase()
        return if (message.isGroup) "group:$name" else "private:$name"
    }

    private fun withinActiveHours(config: EngineConfig, now: Long): Boolean {
        val from = config.activeFromMinute
        val to = config.activeToMinute
        if (from < 0 || to < 0) return true

        val nowTime = LocalTime.now()
        val current = nowTime.hour * 60 + nowTime.minute
        // 支持跨零点，例如 22:00-02:00
        return if (from <= to) current in from..to else current >= from || current <= to
    }

    private fun rateLimitReason(config: EngineConfig, identity: String, now: Long): String? {
        val last = store.lastReplyAt(identity)
        if (last != null && now - last < config.cooldownSeconds * 1000L) {
            val remainMinutes = ((config.cooldownSeconds * 1000L - (now - last)) / 60000L) + 1
            return "刚回过，${remainMinutes} 分钟内不再回"
        }

        val today = store.chatReplyTimes(identity).filter { now - it < DAY_MILLIS }
        store.setChatReplyTimes(identity, today)
        if (today.size >= config.maxPerChatPerDay) {
            return "今天已经回过 ${config.maxPerChatPerDay} 条了"
        }

        val recent = store.recentReplyTimes().filter { now - it < HOUR_MILLIS }
        store.setRecentReplyTimes(recent)
        if (recent.size >= config.maxPerHour) {
            return "一小时内回复数已达上限 ${config.maxPerHour} 条"
        }

        return null
    }

    /** 同一会话内轮换文案，避免连着两次一模一样。 */
    private fun pickReply(identity: String, rule: Rule): String {
        val usable = rule.replies.filter { it.isNotBlank() }
        val key = "$identity::${rule.name}"
        val index = store.rotationIndex(key) + 1
        store.setRotationIndex(key, index)
        return usable[index.mod(usable.size)]
    }

    private fun commit(
        config: EngineConfig,
        identity: String,
        rawText: String,
        reason: String,
        ruleName: String?,
        now: Long,
    ): Decision {
        val text = if (config.signature.isNotBlank()) rawText + config.signature else rawText

        store.setLastReplyAt(identity, now)
        store.setChatReplyTimes(identity, store.chatReplyTimes(identity) + now)
        store.setRecentReplyTimes(store.recentReplyTimes() + now)
        store.flush()

        // 秒回是最明显的机器特征，也最容易触发风控
        val minMs = config.minDelaySeconds.coerceAtLeast(0) * 1000L
        val maxMs = config.maxDelaySeconds.coerceAtLeast(config.minDelaySeconds) * 1000L
        val delay = if (maxMs > minMs) random.nextLong(minMs, maxMs) else minMs

        return Decision(
            shouldReply = true,
            reason = reason,
            text = text,
            delayMillis = delay,
            ruleName = ruleName,
        )
    }
}

/**
 * 引擎状态（冷却、配额、文案轮换下标）的存取。
 * 抽成接口是为了让引擎本身不依赖 Android，方便单测。
 */
interface EngineStateStore {
    fun lastReplyAt(identity: String): Long?
    fun setLastReplyAt(identity: String, at: Long)

    fun chatReplyTimes(identity: String): List<Long>
    fun setChatReplyTimes(identity: String, times: List<Long>)

    fun recentReplyTimes(): List<Long>
    fun setRecentReplyTimes(times: List<Long>)

    fun rotationIndex(key: String): Int
    fun setRotationIndex(key: String, index: Int)

    /** 把内存里的改动落盘。 */
    fun flush()
}

/** 纯内存实现，单测用；App 里用 SharedPreferences 版。 */
class InMemoryStateStore : EngineStateStore {
    private val last = HashMap<String, Long>()
    private val perChat = HashMap<String, List<Long>>()
    private var recent: List<Long> = ArrayList()
    private val rotation = HashMap<String, Int>()

    override fun lastReplyAt(identity: String) = last[identity]
    override fun setLastReplyAt(identity: String, at: Long) { last[identity] = at }
    override fun chatReplyTimes(identity: String) = perChat[identity] ?: emptyList()
    override fun setChatReplyTimes(identity: String, times: List<Long>) { perChat[identity] = times }
    override fun recentReplyTimes() = recent
    override fun setRecentReplyTimes(times: List<Long>) { recent = times }
    override fun rotationIndex(key: String) = rotation[key] ?: -1
    override fun setRotationIndex(key: String, index: Int) { rotation[key] = index }
    override fun flush() = Unit
}
