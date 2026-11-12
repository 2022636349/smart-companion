from __future__ import annotations

import asyncio
import logging
from collections.abc import Awaitable, Callable
from dataclasses import dataclass
from datetime import UTC, datetime
from typing import Any

import httpx2

from app.core.config import TelemetrySettings


LOGGER = logging.getLogger("ai_service.model_telemetry")
Sleep = Callable[[float], Awaitable[None]]


@dataclass(frozen=True, slots=True)
class ModelInvocation:
    invocation_id: str
    request_id: str
    capability: str
    provider: str
    model: str
    prompt_version: str
    duration_ms: int
    input_units: int
    output_units: int
    attempts: int
    outcome: str
    pricing_version: str
    occurred_at: datetime
    provider_request_id: str | None = None
    error_code: str | None = None
    estimated_cost_microunits: int | None = None
    currency: str | None = None

    def as_payload(self) -> dict[str, Any]:
        return {
            "invocation_id": self.invocation_id,
            "request_id": self.request_id,
            "capability": self.capability,
            "provider": self.provider,
            "model": self.model,
            "prompt_version": self.prompt_version,
            "duration_ms": self.duration_ms,
            "input_units": self.input_units,
            "output_units": self.output_units,
            "attempts": self.attempts,
            "provider_request_id": self.provider_request_id,
            "outcome": self.outcome,
            "error_code": self.error_code,
            "pricing_version": self.pricing_version,
            "estimated_cost_microunits": self.estimated_cost_microunits,
            "currency": self.currency,
            "occurred_at": self.occurred_at.astimezone(UTC).isoformat(),
        }


class ModelTelemetryClient:
    """Best-effort, no-body callback to Spring's idempotent audit endpoint."""

    def __init__(
        self,
        settings: TelemetrySettings | None = None,
        *,
        client: httpx2.AsyncClient | None = None,
        sleep: Sleep = asyncio.sleep,
    ) -> None:
        self._settings = settings or TelemetrySettings.from_env()
        self._owns_client = client is None
        self._client = client or httpx2.AsyncClient(
            timeout=self._settings.timeout_seconds, trust_env=False, follow_redirects=False
        )
        self._sleep = sleep

    async def aclose(self) -> None:
        if self._owns_client:
            await self._client.aclose()

    async def record(self, invocation: ModelInvocation) -> bool:
        payload = invocation.as_payload()
        headers = {
            "X-Internal-Token": self._settings.internal_token.get_secret_value(),
            "X-Request-Id": invocation.request_id,
            "Accept": "application/json",
        }
        for attempt in range(1, self._settings.max_retries + 2):
            try:
                response = await self._client.post(
                    f"{self._settings.base_url}/api/internal/ai/model-invocations",
                    headers=headers,
                    json=payload,
                )
            except (httpx2.TimeoutException, httpx2.TransportError):
                if attempt <= self._settings.max_retries:
                    await self._sleep(self._settings.retry_base_seconds * (2 ** (attempt - 1)))
                    continue
                self._log_delivery_failure(invocation)
                return False
            if response.status_code == 202:
                return True
            if 500 <= response.status_code <= 599 and attempt <= self._settings.max_retries:
                await self._sleep(self._settings.retry_base_seconds * (2 ** (attempt - 1)))
                continue
            self._log_delivery_failure(invocation)
            return False
        self._log_delivery_failure(invocation)
        return False

    @staticmethod
    def _log_delivery_failure(invocation: ModelInvocation) -> None:
        # Do not include exception text, payload, token, prompt/completion, or identity.
        LOGGER.warning(
            "model_telemetry_delivery_failed invocation_id=%s provider=%s model=%s outcome=%s",
            invocation.invocation_id,
            invocation.provider,
            invocation.model,
            invocation.outcome,
        )
