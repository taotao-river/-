package com.wxauto.reply

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.wxauto.reply.engine.EngineHolder
import com.wxauto.reply.engine.Message
import com.wxauto.reply.engine.Storage
import java.util.concurrent.Executors

/**
 * 安卓自动回复的全部实现。
 *
 * 原理：微信的消息通知自带「回复」快捷操作（RemoteInput），
 * 系统允许通知监听器往里灌文字并触发。整个过程不碰微信界面、
 * 不需要 root、不需要无障碍权限，微信也不用切到前台。
 *
 * 规则引擎跑在本机（com.wxauto.reply.engine），不需要任何服务器——
 * 装完 APK 打开开关就能用。
 *
 * 局限：
 *   - 只能处理会弹通知的消息，免打扰的会话拿不到
 *   - 通知里的文本可能被系统截断，很长的消息读不全
 *   - 少数定制 ROM 会剥掉 RemoteInput，这时回落到无障碍方案
 *
 * 需要用户手动授予：设置 → 通知 → 通知使用权 → 打开本应用。
 */
class WeChatNotificationService : NotificationListenerService() {

    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var engines: EngineHolder

    override fun onCreate() {
        super.onCreate()
        engines = EngineHolder(this)
    }

    override fun onDestroy() {
        executor.shutdown()
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName != WECHAT_PACKAGE) return

        val notification = sbn.notification ?: return
        val extras = notification.extras ?: return

        // 汇总通知（「x 个联系人发来 y 条消息」）定位不到具体会话，跳过
        if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return

        val chatName = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val rawText = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        if (chatName.isEmpty() || rawText.isEmpty()) return

        val replyAction = findReplyAction(notification)
        if (replyAction == null) {
            Log.i(TAG, "「$chatName」的通知没有回复入口，跳过")
            return
        }

        // 群消息的通知正文形如「张三: 内容」，私聊直接是内容
        val colonIndex = rawText.indexOf(": ")
        val looksLikeGroup = chatName.contains("(") && chatName.contains(")")
        val isGroup = looksLikeGroup || (colonIndex in 1..30)
        val senderName: String
        val text: String
        if (isGroup && colonIndex in 1..30) {
            senderName = rawText.substring(0, colonIndex)
            text = rawText.substring(colonIndex + 2)
        } else {
            senderName = chatName
            text = rawText
        }

        // 引擎判断和延迟发送都不能占用通知回调线程
        executor.execute {
            handle(replyAction, chatName, senderName, text, isGroup)
        }
    }

    private fun handle(
        action: Notification.Action,
        chatName: String,
        senderName: String,
        text: String,
        isGroup: Boolean,
    ) {
        // 每次都重新读配置：用户在界面上改完或用快捷开关关掉，立刻生效
        val config = Storage.loadConfig(this)

        // AI 模式下这一步会走网络，可能要几秒。executor 是单线程的，
        // 所以消息是排队处理的——回复本来就有频率限制，排队不影响结果，
        // 而且能保证不会有两条回复同时往外发。
        val decision = engines.engineFor(config).decide(
            config,
            Message(
                chatId = chatName,
                chatName = chatName,
                text = text,
                senderName = senderName,
                isGroup = isGroup,
                mentionedMe = text.contains("@"),
            ),
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

        // 延迟期间用户可能把开关关了，发之前再确认一次
        if (!Storage.loadConfig(this).enabled) {
            Log.i(TAG, "等待期间开关被关闭，放弃回复「$chatName」")
            return
        }

        sendReply(action, decision.text)
        Log.i(TAG, "已回复「$chatName」：${decision.text}")
    }

    /** 在通知的 actions 里找带 RemoteInput 的那个，就是「回复」按钮。 */
    private fun findReplyAction(notification: Notification): Notification.Action? =
        notification.actions?.firstOrNull { it.remoteInputs?.isNotEmpty() == true }

    /** 把文字塞进 RemoteInput 并触发 —— 等价于用户在通知栏里打字回复。 */
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
        } catch (e: PendingIntent.CanceledException) {
            // 通知被划掉或已过期，重发没有意义
            Log.w(TAG, "回复入口已失效：${e.message}")
        }
    }

    companion object {
        private const val TAG = "WeChatNotifService"
        private const val WECHAT_PACKAGE = "com.tencent.mm"
    }
}
