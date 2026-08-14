package com.wxauto.reply.engine

/**
 * 内嵌规则引擎的数据结构。
 *
 * 这一层刻意不依赖任何 Android API，方便单独做单元测试，
 * 也方便和 Python 版引擎（core/engine.py）保持行为一致。
 */

data class Message(
    val chatId: String,
    val chatName: String,
    val text: String,
    val senderName: String = "",
    val isGroup: Boolean = false,
    val mentionedMe: Boolean = false,
)

data class Decision(
    val shouldReply: Boolean,
    val reason: String,
    val text: String? = null,
    val delayMillis: Long = 0L,
    val ruleName: String? = null,
) {
    companion object {
        fun skip(reason: String) = Decision(shouldReply = false, reason = reason)
    }
}

enum class GroupPolicy {
    NEVER,        // 群消息一律不回
    ONLY_AT_ME,   // 只在被 @ 时回
    ALWAYS;       // 群里任何消息都回（很容易刷屏，慎用）

    companion object {
        fun from(raw: String?): GroupPolicy =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: ONLY_AT_ME
    }
}

/**
 * 一条规则。keywords 命中任意一个即可；pattern 是正则。
 * 两者都为空表示「全部匹配」。
 */
data class Rule(
    val name: String,
    val keywords: List<String> = emptyList(),
    val pattern: String? = null,
    val replies: List<String> = emptyList(),
) {
    /** 正则只编译一次；写错了就当这条规则永不命中，而不是让整个引擎崩掉。 */
    private val regex: Regex? = pattern
        ?.takeIf { it.isNotBlank() }
        ?.let { runCatching { Regex(it) }.getOrNull() }

    fun matches(text: String): Boolean {
        if (keywords.isEmpty() && pattern.isNullOrBlank()) return true
        if (keywords.any { it.isNotBlank() && text.contains(it) }) return true
        return regex?.containsMatchIn(text) == true
    }
}

data class EngineConfig(
    /** 总开关。关掉之后引擎对任何消息都返回「不回复」。 */
    val enabled: Boolean = false,

    val signature: String = "（自动回复）",

    /** 自动回复时段，单位是「距零点的分钟数」。都为 -1 表示全天。 */
    val activeFromMinute: Int = -1,
    val activeToMinute: Int = -1,

    val replyToPrivate: Boolean = true,
    val groupPolicy: GroupPolicy = GroupPolicy.ONLY_AT_ME,

    /** 非空时只对这些人生效。 */
    val allowContacts: List<String> = emptyList(),
    val blockContacts: List<String> = emptyList(),
    val blockKeywords: List<String> = emptyList(),

    val cooldownSeconds: Int = 1800,
    val maxPerChatPerDay: Int = 5,
    val maxPerHour: Int = 30,
    val minDelaySeconds: Int = 3,
    val maxDelaySeconds: Int = 12,

    val rules: List<Rule> = emptyList(),

    /** 所有规则都没命中时回这句；留空表示不回。 */
    val fallbackText: String = "",
)
