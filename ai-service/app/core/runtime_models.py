"""Per-request model configuration. Durable authority is Spring, never process env mutation."""
from __future__ import annotations

import os
import secrets
from contextvars import ContextVar
from typing import Literal
from urllib.parse import urlsplit

import httpx2
from pydantic import BaseModel, ConfigDict, Field, SecretStr

from app.core.animation_config import AnimationModelSettings
from app.core.config import _validate_base_url, OllamaGenerationSettings
from app.providers.base import ModelConfigurationError


class ConnectionSettings(BaseModel):
    model_config = ConfigDict(extra='forbid', frozen=True)
    provider: Literal['deepseek', 'openai_compatible', 'ollama', 'inherit']
    baseUrl: str = Field(max_length=2000)
    model: str = Field(max_length=100)
    timeoutSeconds: int = Field(ge=1, le=60)
    maxRetries: int = Field(ge=0, le=2)
    responseFormat: Literal['json_object', 'json_schema', 'text']
    tokenLimitField: Literal['max_tokens', 'max_completion_tokens']
    sendTemperature: bool


class RuntimeProfile(BaseModel):
    model_config = ConfigDict(extra='forbid', frozen=True)
    slot: Literal['DEFAULT', 'ANIMATION']
    version: int = Field(ge=0)
    settings: ConnectionSettings
    apiKey: SecretStr | None = Field(default=None, max_length=4096, repr=False)
    useEnvironmentKey: bool = False


runtime_profiles: ContextVar[tuple[RuntimeProfile, ...]] = ContextVar('runtime_models', default=())


def runtime_enabled() -> bool:
    return os.getenv('AI_RUNTIME_SETTINGS_ENABLED', 'false').lower() == 'true'


def _env_prefix(slot: str, provider: str) -> str:
    """Return the deployment variable namespace for a model slot/provider."""
    if slot == 'ANIMATION':
        return 'TUTOR_ANIMATION'
    return {
        'deepseek': 'DEEPSEEK',
        'openai_compatible': 'OPENAI_COMPATIBLE',
        'ollama': 'OLLAMA',
    }.get(provider, 'DEEPSEEK')


def deployment_profile(slot: str) -> dict:
    animation = slot == 'ANIMATION'
    provider = os.getenv('TUTOR_ANIMATION_MODEL_PROVIDER', 'inherit') if animation else os.getenv('AI_MODEL_PROVIDER', 'deepseek')
    prefix = _env_prefix(slot, provider)
    return {
        'slot': slot,
        'settings': ConnectionSettings(
            provider=provider,
            baseUrl=os.getenv(prefix + '_BASE_URL', '' if animation else ('http://localhost:11434' if provider == 'ollama' else 'https://api.deepseek.com')).rstrip('/'),
            model=os.getenv(prefix + ('_MODEL' if animation else '_CHAT_MODEL'), ''),
            timeoutSeconds=max(1, min(60, int(float(os.getenv(prefix + '_TIMEOUT_SECONDS', '45' if animation else '60'))))),
            maxRetries=max(0, min(2, int(os.getenv(prefix + '_MAX_RETRIES', '1' if animation else '2')))),
            responseFormat=os.getenv(prefix + '_RESPONSE_FORMAT', 'json_object'),
            tokenLimitField=os.getenv(prefix + '_TOKEN_LIMIT_FIELD', 'max_tokens'),
            sendTemperature=os.getenv(prefix + '_SEND_TEMPERATURE', 'false' if animation else 'true').lower() == 'true',
        ).model_dump(),
        'apiKeyConfigured': bool(os.getenv(prefix + '_API_KEY', '').strip()) if provider not in {'inherit', 'ollama'} else False,
        'runtimeEnabled': runtime_enabled(),
    }


def validate_admin_ollama_url(value: str) -> str:
    """An authenticated Spring administrator explicitly authorizes this endpoint.

    Environment-only providers retain their separate OLLAMA_ALLOWED_HOSTS policy.
    No hostname substitution is performed: Docker users choose the host gateway.
    """
    normalized = value.rstrip('/')
    try:
        parsed = urlsplit(normalized)
        if (value != value.strip() or any(ord(c) < 33 for c in value)
                or parsed.scheme not in {'http', 'https'} or not parsed.hostname
                or parsed.username is not None or parsed.password is not None
                or parsed.query or parsed.fragment
                or parsed.path.endswith(('/api/chat', '/api/tags', '/api/generate', '/api', '/v1'))):
            raise ValueError('Invalid endpoint')
        _ = parsed.port
    except ValueError:
        raise ModelConfigurationError('Use an Ollama base address without credentials or an API endpoint.') from None
    return normalized


def resolve_profile(profile: RuntimeProfile) -> RuntimeProfile:
    settings = profile.settings
    if settings.provider == 'inherit':
        if profile.slot != 'ANIMATION':
            raise ModelConfigurationError('Only animation may inherit the default model.')
        return profile.model_copy(update={'apiKey': None, 'useEnvironmentKey': False})
    if not settings.model.strip() or settings.model != settings.model.strip():
        raise ModelConfigurationError('A model name is required.')
    if any(ord(char) < 32 for char in settings.model):
        raise ModelConfigurationError('The model name contains invalid characters.')
    normalized = (validate_admin_ollama_url if settings.provider == 'ollama' else _validate_base_url)(settings.baseUrl)
    if normalized.endswith('/chat/completions') or normalized.endswith('/api/chat'):
        raise ModelConfigurationError('Use an API base address, not a chat endpoint.')
    resolved_key = None if settings.provider == 'ollama' else profile.apiKey
    if profile.useEnvironmentKey and settings.provider != 'ollama':
        original = deployment_profile(profile.slot)['settings']
        if original['provider'] != settings.provider or original['baseUrl'] != normalized:
            raise ModelConfigurationError('A new API key is required when the provider or address changes.')
        prefix = _env_prefix(profile.slot, settings.provider)
        resolved_key = SecretStr(os.getenv(prefix + '_API_KEY', '').strip())
    if settings.provider != 'ollama' and (not resolved_key or not resolved_key.get_secret_value().strip()):
        raise ModelConfigurationError('An API key is required.')
    if resolved_key and any(ord(char) < 33 for char in resolved_key.get_secret_value()):
        raise ModelConfigurationError('The API key contains invalid whitespace.')
    return profile.model_copy(update={'apiKey': resolved_key, 'settings': settings.model_copy(update={'baseUrl': normalized})})


def provider_from_profile(profile: RuntimeProfile):
    from app.providers.deepseek import DeepSeekProvider
    from app.providers.ollama import OllamaProvider
    from app.providers.openai_compatible import OpenAICompatibleProvider
    resolved = resolve_profile(profile)
    s = resolved.settings
    if s.provider == 'inherit':
        raise ModelConfigurationError('Inherited models must be resolved through the default factory.')
    shared = dict(base_url=s.baseUrl, chat_model=s.model, timeout_seconds=s.timeoutSeconds,
        total_timeout_seconds=60 if profile.slot == 'ANIMATION' or s.provider == 'ollama' else 110,
        max_retries=s.maxRetries, retry_base_seconds=.25, retry_max_seconds=2)
    if s.provider == 'ollama':
        return OllamaProvider(OllamaGenerationSettings(**shared))
    settings = AnimationModelSettings(**shared, api_key=resolved.apiKey,
        response_format=s.responseFormat, token_limit_field=s.tokenLimitField, send_temperature=s.sendTemperature)
    return DeepSeekProvider(settings) if s.provider == 'deepseek' else OpenAICompatibleProvider(settings)


async def fetch_request_profiles(request) -> tuple[RuntimeProfile, ...]:
    """Authenticate before fetching. Failure is explicit, not a fallback to old credentials."""
    if not runtime_enabled():
        return ()
    path = request.url.path
    if not (path.startswith('/internal/ai/') or path == '/internal/models/health'):
        return ()
    expected = os.getenv('INTERNAL_SERVICE_TOKEN', 'local-development-internal-token-2026')
    if not secrets.compare_digest(request.headers.get('X-Internal-Token', ''), expected):
        return ()
    base = os.getenv('BUSINESS_SERVICE_INTERNAL_BASE_URL', 'http://localhost:8080').rstrip('/')
    async with httpx2.AsyncClient(timeout=3, trust_env=False) as client:
        response = await client.get(base + '/api/internal/ai/model-settings', headers={
            'X-Internal-Token': expected, 'X-Request-Id': request.state.request_id,
        })
        response.raise_for_status()
        payload = response.json()
        if not isinstance(payload, list) or len(payload) > 2:
            raise ValueError('Invalid model snapshot.')
        profiles = tuple(RuntimeProfile.model_validate(item) for item in payload)
        if len({p.slot for p in profiles}) != len(profiles):
            raise ValueError('Duplicate model slot.')
        return profiles
