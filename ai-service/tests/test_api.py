import os
import secrets
import unittest
from unittest.mock import patch

from fastapi.testclient import TestClient

from app.main import app
from app.providers.base import ModelProviderHealth


RUNTIME_TEST_KEY = secrets.token_urlsafe(24)


class FoundationApiTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        os.environ["INTERNAL_SERVICE_TOKEN"] = "test-internal-token"
        cls.client = TestClient(app)

    @staticmethod
    def internal_headers() -> dict[str, str]:
        return {
            "X-Internal-Token": "test-internal-token",
            "X-Request-Id": "test-request-0001",
        }

    def test_health_endpoint(self) -> None:
        health = self.client.get("/health")
        self.assertEqual(health.status_code, 200)
        self.assertEqual(health.json()["status"], "UP")

    def test_models_health_without_model_configuration_is_down(self) -> None:
        with patch.dict(
            os.environ,
            {
                "AI_MODEL_PROVIDER": "deepseek",
                "DEEPSEEK_API_KEY": "",
                "DEEPSEEK_CHAT_MODEL": "",
                "MEMORY_EMBEDDING_PROVIDER": "disabled",
            },
            clear=False,
        ):
            response = self.client.get(
                "/internal/models/health", headers=self.internal_headers()
            )

        self.assertEqual(response.status_code, 200)
        body = response.json()
        self.assertEqual(body["status"], "DOWN")
        self.assertEqual(body["generation_provider"]["status"], "DOWN")
        self.assertEqual(body["chat_model"]["status"], "DOWN")
        self.assertEqual(body["embedding_provider"]["status"], "DEGRADED")
        self.assertEqual(body["chroma"]["status"], "DEGRADED")

    def test_models_health_uses_provider_status_and_keeps_v21_shape(self) -> None:
        class FakeProvider:
            provider_name = "deepseek"
            model_name = "deepseek-test-model"

            def __init__(self) -> None:
                self.closed = False

            async def check_health(self) -> ModelProviderHealth:
                return ModelProviderHealth(
                    provider_status="UP",
                    model_status="UP",
                    latency_ms=12,
                    model_version=self.model_name,
                )

            async def aclose(self) -> None:
                self.closed = True

        fake_provider = FakeProvider()
        environment = {
            "AI_MODEL_PROVIDER": "deepseek",
            "DEEPSEEK_API_KEY": RUNTIME_TEST_KEY,
            "DEEPSEEK_CHAT_MODEL": fake_provider.model_name,
            "MEMORY_EMBEDDING_PROVIDER": "disabled",
        }
        with (
            patch.dict(os.environ, environment, clear=False),
            patch(
                "app.routers.health.create_model_provider",
                return_value=fake_provider,
            ),
        ):
            response = self.client.get(
                "/internal/models/health", headers=self.internal_headers()
            )

        self.assertEqual(response.status_code, 200)
        body = response.json()
        self.assertEqual(body["status"], "DEGRADED")
        self.assertEqual(body["service"], "education-agent-ai-service")
        self.assertEqual(body["generation_provider"]["name"], "deepseek")
        self.assertEqual(body["generation_provider"]["status"], "UP")
        self.assertEqual(body["chat_model"]["name"], fake_provider.model_name)
        self.assertEqual(body["chat_model"]["status"], "UP")
        self.assertEqual(body["chat_model"]["version"], fake_provider.model_name)
        self.assertEqual(body["embedding_provider"]["name"], "disabled")
        self.assertEqual(body["chroma"]["name"], "chroma")
        self.assertNotIn("ollama", body)
        self.assertIn("timestamp", body)
        self.assertNotIn(RUNTIME_TEST_KEY, response.text)
        self.assertTrue(fake_provider.closed)

    def test_tutor_endpoint_rejects_request_id_mismatch(self) -> None:
        response = self.client.post(
            "/internal/ai/tutor/chat",
            json={
                "request_id": "test-request-1",
                "course_id": 1,
                "course_name": "Python程序设计",
                "enabled_activity_types": ["EXPLAIN", "QUIZ", "CODE", "VISUAL"],
                "user_id": 1,
                "knowledge_point_id": "5.1.1",
                "message": "请解释函数定义语法",
            },
            headers=self.internal_headers(),
        )

        self.assertEqual(response.status_code, 422)
        self.assertEqual(
            response.json()["detail"]["code"], "TUTOR_REQUEST_ID_MISMATCH"
        )

    def test_internal_endpoint_requires_request_id(self) -> None:
        response = self.client.post(
            "/internal/ai/tutor/chat",
            headers={"X-Internal-Token": "test-internal-token"},
            json={
                "request_id": "test-request-1",
                "user_id": 1,
                "knowledge_point_id": "5.1.1",
                "message": "请解释函数定义语法",
            },
        )
        self.assertEqual(response.status_code, 422)
        self.assertEqual(response.json()["detail"]["code"], "VALIDATION_ERROR")

    def test_internal_endpoint_rejects_wrong_token(self) -> None:
        response = self.client.post(
            "/internal/ai/tutor/chat",
            headers={"X-Internal-Token": "wrong", "X-Request-Id": "test-request-0002"},
            json={
                "request_id": "test-request-2",
                "user_id": 1,
                "knowledge_point_id": "5.1.1",
                "message": "请解释函数定义语法",
            },
        )
        self.assertEqual(response.status_code, 401)
        self.assertEqual(response.json()["detail"]["code"], "INTERNAL_UNAUTHORIZED")


if __name__ == "__main__":
    unittest.main()
