from __future__ import annotations

import asyncio
import json
import time
from collections.abc import Awaitable, Callable
from dataclasses import dataclass
from typing import Any

import httpx2
from pydantic import BaseModel, ValidationError

from app.core.config import DeepSeekSettings
from app.providers.base import (
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


class ChatCompletionsProvider:
    """Shared Chat Completions transport with bounded retries and validated output."""

    provider_name = "openai_compatible"
    provider_label = "Chat Completions provider"

    def __init__(
        self,
        settings: DeepSeekSettings,
        *,
        client: httpx2.AsyncClient | None = None,
        sleep: Sleep = asyncio.sleep,
        clock: Clock = time.perf_counter,
    ) -> None:
        self._settings = settings
        self._sleep = sleep
        self._clock = clock
        self._owns_client = client is None
        self._client = client or httpx2.AsyncClient(
            timeout=self._settings.timeout_seconds,
            trust_env=True,
        )

    @property
    def model_name(self) -> str:
        return self._settings.chat_model

    async def __aenter__(self) -> ChatCompletionsProvider:
        return self

    async def __aexit__(self, *_: object) -> None:
        await self.aclose()

    async def aclose(self) -> None:
        if self._owns_client:
            await self._client.aclose()

    async def generate(
        self,
        request: ModelRequest,
        *,
        output_model: type[BaseModel] | None = None,
    ) -> ModelResponse:
        payload = self._build_payload(request, output_model=output_model)
        started_at = self._clock()
        progress = _RequestProgress()
        try:
            async with asyncio.timeout(self._settings.total_timeout_seconds):
                response, attempts = await self._request_with_retries(
                    "POST",
                    "/chat/completions",
                    json_body=payload,
                    progress=progress,
                )
                latency_ms = self._elapsed_ms(started_at)
                provider_request_id = self._provider_request_id(response)
                body = self._parse_json_response(response, attempts=attempts)

                try:
                    choice = body["choices"][0]
                    content = choice["message"]["content"]
                    finish_reason = choice["finish_reason"]
                    model = body["model"]
                    usage = body["usage"]
                except (KeyError, IndexError, TypeError) as exc:
                    raise ModelResponseError(
                        f"{self.provider_label} returned an incomplete Chat Completions response.",
                        provider_request_id=provider_request_id,
                        attempts=attempts,
                    ) from exc

                if not isinstance(content, str) or not content.strip():
                    raise ModelResponseError(
                        f"{self.provider_label} returned empty completion content.",
                        provider_request_id=provider_request_id,
                        attempts=attempts,
                    )
                if not isinstance(finish_reason, str) or not finish_reason:
                    raise ModelResponseError(
                        f"{self.provider_label} returned an invalid finish reason.",
                        provider_request_id=provider_request_id,
                        attempts=attempts,
                    )
                if not isinstance(model, str) or not model:
                    raise ModelResponseError(
                        f"{self.provider_label} returned an invalid model identifier.",
                        provider_request_id=provider_request_id,
                        attempts=attempts,
                    )

                token_usage = self._parse_token_usage(
                    usage,
                    provider_request_id=provider_request_id,
                    attempts=attempts,
                )
                structured_content = self._validate_structured_output(
                    content,
                    output_model=output_model,
                    provider_request_id=provider_request_id,
                    attempts=attempts,
                )
                return ModelResponse(
                    text=content,
                    structured_content=structured_content,
                    metadata=ModelCallMetadata(
                        provider=self.provider_name,
                        model=model,
                        token_usage=token_usage,
                        finish_reason=finish_reason,
                        latency_ms=latency_ms,
                        provider_request_id=provider_request_id,
                        prompt_version=request.prompt_version,
                        attempts=attempts,
                    ),
                )
        except TimeoutError as exc:
            raise ModelTimeoutError(
                f"{self.provider_label} generation exceeded the configured total timeout.",
                attempts=max(1, progress.attempts),
            ) from exc

    async def check_health(self) -> ModelProviderHealth:
        started_at = self._clock()
        try:
            response, attempts = await self._request_with_retries("GET", "/models")
            body = self._parse_json_response(response, attempts=attempts)
            raw_models = body.get("data")
            if not isinstance(raw_models, list):
                raise ModelResponseError(
                    f"{self.provider_label} returned an invalid model list.",
                    provider_request_id=self._provider_request_id(response),
                    attempts=attempts,
                )
            available_models = {
                item.get("id")
                for item in raw_models
                if isinstance(item, dict) and isinstance(item.get("id"), str)
            }
            model_available = self.model_name in available_models
            return ModelProviderHealth(
                provider_status="UP",
                model_status="UP" if model_available else "DOWN",
                latency_ms=self._elapsed_ms(started_at),
                model_detail=None
                if model_available
                else "The configured chat model is not in the provider model list.",
                model_version=self.model_name if model_available else None,
            )
        except ModelAuthenticationError:
            detail = f"{self.provider_label} authentication failed."
        except ModelRateLimitError:
            detail = f"{self.provider_label} health check remained rate limited after retries."
        except ModelTimeoutError:
            detail = f"{self.provider_label} health check timed out after retries."
        except ModelServerError:
            detail = f"{self.provider_label} health check failed with a server error after retries."
        except ModelTransportError:
            detail = f"{self.provider_label} health check could not reach the provider after retries."
        except (ModelRequestError, ModelResponseError) as exc:
            detail = str(exc)

        return ModelProviderHealth(
            provider_status="DOWN",
            model_status="DOWN",
            latency_ms=self._elapsed_ms(started_at),
            provider_detail=detail,
            model_detail="The configured chat model could not be verified.",
        )

    def _build_payload(
        self,
        request: ModelRequest,
        *,
        output_model: type[BaseModel] | None,
    ) -> dict[str, Any]:
        if output_model is not None and (
            not isinstance(output_model, type) or not issubclass(output_model, BaseModel)
        ):
            raise TypeError("output_model must be a Pydantic BaseModel subclass.")
        messages = [
            {"role": message.role, "content": message.content}
            for message in request.messages
        ]
        payload: dict[str, Any] = {
            "model": self.model_name,
            "messages": messages,
            "stream": False,
        }
        if request.reasoning_mode == "disabled" and self.provider_name == "deepseek":
            payload["thinking"] = {"type": "disabled"}
        if request.max_tokens is not None:
            payload["max_tokens"] = request.max_tokens
        if request.temperature is not None:
            payload["temperature"] = request.temperature
        if request.anonymous_user_id is not None:
            payload["user_id"] = request.anonymous_user_id
        if output_model is not None:
            schema = json.dumps(
                output_model.model_json_schema(),
                ensure_ascii=False,
                separators=(",", ":"),
            )
            instruction = (
                "Return exactly one valid JSON object matching this JSON Schema; "
                f"do not wrap the JSON in Markdown: {schema}"
            )
            if messages and messages[0]["role"] == "system":
                messages[0] = {
                    **messages[0],
                    "content": f"{messages[0]['content']}\n\n{instruction}",
                }
            else:
                messages.insert(0, {"role": "system", "content": instruction})
            payload["response_format"] = {"type": "json_object"}
        return payload

    async def _request_with_retries(
        self,
        method: str,
        path: str,
        *,
        json_body: dict[str, Any] | None = None,
        progress: _RequestProgress | None = None,
    ) -> tuple[httpx2.Response, int]:
        maximum_attempts = self._settings.max_retries + 1
        url = f"{self._settings.base_url}{path}"
        headers = {
            "Authorization": f"Bearer {self._settings.api_key.get_secret_value()}",
            "Accept": "application/json",
            "Content-Type": "application/json",
        }

        for attempt in range(1, maximum_attempts + 1):
            if progress is not None:
                progress.attempts = attempt
            try:
                response = await self._client.request(
                    method,
                    url,
                    headers=headers,
                    json=json_body,
                )
            except httpx2.TimeoutException as exc:
                if attempt < maximum_attempts:
                    await self._sleep(self._retry_delay(attempt))
                    continue
                raise ModelTimeoutError(
                    f"{self.provider_label} request timed out after retries.", attempts=attempt
                ) from exc
            except httpx2.TransportError as exc:
                if attempt < maximum_attempts:
                    await self._sleep(self._retry_delay(attempt))
                    continue
                raise ModelTransportError(
                    f"{self.provider_label} could not be reached after retries.", attempts=attempt
                ) from exc

            status_code = response.status_code
            provider_request_id = self._provider_request_id(response)
            if 200 <= status_code < 300:
                return response, attempt
            if status_code == 401:
                raise ModelAuthenticationError(
                    f"{self.provider_label} authentication failed.",
                    status_code=status_code,
                    provider_request_id=provider_request_id,
                    attempts=attempt,
                )
            if status_code == 429:
                if attempt < maximum_attempts:
                    await self._sleep(self._retry_delay(attempt, response=response))
                    continue
                raise ModelRateLimitError(
                    f"{self.provider_label} remained rate limited after retries.",
                    status_code=status_code,
                    provider_request_id=provider_request_id,
                    attempts=attempt,
                )
            if 500 <= status_code <= 599:
                if attempt < maximum_attempts:
                    await self._sleep(self._retry_delay(attempt, response=response))
                    continue
                raise ModelServerError(
                    f"{self.provider_label} returned a server error after retries.",
                    status_code=status_code,
                    provider_request_id=provider_request_id,
                    attempts=attempt,
                )
            raise self._request_error(response, attempts=attempt)

        raise AssertionError("Retry loop exited unexpectedly.")

    def _request_error(self, response: httpx2.Response, *, attempts: int) -> ModelRequestError:
        return ModelRequestError(
            f"{self.provider_label} rejected the request with HTTP {response.status_code}.",
            status_code=response.status_code,
            provider_request_id=self._provider_request_id(response),
            attempts=attempts,
        )

    def _retry_delay(
        self,
        attempt: int,
        *,
        response: httpx2.Response | None = None,
    ) -> float:
        if response is not None:
            retry_after = response.headers.get("Retry-After")
            if retry_after is not None:
                try:
                    seconds = float(retry_after)
                except ValueError:
                    pass
                else:
                    return max(0.0, min(seconds, self._settings.retry_max_seconds))
        exponential = self._settings.retry_base_seconds * (2 ** (attempt - 1))
        return min(exponential, self._settings.retry_max_seconds)

    @staticmethod
    def _provider_request_id(response: httpx2.Response) -> str | None:
        for header_name in ("x-request-id", "x-requestid", "request-id"):
            value = response.headers.get(header_name)
            if value:
                return value
        try:
            body = response.json()
        except (ValueError, TypeError):
            return None
        completion_id = body.get("id") if isinstance(body, dict) else None
        return completion_id if isinstance(completion_id, str) else None

    @classmethod
    def _parse_json_response(
        cls,
        response: httpx2.Response,
        *,
        attempts: int,
    ) -> dict[str, Any]:
        provider_request_id = cls._provider_request_id(response)
        try:
            body = response.json()
        except (ValueError, TypeError) as exc:
            raise ModelResponseError(
                f"{cls.provider_label} returned a non-JSON response.",
                provider_request_id=provider_request_id,
                attempts=attempts,
            ) from exc
        if not isinstance(body, dict):
            raise ModelResponseError(
                f"{cls.provider_label} returned an invalid JSON response.",
                provider_request_id=provider_request_id,
                attempts=attempts,
            )
        return body

    @classmethod
    def _parse_token_usage(
        cls,
        usage: object,
        *,
        provider_request_id: str | None,
        attempts: int,
    ) -> TokenUsage:
        if not isinstance(usage, dict):
            raise ModelResponseError(
                f"{cls.provider_label} returned invalid token usage metadata.",
                provider_request_id=provider_request_id,
                attempts=attempts,
            )
        values = (
            usage.get("prompt_tokens"),
            usage.get("completion_tokens"),
            usage.get("total_tokens"),
        )
        if any(type(value) is not int or value < 0 for value in values):
            raise ModelResponseError(
                f"{cls.provider_label} returned invalid token usage metadata.",
                provider_request_id=provider_request_id,
                attempts=attempts,
            )
        return TokenUsage(
            prompt_tokens=values[0],
            completion_tokens=values[1],
            total_tokens=values[2],
        )

    @classmethod
    def _validate_structured_output(
        cls,
        content: str,
        *,
        output_model: type[BaseModel] | None,
        provider_request_id: str | None,
        attempts: int,
    ) -> dict[str, Any] | None:
        if output_model is None:
            return None
        try:
            decoded = json.loads(content)
            if not isinstance(decoded, dict):
                raise ValueError("Structured output root must be an object.")
            validated = output_model.model_validate(decoded)
        except (ValueError, TypeError, ValidationError, AttributeError) as exc:
            raise StructuredOutputError(
                f"{cls.provider_label} returned structured content that failed validation.",
                provider_request_id=provider_request_id,
                attempts=attempts,
            ) from exc
        dumped = validated.model_dump(mode="json")
        if not isinstance(dumped, dict):
            raise StructuredOutputError(
                f"{cls.provider_label} returned structured content with an invalid root type.",
                provider_request_id=provider_request_id,
                attempts=attempts,
            )
        return dumped

    def _elapsed_ms(self, started_at: float) -> int:
        return max(0, round((self._clock() - started_at) * 1000))
