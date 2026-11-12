from __future__ import annotations

from app.core.config import DeepSeekSettings
from app.providers.chat_completions import ChatCompletionsProvider


class DeepSeekProvider(ChatCompletionsProvider):
    """Default DeepSeek adapter; shares transport without changing its wire format."""

    provider_name = "deepseek"
    provider_label = "DeepSeek"

    def __init__(self, settings: DeepSeekSettings | None = None, **kwargs) -> None:
        super().__init__(settings or DeepSeekSettings.from_env(), **kwargs)
