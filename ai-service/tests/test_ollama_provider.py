from __future__ import annotations

import asyncio
import json
import os
import unittest

import httpx2
from pydantic import BaseModel

from app.core.config import OllamaGenerationSettings
from app.providers.base import (
    ModelMessage, ModelRateLimitError, ModelRequest, ModelResponseError,
    ModelServerError, ModelTimeoutError,
)
from app.providers.ollama import OllamaProvider


class Output(BaseModel):
    answer: str


def settings(**changes: object) -> OllamaGenerationSettings:
    values = dict(base_url="http://localhost:11434", chat_model="qwen:test", timeout_seconds=1.0, total_timeout_seconds=2.0, max_retries=1, retry_base_seconds=0.0, retry_max_seconds=0.0)
    values.update(changes)
    return OllamaGenerationSettings(**values)


def request() -> ModelRequest:
    return ModelRequest(messages=(ModelMessage(role="user", content="hello"),), prompt_version="test-v1", request_id="trace-ollama-0001", capability="TUTOR_CHAT")


class OllamaProviderTest(unittest.IsolatedAsyncioTestCase):
    async def test_success_returns_complete_metadata_and_json_mode(self) -> None:
        observed = {}
        def handler(value: httpx2.Request) -> httpx2.Response:
            observed["body"] = json.loads(value.content)
            return httpx2.Response(200, headers={"x-request-id": "provider-1"}, json={"model":"qwen:test","message":{"content":"{\"answer\":\"ok\"}"},"done":True,"prompt_eval_count":3,"eval_count":2})
        async with httpx2.AsyncClient(transport=httpx2.MockTransport(handler)) as client:
            result = await OllamaProvider(settings(), client=client).generate(request(), output_model=Output)
        self.assertEqual(result.structured_content, {"answer": "ok"})
        self.assertEqual(result.metadata.token_usage.total_tokens, 5)
        self.assertEqual(result.metadata.provider_request_id, "provider-1")
        self.assertEqual(observed["body"]["format"], Output.model_json_schema())
        self.assertFalse(observed["body"]["stream"])
        self.assertNotIn("Authorization", observed["body"])

    async def test_health_accepts_default_latest_tag(self) -> None:
        async with httpx2.AsyncClient(transport=httpx2.MockTransport(lambda _: httpx2.Response(200, json={"models":[{"name":"qwen:latest"}]}))) as client:
            health = await OllamaProvider(settings(chat_model="qwen"), client=client).check_health()
        self.assertEqual(health.model_status, "UP")

    async def test_malformed_structured_output_is_typed(self) -> None:
        async with httpx2.AsyncClient(transport=httpx2.MockTransport(lambda _: httpx2.Response(200, json={"model":"qwen:test","message":{"content":"not-json"},"done":True,"prompt_eval_count":1,"eval_count":1}))) as client:
            with self.assertRaises(ModelResponseError):
                await OllamaProvider(settings(), client=client).generate(request(), output_model=Output)

    async def test_429_and_5xx_are_bounded_and_typed(self) -> None:
        for status, error in ((429, ModelRateLimitError), (500, ModelServerError)):
            calls = 0
            def handler(_: httpx2.Request, status=status) -> httpx2.Response:
                nonlocal calls; calls += 1; return httpx2.Response(status)
            async with httpx2.AsyncClient(transport=httpx2.MockTransport(handler)) as client:
                with self.assertRaises(error):
                    await OllamaProvider(settings(), client=client).generate(request())
            self.assertEqual(calls, 2)

    async def test_timeout_and_cancellation_propagate(self) -> None:
        def timeout(_: httpx2.Request) -> httpx2.Response:
            raise httpx2.ReadTimeout("timeout")
        async with httpx2.AsyncClient(transport=httpx2.MockTransport(timeout)) as client:
            with self.assertRaises(ModelTimeoutError):
                await OllamaProvider(settings(), client=client).generate(request())
        async def block(_: httpx2.Request) -> httpx2.Response:
            await asyncio.sleep(10); return httpx2.Response(200)
        async with httpx2.AsyncClient(transport=httpx2.MockTransport(block)) as client:
            task = asyncio.create_task(OllamaProvider(settings(), client=client).generate(request()))
            await asyncio.sleep(0)
            task.cancel()
            with self.assertRaises(asyncio.CancelledError):
                await task

    def test_environment_requires_allowlisted_host(self) -> None:
        before = {name: os.environ.get(name) for name in ("OLLAMA_CHAT_MODEL", "OLLAMA_BASE_URL", "OLLAMA_ALLOWED_HOSTS")}
        try:
            os.environ.update({"OLLAMA_CHAT_MODEL":"qwen:test", "OLLAMA_BASE_URL":"http://untrusted:11434", "OLLAMA_ALLOWED_HOSTS":"localhost"})
            with self.assertRaises(Exception):
                OllamaGenerationSettings.from_env()
        finally:
            for name, value in before.items():
                if value is None: os.environ.pop(name, None)
                else: os.environ[name] = value
