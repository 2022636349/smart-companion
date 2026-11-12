"""Independent configuration for the Tutor animation specialist only."""
from __future__ import annotations

import os
from dataclasses import dataclass

from pydantic import SecretStr

from app.core.config import DeepSeekSettings, _read_float, _read_int, _validate_base_url
from app.providers.base import ModelConfigurationError


def animation_provider_mode() -> str:
    mode = os.getenv('TUTOR_ANIMATION_MODEL_PROVIDER', 'inherit').strip().lower()
    if mode not in {'inherit', 'openai_compatible'}:
        raise ModelConfigurationError('TUTOR_ANIMATION_MODEL_PROVIDER must be inherit or openai_compatible.')
    return mode


@dataclass(frozen=True, slots=True)
class AnimationModelSettings(DeepSeekSettings):
    response_format: str = 'json_object'
    token_limit_field: str = 'max_tokens'
    send_temperature: bool = False

    @classmethod
    def from_env(cls) -> AnimationModelSettings:
        names = ('TUTOR_ANIMATION_BASE_URL', 'TUTOR_ANIMATION_API_KEY', 'TUTOR_ANIMATION_MODEL')
        values = {name: os.getenv(name, '').strip() for name in names}
        missing = [name for name, value in values.items() if not value]
        if missing:
            raise ModelConfigurationError('Missing animation model settings: ' + ', '.join(missing) + '.')
        try:
            base_url = _validate_base_url(values['TUTOR_ANIMATION_BASE_URL'])
        except ModelConfigurationError as exc:
            raise ModelConfigurationError(str(exc).replace('DEEPSEEK_BASE_URL', 'TUTOR_ANIMATION_BASE_URL')) from None
        if base_url.endswith('/chat/completions'):
            raise ModelConfigurationError('TUTOR_ANIMATION_BASE_URL must be the API base (often ending in /v1), not /chat/completions.')
        model = values['TUTOR_ANIMATION_MODEL']
        if len(model) > 100:
            raise ModelConfigurationError('TUTOR_ANIMATION_MODEL must be at most 100 characters.')
        response_format = os.getenv('TUTOR_ANIMATION_RESPONSE_FORMAT', 'json_object').strip().lower()
        if response_format not in {'json_object', 'json_schema', 'text'}:
            raise ModelConfigurationError('TUTOR_ANIMATION_RESPONSE_FORMAT must be json_object, json_schema, or text.')
        token_field = os.getenv('TUTOR_ANIMATION_TOKEN_LIMIT_FIELD', 'max_tokens').strip().lower()
        if token_field not in {'max_tokens', 'max_completion_tokens'}:
            raise ModelConfigurationError('TUTOR_ANIMATION_TOKEN_LIMIT_FIELD must be max_tokens or max_completion_tokens.')
        temperature = os.getenv('TUTOR_ANIMATION_SEND_TEMPERATURE', 'false').strip().lower()
        if temperature not in {'true', 'false'}:
            raise ModelConfigurationError('TUTOR_ANIMATION_SEND_TEMPERATURE must be true or false.')
        return cls(
            api_key=SecretStr(values['TUTOR_ANIMATION_API_KEY']), base_url=base_url, chat_model=model,
            timeout_seconds=_read_float('TUTOR_ANIMATION_TIMEOUT_SECONDS', 45, minimum=.1, maximum=60),
            total_timeout_seconds=60,
            max_retries=_read_int('TUTOR_ANIMATION_MAX_RETRIES', 1, minimum=0, maximum=2),
            retry_base_seconds=.25, retry_max_seconds=2,
            response_format=response_format, token_limit_field=token_field, send_temperature=temperature == 'true',
        )
