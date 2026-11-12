from __future__ import annotations

import secrets
import unittest

import httpx2
from pydantic import SecretStr

from app.clients.assessment_source import (
    AssessmentSourceClient,
    AssessmentSourceInputError,
    AssessmentSourceRequestError,
    AssessmentSourceTimeoutError,
    AssessmentSourceTooLargeError,
)
from app.core.config import AssessmentSourceSettings


RUNTIME_TOKEN = secrets.token_urlsafe(24)


def settings(*, max_file_bytes: int = 1024) -> AssessmentSourceSettings:
    return AssessmentSourceSettings(
        base_url="https://business.test",
        internal_token=SecretStr(RUNTIME_TOKEN),
        timeout_seconds=1,
        max_file_bytes=max_file_bytes,
        max_text_chars=10_000,
        max_pdf_pages=20,
    )


class AssessmentSourceClientTest(unittest.IsolatedAsyncioTestCase):
    async def test_downloads_only_exact_spring_file_endpoint_with_trace_headers(self) -> None:
        observed = {}

        async def handler(request: httpx2.Request) -> httpx2.Response:
            observed["path"] = request.url.path
            observed["token"] = request.headers["X-Internal-Token"]
            observed["request_id"] = request.headers["X-Request-Id"]
            return httpx2.Response(
                200,
                content="第一题".encode(),
                headers={"Content-Type": "text/plain; charset=utf-8"},
            )

        async with httpx2.AsyncClient(
            transport=httpx2.MockTransport(handler)
        ) as http_client:
            client = AssessmentSourceClient(settings(), client=http_client)
            result = await client.download(
                source_file_id=42,
                download_url="https://business.test/api/files/42/content",
                request_id="trace-assessment-0001",
            )

        self.assertEqual(observed["path"], "/api/files/42/content")
        self.assertEqual(observed["token"], RUNTIME_TOKEN)
        self.assertEqual(observed["request_id"], "trace-assessment-0001")
        self.assertEqual(result.content, "第一题".encode())
        self.assertEqual(result.content_type, "text/plain")

    async def test_cross_origin_or_mismatched_file_id_is_rejected_before_http(self) -> None:
        calls = 0

        async def handler(_: httpx2.Request) -> httpx2.Response:
            nonlocal calls
            calls += 1
            return httpx2.Response(200, content=b"unexpected")

        async with httpx2.AsyncClient(
            transport=httpx2.MockTransport(handler)
        ) as http_client:
            client = AssessmentSourceClient(settings(), client=http_client)
            for url in (
                "https://attacker.test/api/files/42/content",
                "https://business.test/api/files/41/content",
                "https://business.test/api/files/42/content?redirect=1",
            ):
                with self.assertRaises(AssessmentSourceInputError):
                    await client.download(
                        source_file_id=42,
                        download_url=url,
                        request_id="trace-assessment-0002",
                    )

        self.assertEqual(calls, 0)

    async def test_redirect_is_not_followed(self) -> None:
        calls = 0

        async def handler(_: httpx2.Request) -> httpx2.Response:
            nonlocal calls
            calls += 1
            return httpx2.Response(
                302, headers={"Location": "https://attacker.test/file"}
            )

        async with httpx2.AsyncClient(
            transport=httpx2.MockTransport(handler)
        ) as http_client:
            client = AssessmentSourceClient(settings(), client=http_client)
            with self.assertRaises(AssessmentSourceRequestError):
                await client.download(
                    source_file_id=42,
                    download_url="https://business.test/api/files/42/content",
                    request_id="trace-assessment-0003",
                )

        self.assertEqual(calls, 1)

    async def test_stream_is_stopped_when_file_exceeds_limit(self) -> None:
        async def handler(_: httpx2.Request) -> httpx2.Response:
            return httpx2.Response(200, content=b"x" * 11)

        async with httpx2.AsyncClient(
            transport=httpx2.MockTransport(handler)
        ) as http_client:
            client = AssessmentSourceClient(
                settings(max_file_bytes=10), client=http_client
            )
            with self.assertRaises(AssessmentSourceTooLargeError):
                await client.download(
                    source_file_id=42,
                    download_url="https://business.test/api/files/42/content",
                    request_id="trace-assessment-0004",
                )

    async def test_timeout_is_typed_without_leaking_url_or_token(self) -> None:
        async def handler(request: httpx2.Request) -> httpx2.Response:
            raise httpx2.ReadTimeout("mock timeout", request=request)

        async with httpx2.AsyncClient(
            transport=httpx2.MockTransport(handler)
        ) as http_client:
            client = AssessmentSourceClient(settings(), client=http_client)
            with self.assertRaises(AssessmentSourceTimeoutError) as raised:
                await client.download(
                    source_file_id=42,
                    download_url="https://business.test/api/files/42/content",
                    request_id="trace-assessment-0005",
                )

        self.assertNotIn(RUNTIME_TOKEN, str(raised.exception))


if __name__ == "__main__":
    unittest.main()
