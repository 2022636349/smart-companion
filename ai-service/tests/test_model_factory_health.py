from __future__ import annotations

import os
import unittest

from app.core.config import AIServiceSettings
from app.providers.base import ModelProviderHealth
from app.providers.factory import create_model_provider
from app.providers.telemetry import TelemetryModelProvider
from app.routers.health import collect_models_health


class FakeProvider:
    provider_name = "ollama"
    model_name = "qwen:test"
    async def check_health(self):
        return ModelProviderHealth("UP", "UP", 3, model_version=self.model_name)
    async def aclose(self): return None


class FactoryAndHealthTest(unittest.IsolatedAsyncioTestCase):
    async def test_health_reports_the_selected_ollama_provider(self):
        response = await collect_models_health(
            settings=AIServiceSettings("ollama", "disabled"), provider=FakeProvider()
        )
        self.assertEqual(response.generation_provider.name, "ollama")
        self.assertEqual(response.chat_model.name, "qwen:test")

    def test_factory_switches_to_ollama_without_a_vendor_leak(self):
        before = {name: os.environ.get(name) for name in ("OLLAMA_CHAT_MODEL", "OLLAMA_BASE_URL", "OLLAMA_ALLOWED_HOSTS")}
        try:
            os.environ.update({"OLLAMA_CHAT_MODEL":"qwen:test", "OLLAMA_BASE_URL":"http://localhost:11434", "OLLAMA_ALLOWED_HOSTS":"localhost"})
            provider = create_model_provider(AIServiceSettings("ollama", "disabled"))
            self.assertIsInstance(provider, TelemetryModelProvider)
            self.assertEqual(provider.provider_name, "ollama")
        finally:
            for name, value in before.items():
                if value is None: os.environ.pop(name, None)
                else: os.environ[name] = value
