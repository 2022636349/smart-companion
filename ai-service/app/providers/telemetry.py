from __future__ import annotations

import asyncio
import logging
import time
import uuid
from datetime import UTC, datetime
from contextlib import suppress

from pydantic import BaseModel

from app.clients.model_telemetry import ModelInvocation, ModelTelemetryClient
from app.core.config import TelemetrySettings
from app.providers.base import ModelProvider, ModelProviderError, ModelRequest, ModelResponse


LOGGER = logging.getLogger("ai_service.model_telemetry")
_SHUTDOWN_GRACE_SECONDS = 2.0


class TelemetryDeliveryQueue:
    """A bounded, loop-owned delivery worker for non-critical telemetry."""

    def __init__(
        self,
        client: ModelTelemetryClient,
        *,
        capacity: int = 128,
        close_client: bool = False,
    ) -> None:
        self._client = client
        self._capacity = capacity
        self._close_client = close_client
        self._queue: asyncio.Queue[ModelInvocation] | None = None
        self._worker: asyncio.Task[None] | None = None
        self._closed = False

    def submit(self, invocation: ModelInvocation) -> None:
        """Queue an event without awaiting network I/O or changing model output."""
        if self._closed:
            return
        try:
            asyncio.get_running_loop()
            if self._queue is None:
                self._queue = asyncio.Queue(maxsize=self._capacity)
                self._worker = asyncio.create_task(
                    self._run(), name="model-telemetry-delivery"
                )
            self._queue.put_nowait(invocation)
        except asyncio.QueueFull:
            LOGGER.warning(
                "model_telemetry_queue_full invocation_id=%s provider=%s model=%s outcome=%s",
                invocation.invocation_id, invocation.provider, invocation.model, invocation.outcome,
            )
        except Exception:
            # Config/task-loop errors are non-critical. Do not expose exception details.
            LOGGER.warning(
                "model_telemetry_enqueue_failed invocation_id=%s provider=%s model=%s outcome=%s",
                invocation.invocation_id, invocation.provider, invocation.model, invocation.outcome,
            )

    async def _run(self) -> None:
        assert self._queue is not None
        while True:
            invocation = await self._queue.get()
            try:
                await self._client.record(invocation)
            except asyncio.CancelledError:
                # A callback may raise CancelledError itself.  Only cancellation
                # directed at this worker is lifecycle shutdown; it must never
                # leak back into or terminate the model invocation path.
                if asyncio.current_task() is not None and asyncio.current_task().cancelling():
                    raise
                LOGGER.warning(
                    "model_telemetry_worker_cancelled invocation_id=%s provider=%s model=%s outcome=%s",
                    invocation.invocation_id, invocation.provider, invocation.model, invocation.outcome,
                )
            except Exception:
                LOGGER.warning(
                    "model_telemetry_worker_failed invocation_id=%s provider=%s model=%s outcome=%s",
                    invocation.invocation_id, invocation.provider, invocation.model, invocation.outcome,
                )
            finally:
                self._queue.task_done()

    async def aclose(self) -> None:
        if self._closed:
            return
        self._closed = True
        if self._queue is not None:
            with suppress(TimeoutError):
                await asyncio.wait_for(self._queue.join(), timeout=_SHUTDOWN_GRACE_SECONDS)
        if self._worker is not None:
            self._worker.cancel()
            with suppress(asyncio.CancelledError):
                await self._worker
        if self._close_client:
            with suppress(Exception):
                await self._client.aclose()


_shared_delivery: TelemetryDeliveryQueue | None = None


def shared_telemetry_delivery(settings: TelemetrySettings) -> TelemetryDeliveryQueue:
    """Return the service-lifetime queue; it is closed by the FastAPI lifespan."""
    global _shared_delivery
    if _shared_delivery is None:
        _shared_delivery = TelemetryDeliveryQueue(ModelTelemetryClient(settings), close_client=True)
    return _shared_delivery


async def close_shared_telemetry_delivery() -> None:
    global _shared_delivery
    if _shared_delivery is not None:
        await _shared_delivery.aclose()
        _shared_delivery = None


class TelemetryModelProvider:
    """Decorator that never lets Spring audit delivery alter a model result."""

    def __init__(
        self,
        provider: ModelProvider,
        telemetry_client: ModelTelemetryClient | None = None,
        pricing: TelemetrySettings | None = None,
        delivery: TelemetryDeliveryQueue | None = None,
    ) -> None:
        self._provider = provider
        self._owns_delivery = False
        try:
            self._pricing = pricing or TelemetrySettings.from_env()
            if delivery is not None:
                self._delivery = delivery
            elif telemetry_client is not None:
                self._delivery = TelemetryDeliveryQueue(telemetry_client)
                self._owns_delivery = True
            else:
                self._delivery = shared_telemetry_delivery(self._pricing)
        except Exception:
            # Invalid telemetry pricing/callback configuration must not take down generation.
            self._pricing = None
            self._delivery = None
            LOGGER.warning("model_telemetry_disabled_due_to_configuration")

    @property
    def provider_name(self) -> str:
        return self._provider.provider_name

    @property
    def model_name(self) -> str:
        return self._provider.model_name

    async def generate(
        self, request: ModelRequest, *, output_model: type[BaseModel] | None = None
    ) -> ModelResponse:
        started = time.perf_counter()
        invocation_id = "mi_" + uuid.uuid4().hex
        try:
            response = await self._provider.generate(request, output_model=output_model)
        except Exception as exc:
            self._record_failure(invocation_id, request, exc, started)
            raise
        self._record_success(invocation_id, request, response)
        return response

    async def check_health(self):
        return await self._provider.check_health()

    async def aclose(self) -> None:
        try:
            await self._provider.aclose()
        finally:
            if self._owns_delivery and self._delivery is not None:
                await self._delivery.aclose()

    def _record_success(self, invocation_id: str, request: ModelRequest, response: ModelResponse) -> None:
        if self._pricing is None:
            return
        metadata = response.metadata
        self._record(
            ModelInvocation(
                invocation_id=invocation_id, request_id=request.request_id,
                capability=request.capability, provider=metadata.provider, model=metadata.model,
                prompt_version=metadata.prompt_version, duration_ms=metadata.latency_ms,
                input_units=metadata.token_usage.prompt_tokens,
                output_units=metadata.token_usage.completion_tokens, attempts=metadata.attempts,
                provider_request_id=metadata.provider_request_id, outcome="SUCCESS",
                pricing_version=self._pricing.pricing_version,
                estimated_cost_microunits=self._estimate_cost(
                    metadata.token_usage.prompt_tokens, metadata.token_usage.completion_tokens
                ), currency=self._pricing.currency, occurred_at=datetime.now(UTC),
            )
        )

    def _record_failure(self, invocation_id: str, request: ModelRequest, exc: BaseException, started: float) -> None:
        if self._pricing is None:
            return
        typed = exc if isinstance(exc, ModelProviderError) else None
        self._record(
            ModelInvocation(
                invocation_id=invocation_id, request_id=request.request_id,
                capability=request.capability, provider=self.provider_name, model=self.model_name,
                prompt_version=request.prompt_version,
                duration_ms=max(0, round((time.perf_counter() - started) * 1000)),
                input_units=0, output_units=0,
                attempts=typed.attempts if typed is not None else 1,
                provider_request_id=typed.provider_request_id if typed is not None else None,
                outcome="FAILED", error_code=typed.code if typed is not None else "MODEL_PROVIDER_ERROR",
                pricing_version=self._pricing.pricing_version, estimated_cost_microunits=0,
                currency=self._pricing.currency, occurred_at=datetime.now(UTC),
            )
        )

    def _record(self, invocation: ModelInvocation) -> None:
        if self._delivery is not None:
            self._delivery.submit(invocation)

    def _estimate_cost(self, input_units: int, output_units: int) -> int:
        return (
            input_units * self._pricing.input_microunits_per_million
            + output_units * self._pricing.output_microunits_per_million
        ) // 1_000_000
