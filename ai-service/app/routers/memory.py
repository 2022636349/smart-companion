from __future__ import annotations

import asyncio
from typing import Annotated, Literal, NoReturn

from fastapi import APIRouter, Depends, HTTPException, Path, Query, status

from app.contracts.memory import (
    DeleteIndexResponse,
    MemorySearchRequest,
    MemorySearchResponse,
    MemoryUpsertRequest,
    MemoryUpsertResponse,
    MAX_INT64,
    MAX_GENERATION,
)
from app.core.config import MemoryConfigurationError
from app.core.security import require_internal_headers
from app.memory.store import MemoryStoreConflictError, MemoryStoreUnavailableError
from app.providers.base import (
    EmbeddingConfigurationError,
    EmbeddingResponseError,
    EmbeddingTimeoutError,
    EmbeddingUnavailableError,
)
from app.services.memory import (
    MemoryDeadlineExceededError,
    MemoryRequestIdMismatchError,
    create_memory_service,
)


router = APIRouter(prefix="/internal/memory", tags=["memory"])


@router.post("/upsert", operation_id="internalUpsertMemory", response_model=MemoryUpsertResponse)
async def upsert_memory(
    request: MemoryUpsertRequest,
    trace_request_id: Annotated[str, Depends(require_internal_headers)],
) -> MemoryUpsertResponse:
    try:
        service = await asyncio.to_thread(create_memory_service)
        return await service.upsert(request, trace_request_id=trace_request_id)
    except MemoryRequestIdMismatchError:
        _raise(status.HTTP_422_UNPROCESSABLE_CONTENT, "MEMORY_REQUEST_ID_MISMATCH", "request_id must match X-Request-Id.")
    except MemoryStoreConflictError:
        _raise(status.HTTP_422_UNPROCESSABLE_CONTENT, "MEMORY_INDEX_CONFLICT", "The memory index request is stale or inconsistent.")
    except (EmbeddingTimeoutError, MemoryDeadlineExceededError):
        _raise(status.HTTP_504_GATEWAY_TIMEOUT, "MEMORY_INDEX_TIMEOUT", "The memory index operation timed out.")
    except (MemoryConfigurationError, EmbeddingConfigurationError, EmbeddingUnavailableError, EmbeddingResponseError, MemoryStoreUnavailableError):
        _raise(status.HTTP_503_SERVICE_UNAVAILABLE, "MEMORY_INDEX_UNAVAILABLE", "The memory index is unavailable.")


@router.post("/search", operation_id="internalSearchMemory", response_model=MemorySearchResponse)
async def search_memory(
    request: MemorySearchRequest,
    trace_request_id: Annotated[str, Depends(require_internal_headers)],
) -> MemorySearchResponse:
    try:
        service = await asyncio.to_thread(create_memory_service)
        return await service.search(request, trace_request_id=trace_request_id)
    except MemoryRequestIdMismatchError:
        _raise(status.HTTP_422_UNPROCESSABLE_CONTENT, "MEMORY_REQUEST_ID_MISMATCH", "request_id must match X-Request-Id.")
    except (EmbeddingTimeoutError, MemoryDeadlineExceededError):
        _raise(status.HTTP_504_GATEWAY_TIMEOUT, "MEMORY_SEARCH_TIMEOUT", "The memory search timed out.")
    except (MemoryConfigurationError, EmbeddingConfigurationError, EmbeddingUnavailableError, EmbeddingResponseError, MemoryStoreUnavailableError):
        _raise(status.HTTP_503_SERVICE_UNAVAILABLE, "MEMORY_INDEX_UNAVAILABLE", "The memory index is unavailable.")


@router.delete("/{memory_id}", operation_id="internalDeleteMemory", response_model=DeleteIndexResponse)
async def delete_memory(
    memory_id: Annotated[int, Path(ge=1, le=MAX_INT64)],
    mode: Annotated[Literal["DEINDEX", "TERMINAL_DELETE"], Query()],
    generation: Annotated[int, Query(ge=1, le=MAX_GENERATION)],
    trace_request_id: Annotated[str, Depends(require_internal_headers)],
) -> DeleteIndexResponse:
    try:
        service = await asyncio.to_thread(create_memory_service)
        return await service.delete(
            memory_id,
            mode,
            generation,
            trace_request_id=trace_request_id,
        )
    except (EmbeddingTimeoutError, MemoryDeadlineExceededError):
        _raise(status.HTTP_504_GATEWAY_TIMEOUT, "MEMORY_DELETE_TIMEOUT", "The memory delete timed out.")
    except MemoryStoreConflictError:
        _raise(status.HTTP_422_UNPROCESSABLE_CONTENT, "MEMORY_INDEX_CONFLICT", "The memory index request is stale or inconsistent.")
    except (MemoryConfigurationError, EmbeddingConfigurationError, EmbeddingUnavailableError, EmbeddingResponseError, MemoryStoreUnavailableError):
        _raise(status.HTTP_503_SERVICE_UNAVAILABLE, "MEMORY_INDEX_UNAVAILABLE", "The memory index is unavailable.")


def _raise(status_code: int, code: str, message: str) -> NoReturn:
    raise HTTPException(
        status_code=status_code,
        detail={"code": code, "owner": "D", "message": message},
    )
