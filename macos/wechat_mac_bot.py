"""macOS 微信自动回复 —— 走辅助功能 API。

为什么这条路值得单独做：微信支持手机和 Mac 同时在线，消息是同一份。
Mac 端自动回复，效果上就等于替你的 iPhone 回了消息，而且不需要越狱、
不需要 Mac 长期插着手机、不会被 iOS 沙盒挡住。对大多数人这是
Apple 生态里最省事的方案。

前提：
  1. macOS 版微信已登录
  2. 系统设置 → 隐私与安全性 → 辅助功能 → 勾选终端（或你跑脚本的 App）
  3. pip install pyobjc-framework-ApplicationServices requests

用法：
    export WXAUTO_SERVER=http://127.0.0.1:8848
    export WXAUTO_TOKEN=<和服务端一致>
    export WXAUTO_ACCOUNT=私人号   # 跑多个微信号时用来区分，见下
    python macos/wechat_mac_bot.py --dry-run
    python macos/wechat_mac_bot.py

关于 WXAUTO_ACCOUNT：限流和去重是按「账号」隔离的，不是按平台。
  - 两个不同的微信号分别跑在 Mac 和安卓上 → 填不同的值（或都留空）
  - 同一个微信号在 Mac 和安卓同时登录 → 两端填相同的值，避免重复回复
"""

from __future__ import annotations

import argparse
import logging
import os
import re
import subprocess
import sys
import time
from typing import Optional

import requests

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
logger = logging.getLogger("wechat-mac")

# AppleScript 片段。微信 Mac 版没有开放 AppleScript 字典，只能通过
# System Events 走辅助功能树。控件层级随版本会变，改版本时调这里。
_LIST_UNREAD = """
tell application "System Events"
    if not (exists process "WeChat") then return "ERR:微信未运行"
    tell process "WeChat"
        if (count of windows) = 0 then return "ERR:微信没有打开的窗口"
        set out to ""
        try
            set convRows to rows of table 1 of scroll area 1 of splitter group 1 of window 1
        on error
            return "ERR:找不到会话列表，可能是微信版本变了"
        end try
        repeat with r in convRows
            try
                set labels to value of static texts of UI element 1 of r
                set desc to description of r
                -- 未读会话的辅助功能描述里会带未读条数
                if desc contains "未读" or desc contains "unread" then
                    set out to out & (item 1 of labels) & "\\n"
                end if
            end try
        end repeat
        return out
    end tell
end tell
"""

_OPEN_AND_READ = """
on run argv
    set targetName to item 1 of argv
    tell application "System Events"
        tell process "WeChat"
            set convRows to rows of table 1 of scroll area 1 of splitter group 1 of window 1
            repeat with r in convRows
                try
                    set labels to value of static texts of UI element 1 of r
                    if (item 1 of labels) is targetName then
                        select r
                        delay 0.6
                        exit repeat
                    end if
                end try
            end repeat

            -- 消息区最后一条静态文本即最新消息
            try
                set msgTexts to value of static texts of UI element 1 of ¬
                    (last row of table 1 of scroll area 1 of splitter group 2 of splitter group 1 of window 1)
                return item 1 of msgTexts
            on error
                return "ERR:读不到消息内容"
            end try
        end tell
    end tell
end run
"""

_SEND = """
on run argv
    set targetName to item 1 of argv
    set replyText to item 2 of argv
    tell application "System Events"
        tell process "WeChat"
            set frontmost to true
            delay 0.3

            -- 发之前必须重新选中目标会话。
            -- 读消息和发送之间隔着 3-12 秒的随机延迟，这期间用户完全
            -- 可能切到别的聊天；直接 keystroke 会把话打进别人的对话框
            -- 然后发出去。宁可不发，也不能发错人。
            set found to false
            try
                set convRows to rows of table 1 of scroll area 1 of splitter group 1 of window 1
                repeat with r in convRows
                    try
                        set labels to value of static texts of UI element 1 of r
                        if (item 1 of labels) is targetName then
                            select r
                            delay 0.5
                            set found to true
                            exit repeat
                        end if
                    end try
                end repeat
            end try
            if not found then
                return "ERR:发送前找不到会话「" & targetName & "」，已放弃，没有发出任何内容"
            end if

            -- 尽量把焦点明确放进输入框。找不到就退回直接键入——
            -- 但此时会话已经确认选中，风险可控。
            try
                set inputArea to text area 1 of scroll area 2 of splitter group 2 of splitter group 1 of window 1
                set focused of inputArea to true
                delay 0.2
            end try

            keystroke replyText
            delay 0.3
            key code 36  -- Return
        end tell
    end tell
    return "OK"
end run
"""

# 诊断用：把微信的辅助功能树打出来。
# 选择器随微信版本变化，出问题时靠这个定位，而不是靠猜。
_DOCTOR = """
tell application "System Events"
    if not (exists process "WeChat") then return "微信没在运行。先打开并登录 macOS 版微信。"
    tell process "WeChat"
        set out to "微信进程: 在运行" & return
        set out to out & "窗口数: " & (count of windows) & return
        if (count of windows) = 0 then
            return out & return & "微信在运行但没有打开的窗口——点一下 Dock 里的微信图标把主窗口调出来。"
        end if

        try
            set out to out & "窗口1 名称: " & (name of window 1) & return
        end try

        set out to out & return & "=== 第 1 层：窗口1 的直接子元素 ===" & return
        try
            repeat with e in UI elements of window 1
                set entryLine to "  " & (class of e as text)
                try
                    set entryLine to entryLine & "  desc=" & (description of e)
                end try
                set out to out & entryLine & return
            end repeat
        on error errMsg
            set out to out & "  读不到：" & errMsg & return
        end try

        set out to out & return & "=== 第 2 层：splitter group 1 里有什么 ===" & return
        try
            repeat with e in UI elements of splitter group 1 of window 1
                set entryLine to "  " & (class of e as text)
                try
                    set entryLine to entryLine & "  desc=" & (description of e)
                end try
                set out to out & entryLine & return
            end repeat
        on error errMsg
            set out to out & "  没有 splitter group 1：" & errMsg & return
        end try

        set out to out & return & "=== 关键路径逐段探测 ===" & return

        try
            set sg to splitter group 1 of window 1
            set out to out & "  [OK] splitter group 1" & return
        on error
            set out to out & "  [X ] splitter group 1  <- 断在这里" & return
            return out
        end try

        try
            set sa to scroll area 1 of sg
            set out to out & "  [OK] scroll area 1" & return
        on error
            set out to out & "  [X ] scroll area 1  <- 断在这里" & return
            return out
        end try

        try
            set tb to table 1 of sa
            set out to out & "  [OK] table 1（会话列表）" & return
        on error
            set out to out & "  [X ] table 1  <- 断在这里" & return
            return out
        end try

        try
            set rowCount to count of rows of tb
            set out to out & "  [OK] 会话列表有 " & rowCount & " 行" & return
        on error errMsg
            set out to out & "  [X ] 读不到行：" & errMsg & return
            return out
        end try

        set out to out & return & "=== 前 3 个会话读出来长什么样 ===" & return
        try
            set n to 0
            repeat with r in rows of tb
                set n to n + 1
                if n > 3 then exit repeat
                try
                    set labels to value of static texts of UI element 1 of r
                    set out to out & "  会话 " & n & ": " & (item 1 of labels) & return
                    try
                        set out to out & "         desc=" & (description of r) & return
                    end try
                on error errMsg
                    set out to out & "  会话 " & n & " 读不出名字：" & errMsg & return
                end try
            end repeat
        end try

        return out
    end tell
end tell
"""


def run_applescript(script: str, *args: str) -> str:
    """执行 AppleScript。失败返回以 ERR: 开头的字符串，绝不抛给主循环。"""
    try:
        result = subprocess.run(
            ["osascript", "-", *args],
            input=script,
            capture_output=True,
            text=True,
            timeout=30,
        )
    except subprocess.TimeoutExpired:
        return "ERR:AppleScript 执行超时"

    if result.returncode != 0:
        stderr = result.stderr.strip()
        if "not allowed assistive access" in stderr:
            return "ERR:未授予辅助功能权限，去 系统设置→隐私与安全性→辅助功能 勾选终端"
        return f"ERR:{stderr}"
    return result.stdout.strip()


class EngineClient:
    def __init__(self, base_url: str, token: str, account: str = "") -> None:
        self._url = base_url.rstrip("/") + "/reply"
        self._headers = {"Authorization": f"Bearer {token}"}
        self._account = account

    def decide(self, chat_name: str, text: str, is_group: bool, mentioned_me: bool) -> Optional[dict]:
        try:
            resp = requests.post(
                self._url,
                json={
                    "chat_id": f"macos:{chat_name}",
                    "chat_name": chat_name,
                    "text": text,
                    "sender_name": chat_name,
                    "is_group": is_group,
                    "mentioned_me": mentioned_me,
                    "platform": "macos",
                    "account": self._account,
                },
                headers=self._headers,
                timeout=15,
            )
            resp.raise_for_status()
            return resp.json()
        except requests.RequestException as exc:
            logger.error("规则服务不可用，本轮跳过: %s", exc)
            return None


def tick(engine: EngineClient, dry_run: bool) -> None:
    listing = run_applescript(_LIST_UNREAD)
    if listing.startswith("ERR:"):
        logger.warning(listing[4:])
        return

    names = [n.strip() for n in listing.splitlines() if n.strip()]
    if not names:
        return

    for name in names[:5]:
        text = run_applescript(_OPEN_AND_READ, name)
        if text.startswith("ERR:") or not text:
            logger.info("「%s」读取失败：%s", name, text[4:] or "空内容")
            continue

        logger.info("「%s」最后一条: %s", name, text)

        is_group = bool(re.search(r"\(\d+\)$", name))
        decision = engine.decide(
            chat_name=re.sub(r"\(\d+\)$", "", name),
            text=text,
            is_group=is_group,
            mentioned_me="@" in text,
        )

        if not decision or not decision["should_reply"]:
            logger.info("  不回复：%s", decision["reason"] if decision else "服务不可用")
            continue

        reply = decision["text"]
        delay = decision.get("delay_seconds", 0)
        if dry_run:
            logger.info("  [DRY-RUN] 本应回复: %s（延迟 %.1fs）", reply, delay)
            continue

        logger.info("  等待 %.1fs 后回复: %s", delay, reply)
        time.sleep(delay)
        # 把会话名一起传进去：发之前要重新选中，否则等待期间用户
        # 切走了就会发错人
        result = run_applescript(_SEND, name, reply)
        if result.startswith("ERR:"):
            logger.error("  发送失败：%s", result[4:])
        else:
            logger.info("  已发送")


def doctor() -> int:
    """把微信的界面结构打出来。

    这个方案靠读辅助功能树定位控件，而那个树的结构随微信版本变化。
    出问题时不该让用户对着「找不到会话列表」干瞪眼——把实际看到的
    结构打出来，才有得改。
    """
    print()
    print("=" * 56)
    print("  微信界面结构诊断")
    print("=" * 56)
    print()
    print("  如果下面有 [X ]，把从这行往下的全部内容复制给帮你配置的人。")
    print()

    output = run_applescript(_DOCTOR)
    if output.startswith("ERR:"):
        print(f"  执行失败：{output[4:]}")
        print()
        print("  最常见的原因是没授权：")
        print("    系统设置 → 隐私与安全性 → 辅助功能 → 勾选「终端」")
        print("  勾了之后要把终端完全退出（⌘Q）再重开，授权才生效。")
        return 1

    print(output)
    print()
    if "[X ]" in output:
        print("  ⚠️ 上面有断掉的地方，说明你的微信版本和代码里的选择器对不上。")
        print("     把这整段输出发给帮你配置的人，改几行就能适配。")
        return 1

    print("  ✅ 界面结构对得上，可以继续跑 --dry-run 了。")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description="macOS 微信自动回复")
    parser.add_argument("--interval", type=float, default=15.0)
    parser.add_argument("--dry-run", action="store_true", help="只打印不发送")
    parser.add_argument(
        "--doctor",
        action="store_true",
        help="打印微信界面结构，用来排查「读不到会话」这类问题",
    )
    parser.add_argument("--once", action="store_true", help="只扫一轮就退出")
    args = parser.parse_args()

    # 诊断不需要连规则服务，也不需要 token
    if args.doctor:
        return doctor()

    token = os.environ.get("WXAUTO_TOKEN", "")
    if not token:
        logger.error("请设置 WXAUTO_TOKEN，与规则服务保持一致")
        return 1

    account = os.environ.get("WXAUTO_ACCOUNT", "")
    engine = EngineClient(
        os.environ.get("WXAUTO_SERVER", "http://127.0.0.1:8848"), token, account
    )
    if account:
        logger.info("驱动的微信号: %s", account)
    logger.info("启动，每 %.0fs 扫一次%s", args.interval, "（DRY-RUN）" if args.dry_run else "")
    if args.dry_run:
        # 说清楚 dry-run 到底「干」在哪：它不发消息，但读消息这件事
        # 本身要靠点开会话，所以未读会被标成已读。这一点不提前讲，
        # 用户会以为自己被偷看了消息。
        logger.info("DRY-RUN：不会发出任何消息。但读取需要点开会话，未读会被标为已读。")

    try:
        while True:
            tick(engine, args.dry_run)
            if args.once:
                break
            time.sleep(args.interval)
    except KeyboardInterrupt:
        logger.info("收到中断，退出")
    return 0


if __name__ == "__main__":
    sys.exit(main())
