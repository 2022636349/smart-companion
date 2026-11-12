from datetime import datetime
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field


class DependencyHealth(BaseModel):
    status: Literal["UP", "DOWN", "DEGRADED"]
    name: str | None = None
    version: str | None = None
    latency_ms: int | None = Field(default=None, ge=0)
    detail: str | None = None


class ModelsHealthResponse(BaseModel):
    model_config = ConfigDict(protected_namespaces=())

    status: Literal["UP", "DEGRADED", "DOWN"]
    service: str
    generation_provider: DependencyHealth
    chat_model: DependencyHealth
    embedding_provider: DependencyHealth
    chroma: DependencyHealth
    ollama: DependencyHealth | None = None
    timestamp: datetime
