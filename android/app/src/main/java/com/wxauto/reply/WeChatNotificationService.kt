package com.wxauto.reply

import android.app.Notification
import android.app.RemoteInput
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import java.util.concurrent.Executors

/**
 * 安卓自动回复的主力实现。
 *
 * 原理：微信的消息通知自带一个「回复」快捷操作（RemoteInput），
 * 系统允许通知监听器直接往里灌文字并触发。整个过程不碰微信 UI、
 * 不需要 root、不需要无障碍权限，也不会把微信切到前台——
 * 这是安卓上侵入性最低的做法。
 *
 * 局限（用之前必须知道）：
 *   - 只能处理「会弹通知」的消息。免打扰的会话拿不到。
 *   - 通知里的文本可能被系统截断，长消息读不全。
 *   - 部分定制 ROM 会剥掉 RemoteInput，这时回落到无障碍方案。
 *
 * 需要用户手动授予：设置 → 通知 → 通知使用权 → 打开本应用。
 */
class WeChatNotificationService : NotificationListenerService() {

    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var engine: ReplyEngineClient

    override fun onCreate() {
        super.onCreate()
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        engine = ReplyEngineClient(
            baseUrl = prefs.getString(KEY_URL, DEFAULT_URL)!!,
            token = prefs.getString(KEY_TOKEN, "")!!,
            account = prefs.getString(KEY_ACCOUNT, "")!!,
        )
    }

    override fun onDestroy() {
        executor.shutdown()
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName != WECHAT_PACKAGE) return

        val notification = sbn.notification ?: return
        val extras = notification.extras ?: return

        // 微信的群通知汇总（「x 个联系人发来 y 条消息」）没法定位到具体会话，跳过
        if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return

        val chatName = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val rawText = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        if (chatName.isEmpty() || rawText.isEmpty()) return

        val replyAction = findReplyAction(notification)
        if (replyAction == null) {
            Log.i(TAG, "「$chatName」的通知没有回复入口，交给无障碍方案兜底")
            return
        }

        // 群消息的通知正文形如 "张三: 内容"，私聊则直接是内容
        val isGroup = chatName.contains("(") && chatName.contains(")") || rawText.contains(": ")
        val senderName: String
        val text: String
        val colonIndex = rawText.indexOf(": ")
        if (isGroup && colonIndex in 1..30) {
            senderName = rawText.substring(0, colonIndex)
            text = rawText.substring(colonIndex + 2)
        } else {
            senderName = chatName
            text = rawText
        }

        // 网络请求不能跑在通知回调线程上
        executor.execute {
            handle(sbn, replyAction, chatName, senderName, text, isGroup)
        }
    }

    private fun handle(
        sbn: StatusBarNotification,
        action: Notification.Action,
        chatName: String,
        senderName: String,
        text: String,
        isGroup: Boolean,
    ) {
        val decision = engine.decide(
            chatId = "android:${sbn.packageName}:$chatName",
            chatName = chatName,
            text = text,
            senderName = senderName,
            isGroup = isGroup,
            mentionedMe = text.contains("@"),
        )

        if (!decision.shouldReply || decision.text == null) {
            Log.i(TAG, "不回复「$chatName」：${decision.reason}")
            return
        }

        if (decision.delayMillis > 0) {
            try {
                Thread.sleep(decision.delayMillis)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }

        sendReply(action, decision.text)
        Log.i(TAG, "已回复「$chatName」：${decision.text}")
    }

    /** 在通知的 actions 里找带 RemoteInput 的那个（就是「回复」按钮）。 */
    private fun findReplyAction(notification: Notification): Notification.Action? =
        notification.actions?.firstOrNull { action ->
            action.remoteInputs?.isNotEmpty() == true
        }

    /** 把文字塞进 RemoteInput 并触发 PendingIntent —— 等价于用户在通知栏里打字回复。 */
    private fun sendReply(action: Notification.Action, text: String) {
        val remoteInputs = action.remoteInputs ?: return
        val bundle = Bundle()
        for (input in remoteInputs) {
            bundle.putCharSequence(input.resultKey, text)
        }

        val intent = Intent()
        RemoteInput.addResultsToIntent(remoteInputs, intent, bundle)

        try {
            action.actionIntent.send(this, 0, intent)
        } catch (e: android.app.PendingIntent.CanceledException) {
            // 通知已被划掉或过期，重发没有意义
            Log.w(TAG, "回复入口已失效: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "WeChatNotifService"
        private const val WECHAT_PACKAGE = "com.tencent.mm"

        const val PREFS = "wxauto"
        const val KEY_URL = "engine_url"
        const val KEY_TOKEN = "engine_token"
        const val KEY_ACCOUNT = "engine_account"
        const val DEFAULT_URL = "http://10.0.2.2:8848"
    }
}
