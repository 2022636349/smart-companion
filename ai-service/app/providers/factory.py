from __future__ import annotations

from collections.abc import Callable

from app.core.animation_config import AnimationModelSettings, animation_provider_mode

from app.core.config import (
    AIServiceSettings,
    DeepSeekSettings,
    MemorySettings,
    OllamaGenerationSettings,
)
from app.providers.base import (
    EmbeddingConfigurationError,
    EmbeddingProvider,
    ModelConfigurationError,
    ModelProvider,
)
from app.providers.deepseek import DeepSeekProvider
from app.providers.ollama import OllamaEmbeddingProvider, OllamaProvider
from app.providers.openai_compatible import OpenAICompatibleProvider
from app.providers.telemetry import TelemetryModelProvider


def create_model_provider(
    settings: AIServiceSettings | None = None,
) -> ModelProvider:
    from app.core.runtime_models import runtime_profiles, provider_from_profile
    profile = next((p for p in runtime_profiles.get() if p.slot == 'DEFAULT'), None)
    if profile is not None:
        return TelemetryModelProvider(provider_from_profile(profile))
    selected = settings or AIServiceSettings.from_env()
    if selected.generation_provider == "deepseek":
        return TelemetryModelProvider(DeepSeekProvider(DeepSeekSettings.from_env()))
    if selected.generation_provider == "ollama":
        return TelemetryModelProvider(OllamaProvider(OllamaGenerationSettings.from_env()))
    if selected.generation_provider == "openai_compatible":
        # Keep a deployment-only OpenAI-compatible mode useful when the
        # runtime settings bridge is intentionally disabled. The same
        # validation and provider construction path is used as for an admin
        # override; no process-wide settings are mutated.
        from app.core.runtime_models import RuntimeProfile, deployment_profile
        deployment = deployment_profile('DEFAULT')
        profile = RuntimeProfile.model_validate({
            'slot': 'DEFAULT',
            'version': 0,
            'settings': deployment['settings'],
            'apiKey': None,
            'useEnvironmentKey': True,
        })
        return TelemetryModelProvider(provider_from_profile(profile))
    raise ModelConfigurationError(
        f"Unsupported AI_MODEL_PROVIDER: {selected.generation_provider or '<empty>'}."
    )


def create_animation_model_provider(
    *, default_factory: Callable[[], ModelProvider] = create_model_provider,
) -> ModelProvider:
    from app.core.runtime_models import runtime_profiles, provider_from_profile
    profile = next((p for p in runtime_profiles.get() if p.slot == 'ANIMATION'), None)
    if profile is not None:
        if profile.settings.provider == 'inherit':
            return default_factory()
        return TelemetryModelProvider(provider_from_profile(profile))
    if animation_provider_mode() == "inherit":
        return default_factory()
    # Explicit overrides never borrow the default endpoint or key, even on failure.
    return TelemetryModelProvider(OpenAICompatibleProvider(AnimationModelSettings.from_env()))


def create_embedding_provider(
    settings: MemorySettings | None = None,
) -> EmbeddingProvider:
    selected = settings or MemorySettings.from_env()
    if selected.embedding_provider == "ollama":
        return OllamaEmbeddingProvider(selected)
    raise EmbeddingConfigurationError(
        "Memory embedding is disabled or unsupported."
    )
