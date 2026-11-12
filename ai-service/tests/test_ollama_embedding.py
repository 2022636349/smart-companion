import os
import unittest
from unittest.mock import patch

import httpx2

from app.core.config import MemorySettings
from app.providers.base import EmbeddingResponseError, EmbeddingTimeoutError
from app.providers.ollama import OllamaEmbeddingProvider


def settings() -> MemorySettings:
    with patch.dict(
        os.environ,
        {
            "MEMORY_EMBEDDING_PROVIDER": "ollama",
            "MEMORY_EMBEDDING_MODEL": "nomic-embed-text:test",
            "OLLAMA_BASE_URL": "http://localhost:11434",
            "CHROMA_PERSIST_DIR": "D:\\CodexEnvs\\EducationAgent\\chroma-test",
        },
        clear=False,
    ):
        return MemorySettings.from_env()


class OllamaEmbeddingProviderTest(unittest.IsolatedAsyncioTestCase):
    async def test_embed_uses_ollama_api_and_validates_vectors(self) -> None:
        captured = {}

        def handler(request: httpx2.Request) -> httpx2.Response:
            captured["request"] = request
            return httpx2.Response(200, json={"embeddings": [[1, 0], [0, 1]]})

        async with httpx2.AsyncClient(transport=httpx2.MockTransport(handler)) as client:
            provider = OllamaEmbeddingProvider(settings(), client=client)
            vectors = await provider.embed(("one", "two"))

        self.assertEqual(vectors, [[1.0, 0.0], [0.0, 1.0]])
        self.assertEqual(captured["request"].url.path, "/api/embed")
        self.assertNotIn("987654321", captured["request"].content.decode())

    async def test_malformed_vectors_are_typed(self) -> None:
        def handler(_: httpx2.Request) -> httpx2.Response:
            return httpx2.Response(200, json={"embeddings": "not-a-vector-list"})

        async with httpx2.AsyncClient(transport=httpx2.MockTransport(handler)) as client:
            with self.assertRaises(EmbeddingResponseError):
                await OllamaEmbeddingProvider(settings(), client=client).embed(("one",))

    async def test_timeout_is_typed(self) -> None:
        def handler(request: httpx2.Request) -> httpx2.Response:
            raise httpx2.ReadTimeout("mock", request=request)

        async with httpx2.AsyncClient(transport=httpx2.MockTransport(handler)) as client:
            with self.assertRaises(EmbeddingTimeoutError):
                await OllamaEmbeddingProvider(settings(), client=client).embed(("one",))


if __name__ == "__main__":
    unittest.main()
