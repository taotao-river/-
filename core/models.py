"""跨平台共用的数据结构。

iOS / Android / macOS 各端把抓到的消息统一转成 IncomingMessage，
引擎返回 ReplyDecision，端上只负责「按不按发送键」。
"""

from __future__ import annotations

import time
from dataclasses import dataclass, field
from typing import Optional


@dataclass
class IncomingMessage:
    """一条待处理的微信消息。"""

    chat_id: str
    """会话唯一标识。安卓用通知的 conversation key，iOS/macOS 用会话名。"""

    chat_name: str
    """会话显示名（群名或联系人昵称）。"""

    text: str
    """消息正文。"""

    sender_name: str = ""
    """群里的发送者昵称；私聊时等于 chat_name。"""

    is_group: bool = False

    mentioned_me: bool = False
    """群消息里是否 @ 了我。"""

    platform: str = "unknown"
    """android / ios / macos，仅用于日志。"""

    timestamp: float = field(default_factory=time.time)

    def __post_init__(self) -> None:
        self.text = (self.text or "").strip()
        if not self.sender_name:
            self.sender_name = self.chat_name


@dataclass
class ReplyDecision:
    """引擎的判断结果。should_reply 为 False 时 text 无意义。"""

    should_reply: bool
    reason: str
    """为什么回 / 为什么不回，直接写进日志，方便调参。"""

    text: Optional[str] = None
    delay_seconds: float = 0.0
    """建议延迟多久再发，避免秒回被风控盯上。"""

    rule_name: Optional[str] = None

    @classmethod
    def skip(cls, reason: str) -> "ReplyDecision":
        return cls(should_reply=False, reason=reason)
