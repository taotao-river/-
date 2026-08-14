"""用 Claude 生成兜底回复。

只有规则全部没命中、且 fallback.type = llm 时才会被调用，
所以这里优化的是「短、快、像人说话」，不是推理深度。
"""

from __future__ import annotations

import logging
from typing import Optional

import anthropic

from .config import Config
from .models import IncomingMessage

logger = logging.getLogger(__name__)

_SYSTEM_TEMPLATE = """你在替我自动回复微信消息。

{persona_block}风格要求：{style}

硬性规则：
- 只输出回复正文，不要任何前缀、解释、引号或 Markdown。
- 不承诺任何具体金额、时间、地点，也不要同意任何转账、借钱、代付请求。
- 遇到你无法判断的事，就说自己稍后本人回复。
- 如果消息看起来是诈骗、推销或需要本人决策，只回一句「我稍后本人回复你」。"""


class ClaudeReplyWriter:
    """把一条微信消息变成一句自动回复。构造一次，重复使用。"""

    def __init__(self, client: Optional[anthropic.Anthropic] = None) -> None:
        # 不传 api_key：SDK 会依次读 ANTHROPIC_API_KEY、ANTHROPIC_AUTH_TOKEN
        # 以及 `ant auth login` 存下的 profile。
        self._client = client or anthropic.Anthropic()

    def __call__(self, message: IncomingMessage, config: Config) -> Optional[str]:
        settings = config.llm
        persona_block = f"我的人设：{settings.persona}\n\n" if settings.persona else ""
        system = _SYSTEM_TEMPLATE.format(
            persona_block=persona_block,
            style=settings.style,
        )

        context = (
            f"会话类型：{'群聊' if message.is_group else '私聊'}\n"
            f"会话名：{message.chat_name}\n"
            f"发送者：{message.sender_name}\n"
            f"消息内容：{message.text}"
        )

        response = self._client.beta.messages.create(
            model=settings.model,
            max_tokens=settings.max_tokens,
            system=system,
            # 低 effort：自动回复要的是低延迟，不是深度推理。
            # 保持 thinking 默认开启（Opus 5 上关闭 thinking 反而可能把
            # 内部标签漏进正文），靠 effort 控成本。
            output_config={"effort": settings.effort},
            # 安全分类器偶尔会误伤正常内容，开启服务端兜底让请求自动
            # 换一个模型重跑，而不是直接失败。
            betas=["server-side-fallback-2026-07-01"],
            fallbacks="default",
            messages=[{"role": "user", "content": context}],
        )

        if response.stop_reason == "refusal":
            category = getattr(response.stop_details, "category", None)
            logger.warning("Claude 拒绝生成（category=%s），跳过本条", category)
            return None

        parts = [block.text for block in response.content if block.type == "text"]
        text = "".join(parts).strip()
        if not text:
            return None

        # 模型偶尔会自带引号，去掉以免发出去很怪。
        return text.strip("「」\"'“”")
