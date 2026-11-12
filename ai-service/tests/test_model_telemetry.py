from __future__ import annotations

import asyncio
import json
import unittest
from unittest.mock import patch

import httpx2
from datetime import UTC, datetime

from app.clients.model_telemetry import ModelInvocation, ModelTelemetryClient
from app.core.config import TelemetrySettings
from app.providers.base import ModelCallMetadata, ModelMessage, ModelRequest, ModelResponse, ModelServerError, TokenUsage
from app.providers.telemetry import TelemetryModelProvider


class FakeTelemetry:
    def __init__(self): self.events = []
    async def record(self, event): self.events.append(event); return True
    async def aclose(self): return None


class CancelledTelemetry:
    async def record(self, event):
        raise asyncio.CancelledError("telemetry cancelled")
    async def aclose(self): return None


class BlockingTelemetry:
    def __init__(self): self.started = asyncio.Event(); self.release = asyncio.Event()
    async def record(self, event):
        self.started.set()
        await self.release.wait()
    async def aclose(self): return None


class FakeProvider:
    provider_name = "fake"
    model_name = "fake-v1"
    def __init__(self, error=None): self.error = error
    async def generate(self, request, *, output_model=None):
        if self.error: raise self.error
        return ModelResponse("ok", None, ModelCallMetadata("fake", "fake-v1", TokenUsage(12, 8, 20), "stop", 7, "provider-id", request.prompt_version, 1))
    async def check_health(self): raise AssertionError
    async def aclose(self): return None


def pricing() -> TelemetrySettings:
    return TelemetrySettings("http://localhost:8080", __import__("pydantic").SecretStr("token"), 1, 1, 0, "prices-v1", "USD", 1_000_000, 2_000_000)


def request() -> ModelRequest:
    return ModelRequest(messages=(ModelMessage(role="user", content="secret prompt"),), prompt_version="prompt-v1", request_id="trace-telemetry-0001", capability="TUTOR_CHAT")


class TelemetryProviderTest(unittest.IsolatedAsyncioTestCase):
    async def test_success_is_deidentified_and_costed(self):
        sink = FakeTelemetry()
        provider = TelemetryModelProvider(FakeProvider(), sink, pricing())
        await provider.generate(request())
        await provider.aclose()
        event = sink.events[0]
        self.assertEqual(event.outcome, "SUCCESS")
        self.assertEqual(event.estimated_cost_microunits, 28)
        self.assertNotIn("secret prompt", str(event.as_payload()))
        self.assertTrue(event.invocation_id.startswith("mi_"))

    async def test_failure_is_reported_without_changing_error(self):
        sink = FakeTelemetry()
        provider = TelemetryModelProvider(FakeProvider(ModelServerError("raw provider failure", attempts=2)), sink, pricing())
        with self.assertRaises(ModelServerError): await provider.generate(request())
        await provider.aclose()
        self.assertEqual(sink.events[0].outcome, "FAILED")
        self.assertEqual(sink.events[0].error_code, "MODEL_SERVER_ERROR")
        self.assertNotIn("raw provider failure", str(sink.events[0].as_payload()))

    async def test_client_retries_the_same_deidentified_invocation(self):
        calls, observed = 0, {"invocation_ids": []}
        event = ModelInvocation("mi_test-000000000001", "trace-telemetry-0001", "TUTOR_CHAT", "fake", "fake-v1", "prompt-v1", 1, 0, 0, 1, "FAILED", "prices-v1", datetime.now(UTC), error_code="MODEL_TIMEOUT_ERROR", estimated_cost_microunits=0, currency="USD")
        def handler(request):
            nonlocal calls
            calls += 1
            observed["token"] = request.headers["X-Internal-Token"]
            observed["request_id"] = request.headers["X-Request-Id"]
            observed["body"] = request.content.decode()
            observed["invocation_ids"].append(json.loads(request.content)["invocation_id"])
            return httpx2.Response(500 if calls == 1 else 202, json={"accepted": True})
        async with httpx2.AsyncClient(transport=httpx2.MockTransport(handler)) as client:
            recorder = ModelTelemetryClient(pricing(), client=client)
            self.assertTrue(await recorder.record(event))
        self.assertEqual(calls, 2)
        self.assertEqual(observed["invocation_ids"], [event.invocation_id, event.invocation_id])
        self.assertEqual(observed["request_id"], "trace-telemetry-0001")
        self.assertNotIn("secret", observed["body"])

    async def test_callback_cancellation_never_replaces_a_successful_model_result(self):
        provider = TelemetryModelProvider(FakeProvider(), CancelledTelemetry(), pricing())
        self.assertEqual((await provider.generate(request())).text, "ok")
        await provider.aclose()

    async def test_callback_cancellation_never_replaces_typed_provider_failure(self):
        provider = TelemetryModelProvider(
            FakeProvider(ModelServerError("provider failed", attempts=2)),
            CancelledTelemetry(), pricing(),
        )
        with self.assertRaises(ModelServerError):
            await provider.generate(request())
        await provider.aclose()

    async def test_blocking_callback_does_not_delay_generate(self):
        sink = BlockingTelemetry()
        provider = TelemetryModelProvider(FakeProvider(), sink, pricing())
        result = await asyncio.wait_for(provider.generate(request()), timeout=0.05)
        self.assertEqual(result.text, "ok")
        await asyncio.wait_for(sink.started.wait(), timeout=0.2)
        sink.release.set()
        await provider.aclose()

    async def test_queue_full_drops_without_changing_model_result(self):
        from app.providers.telemetry import TelemetryDeliveryQueue

        sink = BlockingTelemetry()
        delivery = TelemetryDeliveryQueue(sink, capacity=1)
        provider = TelemetryModelProvider(FakeProvider(), pricing=pricing(), delivery=delivery)
        self.assertEqual((await provider.generate(request())).text, "ok")
        await asyncio.wait_for(sink.started.wait(), timeout=0.2)
        self.assertEqual((await provider.generate(request())).text, "ok")
        self.assertEqual((await provider.generate(request())).text, "ok")
        sink.release.set()
        await delivery.aclose()
        self.assertTrue(delivery._worker is not None and delivery._worker.done())

    async def test_shared_delivery_is_not_closed_by_request_provider(self):
        from app.providers.telemetry import TelemetryDeliveryQueue

        sink = FakeTelemetry()
        delivery = TelemetryDeliveryQueue(sink)
        provider = TelemetryModelProvider(FakeProvider(), pricing=pricing(), delivery=delivery)
        await provider.generate(request())
        await provider.aclose()
        await asyncio.sleep(0)
        self.assertFalse(delivery._closed)
        await delivery.aclose()

    async def test_invalid_telemetry_configuration_leaves_provider_usable(self):
        with patch("app.providers.telemetry.TelemetrySettings.from_env", side_effect=ValueError("bad telemetry")):
            provider = TelemetryModelProvider(FakeProvider())
        self.assertEqual((await provider.generate(request())).text, "ok")
        await provider.aclose()
