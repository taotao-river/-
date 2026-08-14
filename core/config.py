"""配置加载与校验。"""

from __future__ import annotations

import re
from dataclasses import dataclass, field
from datetime import time as dtime
from pathlib import Path
from typing import Any, Optional

from .persona import Persona, build_persona

import yaml


class ConfigError(ValueError):
    """配置文件写错时抛出，消息里带上具体哪一项错了。"""


@dataclass
class Rule:
    name: str
    kind: str  # keyword | regex | always
    replies: list[str]
    keywords: list[str] = field(default_factory=list)
    pattern: Optional[re.Pattern[str]] = None

    def matches(self, text: str) -> bool:
        if self.kind == "always":
            return True
        if self.kind == "keyword":
            return any(k in text for k in self.keywords)
        if self.kind == "regex" and self.pattern is not None:
            return self.pattern.search(text) is not None
        return False


@dataclass
class Limits:
    per_chat_cooldown_seconds: int = 1800
    max_replies_per_chat_per_day: int = 5
    global_max_replies_per_hour: int = 30
    min_delay_seconds: float = 3.0
    max_delay_seconds: float = 12.0
    # 微信多端同时在线时，同一条消息会被安卓和 macOS 分别上报。
    # 这个窗口内内容相同的消息只回一次，避免对方收到两条。
    cross_device_dedup_seconds: int = 120


@dataclass
class Scope:
    reply_to_private: bool = True
    reply_to_group: str = "only_at_me"  # never | only_at_me | always
    allow_contacts: list[str] = field(default_factory=list)
    block_contacts: list[str] = field(default_factory=list)
    block_keywords: list[str] = field(default_factory=list)


@dataclass
class LLMSettings:
    enabled: bool = False
    model: str = "claude-opus-5"
    max_tokens: int = 300
    effort: str = "low"
    style: str = "用简短口语化的中文回复，不超过 30 个字。"
    persona: str = ""


@dataclass
class Fallback:
    kind: str = "text"  # llm | text | none
    text: str = ""


@dataclass
class Config:
    enabled: bool = True
    reply_mode: str = "rules_then_ai"
    """ai：全部交给模型按人设生成（规则忽略）
    rules：只用关键词规则
    rules_then_ai：规则优先，没命中才用模型"""
    persona: Persona = field(default_factory=Persona)
    signature: str = ""
    active_hours: list[tuple[dtime, dtime]] = field(default_factory=list)
    scope: Scope = field(default_factory=Scope)
    limits: Limits = field(default_factory=Limits)
    rules: list[Rule] = field(default_factory=list)
    fallback: Fallback = field(default_factory=Fallback)
    llm: LLMSettings = field(default_factory=LLMSettings)


_TIME_RANGE = re.compile(r"^(\d{1,2}):(\d{2})\s*-\s*(\d{1,2}):(\d{2})$")


def _parse_time_range(raw: str) -> tuple[dtime, dtime]:
    m = _TIME_RANGE.match(raw.strip())
    if not m:
        raise ConfigError(f"active_hours 格式应为 'HH:MM-HH:MM'，收到: {raw!r}")
    sh, sm, eh, em = (int(g) for g in m.groups())
    for h, mi in ((sh, sm), (eh, em)):
        if not (0 <= h <= 23 and 0 <= mi <= 59):
            raise ConfigError(f"active_hours 时间越界: {raw!r}")
    return dtime(sh, sm), dtime(eh, em)


def _parse_rule(raw: dict[str, Any], index: int) -> Rule:
    name = raw.get("name") or f"rule#{index}"
    match = raw.get("match") or {}
    kind = match.get("type", "keyword")

    replies = raw.get("reply")
    if isinstance(replies, str):
        replies = [replies]
    if not replies:
        raise ConfigError(f"规则 {name!r} 缺少 reply")

    if kind == "keyword":
        keywords = match.get("any") or []
        if not keywords:
            raise ConfigError(f"规则 {name!r} 的 keyword 匹配缺少 any 列表")
        return Rule(name=name, kind=kind, replies=replies, keywords=list(keywords))

    if kind == "regex":
        pattern = match.get("pattern")
        if not pattern:
            raise ConfigError(f"规则 {name!r} 的 regex 匹配缺少 pattern")
        try:
            compiled = re.compile(pattern)
        except re.error as exc:
            raise ConfigError(f"规则 {name!r} 的正则无法编译: {exc}") from exc
        return Rule(name=name, kind=kind, replies=replies, pattern=compiled)

    if kind == "always":
        return Rule(name=name, kind=kind, replies=replies)

    raise ConfigError(f"规则 {name!r} 的 match.type 只能是 keyword/regex/always，收到 {kind!r}")


def load_config(path: str | Path) -> Config:
    """从 YAML 读配置。任何格式错误都会抛 ConfigError 并指出问题所在。"""
    data = yaml.safe_load(Path(path).read_text(encoding="utf-8")) or {}
    return build_config(data)


def build_config(data: dict[str, Any]) -> Config:
    scope_raw = data.get("scope") or {}
    group_policy = scope_raw.get("reply_to_group", "only_at_me")
    if group_policy not in ("never", "only_at_me", "always"):
        raise ConfigError(
            f"scope.reply_to_group 只能是 never/only_at_me/always，收到 {group_policy!r}"
        )

    limits_raw = data.get("limits") or {}
    limits = Limits(**{k: v for k, v in limits_raw.items() if k in Limits.__annotations__})
    if limits.min_delay_seconds > limits.max_delay_seconds:
        raise ConfigError("limits.min_delay_seconds 不能大于 max_delay_seconds")

    fallback_raw = data.get("fallback") or {}
    fallback_kind = fallback_raw.get("type", "text")
    if fallback_kind not in ("llm", "text", "none"):
        raise ConfigError(f"fallback.type 只能是 llm/text/none，收到 {fallback_kind!r}")

    mode = data.get("reply_mode", "rules_then_ai")
    if mode not in ("ai", "rules", "rules_then_ai"):
        raise ConfigError(
            f"reply_mode 只能是 ai/rules/rules_then_ai，收到 {mode!r}"
        )

    persona = build_persona(data.get("persona") or {})
    if mode == "ai" and not persona.is_configured():
        raise ConfigError(
            "reply_mode 为 ai 时必须配置 persona.identity 或 persona.playbook，"
            "否则生成出来的只会是客服腔"
        )

    llm_raw = data.get("llm") or {}
    llm = LLMSettings(
        enabled=mode in ("ai", "rules_then_ai") and fallback_kind == "llm" or mode == "ai",
        model=llm_raw.get("model", "claude-opus-5"),
        max_tokens=int(llm_raw.get("max_tokens", 300)),
        effort=llm_raw.get("effort", "low"),
        style=llm_raw.get("style", LLMSettings.style),
        persona=llm_raw.get("persona", ""),
    )

    return Config(
        enabled=bool(data.get("enabled", True)),
        reply_mode=mode,
        persona=persona,
        signature=data.get("signature", ""),
        active_hours=[_parse_time_range(r) for r in (data.get("active_hours") or [])],
        scope=Scope(
            reply_to_private=bool(scope_raw.get("reply_to_private", True)),
            reply_to_group=group_policy,
            allow_contacts=list(scope_raw.get("allow_contacts") or []),
            block_contacts=list(scope_raw.get("block_contacts") or []),
            block_keywords=list(scope_raw.get("block_keywords") or []),
        ),
        limits=limits,
        rules=[_parse_rule(r, i) for i, r in enumerate(data.get("rules") or [])],
        fallback=Fallback(kind=fallback_kind, text=fallback_raw.get("text", "")),
        llm=llm,
    )
