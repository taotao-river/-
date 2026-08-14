package com.wxauto.reply

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * 极简配置页：填规则服务地址和 token，然后跳去系统授权。
 * 真正的规则都在服务端 YAML 里，这里不做规则编辑。
 */
class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences(WeChatNotificationService.PREFS, MODE_PRIVATE)

        val urlField = EditText(this).apply {
            hint = "规则服务地址，如 http://192.168.1.10:8848"
            setText(prefs.getString(WeChatNotificationService.KEY_URL, WeChatNotificationService.DEFAULT_URL))
            inputType = InputType.TYPE_TEXT_VARIATION_URI
        }

        val tokenField = EditText(this).apply {
            hint = "WXAUTO_TOKEN"
            setText(prefs.getString(WeChatNotificationService.KEY_TOKEN, ""))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }

        val save = Button(this).apply {
            text = "保存"
            setOnClickListener {
                prefs.edit()
                    .putString(WeChatNotificationService.KEY_URL, urlField.text.toString().trim())
                    .putString(WeChatNotificationService.KEY_TOKEN, tokenField.text.toString().trim())
                    .apply()
                // 通知监听服务在 onCreate 读配置，改完要重开一次才生效
                Toast.makeText(
                    this@MainActivity,
                    "已保存。请到「通知使用权」里关掉再打开本应用，让新配置生效。",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }

        val notifPerm = Button(this).apply {
            text = "① 授予通知使用权（主力方案，必需）"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
        }

        val a11yPerm = Button(this).apply {
            text = "② 授予无障碍权限（兜底方案，可选）"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }

        val notice = TextView(this).apply {
            text = """
                说明：
                • 主力方案靠微信通知里的「回复」按钮，不需要无障碍权限。
                • 只有当通知没有回复入口、或会话开了免打扰时，才需要开②。
                • 所有规则、冷却、敏感词都在服务端配置，本 App 不存消息内容。
            """.trimIndent()
            setPadding(0, 32, 0, 0)
        }

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 64, 48, 48)
            addView(urlField)
            addView(tokenField)
            addView(save)
            addView(notifPerm)
            addView(a11yPerm)
            addView(notice)
        })
    }
}
