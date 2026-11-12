from __future__ import annotations

import asyncio
import json
import math
import time
from collections.abc import Awaitable, Callable
from dataclasses import dataclass
from typing import Any

import httpx2
from pydantic import BaseModel, ValidationError

from app.core.config import MemorySettings, OllamaGenerationSettings
from app.providers.base import (
    EmbeddingProviderHealth,
    EmbeddingResponseError,
    EmbeddingTimeoutError,
    EmbeddingUnavailableError,
    ModelAuthenticationError,
    ModelCallMetadata,
    ModelProviderHealth,
    ModelRateLimitError,
    ModelRequest,
    ModelRequestError,
    ModelResponse,
    ModelResponseError,
    ModelServerError,
    ModelTimeoutError,
    ModelTransportError,
    StructuredOutputError,
    TokenUsage,
)


Sleep = Callable[[float], Awaitable[None]]
Clock = Callable[[], float]


@dataclass(slots=True)
class _RequestProgress:
    attempts: int = 0


class OllamaProvider:
    """Local/deployment Ollama chat adapter implementing the generation boundary."""

    provider_name = "ollama"

    def __init__(
        self,
        settings: OllamaGenerationSettings | None = None,
        *,
        client: httpx2.AsyncClient | None = None,
        sleep: Sleep = asyncio.sleep,
        clock: Clock = time.perf_counter,
    ) -> None:
        self._settings = settings or OllamaGenerationSettings.from_env()
        self._sleep = sleep
        self._clock = clock
        self._owns_client = client is None
        self._client = client or httpx2.AsyncClient(
            timeout=self._settings.timeout_seconds, trust_env=False
        )

    @property
    def model_name(self) -> str:
        return self._settings.chat_model

    async def aclose(self) -> None:
        if self._owns_client:
            await self._client.aclose()

    async def generate(
        self, request: ModelRequest, *, output_model: type[BaseModel] | None = None
    ) -> ModelResponse:
        payload = self._build_payload(request, output_model=output_model)
        started = self._clock()
        progress = _RequestProgress()
        try:
            async with asyncio.timeout(self._settings.total_timeout_seconds):
                response, attempts = await self._request_with_retries(
                    "POST", "/api/chat", json_body=payload, progress=progress
                )
                body = self._parse_json(response, attempts)
                provider_request_id = self._provider_request_id(response)
                try:
                    message = body["message"]
                    content = message["content"]
                    done_reason = body.get("done_reason") or (
                        "stop" if body.get("done") is True else None
                    )
                    model = body.get("model") or self.model_name
                    input_units = body["prompt_eval_count"]
                    output_units = body["eval_count"]
                except (KeyError, TypeError) as exc:
                    raise ModelResponseError(
                        "Ollama returned an incomplete chat response.",
                        provider_request_id=provider_request_id, attempts=attempts,
                    ) from exc
                if not isinstance(content, str) or not content.strip():
                    raise ModelResponseError("Ollama returned empty completion content.", provider_request_id=provider_request_id, attempts=attempts)
                if not isinstance(done_reason, str) or not done_reason:
                    raise ModelResponseError("Ollama returned an invalid finish reason.", provider_request_id=provider_request_id, attempts=attempts)
                if not isinstance(model, str) or not model:
                    raise ModelResponseError("Ollama returned an invalid model identifier.", provider_request_id=provider_request_id, attempts=attempts)
                if any(type(value) is not int or value < 0 for value in (input_units, output_units)):
                    raise ModelResponseError("Ollama returned invalid token usage metadata.", provider_request_id=provider_request_id, attempts=attempts)
                structured = self._validate_structured(
                    content, output_model, provider_request_id, attempts
                )
                return ModelResponse(
                    text=content,
                    structured_content=structured,
                    metadata=ModelCallMetadata(
                        provider=self.provider_name, model=model,
                        token_usage=TokenUsage(input_units, output_units, input_units + output_units),
                        finish_reason=done_reason,
                        latency_ms=self._elapsed_ms(started),
                        provider_request_id=provider_request_id,
                        prompt_version=request.prompt_version, attempts=attempts,
                    ),
                )
        except TimeoutError as exc:
            raise ModelTimeoutError(
                "Ollama generation exceeded the configured total timeout.",
                attempts=max(1, progress.attempts),
            ) from exc

    async def check_health(self) -> ModelProviderHealth:
        started = self._clock()
        try:
            response, attempts = await self._request_with_retries("GET", "/api/tags")
            body = self._parse_json(response, attempts)
            models = body.get("models")
            if not isinstance(models, list):
                raise ModelResponseError("Ollama returned an invalid model list.", attempts=attempts)
            names = {item.get("name") or item.get("model") for item in models if isinstance(item, dict)}
            available = self.model_name in names or (':' not in self.model_name and self.model_name + ':latest' in names)
            return ModelProviderHealth(
                provider_status="UP", model_status="UP" if available else "DOWN",
                latency_ms=self._elapsed_ms(started),
                model_detail=None if available else "The configured chat model is not installed in Ollama.",
                model_version=self.model_name if available else None,
            )
        except ModelAuthenticationError:
            detail = "Ollama authentication failed."
        except ModelRateLimitError:
            detail = "Ollama health check remained rate limited after retries."
        except ModelTimeoutError:
            detail = "Ollama health check timed out after retries."
        except ModelServerError:
            detail = "Ollama health check failed with a server error after retries."
        except ModelTransportError:
            detail = "Ollama health check could not reach the provider after retries."
        except (ModelRequestError, ModelResponseError) as exc:
            detail = str(exc)
        return ModelProviderHealth(
            provider_status="DOWN", model_status="DOWN", latency_ms=self._elapsed_ms(started),
            provider_detail=detail, model_detail="The configured chat model could not be verified.",
        )

    def _build_payload(self, request: ModelRequest, *, output_model: type[BaseModel] | None) -> dict[str, Any]:
        payload: dict[str, Any] = {
            "model": self.model_name,
            "messages": [{"role": item.role, "content": item.content} for item in request.messages],
            "stream": False,
        }
        options: dict[str, Any] = {}
        if request.max_tokens is not None:
            options["num_predict"] = request.max_tokens
        if request.temperature is not None:
            options["temperature"] = request.temperature
        if options:
            payload["options"] = options
        if output_model is not None:
            if not isinstance(output_model, type) or not issubclass(output_model, BaseModel):
                raise TypeError("output_model must be a Pydantic BaseModel subclass.")
            payload["format"] = output_model.model_json_schema()
            schema = json.dumps(output_model.model_json_schema(), ensure_ascii=False, separators=(",", ":"))
            instruction = "Return exactly one valid JSON object matching this JSON Schema; do not wrap the JSON in Markdown: " + schema
            payload["messages"][0:0] = [{"role": "system", "content": instruction}]
        return payload

    async def _request_with_retries(self, method: str, path: str, *, json_body: dict[str, Any] | None = None, progress: _RequestProgress | None = None) -> tuple[httpx2.Response, int]:
        maximum_attempts = self._settings.max_retries + 1
        for attempt in range(1, maximum_attempts + 1):
            if progress is not None:
                progress.attempts = attempt
            try:
                response = await self._client.request(method, f"{self._settings.base_url}{path}", json=json_body)
            except httpx2.TimeoutException as exc:
                if attempt < maximum_attempts:
                    await self._sleep(self._retry_delay(attempt)); continue
                raise ModelTimeoutError("Ollama request timed out after retries.", attempts=attempt) from exc
            except httpx2.TransportError as exc:
                if attempt < maximum_attempts:
                    await self._sleep(self._retry_delay(attempt)); continue
                raise ModelTransportError("Ollama could not be reached after retries.", attempts=attempt) from exc
            request_id = self._provider_request_id(response)
            if 200 <= response.status_code < 300:
                return response, attempt
            if response.status_code == 401:
                raise ModelAuthenticationError(
                    "Ollama authentication failed.", status_code=401,
                    provider_request_id=request_id, attempts=attempt,
                )
            if response.status_code == 429:
                if attempt < maximum_attempts:
                    await self._sleep(self._retry_delay(attempt)); continue
                raise ModelRateLimitError("Ollama remained rate limited after retries.", status_code=429, provider_request_id=request_id, attempts=attempt)
            if 500 <= response.status_code <= 599:
                if attempt < maximum_attempts:
                    await self._sleep(self._retry_delay(attempt)); continue
                raise ModelServerError("Ollama returned a server error after retries.", status_code=response.status_code, provider_request_id=request_id, attempts=attempt)
            raise ModelRequestError(f"Ollama rejected the request with HTTP {response.status_code}.", status_code=response.status_code, provider_request_id=request_id, attempts=attempt)
        raise AssertionError("unreachable")

    def _retry_delay(self, attempt: int) -> float:
        return min(self._settings.retry_base_seconds * (2 ** (attempt - 1)), self._settings.retry_max_seconds)

    @staticmethod
    def _provider_request_id(response: httpx2.Response) -> str | None:
        for name in ("x-request-id", "x-requestid", "request-id"):
            if value := response.headers.get(name):
                return value[:200]
        return None

    @staticmethod
    def _parse_json(response: httpx2.Response, attempts: int) -> dict[str, Any]:
        try:
            body = response.json()
        except (ValueError, TypeError) as exc:
            raise ModelResponseError("Ollama returned a non-JSON response.", provider_request_id=OllamaProvider._provider_request_id(response), attempts=attempts) from exc
        if not isinstance(body, dict):
            raise ModelResponseError("Ollama returned an invalid JSON response.", provider_request_id=OllamaProvider._provider_request_id(response), attempts=attempts)
        return body

    @staticmethod
    def _validate_structured(content: str, output_model: type[BaseModel] | None, provider_request_id: str | None, attempts: int) -> dict[str, Any] | None:
        if output_model is None:
            return None
        try:
            parsed = json.loads(content)
            value = output_model.model_validate(parsed).model_dump(mode="json")
        except (ValueError, TypeError, ValidationError, AttributeError) as exc:
            raise StructuredOutputError("Ollama returned structured content that failed validation.", provider_request_id=provider_request_id, attempts=attempts) from exc
        if not isinstance(value, dict):
            raise StructuredOutputError("Ollama returned structured content with an invalid root type.", provider_request_id=provider_request_id, attempts=attempts)
        return value

    def _elapsed_ms(self, started: float) -> int:
        return max(0, round((self._clock() - started) * 1000))


class OllamaEmbeddingProvider:
    """Local Ollama embedding adapter; it is never used for generation."""

    provider_name = "ollama"

    def __init__(
        self,
        settings: MemorySettings | None = None,
        *,
        client: httpx2.AsyncClient | None = None,
    ) -> None:
        self._settings = settings or MemorySettings.from_env()
        self._owns_client = client is None
        self._client = client or httpx2.AsyncClient(
            timeout=self._settings.embedding_timeout_seconds,
            trust_env=False,
        )

    @property
    def model_name(self) -> str:
        return self._settings.embedding_model

    async def aclose(self) -> None:
        if self._owns_client:
            await self._client.aclose()

    async def embed(self, texts: tuple[str, ...]) -> list[list[float]]:
        if not texts or any(not isinstance(text, str) or not text.strip() for text in texts):
            raise ValueError("Embedding input texts must be non-empty strings.")
        try:
            response = await self._client.post(
                f"{self._settings.ollama_base_url}/api/embed",
                json={"model": self.model_name, "input": list(texts), "truncate": False},
            )
        except httpx2.TimeoutException as exc:
            raise EmbeddingTimeoutError("Ollama embedding timed out.") from exc
        except httpx2.HTTPError as exc:
            raise EmbeddingUnavailableError("Ollama embedding is unavailable.") from exc
        if response.status_code >= 500:
            raise EmbeddingUnavailableError("Ollama embedding is unavailable.")
        if response.status_code >= 400:
            raise EmbeddingUnavailableError("Ollama rejected the embedding request.")
        try:
            body = response.json()
            vectors = body["embeddings"]
        except (ValueError, KeyError, TypeError) as exc:
            raise EmbeddingResponseError("Ollama returned invalid embedding JSON.") from exc
        if not isinstance(vectors, list) or len(vectors) != len(texts):
            raise EmbeddingResponseError("Ollama returned the wrong embedding count.")
        dimension: int | None = None
        validated: list[list[float]] = []
        for vector in vectors:
            if not isinstance(vector, list) or not vector:
                raise EmbeddingResponseError("Ollama returned an empty embedding.")
            if dimension is None:
                dimension = len(vector)
            if len(vector) != dimension:
                raise EmbeddingResponseError("Ollama returned inconsistent dimensions.")
            converted: list[float] = []
            for value in vector:
                if isinstance(value, bool) or not isinstance(value, (int, float)):
                    raise EmbeddingResponseError("Ollama returned a non-numeric embedding.")
                number = float(value)
                if not math.isfinite(number):
                    raise EmbeddingResponseError("Ollama returned a non-finite embedding.")
                converted.append(number)
            validated.append(converted)
        return validated

    async def check_health(self) -> EmbeddingProviderHealth:
        started = time.perf_counter()
        try:
            await self.embed(("health",))
        except (EmbeddingTimeoutError, EmbeddingUnavailableError, EmbeddingResponseError) as exc:
            return EmbeddingProviderHealth(
                status="DOWN",
                latency_ms=max(0, round((time.perf_counter() - started) * 1000)),
                detail=str(exc),
            )
        return EmbeddingProviderHealth(
            status="UP",
            latency_ms=max(0, round((time.perf_counter() - started) * 1000)),
            model_version=self.model_name,
        )
