package com.wxauto.reply

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.wxauto.reply.engine.Message
import com.wxauto.reply.engine.ReplyEngine
import com.wxauto.reply.engine.Storage
import java.util.concurrent.Executors

/**
 * 兜底方案：直接操作微信界面。
 *
 * 什么时候需要它：
 *   - 定制 ROM 剥掉了通知里的 RemoteInput
 *   - 会话开了免打扰，压根不弹通知
 *   - 消息太长，通知里被截断
 *
 * 代价：需要无障碍权限（权限很大，用户要清楚自己授了什么），
 * 而且必须让微信保持在前台的聊天页面，否则找不到输入框。
 * 能用通知方案就别用这个。
 */
class WeChatAccessibilityService : AccessibilityService() {

    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var engine: ReplyEngine

    /** 记住上次处理过的消息，避免同一条被界面刷新触发多次。 */
    private var lastHandled: String? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        engine = ReplyEngine(Storage.stateStore(this))
        Log.i(TAG, "无障碍服务已连接")
    }

    override fun onDestroy() {
        executor.shutdown()
        super.onDestroy()
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.packageName != WECHAT_PACKAGE) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        ) return

        val root = rootInActiveWindow ?: return
        val chatName = readChatTitle(root) ?: return
        val incoming = readLastIncomingMessage(root) ?: return

        val fingerprint = "$chatName|$incoming"
        if (fingerprint == lastHandled) return
        lastHandled = fingerprint

        executor.execute { handle(chatName, incoming) }
    }

    private fun handle(chatName: String, text: String) {
        val isGroup = Regex("""\(\d+\)$""").containsMatchIn(chatName)
        val decision = engine.decide(
            Storage.loadConfig(this),
            Message(
                chatId = chatName,
                chatName = chatName,
                text = text,
                senderName = chatName,
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

        // UI 操作必须回主线程
        mainExecutor.execute { typeAndSend(decision.text) }
    }

    /** 聊天页标题栏就是会话名。 */
    private fun readChatTitle(root: AccessibilityNodeInfo): String? =
        root.findAccessibilityNodeInfosByViewId("$WECHAT_PACKAGE:id/kn")
            ?.firstOrNull()?.text?.toString()
            ?: root.findAccessibilityNodeInfosByViewId("$WECHAT_PACKAGE:id/g3")
                ?.firstOrNull()?.text?.toString()

    /**
     * 读最后一条对方发来的消息。
     *
     * 和 iOS 方案同理：靠气泡在屏幕上的水平位置区分收发。
     * 微信的 viewId 每个版本都变，位置判据反而更耐用。
     */
    private fun readLastIncomingMessage(root: AccessibilityNodeInfo): String? {
        val screenWidth = resources.displayMetrics.widthPixels
        val candidates = mutableListOf<Pair<Int, String>>()

        fun walk(node: AccessibilityNodeInfo?) {
            if (node == null) return
            val text = node.text?.toString()
            if (!text.isNullOrBlank() && node.className == "android.widget.TextView") {
                val bounds = android.graphics.Rect()
                node.getBoundsInScreen(bounds)
                candidates += bounds.centerX() to text
            }
            for (i in 0 until node.childCount) walk(node.getChild(i))
        }
        walk(root)

        // 中心点在左半屏 = 对方发的
        return candidates.lastOrNull { it.first < screenWidth / 2 }?.second
    }

    private fun typeAndSend(text: String) {
        val root = rootInActiveWindow ?: return

        val input = findEditable(root)
        if (input == null) {
            Log.w(TAG, "找不到输入框，可能不在聊天页")
            return
        }

        val args = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text
            )
        }
        input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)

        val sendButton = root.findAccessibilityNodeInfosByText("发送")
            ?.firstOrNull { it.isClickable }
        if (sendButton == null) {
            Log.w(TAG, "找不到发送按钮，文本已填入但未发出")
            return
        }
        sendButton.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        Log.i(TAG, "已发送：$text")
    }

    private fun findEditable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isEditable) return node
        for (i in 0 until node.childCount) {
            findEditable(node.getChild(i))?.let { return it }
        }
        return null
    }

    companion object {
        private const val TAG = "WeChatA11yService"
        private const val WECHAT_PACKAGE = "com.tencent.mm"
    }
}
