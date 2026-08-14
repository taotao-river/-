package com.wxauto.reply

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.wxauto.reply.engine.EngineConfig
import com.wxauto.reply.engine.GroupPolicy
import com.wxauto.reply.engine.InMemoryStateStore
import com.wxauto.reply.engine.Message
import com.wxauto.reply.engine.ReplyEngine
import com.wxauto.reply.engine.Rule
import com.wxauto.reply.engine.Storage

/**
 * 唯一的界面。目标是「装完打开、拨一下开关就能用」，
 * 所以默认值都填好了，用户不改任何东西也能正常工作。
 */
class MainActivity : Activity() {

    private lateinit var masterSwitch: Switch
    private lateinit var permissionStatus: TextView
    private lateinit var groupPolicyGroup: RadioGroup
    private lateinit var fallbackField: EditText
    private lateinit var blockContactsField: EditText
    private lateinit var rulesContainer: LinearLayout
    private lateinit var testInput: EditText
    private lateinit var testResult: TextView
    private lateinit var testAsGroup: CheckBox

    private var groupNeverId = View.generateViewId()
    private var groupAtMeId = View.generateViewId()
    private var groupAlwaysId = View.generateViewId()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        loadIntoUi(Storage.loadConfig(this))
    }

    override fun onResume() {
        super.onResume()
        // 从系统设置页返回时刷新授权状态
        refreshPermissionStatus()
        masterSwitch.isChecked = Storage.loadConfig(this).enabled
    }

    // ------------------------------------------------------------------ 界面

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(28))
        }

        root.addView(title("微信自动回复"))

        // ---- 总开关 ----
        masterSwitch = Switch(this).apply {
            text = "  自动回复"
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(12), dp(16), dp(12), dp(16))
            setOnCheckedChangeListener { _, checked ->
                Storage.setEnabled(this@MainActivity, checked)
                Toast.makeText(
                    this@MainActivity,
                    if (checked) "自动回复已开启" else "自动回复已关闭",
                    Toast.LENGTH_SHORT,
                ).show()
                refreshPermissionStatus()
            }
        }
        root.addView(masterSwitch)

        permissionStatus = TextView(this).apply {
            setPadding(dp(12), 0, dp(12), dp(12))
        }
        root.addView(permissionStatus)

        root.addView(Button(this).apply {
            text = "授予通知使用权（必须）"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
        })

        root.addView(hint("在打开的页面里找到「微信自动回复」并打开。不给这个权限，程序看不到微信消息。"))

        root.addView(divider())

        // ---- 群聊 ----
        root.addView(section("群聊消息"))
        groupPolicyGroup = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
            addView(RadioButton(context).apply { id = groupNeverId; text = "不回群消息（推荐）" })
            addView(RadioButton(context).apply { id = groupAtMeId; text = "只在别人 @ 我时回" })
            addView(RadioButton(context).apply { id = groupAlwaysId; text = "群里任何消息都回（容易刷屏）" })
        }
        root.addView(groupPolicyGroup)

        root.addView(divider())

        // ---- 规则 ----
        root.addView(section("收到这些词，就回这句话"))
        rulesContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(rulesContainer)

        root.addView(Button(this).apply {
            text = "＋ 再加一条"
            setOnClickListener { rulesContainer.addView(ruleRow(Rule(name = "规则", replies = listOf(""))))}
        })

        root.addView(divider())

        // ---- 兜底 ----
        root.addView(section("其他消息统一回"))
        fallbackField = EditText(this).apply {
            hint = "留空表示不回"
        }
        root.addView(fallbackField)
        root.addView(hint("上面的词都没匹配上时，回这一句。"))

        root.addView(divider())

        // ---- 不回复名单 ----
        root.addView(section("这些人永远不自动回"))
        blockContactsField = EditText(this).apply {
            hint = "多个人用逗号隔开，例如：老板，妈妈"
        }
        root.addView(blockContactsField)

        root.addView(divider())

        // ---- 试一试 ----
        // 不真发消息就能看到会回什么。开启之前先在这里把文案调顺，
        // 比让对方当小白鼠强。
        root.addView(section("试一试（不会真的发出去）"))
        testInput = EditText(this).apply {
            hint = "假装别人发来一句话，比如：在吗"
        }
        root.addView(testInput)

        testAsGroup = CheckBox(this).apply { text = "当作群里 @ 我的消息" }
        root.addView(testAsGroup)

        root.addView(Button(this).apply {
            text = "看看会回什么"
            setOnClickListener { runPreview() }
        })

        testResult = TextView(this).apply {
            setPadding(dp(12), dp(8), dp(12), dp(8))
            textSize = 15f
        }
        root.addView(testResult)

        root.addView(hint("用的是你当前填的内容，不用先保存。试的时候不占用「每天最多回几条」的额度。"))

        root.addView(divider())

        root.addView(Button(this).apply {
            text = "保存设置"
            setOnClickListener { saveFromUi() }
        })

        root.addView(hint(
            "小提示：下拉通知栏 → 点编辑（铅笔图标）→ 把「微信自动回复」拖进快捷开关，" +
                "以后下拉一点就能开关，不用每次打开这个 App。"
        ))

        root.addView(hint(
            "安全说明：遇到含「转账、红包、验证码、借钱」等字样的消息，" +
                "程序一律不自动回，交给你本人处理。这条改不了。\n\n" +
                "每条回复末尾会带「（自动回复）」，让对方知道不是你本人在回。"
        ))

        return ScrollView(this).apply {
            addView(root, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    /** 一条规则的编辑行：关键词 + 回复内容 + 删除按钮。 */
    private fun ruleRow(rule: Rule): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(8))
        }

        val keywords = EditText(this).apply {
            hint = "关键词，多个用逗号隔开，例如：在吗，在么"
            setText(rule.keywords.joinToString("，"))
            tag = TAG_KEYWORDS
        }
        val reply = EditText(this).apply {
            hint = "回复内容"
            setText(rule.replies.firstOrNull().orEmpty())
            tag = TAG_REPLY
        }
        val remove = Button(this).apply {
            text = "删除这条"
            setOnClickListener { rulesContainer.removeView(row) }
        }

        row.addView(keywords)
        row.addView(reply)
        row.addView(remove)
        return row
    }

    // ------------------------------------------------------------------ 读写

    private fun loadIntoUi(config: EngineConfig) {
        masterSwitch.isChecked = config.enabled
        groupPolicyGroup.check(
            when (config.groupPolicy) {
                GroupPolicy.NEVER -> groupNeverId
                GroupPolicy.ONLY_AT_ME -> groupAtMeId
                GroupPolicy.ALWAYS -> groupAlwaysId
            }
        )
        fallbackField.setText(config.fallbackText)
        blockContactsField.setText(config.blockContacts.joinToString("，"))

        rulesContainer.removeAllViews()
        val rules = config.rules.ifEmpty { listOf(Rule(name = "规则", replies = listOf(""))) }
        rules.forEach { rulesContainer.addView(ruleRow(it)) }
    }

    private fun saveFromUi() {
        Storage.saveConfig(this, buildConfigFromUi())
        Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show()
    }

    /** 把界面上当前填的内容组装成配置。保存和「试一试」共用，避免两边不一致。 */
    private fun buildConfigFromUi(): EngineConfig {
        val rules = ArrayList<Rule>()
        for (i in 0 until rulesContainer.childCount) {
            val row = rulesContainer.getChildAt(i) as? ViewGroup ?: continue
            val keywords = (row.findViewWithTag<EditText>(TAG_KEYWORDS))?.text?.toString().orEmpty()
            val reply = (row.findViewWithTag<EditText>(TAG_REPLY))?.text?.toString().orEmpty()
            val words = splitList(keywords)
            if (words.isEmpty() || reply.isBlank()) continue
            rules += Rule(
                name = words.first(),
                keywords = words,
                replies = listOf(reply.trim()),
            )
        }

        val policy = when (groupPolicyGroup.checkedRadioButtonId) {
            groupAtMeId -> GroupPolicy.ONLY_AT_ME
            groupAlwaysId -> GroupPolicy.ALWAYS
            else -> GroupPolicy.NEVER
        }

        return Storage.loadConfig(this).copy(
            enabled = masterSwitch.isChecked,
            groupPolicy = policy,
            rules = rules,
            fallbackText = fallbackField.text.toString().trim(),
            blockContacts = splitList(blockContactsField.text.toString()),
        )
    }

    /**
     * 试一试：用一个全新的内存状态跑一次引擎。
     *
     * 用 InMemoryStateStore 而不是真实存储，所以既不会被冷却挡住
     * （否则试第二次就没反应了），也不会吃掉真实的每日额度。
     * 敏感词、群聊策略、黑名单照常生效——那些正是要看的东西。
     */
    private fun runPreview() {
        val text = testInput.text.toString().trim()
        if (text.isEmpty()) {
            testResult.text = "先在上面输入一句话"
            testResult.setTextColor(Color.parseColor("#757575"))
            return
        }

        // 忽略总开关和时段，其余全部照常
        val config = buildConfigFromUi().copy(
            enabled = true,
            activeFromMinute = -1,
            activeToMinute = -1,
        )
        val isGroup = testAsGroup.isChecked
        val decision = ReplyEngine(InMemoryStateStore()).decide(
            config,
            Message(
                chatId = "preview",
                chatName = "测试联系人",
                text = text,
                isGroup = isGroup,
                mentionedMe = isGroup,
            ),
        )

        if (decision.shouldReply) {
            testResult.text = "✅ 会回复：\n${decision.text}\n\n（${decision.reason}，" +
                "${decision.delayMillis / 1000} 秒后发出）"
            testResult.setTextColor(Color.parseColor("#2E7D32"))
        } else {
            testResult.text = "⛔ 不会回复\n原因：${decision.reason}"
            testResult.setTextColor(Color.parseColor("#D32F2F"))
        }
    }

    /** 中英文逗号都当分隔符——用户不该被要求分清全角半角。 */
    private fun splitList(raw: String): List<String> =
        raw.split(",", "，", "、")
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    // ------------------------------------------------------------------ 状态

    private fun refreshPermissionStatus() {
        val granted = isNotificationAccessGranted()
        val enabled = Storage.loadConfig(this).enabled

        val (text, color) = when {
            !granted -> "⚠️ 还没授予通知使用权，现在不会自动回复" to Color.parseColor("#D32F2F")
            !enabled -> "已授权。开关打开后开始工作。" to Color.parseColor("#757575")
            else -> "✅ 正在工作中" to Color.parseColor("#2E7D32")
        }
        permissionStatus.text = text
        permissionStatus.setTextColor(color)
    }

    private fun isNotificationAccessGranted(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        if (TextUtils.isEmpty(flat)) return false
        return flat.split(":").any { it.contains(packageName) }
    }

    // ------------------------------------------------------------------ 小工具

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun title(text: String) = TextView(this).apply {
        this.text = text
        textSize = 24f
        setTypeface(null, Typeface.BOLD)
        setPadding(dp(12), 0, dp(12), dp(16))
    }

    private fun section(text: String) = TextView(this).apply {
        this.text = text
        textSize = 17f
        setTypeface(null, Typeface.BOLD)
        setPadding(dp(12), dp(8), dp(12), dp(4))
    }

    private fun hint(text: String) = TextView(this).apply {
        this.text = text
        textSize = 13f
        setTextColor(Color.parseColor("#757575"))
        setPadding(dp(12), dp(4), dp(12), dp(12))
    }

    private fun divider() = View(this).apply {
        setBackgroundColor(Color.parseColor("#E0E0E0"))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(1)
        ).apply { setMargins(0, dp(16), 0, dp(16)) }
    }

    companion object {
        private const val TAG_KEYWORDS = "kw"
        private const val TAG_REPLY = "rp"
    }
}
