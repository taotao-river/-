package com.wxauto.reply.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 开场问答的单测，对应 core/tests/test_wizard.py。
 *
 * 重点不是「生成的话好不好听」——那没法测——而是：
 *   1. 不管用户怎么答（包括根本没答），生成的东西都能直接喂给引擎
 *   2. 有几条底线不受回答影响，永远在
 *   3. 答案确实影响输出，而不是摆设
 */
class SetupWizardTest {

    private fun answers(vararg overrides: Pair<String, List<String>>): Map<String, List<String>> {
        val base = mutableMapOf(
            "who" to listOf("work", "friend"),
            "busy" to listOf("hands"),
            "style" to listOf("casual"),
            "length" to listOf("varies"),
            "emoji" to listOf("none"),
            "appointment" to listOf("hold"),
            "progress" to listOf("rough"),
            "stranger" to listOf("polite"),
            "greeting" to listOf(""),
            "never" to listOf(""),
        )
        overrides.forEach { (k, v) -> base[k] = v }
        return base
    }

    private val styles = listOf("casual", "polite", "warm", "brief")

    // ------------------------------------------------------------ 问题本身

    @Test
    fun questionIdsAreUnique() {
        val ids = SetupWizard.QUESTIONS.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun optionIdsAreUniqueWithinEachQuestion() {
        SetupWizard.QUESTIONS.forEach { q ->
            assertEquals(q.id, q.options.size, q.options.map { it.id }.toSet().size)
        }
    }

    @Test
    fun choiceQuestionsHaveOptionsAndTextQuestionsDoNot() {
        SetupWizard.QUESTIONS.forEach { q ->
            if (q.kind == QuestionKind.TEXT) {
                assertTrue(q.id, q.options.isEmpty())
                // 强制一个不懂技术的人写自由文本，是最容易让他卡住的一步
                assertTrue(q.id, q.optional)
            } else {
                assertTrue(q.id, q.options.size >= 2)
            }
        }
    }

    @Test
    fun bothImplementationsAskTheSameQuestions() {
        // Python 版（core/wizard.py）必须问一模一样的题，否则两边生成的
        // 人设会不一样，同一个人在电脑和手机上的语气就对不上了
        assertEquals(
            listOf(
                "who", "busy", "style", "length", "emoji",
                "appointment", "progress", "stranger", "greeting", "never",
            ),
            SetupWizard.QUESTIONS.map { it.id },
        )
    }

    // ------------------------------------------------------------ 底线

    @Test
    fun playbookAlwaysKeepsTheEscapeHatch() {
        // 「看不懂就交给本人」是最重要的一条，任何回答组合下都必须在
        for (style in styles) {
            for (appointment in listOf("hold", "refuse", "ask")) {
                for (stranger in listOf("polite", "blunt", "later")) {
                    val result = SetupWizard.build(
                        answers(
                            "style" to listOf(style),
                            "appointment" to listOf(appointment),
                            "stranger" to listOf(stranger),
                        )
                    )
                    assertTrue(result.persona.playbook.contains("等我本人回你"))
                }
            }
        }
    }

    @Test
    fun generatedPersonaIsAlwaysConsideredConfigured() {
        // 没配人设时引擎会拒绝走 AI，问答生成的必须过得去
        assertTrue(SetupWizard.build(emptyMap()).persona.isConfigured())
        assertTrue(SetupWizard.build(answers()).persona.isConfigured())
    }

    @Test
    fun everyGeneratedRuleIsUsable() {
        for (style in styles) {
            val result = SetupWizard.build(answers("style" to listOf(style)))
            result.rules.forEach { rule ->
                // 空回复会被引擎跳过，等于这条规则白写
                assertTrue(rule.name, rule.replies.isNotEmpty())
                assertTrue(rule.name, rule.replies.all { it.isNotBlank() })
                assertTrue(rule.name, rule.keywords.isNotEmpty())
            }
        }
    }

    @Test
    fun unknownAnswersFallBackInsteadOfCrashing() {
        // 旧版本存下来的答案、或者被改坏的数据，不该让 App 崩在启动页上
        val result = SetupWizard.build(
            answers(
                "style" to listOf("不存在"),
                "appointment" to listOf("乱写"),
                "busy" to listOf("???"),
                "length" to listOf(""),
            )
        )
        assertTrue(result.persona.tone.isNotBlank())
        assertTrue(result.persona.playbook.isNotBlank())
        assertTrue(result.persona.maxChars > 0)
        assertTrue(result.fallbackText.isNotBlank())
    }

    @Test
    fun emptyAnswersStillProduceAWorkingSet() {
        val result = SetupWizard.build(emptyMap())
        assertTrue(result.persona.identity.isNotBlank())
        assertTrue(result.persona.tone.isNotBlank())
        assertEquals(4, result.persona.examples.size)
        assertEquals(4, result.rules.size)
        assertTrue(result.fallbackText.isNotBlank())
    }

    // ------------------------------------------------------------ 答案有效

    @Test
    fun styleChangesTheVoice() {
        val voices = styles.map {
            SetupWizard.build(answers("style" to listOf(it))).persona.examples[0].me
        }
        assertEquals(voices.size, voices.toSet().size)
        assertTrue(voices[1].contains("您"))
        assertTrue(voices[3].length <= voices[0].length)
    }

    @Test
    fun noExclamationWhenUserSaidTheyDoNotUseThem() {
        // 热情风格自带感叹号，但用户说了不用——示范必须跟着改。
        // 示范和语气说明打架时，模型照着示范走。
        val result = SetupWizard.build(
            answers("style" to listOf("warm"), "emoji" to listOf("none"))
        )
        result.persona.examples.forEach { assertFalse(it.me, it.me.contains("！")) }
        assertTrue(result.persona.tone.contains("不用感叹号"))
    }

    @Test
    fun exclamationKeptWhenUserLikesThem() {
        val result = SetupWizard.build(
            answers("style" to listOf("warm"), "emoji" to listOf("lots"))
        )
        assertTrue(result.persona.examples.any { it.me.contains("！") })
    }

    @Test
    fun lengthControlsMaxChars() {
        assertEquals(20, SetupWizard.build(answers("length" to listOf("short"))).persona.maxChars)
        assertEquals(45, SetupWizard.build(answers("length" to listOf("medium"))).persona.maxChars)
        assertEquals(30, SetupWizard.build(answers("length" to listOf("varies"))).persona.maxChars)
    }

    @Test
    fun ownWordsBeatTheTemplate() {
        // 用户自己写的那句是他真实的声音，比我们按风格挑的任何一句都准
        val result = SetupWizard.build(answers("greeting" to listOf("咋了老铁")))
        assertEquals("咋了老铁", result.persona.examples[0].me)
        // 关键词规则里也要用同一句，两种模式下表现才一致
        assertEquals(listOf("咋了老铁"), result.rules.first { it.name == "问在不在" }.replies)
    }

    @Test
    fun appointmentChoiceChangesBothPlaybookAndExamples() {
        val hold = SetupWizard.build(answers("appointment" to listOf("hold")))
        val refuse = SetupWizard.build(answers("appointment" to listOf("refuse")))
        assertNotEquals(hold.persona.playbook, refuse.persona.playbook)
        assertNotEquals(hold.persona.examples[1].me, refuse.persona.examples[1].me)
        assertTrue(hold.persona.examples[1].me.contains("日程"))
    }

    @Test
    fun boundariesAcceptAnySeparator() {
        val result = SetupWizard.build(
            answers("never" to listOf("不谈价格，不评价别人、不帮忙转发\n不借钱"))
        )
        assertEquals(
            listOf("不谈价格", "不评价别人", "不帮忙转发", "不借钱"),
            result.persona.boundaries,
        )
    }

    @Test
    fun blankBoundariesStayEmpty() {
        assertTrue(SetupWizard.build(answers("never" to listOf("   "))).persona.boundaries.isEmpty())
    }

    @Test
    fun whoAppearsInIdentity() {
        assertTrue(
            SetupWizard.build(answers("who" to listOf("client"))).persona.identity.contains("客户")
        )
        // 一个都没选也得有句像样的自我介绍
        assertTrue(
            SetupWizard.build(answers("who" to emptyList())).persona.identity.isNotBlank()
        )
    }

    // ------------------------------------------------------------ 接进引擎

    @Test
    fun applyToKeepsEverythingElseUntouched() {
        // 重答一遍问答不该把用户的开关、限流、黑名单冲掉
        val existing = EngineConfig(
            enabled = true,
            cooldownSeconds = 999,
            blockContacts = listOf("老板"),
            groupPolicy = GroupPolicy.ALWAYS,
            replyMode = ReplyMode.AI,
            ai = AiConfig(baseUrl = "https://a", apiKey = "k", model = "m"),
        )
        val applied = SetupWizard.applyTo(existing, SetupWizard.build(answers()))

        assertTrue(applied.enabled)
        assertEquals(999, applied.cooldownSeconds)
        assertEquals(listOf("老板"), applied.blockContacts)
        assertEquals(GroupPolicy.ALWAYS, applied.groupPolicy)
        assertEquals(ReplyMode.AI, applied.replyMode)
        assertEquals("k", applied.ai.apiKey)
        // 该换的换掉了
        assertTrue(applied.persona.isConfigured())
        assertEquals(4, applied.rules.size)
    }

    @Test
    fun generatedRulesActuallyFireInTheEngine() {
        // 生成出来但引擎打不中，等于白干
        val config = SetupWizard.applyTo(
            EngineConfig(
                enabled = true,
                signature = "",
                cooldownSeconds = 0,
                maxPerChatPerDay = 50,
                maxPerHour = 50,
                minDelaySeconds = 0,
                maxDelaySeconds = 0,
            ),
            SetupWizard.build(answers()),
        )
        val engine = ReplyEngine(InMemoryStateStore())

        for ((text, expectedRule) in listOf(
            "在吗" to "问在不在",
            "明天有空吗" to "约时间",
            "那个什么时候好" to "问进度",
            "了解一下我们的产品" to "推销拉群",
        )) {
            val decision = engine.decide(
                config,
                Message(chatId = text, chatName = text, text = text),
            )
            assertTrue("「$text」应该命中规则", decision.shouldReply)
            assertEquals(text, expectedRule, decision.ruleName)
        }
    }

    @Test
    fun generatedPromptCarriesTheAnswers() {
        val result = SetupWizard.build(
            answers(
                "style" to listOf("brief"),
                "appointment" to listOf("refuse"),
                "never" to listOf("不谈价格"),
            )
        )
        val prompt = buildSystemPrompt(result.persona)
        assertTrue(prompt.contains("能少说就少说"))
        assertTrue(prompt.contains("最近排不开"))
        assertTrue(prompt.contains("不谈价格"))
        // 内置边界不受问答影响
        assertTrue(prompt.contains("不答应任何转账"))
    }

    @Test
    fun wizardOutputNeverDefeatsTheSafetyRules() {
        // 不管问答生成了什么，敏感词照样拦住
        val config = SetupWizard.applyTo(
            EngineConfig(enabled = true, cooldownSeconds = 0),
            SetupWizard.build(answers()),
        )
        val decision = ReplyEngine(InMemoryStateStore()).decide(
            config,
            Message(chatId = "x", chatName = "小王", text = "帮我转账500"),
        )
        assertFalse(decision.shouldReply)
        assertTrue(decision.reason.contains("敏感词"))
    }
}
