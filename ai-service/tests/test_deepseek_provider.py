import asyncio
import json
import os
import secrets
import unittest
from unittest.mock import patch

import httpx2
from pydantic import BaseModel, Field

from app.core.config import DeepSeekSettings
from app.contracts.assessment import GradeProviderOutput
from app.providers.base import (
    ModelAuthenticationError,
    ModelConfigurationError,
    ModelProvider,
    ModelRateLimitError,
    ModelRequest,
    ModelRequestError,
    ModelServerError,
    ModelTimeoutError,
    ModelMessage,
    StructuredOutputError,
)
from app.providers.deepseek import DeepSeekProvider
from app.providers.chat_completions import ChatCompletionsProvider


RUNTIME_TEST_KEY = secrets.token_urlsafe(24)


class StructuredAnswer(BaseModel):
    answer: str
    confidence: float = Field(ge=0, le=1)


def deepseek_settings(**overrides: str) -> DeepSeekSettings:
    environment = {
        "DEEPSEEK_API_KEY": RUNTIME_TEST_KEY,
        "DEEPSEEK_BASE_URL": "https://deepseek.test",
        "DEEPSEEK_CHAT_MODEL": "deepseek-test-model",
        "DEEPSEEK_TIMEOUT_SECONDS": "1",
        "DEEPSEEK_TOTAL_TIMEOUT_SECONDS": "5",
        "DEEPSEEK_MAX_RETRIES": "2",
        "DEEPSEEK_RETRY_BASE_SECONDS": "0.01",
        "DEEPSEEK_RETRY_MAX_SECONDS": "0.05",
    }
    environment.update(overrides)
    with patch.dict(os.environ, environment, clear=False):
        return DeepSeekSettings.from_env()


def completion_response(
    content: str,
    *,
    request_id: str = "completion-body-id",
) -> dict:
    return {
        "id": request_id,
        "model": "deepseek-resolved-model",
        "choices": [
            {
                "index": 0,
                "message": {"role": "assistant", "content": content},
                "finish_reason": "stop",
            }
        ],
        "usage": {
            "prompt_tokens": 11,
            "completion_tokens": 7,
            "total_tokens": 18,
        },
    }


class DeepSeekProviderTest(unittest.IsolatedAsyncioTestCase):
    @staticmethod
    def request(*, reasoning_mode: str = "provider_default") -> ModelRequest:
        return ModelRequest(
            messages=(ModelMessage(role="user", content="Explain a Python loop."),),
            prompt_version="tutor-v1",
            max_tokens=256,
            temperature=0.2,
            anonymous_user_id="anon_" + ("a" * 64),
            reasoning_mode=reasoning_mode,
        )

    async def test_reasoning_mode_is_disabled_only_for_deepseek_adapter(self) -> None:
        request = self.request(reasoning_mode="disabled")
        async with httpx2.AsyncClient() as client:
            deepseek_payload = DeepSeekProvider(deepseek_settings(), client=client)._build_payload(
                request, output_model=StructuredAnswer
            )
            compatible_payload = ChatCompletionsProvider(deepseek_settings(), client=client)._build_payload(
                request, output_model=StructuredAnswer
            )

        self.assertEqual(deepseek_payload["thinking"], {"type": "disabled"})
        self.assertNotIn("thinking", compatible_payload)

    async def test_provider_default_keeps_deepseek_request_unchanged(self) -> None:
        async with httpx2.AsyncClient() as client:
            payload = DeepSeekProvider(deepseek_settings(), client=client)._build_payload(
                self.request(), output_model=StructuredAnswer
            )

        self.assertNotIn("thinking", payload)

    def test_plain_business_identifier_cannot_be_sent_as_provider_user_id(self) -> None:
        with self.assertRaises(ValueError):
            ModelRequest(
                messages=(ModelMessage(role="user", content="hello"),),
                prompt_version="test-v1",
                anonymous_user_id="student-123",
            )

    def test_total_timeout_is_configurable_and_capped_at_spring_budget(self) -> None:
        defaults = deepseek_settings(DEEPSEEK_TOTAL_TIMEOUT_SECONDS="")
        settings = deepseek_settings(DEEPSEEK_TOTAL_TIMEOUT_SECONDS="12.5")

        self.assertEqual(defaults.total_timeout_seconds, 110.0)
        self.assertEqual(settings.timeout_seconds, 1.0)
        self.assertEqual(settings.total_timeout_seconds, 12.5)
        self.assertEqual(settings.max_retries, 2)

        with self.assertRaises(ModelConfigurationError):
            deepseek_settings(DEEPSEEK_TOTAL_TIMEOUT_SECONDS="120.1")

    async def test_success_returns_text_and_complete_metadata(self) -> None:
        observed_payload: dict = {}

        async def handler(request: httpx2.Request) -> httpx2.Response:
            self.assertEqual(request.url.path, "/chat/completions")
            self.assertEqual(
                request.headers["Authorization"], f"Bearer {RUNTIME_TEST_KEY}"
            )
            observed_payload.update(json.loads(request.content))
            return httpx2.Response(
                200,
                json=completion_response("Use range in a for loop."),
                headers={"X-Request-Id": "provider-request-42"},
            )

        ticks = iter((10.0, 10.125))
        async with httpx2.AsyncClient(
            transport=httpx2.MockTransport(handler)
        ) as client:
            provider = DeepSeekProvider(
                deepseek_settings(), client=client, clock=lambda: next(ticks)
            )
            self.assertIsInstance(provider, ModelProvider)
            result = await provider.generate(self.request())

        self.assertEqual(result.text, "Use range in a for loop.")
        self.assertIsNone(result.structured_content)
        self.assertEqual(result.metadata.provider, "deepseek")
        self.assertEqual(result.metadata.model, "deepseek-resolved-model")
        self.assertEqual(result.metadata.finish_reason, "stop")
        self.assertEqual(result.metadata.latency_ms, 125)
        self.assertEqual(result.metadata.provider_request_id, "provider-request-42")
        self.assertEqual(result.metadata.prompt_version, "tutor-v1")
        self.assertEqual(result.metadata.attempts, 1)
        self.assertEqual(result.metadata.token_usage.total_tokens, 18)
        self.assertEqual(observed_payload["model"], "deepseek-test-model")
        self.assertEqual(observed_payload["max_tokens"], 256)
        self.assertEqual(observed_payload["temperature"], 0.2)
        self.assertEqual(observed_payload["user_id"], "anon_" + ("a" * 64))
        self.assertFalse(observed_payload["stream"])

    async def test_structured_output_enables_json_mode_and_validates_schema(self) -> None:
        async def handler(request: httpx2.Request) -> httpx2.Response:
            payload = json.loads(request.content)
            self.assertEqual(payload["response_format"], {"type": "json_object"})
            self.assertIn("JSON Schema", payload["messages"][0]["content"])
            return httpx2.Response(
                200,
                json=completion_response(
                    '{"answer":"A loop repeats work.","confidence":0.9}'
                ),
            )

        async with httpx2.AsyncClient(
            transport=httpx2.MockTransport(handler)
        ) as client:
            result = await DeepSeekProvider(
                deepseek_settings(), client=client
            ).generate(self.request(), output_model=StructuredAnswer)

        self.assertEqual(
            result.structured_content,
            {"answer": "A loop repeats work.", "confidence": 0.9},
        )

    async def test_malformed_structured_output_is_rejected(self) -> None:
        async def handler(_: httpx2.Request) -> httpx2.Response:
            return httpx2.Response(200, json=completion_response("not valid json"))

        async with httpx2.AsyncClient(
            transport=httpx2.MockTransport(handler)
        ) as client:
            provider = DeepSeekProvider(deepseek_settings(), client=client)
            with self.assertRaises(StructuredOutputError) as raised:
                await provider.generate(self.request(), output_model=StructuredAnswer)

        self.assertEqual(raised.exception.attempts, 1)
        self.assertNotIn(RUNTIME_TEST_KEY, str(raised.exception))

    async def test_structured_grading_numeric_warning_is_sanitized_error(self) -> None:
        malicious_value = 987654321

        async def handler(_: httpx2.Request) -> httpx2.Response:
            return httpx2.Response(
                200,
                json=completion_response(
                    json.dumps(
                        {
                            "suggested_score": 0,
                            "rubric_grades": [{}],
                            "overall_comment": "manual review",
                            "warnings": [malicious_value],
                        }
                    )
                ),
            )

        async with httpx2.AsyncClient(
            transport=httpx2.MockTransport(handler)
        ) as client:
            provider = DeepSeekProvider(deepseek_settings(), client=client)
            with self.assertRaises(StructuredOutputError) as raised:
                await provider.generate(
                    self.request(),
                    output_model=GradeProviderOutput,
                )

        rendered = str(raised.exception)
        self.assertEqual(
            rendered,
            "DeepSeek returned structured content that failed validation.",
        )
        self.assertNotIn(str(malicious_value), rendered)
        self.assertNotIn("AttributeError", rendered)

    async def test_401_fails_immediately_without_retry(self) -> None:
        calls = 0

        async def handler(_: httpx2.Request) -> httpx2.Response:
            nonlocal calls
            calls += 1
            return httpx2.Response(
                401,
                json={"error": {"message": "request echoed a credential"}},
                headers={"X-Request-Id": "auth-failure-1"},
            )

        async with httpx2.AsyncClient(
            transport=httpx2.MockTransport(handler)
        ) as client:
            provider = DeepSeekProvider(deepseek_settings(), client=client)
            with self.assertRaises(ModelAuthenticationError) as raised:
                await provider.generate(self.request())

        self.assertEqual(calls, 1)
        self.assertEqual(raised.exception.status_code, 401)
        self.assertEqual(raised.exception.provider_request_id, "auth-failure-1")
        self.assertEqual(raised.exception.attempts, 1)
        self.assertNotIn("credential", str(raised.exception))

    async def test_429_uses_bounded_retry_after_then_succeeds(self) -> None:
        calls = 0
        delays: list[float] = []

        async def handler(_: httpx2.Request) -> httpx2.Response:
            nonlocal calls
            calls += 1
            if calls < 3:
                return httpx2.Response(429, headers={"Retry-After": "9"})
            return httpx2.Response(200, json=completion_response("ok"))

        async def record_sleep(delay: float) -> None:
            delays.append(delay)

        async with httpx2.AsyncClient(
            transport=httpx2.MockTransport(handler)
        ) as client:
            result = await DeepSeekProvider(
                deepseek_settings(), client=client, sleep=record_sleep
            ).generate(self.request())

        self.assertEqual(calls, 3)
        self.assertEqual(delays, [0.05, 0.05])
        self.assertEqual(result.metadata.attempts, 3)

    async def test_429_after_retry_budget_raises_typed_error(self) -> None:
        calls = 0

        async def handler(_: httpx2.Request) -> httpx2.Response:
            nonlocal calls
            calls += 1
            return httpx2.Response(429)

        async def no_wait(_: float) -> None:
            return None

        async with httpx2.AsyncClient(
            transport=httpx2.MockTransport(handler)
        ) as client:
            provider = DeepSeekProvider(
                deepseek_settings(DEEPSEEK_MAX_RETRIES="1"),
                client=client,
                sleep=no_wait,
            )
            with self.assertRaises(ModelRateLimitError) as raised:
                await provider.generate(self.request())

        self.assertEqual(calls, 2)
        self.assertEqual(raised.exception.attempts, 2)

    async def test_5xx_retries_are_limited(self) -> None:
        calls = 0
        delays: list[float] = []

        async def handler(_: httpx2.Request) -> httpx2.Response:
            nonlocal calls
            calls += 1
            return httpx2.Response(
                503, headers={"X-Request-Id": f"server-failure-{calls}"}
            )

        async def record_sleep(delay: float) -> None:
            delays.append(delay)

        async with httpx2.AsyncClient(
            transport=httpx2.MockTransport(handler)
        ) as client:
            provider = DeepSeekProvider(
                deepseek_settings(), client=client, sleep=record_sleep
            )
            with self.assertRaises(ModelServerError) as raised:
                await provider.generate(self.request())

        self.assertEqual(calls, 3)
        self.assertEqual(delays, [0.01, 0.02])
        self.assertEqual(raised.exception.status_code, 503)
        self.assertEqual(raised.exception.attempts, 3)
        self.assertEqual(raised.exception.provider_request_id, "server-failure-3")

    async def test_timeout_retries_are_limited(self) -> None:
        calls = 0
        delays: list[float] = []

        async def handler(request: httpx2.Request) -> httpx2.Response:
            nonlocal calls
            calls += 1
            raise httpx2.ReadTimeout("mock timeout", request=request)

        async def record_sleep(delay: float) -> None:
            delays.append(delay)

        async with httpx2.AsyncClient(
            transport=httpx2.MockTransport(handler)
        ) as client:
            provider = DeepSeekProvider(
                deepseek_settings(), client=client, sleep=record_sleep
            )
            with self.assertRaises(ModelTimeoutError) as raised:
                await provider.generate(self.request())

        self.assertEqual(calls, 3)
        self.assertEqual(delays, [0.01, 0.02])
        self.assertEqual(raised.exception.attempts, 3)

    async def test_total_timeout_includes_retry_backoff(self) -> None:
        calls = 0
        observed_delays: list[float] = []

        async def handler(_: httpx2.Request) -> httpx2.Response:
            nonlocal calls
            calls += 1
            return httpx2.Response(429)

        async def blocked_sleep(delay: float) -> None:
            observed_delays.append(delay)
            await asyncio.Event().wait()

        async with httpx2.AsyncClient(
            transport=httpx2.MockTransport(handler)
        ) as client:
            provider = DeepSeekProvider(
                deepseek_settings(
                    DEEPSEEK_TOTAL_TIMEOUT_SECONDS="0.1",
                    DEEPSEEK_MAX_RETRIES="5",
                    DEEPSEEK_RETRY_BASE_SECONDS="1",
                    DEEPSEEK_RETRY_MAX_SECONDS="1",
                ),
                client=client,
                sleep=blocked_sleep,
            )
            with self.assertRaises(ModelTimeoutError) as raised:
                await provider.generate(self.request())

        self.assertEqual(calls, 1)
        self.assertEqual(observed_delays, [1.0])
        self.assertEqual(raised.exception.attempts, 1)
        self.assertIn("total timeout", str(raised.exception))

    async def test_cancellation_is_not_swallowed_or_retried(self) -> None:
        started = asyncio.Event()
        calls = 0

        async def handler(_: httpx2.Request) -> httpx2.Response:
            nonlocal calls
            calls += 1
            started.set()
            await asyncio.Event().wait()
            raise AssertionError("The cancelled request unexpectedly resumed.")

        async with httpx2.AsyncClient(
            transport=httpx2.MockTransport(handler)
        ) as client:
            provider = DeepSeekProvider(deepseek_settings(), client=client)
            task = asyncio.create_task(provider.generate(self.request()))
            await started.wait()
            task.cancel()
            with self.assertRaises(asyncio.CancelledError):
                await task

        self.assertEqual(calls, 1)

    async def test_non_retryable_request_error_is_typed(self) -> None:
        calls = 0

        async def handler(_: httpx2.Request) -> httpx2.Response:
            nonlocal calls
            calls += 1
            return httpx2.Response(422)

        async with httpx2.AsyncClient(
            transport=httpx2.MockTransport(handler)
        ) as client:
            provider = DeepSeekProvider(deepseek_settings(), client=client)
            with self.assertRaises(ModelRequestError) as raised:
                await provider.generate(self.request())

        self.assertEqual(calls, 1)
        self.assertEqual(raised.exception.status_code, 422)

    async def test_health_checks_configured_model_with_mock_http(self) -> None:
        async def handler(request: httpx2.Request) -> httpx2.Response:
            self.assertEqual(request.url.path, "/models")
            return httpx2.Response(
                200,
                json={
                    "object": "list",
                    "data": [
                        {
                            "id": "deepseek-test-model",
                            "object": "model",
                            "owned_by": "deepseek",
                        }
                    ],
                },
            )

        async with httpx2.AsyncClient(
            transport=httpx2.MockTransport(handler)
        ) as client:
            provider = DeepSeekProvider(deepseek_settings(), client=client)
            health = await provider.check_health()

        self.assertEqual(health.provider_status, "UP")
        self.assertEqual(health.model_status, "UP")
        self.assertEqual(health.model_version, "deepseek-test-model")

    def test_environment_key_is_redacted_from_configuration_repr(self) -> None:
        settings = deepseek_settings()
        self.assertNotIn(RUNTIME_TEST_KEY, repr(settings))


if __name__ == "__main__":
    unittest.main()
