"""跨平台共用的数据结构。

iOS / Android / macOS 各端把抓到的消息统一转成 IncomingMessage，
引擎返回 ReplyDecision，端上只负责「按不按发送键」。
"""

from __future__ import annotations

import re
import time
from dataclasses import dataclass, field
from typing import Optional

# 群名后面的成员数，如「项目组(8)」「项目组（8）」。
# 人数会变，不能进身份标识，否则有人进群就等于换了个会话。
_MEMBER_COUNT = re.compile(r"[（(]\s*\d+\s*[)）]\s*$")


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


def chat_identity(message: IncomingMessage) -> str:
    """把一条消息归一化成「会话身份」，作为限流和去重的键。

    为什么不直接用 chat_id：微信支持多端同时在线，同一条消息安卓和
    macOS 会各上报一次，而两端的 chat_id 前缀天然不同
    （android:com.tencent.mm:小王 vs macos:小王）。用 chat_id 做键会
    分裂成两套独立冷却，对方就会收到两条一模一样的自动回复。

    改用会话名归一化：
      - 去掉群名后缀的成员数，人数变化不该被当成新会话
      - 去掉首尾空白、统一大小写，抹平各端取名的细微差异
      - 群聊和私聊分开命名空间，避免同名的群和人撞到一起

    代价是两个昵称完全相同的联系人会共享额度。这个方向的误判是
    「少回一条」，比重复回复安全，可以接受。
    """
    name = _MEMBER_COUNT.sub("", message.chat_name).strip().casefold()
    return f"{'group' if message.is_group else 'private'}:{name}"
