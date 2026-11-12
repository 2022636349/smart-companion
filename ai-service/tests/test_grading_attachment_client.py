from __future__ import annotations

import asyncio
import secrets
import unittest

import httpx2
from pydantic import SecretStr

from app.clients.grading_attachment import (
    GradingAttachmentClient,
    GradingAttachmentInputError,
    GradingAttachmentRequestError,
    GradingAttachmentTimeoutError,
    GradingAttachmentTooLargeError,
)
from app.core.config import AssessmentSourceSettings, GradingSettings


TOKEN = secrets.token_urlsafe(24)


def source_settings() -> AssessmentSourceSettings:
    return AssessmentSourceSettings(
        base_url="https://business.test",
        internal_token=SecretStr(TOKEN),
        timeout_seconds=1,
        max_file_bytes=1_024,
        max_text_chars=10_000,
        max_pdf_pages=20,
    )


def grading_settings(**updates) -> GradingSettings:
    values = {
        "total_timeout_seconds": 2,
        "attachment_timeout_seconds": 1,
        "max_attachment_files": 5,
        "max_attachment_file_bytes": 20,
        "max_attachment_total_bytes": 30,
        "max_extracted_chars_per_file": 1_000,
        "max_extracted_chars_total": 2_000,
        "max_pdf_pages": 20,
    }
    values.update(updates)
    return GradingSettings(**values)


class GradingAttachmentClientTest(unittest.IsolatedAsyncioTestCase):
    async def test_download_binds_internal_token_request_id_and_filename(self) -> None:
        observed = {}

        async def handler(request: httpx2.Request) -> httpx2.Response:
            observed["token"] = request.headers["X-Internal-Token"]
            observed["request_id"] = request.headers["X-Request-Id"]
            return httpx2.Response(
                200,
                content="学生答案".encode(),
                headers={
                    "Content-Type": "text/plain",
                    "Content-Disposition": "attachment; filename*=UTF-8''answer.txt",
                },
            )

        async with httpx2.AsyncClient(transport=httpx2.MockTransport(handler)) as http:
            client = GradingAttachmentClient(
                source_settings(), grading_settings(), client=http
            )
            result = await client.download_all(
                ["https://business.test/api/files/7/content"],
                request_id="trace-grading-download-01",
            )
        self.assertEqual(observed, {"token": TOKEN, "request_id": "trace-grading-download-01"})
        self.assertEqual(result[0].file_id, 7)
        self.assertEqual(result[0].filename, "answer.txt")

    async def test_rejects_cross_origin_query_credentials_and_non_file_paths(self) -> None:
        calls = 0

        async def handler(_: httpx2.Request) -> httpx2.Response:
            nonlocal calls
            calls += 1
            return httpx2.Response(200, content=b"unexpected")

        bad_urls = [
            "https://attacker.test/api/files/7/content",
            "https://business.test/api/files/7/content?next=evil",
            "https://user@business.test/api/files/7/content",
            "https://business.test/api/files/7/content/extra",
        ]
        async with httpx2.AsyncClient(transport=httpx2.MockTransport(handler)) as http:
            client = GradingAttachmentClient(
                source_settings(), grading_settings(), client=http
            )
            for url in bad_urls:
                with self.assertRaises(GradingAttachmentInputError):
                    await client.download_all([url], request_id="trace-grading-download-02")
        self.assertEqual(calls, 0)

    async def test_redirect_is_never_followed(self) -> None:
        calls = 0

        async def handler(_: httpx2.Request) -> httpx2.Response:
            nonlocal calls
            calls += 1
            return httpx2.Response(302, headers={"Location": "https://attacker.test"})

        async with httpx2.AsyncClient(transport=httpx2.MockTransport(handler)) as http:
            client = GradingAttachmentClient(source_settings(), grading_settings(), client=http)
            with self.assertRaises(GradingAttachmentRequestError):
                await client.download_all(
                    ["https://business.test/api/files/7/content"],
                    request_id="trace-grading-download-03",
                )
        self.assertEqual(calls, 1)

    async def test_per_file_and_aggregate_budgets_fail_closed(self) -> None:
        async def handler(request: httpx2.Request) -> httpx2.Response:
            return httpx2.Response(
                200,
                content=b"x" * (16 if request.url.path.endswith("/7/content") else 15),
                headers={"Content-Disposition": "attachment; filename=answer.txt"},
            )

        async with httpx2.AsyncClient(transport=httpx2.MockTransport(handler)) as http:
            client = GradingAttachmentClient(source_settings(), grading_settings(), client=http)
            with self.assertRaises(GradingAttachmentTooLargeError):
                await client.download_all(
                    [
                        "https://business.test/api/files/7/content",
                        "https://business.test/api/files/8/content",
                    ],
                    request_id="trace-grading-download-04",
                )

    async def test_timeout_and_cancellation_remain_typed_or_propagated(self) -> None:
        async def timeout_handler(request: httpx2.Request) -> httpx2.Response:
            raise httpx2.ReadTimeout("mock", request=request)

        async with httpx2.AsyncClient(transport=httpx2.MockTransport(timeout_handler)) as http:
            client = GradingAttachmentClient(source_settings(), grading_settings(), client=http)
            with self.assertRaises(GradingAttachmentTimeoutError):
                await client.download_all(
                    ["https://business.test/api/files/7/content"],
                    request_id="trace-grading-download-05",
                )

        started = asyncio.Event()
        release = asyncio.Event()

        async def slow_handler(_: httpx2.Request) -> httpx2.Response:
            started.set()
            await release.wait()
            return httpx2.Response(200, content=b"late")

        async with httpx2.AsyncClient(transport=httpx2.MockTransport(slow_handler)) as http:
            client = GradingAttachmentClient(source_settings(), grading_settings(), client=http)
            task = asyncio.create_task(
                client.download_all(
                    ["https://business.test/api/files/7/content"],
                    request_id="trace-grading-download-06",
                )
            )
            await started.wait()
            task.cancel()
            with self.assertRaises(asyncio.CancelledError):
                await task
            release.set()


if __name__ == "__main__":
    unittest.main()
