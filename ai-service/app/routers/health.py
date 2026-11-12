import asyncio
import os
from datetime import UTC, datetime

from fastapi import APIRouter, Depends

from app.contracts.models import DependencyHealth, ModelsHealthResponse
from app.core.config import AIServiceSettings, MemoryConfigurationError, MemorySettings
from app.core.security import require_internal_headers
from app.providers.base import ModelConfigurationError, ModelProvider
from app.providers.factory import create_model_provider
from app.providers.factory import create_embedding_provider
from app.memory.store import MemoryStoreError
from app.services.memory import create_memory_service
from app.providers.base import EmbeddingProviderError


router = APIRouter(tags=["system"])


@router.get("/health", operation_id="internalHealth")
def health() -> dict[str, str]:
    return {
        "status": "UP",
        "service": "education-agent-ai-service",
        "timestamp": datetime.now(UTC).isoformat(),
    }


@router.get(
    "/internal/models/health",
    operation_id="internalModelsHealth",
    response_model=ModelsHealthResponse,
    response_model_exclude_none=True,
    dependencies=[Depends(require_internal_headers)],
)
async def models_health() -> ModelsHealthResponse:
    return await collect_models_health()


async def collect_models_health(
    *,
    settings: AIServiceSettings | None = None,
    provider: ModelProvider | None = None,
) -> ModelsHealthResponse:
    """Collect V2.1 dependency health without exposing credentials or raw errors."""
    selected = settings or AIServiceSettings.from_env()
    provider_name = selected.generation_provider or None
    model_name = (
        os.getenv("OLLAMA_CHAT_MODEL", "").strip()
        if provider_name == "ollama"
        else os.getenv("DEEPSEEK_CHAT_MODEL", "").strip()
    ) or None
    owns_provider = provider is None

    try:
        active_provider = provider or create_model_provider(selected)
    except ModelConfigurationError as exc:
        generation_provider = DependencyHealth(
            status="DOWN",
            name=provider_name,
            detail=str(exc),
        )
        chat_model = DependencyHealth(
            status="DOWN",
            name=model_name,
            detail="The configured chat model could not be checked.",
        )
    else:
        try:
            provider_health = await active_provider.check_health()
        finally:
            if owns_provider:
                await active_provider.aclose()
        generation_provider = DependencyHealth(
            status=provider_health.provider_status,
            name=active_provider.provider_name,
            latency_ms=provider_health.latency_ms,
            detail=provider_health.provider_detail,
        )
        chat_model = DependencyHealth(
            status=provider_health.model_status,
            name=active_provider.model_name,
            version=provider_health.model_version,
            latency_ms=provider_health.latency_ms,
            detail=provider_health.model_detail,
        )

    embedding_provider, chroma, ollama = await _memory_dependency_health(
        selected.memory_embedding_provider
    )
    if generation_provider.status == "DOWN" or chat_model.status == "DOWN":
        overall_status = "DOWN"
    elif embedding_provider.status != "UP" or chroma.status != "UP":
        overall_status = "DEGRADED"
    else:
        overall_status = "UP"

    return ModelsHealthResponse(
        status=overall_status,
        service="education-agent-ai-service",
        generation_provider=generation_provider,
        chat_model=chat_model,
        embedding_provider=embedding_provider,
        chroma=chroma,
        ollama=ollama,
        timestamp=datetime.now(UTC),
    )


async def _memory_dependency_health(
    embedding_provider_name: str,
) -> tuple[DependencyHealth, DependencyHealth, DependencyHealth | None]:
    if embedding_provider_name == "disabled":
        detail = "Learning-memory embedding is disabled."
        return (
            DependencyHealth(status="DEGRADED", name="disabled", detail=detail),
            DependencyHealth(status="DEGRADED", name="chroma", detail=detail),
            None,
        )
    provider = None
    try:
        settings = MemorySettings.from_env()
        provider = create_embedding_provider(settings)
        provider_health = await provider.check_health()
        embedding = DependencyHealth(
            status=provider_health.status,
            name=provider.provider_name,
            version=provider_health.model_version,
            latency_ms=provider_health.latency_ms,
            detail=provider_health.detail,
        )
        await asyncio.to_thread(lambda: create_memory_service().heartbeat())
        chroma = DependencyHealth(status="UP", name="chroma")
    except (MemoryConfigurationError, EmbeddingProviderError, MemoryStoreError) as exc:
        embedding = DependencyHealth(
            status="DOWN", name=embedding_provider_name or None, detail=str(exc)
        )
        chroma = DependencyHealth(
            status="DOWN", name="chroma", detail="The memory index could not be checked."
        )
    finally:
        if provider is not None:
            await provider.aclose()
    return embedding, chroma, embedding if embedding_provider_name == "ollama" else None
