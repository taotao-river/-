"""跨平台微信自动回复核心。"""

from .config import Config, ConfigError, build_config, load_config
from .engine import HARD_BLOCK_KEYWORDS, ReplyEngine
from .models import IncomingMessage, ReplyDecision

__all__ = [
    "Config",
    "ConfigError",
    "HARD_BLOCK_KEYWORDS",
    "IncomingMessage",
    "ReplyDecision",
    "ReplyEngine",
    "build_config",
    "load_config",
]
