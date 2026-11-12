from __future__ import annotations

import asyncio
import math
import threading
from collections.abc import Callable

from app.contracts.memory import (
    DeleteIndexResponse,
    DeleteMode,
    MemorySearchRequest,
    MemorySearchResponse,
    MemoryUpsertRequest,
    MemoryUpsertResponse,
)
from app.core.config import MemorySettings
from app.memory.store import LearningMemoryStore
from app.providers.base import EmbeddingConfigurationError, EmbeddingProvider
from app.providers.base import EmbeddingResponseError
from app.providers.factory import create_embedding_provider


class MemoryRequestIdMismatchError(ValueError):
    pass


class MemoryDeadlineExceededError(TimeoutError):
    pass


EmbeddingFactory = Callable[[], EmbeddingProvider]


class MemoryService:
    def __init__(
        self,
        store: LearningMemoryStore,
        *,
        provider_factory: EmbeddingFactory = create_embedding_provider,
        settings: MemorySettings,
    ) -> None:
        self._store = store
        self._provider_factory = provider_factory
        self._settings = settings

    async def upsert(
        self, request: MemoryUpsertRequest, *, trace_request_id: str
    ) -> MemoryUpsertResponse:
        self._check_request_id(request.request_id, trace_request_id)
        try:
            async with asyncio.timeout(self._settings.total_timeout_seconds):
                provider = self._provider_factory()
                try:
                    model_name = provider.model_name
                    embedding = _single_embedding(await provider.embed((request.content,)))
                finally:
                    await provider.aclose()
                indexed, index_state = await asyncio.to_thread(
                    self._store.upsert, request, embedding
                )
        except TimeoutError as exc:
            raise MemoryDeadlineExceededError("Memory upsert exceeded its deadline.") from exc
        return MemoryUpsertResponse(
            request_id=request.request_id,
            collection="learning_memory",
            memory_id=request.memory_id,
            generation=request.generation,
            indexed=indexed,
            embedding_model=model_name,
            memory_index_epoch=index_state.epoch,
            memory_rebuild_required=index_state.rebuild_required,
        )

    async def search(
        self, request: MemorySearchRequest, *, trace_request_id: str
    ) -> MemorySearchResponse:
        self._check_request_id(request.request_id, trace_request_id)
        try:
            async with asyncio.timeout(self._settings.total_timeout_seconds):
                provider = self._provider_factory()
                try:
                    embedding = _single_embedding(await provider.embed((request.query,)))
                finally:
                    await provider.aclose()
                items, index_state = await asyncio.to_thread(
                    self._store.search, request, embedding
                )
        except TimeoutError as exc:
            raise MemoryDeadlineExceededError("Memory search exceeded its deadline.") from exc
        return MemorySearchResponse(
            course_id=request.course_id,
            request_id=request.request_id,
            student_id=request.student_id,
            items=items,
            collection="learning_memory",
            memory_index_epoch=index_state.epoch,
            memory_rebuild_required=index_state.rebuild_required,
        )

    async def delete(
        self,
        memory_id: int,
        mode: DeleteMode,
        generation: int,
        *,
        trace_request_id: str,
    ) -> DeleteIndexResponse:
        try:
            async with asyncio.timeout(self._settings.total_timeout_seconds):
                provider = self._provider_factory()
                try:
                    embedding = _single_embedding(
                        await provider.embed(("learning memory index barrier",))
                    )
                finally:
                    await provider.aclose()
                deleted, index_state = await asyncio.to_thread(
                    self._store.delete, memory_id, mode, generation, embedding
                )
        except TimeoutError as exc:
            raise MemoryDeadlineExceededError("Memory delete exceeded its deadline.") from exc
        return DeleteIndexResponse(
            request_id=trace_request_id,
            collection="learning_memory",
            memory_id=memory_id,
            mode=mode,
            generation=generation,
            deleted_count=deleted,
            memory_index_epoch=index_state.epoch,
            memory_rebuild_required=index_state.rebuild_required,
        )

    def heartbeat(self) -> int:
        return self._store.heartbeat()

    def close(self) -> None:
        self._store.close()

    @staticmethod
    def _check_request_id(body: str, header: str) -> None:
        if body != header:
            raise MemoryRequestIdMismatchError(
                "request_id must match X-Request-Id."
            )


_service_lock = threading.Lock()
_service: MemoryService | None = None


def create_memory_service() -> MemoryService:
    global _service
    if _service is None:
        with _service_lock:
            if _service is None:
                settings = MemorySettings.from_env()
                if settings.embedding_provider == "disabled":
                    raise EmbeddingConfigurationError(
                        "Learning-memory embedding is disabled."
                    )
                _service = MemoryService(
                    LearningMemoryStore(
                        settings.chroma_persist_dir,
                        embedding_provider=settings.embedding_provider,
                        embedding_model=settings.embedding_model,
                    ),
                    settings=settings,
                )
    return _service


def close_memory_service() -> None:
    global _service
    with _service_lock:
        if _service is not None:
            _service.close()
            _service = None


def _single_embedding(vectors: list[list[float]]) -> list[float]:
    if len(vectors) != 1 or not vectors[0]:
        raise EmbeddingResponseError("The embedding provider returned invalid output.")
    result: list[float] = []
    for value in vectors[0]:
        if isinstance(value, bool) or not isinstance(value, (int, float)):
            raise EmbeddingResponseError("The embedding provider returned invalid output.")
        number = float(value)
        if not math.isfinite(number):
            raise EmbeddingResponseError("The embedding provider returned invalid output.")
        result.append(number)
    return result
