from __future__ import annotations

import asyncio
import io
import json
import struct
import sys
import unittest
import zipfile
import zlib

from app.services.document_extraction import (
    PDF_DOCUMENT_MAX_DECODED_BYTES,
    PDF_STREAM_MAX_OUTPUT_BYTES,
    AssessmentDocumentExtractor,
    AssessmentDocumentInvalidError,
    AssessmentDocumentTooLargeError,
    AssessmentDocumentUnsupportedError,
    AssessmentDocumentWorkerError,
    AssessmentDocumentWorkerPool,
)


def extractor(*, max_chars: int = 10_000) -> AssessmentDocumentExtractor:
    return AssessmentDocumentExtractor(
        max_text_chars=max_chars,
        max_pdf_pages=20,
        max_uncompressed_docx_bytes=1024 * 1024,
    )


def docx_bytes(text: str) -> bytes:
    body = (
        '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
        '<w:document xmlns:w="http://schemas.openxmlformats.org/'
        'wordprocessingml/2006/main"><w:body><w:p><w:r><w:t>'
        f"{text}"
        "</w:t></w:r></w:p></w:body></w:document>"
    )
    output = io.BytesIO()
    with zipfile.ZipFile(output, "w", zipfile.ZIP_DEFLATED) as document:
        document.writestr("word/document.xml", body.encode("utf-8"))
    return output.getvalue()


def pdf_bytes(stream: bytes, *, filter_name: bytes | None = None) -> bytes:
    filter_entry = b"" if filter_name is None else b" /Filter " + filter_name
    objects = [
        b"<< /Type /Catalog /Pages 2 0 R >>",
        b"<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
        (
            b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] "
            b"/Resources << /Font << /F1 5 0 R >> >> /Contents 4 0 R >>"
        ),
        b"<< /Length "
        + str(len(stream)).encode()
        + filter_entry
        + b" >>\nstream\n"
        + stream
        + b"\nendstream",
        b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
    ]
    output = bytearray(b"%PDF-1.4\n")
    offsets = [0]
    for index, value in enumerate(objects, 1):
        offsets.append(len(output))
        output.extend(f"{index} 0 obj\n".encode() + value + b"\nendobj\n")
    xref_offset = len(output)
    output.extend(f"xref\n0 {len(objects) + 1}\n".encode())
    output.extend(b"0000000000 65535 f \n")
    for offset in offsets[1:]:
        output.extend(f"{offset:010d} 00000 n \n".encode())
    output.extend(
        (
            f"trailer\n<< /Size {len(objects) + 1} /Root 1 0 R >>\n"
            f"startxref\n{xref_offset}\n%%EOF\n"
        ).encode()
    )
    return bytes(output)


def text_pdf_bytes(text: str) -> bytes:
    escaped = text.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)")
    stream = f"BT /F1 12 Tf 72 720 Td ({escaped}) Tj ET".encode("ascii")
    return pdf_bytes(stream)


def zlib_bomb_pdf_bytes() -> bytes:
    expanded = b"%" * (PDF_STREAM_MAX_OUTPUT_BYTES + 1)
    return pdf_bytes(zlib.compress(expanded, level=9), filter_name=b"/FlateDecode")


def cumulative_zlib_pdf_bytes() -> bytes:
    per_stream_size = 7 * 1024 * 1024
    stream_count = PDF_DOCUMENT_MAX_DECODED_BYTES // per_stream_size + 1
    compressed = zlib.compress(b"%" * per_stream_size, level=9)
    stream_ids = list(range(4, 4 + stream_count))
    font_id = 4 + stream_count
    content_refs = b" ".join(f"{item} 0 R".encode() for item in stream_ids)
    objects = [
        b"<< /Type /Catalog /Pages 2 0 R >>",
        b"<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
        (
            b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] "
            + f"/Resources << /Font << /F1 {font_id} 0 R >> >> ".encode()
            + b"/Contents ["
            + content_refs
            + b"] >>"
        ),
        *[
            b"<< /Length "
            + str(len(compressed)).encode()
            + b" /Filter /FlateDecode >>\nstream\n"
            + compressed
            + b"\nendstream"
            for _ in stream_ids
        ],
        b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
    ]
    output = bytearray(b"%PDF-1.4\n")
    offsets = [0]
    for index, value in enumerate(objects, 1):
        offsets.append(len(output))
        output.extend(f"{index} 0 obj\n".encode() + value + b"\nendobj\n")
    xref_offset = len(output)
    output.extend(f"xref\n0 {len(objects) + 1}\n".encode())
    output.extend(b"0000000000 65535 f \n")
    for offset in offsets[1:]:
        output.extend(f"{offset:010d} 00000 n \n".encode())
    output.extend(
        (
            f"trailer\n<< /Size {len(objects) + 1} /Root 1 0 R >>\n"
            f"startxref\n{xref_offset}\n%%EOF\n"
        ).encode()
    )
    return bytes(output)


def worker_command(script: str) -> tuple[str, ...]:
    return (sys.executable, "-I", "-c", script)


CONTROLLED_WORKER_SCRIPT = r"""
import json
import struct
import sys
import time

request = sys.stdin.buffer.read()
if request.endswith(b"HANG"):
    time.sleep(60)
payload = json.dumps(
    {"ok": True, "text": "worker result", "file_kind": "TEXT", "warnings": []},
    separators=(",", ":"),
).encode("utf-8")
sys.stdout.buffer.write(struct.pack(">I", len(payload)) + payload)
sys.stdout.buffer.flush()
"""


SLOW_WORKER_SCRIPT = CONTROLLED_WORKER_SCRIPT.replace(
    'if request.endswith(b"HANG"):\n    time.sleep(60)',
    "time.sleep(0.2)",
)


BAD_PROTOCOL_WORKER_SCRIPT = r"""
import struct
import sys

sys.stdin.buffer.read()
payload = b"not-json"
sys.stdout.buffer.write(struct.pack(">I", len(payload)) + payload)
sys.stdout.buffer.flush()
"""


class AssessmentDocumentExtractorTest(unittest.TestCase):
    def test_extracts_utf8_and_gb18030_text_without_persistence(self) -> None:
        utf8 = extractor().extract(filename="作业.txt", content="第一题\n答案".encode())
        gb = extractor().extract(
            filename="作业.md", content="第二题：列表".encode("gb18030")
        )

        self.assertEqual(utf8.file_kind, "TEXT")
        self.assertIn("第一题", utf8.text)
        self.assertIn("第二题", gb.text)

    def test_extracts_docx_document_body(self) -> None:
        result = extractor().extract(
            filename="questions.docx",
            content=docx_bytes("Question 1: explain functions."),
        )

        self.assertEqual(result.file_kind, "DOCX")
        self.assertEqual(result.text, "Question 1: explain functions.")

    def test_extracts_text_pdf(self) -> None:
        result = extractor().extract(
            filename="questions.pdf",
            content=text_pdf_bytes("Question 1: What is Python?"),
        )

        self.assertEqual(result.file_kind, "PDF")
        self.assertIn("What is Python", result.text)

    def test_rejects_legacy_word_and_disguised_binary(self) -> None:
        with self.assertRaises(AssessmentDocumentUnsupportedError):
            extractor().extract(filename="legacy.doc", content=b"legacy")
        with self.assertRaises(AssessmentDocumentInvalidError):
            extractor().extract(filename="fake.txt", content=b"%PDF-1.4\n")

    def test_rejects_text_over_prompt_limit(self) -> None:
        with self.assertRaises(AssessmentDocumentTooLargeError):
            extractor(max_chars=10).extract(
                filename="too-long.txt",
                content=b"a" * 11,
            )

    def test_rejects_pdf_zlib_expansion_bomb(self) -> None:
        with self.assertRaises(AssessmentDocumentTooLargeError):
            extractor().extract(
                filename="zlib-bomb.pdf",
                content=zlib_bomb_pdf_bytes(),
            )

    def test_rejects_cumulative_pdf_decode_budget_overflow(self) -> None:
        with self.assertRaises(AssessmentDocumentTooLargeError):
            extractor().extract(
                filename="cumulative-zlib.pdf",
                content=cumulative_zlib_pdf_bytes(),
            )

    def test_pdf_page_text_visitor_enforces_character_budget(self) -> None:
        with self.assertRaises(AssessmentDocumentTooLargeError):
            extractor(max_chars=1_000).extract(
                filename="large-page.pdf",
                content=text_pdf_bytes("A" * 1_001),
            )


class AssessmentDocumentWorkerPoolTest(unittest.IsolatedAsyncioTestCase):
    async def _wait_for_active(
        self,
        pool: AssessmentDocumentWorkerPool,
        expected: int,
        *,
        timeout: float = 2.0,
    ) -> None:
        loop = asyncio.get_running_loop()
        deadline = loop.time() + timeout
        while pool.active_process_count != expected:
            if loop.time() >= deadline:
                self.fail(
                    f"worker count did not reach {expected}; "
                    f"active={pool.active_process_count}"
                )
            await asyncio.sleep(0.005)

    async def test_real_disposable_subprocess_extracts_text_and_reaps(self) -> None:
        pool = AssessmentDocumentWorkerPool(max_workers=1)

        result = await pool.extract(
            extractor(),
            filename="questions.txt",
            content=b"Question 1: What is Python?",
        )

        self.assertEqual(result.text, "Question 1: What is Python?")
        self.assertEqual(result.file_kind, "TEXT")
        self.assertEqual(pool.active_process_count, 0)
        self.assertEqual(pool.active_pids, ())

    async def test_pool_configuration_cannot_exceed_global_limit(self) -> None:
        with self.assertRaises(ValueError):
            AssessmentDocumentWorkerPool(max_workers=3)

    async def test_real_subprocess_rejects_zlib_bomb_and_page_text_overflow(self) -> None:
        pool = AssessmentDocumentWorkerPool(max_workers=1)

        with self.assertRaises(AssessmentDocumentTooLargeError):
            await pool.extract(
                extractor(),
                filename="zlib-bomb.pdf",
                content=zlib_bomb_pdf_bytes(),
            )
        with self.assertRaises(AssessmentDocumentTooLargeError):
            await pool.extract(
                extractor(max_chars=1_000),
                filename="large-page.pdf",
                content=text_pdf_bytes("A" * 1_001),
            )
        with self.assertRaises(AssessmentDocumentTooLargeError):
            await pool.extract(
                extractor(),
                filename="cumulative-zlib.pdf",
                content=cumulative_zlib_pdf_bytes(),
            )

        self.assertEqual(pool.active_process_count, 0)
        self.assertEqual(pool.active_pids, ())

    async def test_pool_never_runs_more_than_two_workers(self) -> None:
        pool = AssessmentDocumentWorkerPool(
            worker_command=worker_command(SLOW_WORKER_SCRIPT),
        )
        tasks = [
            asyncio.create_task(
                pool.extract(
                    extractor(),
                    filename=f"questions-{index}.txt",
                    content=f"question-{index}".encode(),
                )
            )
            for index in range(3)
        ]
        peak = 0
        while not all(task.done() for task in tasks):
            peak = max(peak, pool.active_process_count)
            self.assertLessEqual(pool.active_process_count, 2)
            await asyncio.sleep(0.005)
        results = await asyncio.gather(*tasks)

        self.assertEqual(peak, 2)
        self.assertEqual([item.text for item in results], ["worker result"] * 3)
        self.assertEqual(pool.active_process_count, 0)

    async def test_worker_spawn_failure_is_typed_and_releases_slot(self) -> None:
        pool = AssessmentDocumentWorkerPool(
            max_workers=1,
            worker_command=("Z:\\missing-education-agent-worker.exe",),
        )

        for _ in range(2):
            with self.assertRaises(AssessmentDocumentWorkerError):
                await asyncio.wait_for(
                    pool.extract(
                        extractor(),
                        filename="questions.txt",
                        content=b"normal",
                    ),
                    timeout=2.0,
                )

        self.assertEqual(pool.active_process_count, 0)
        self.assertEqual(pool.active_pids, ())

    async def test_explicit_cancellation_reaps_pid_and_reuses_slot(self) -> None:
        pool = AssessmentDocumentWorkerPool(
            max_workers=1,
            worker_command=worker_command(CONTROLLED_WORKER_SCRIPT),
        )
        task = asyncio.create_task(
            pool.extract(
                extractor(),
                filename="questions.txt",
                content=b"HANG",
            )
        )
        await self._wait_for_active(pool, 1)
        started_pids = pool.active_pids

        task.cancel()
        with self.assertRaises(asyncio.CancelledError):
            await task

        self.assertTrue(started_pids)
        self.assertEqual(pool.active_process_count, 0)
        self.assertEqual(pool.active_pids, ())
        reused = await asyncio.wait_for(
            pool.extract(
                extractor(),
                filename="questions.txt",
                content=b"normal",
            ),
            timeout=2.0,
        )
        self.assertEqual(reused.text, "worker result")
        self.assertEqual(pool.active_process_count, 0)

    async def test_outer_timeout_reaps_pid_and_reuses_slot(self) -> None:
        pool = AssessmentDocumentWorkerPool(
            max_workers=1,
            worker_command=worker_command(CONTROLLED_WORKER_SCRIPT),
        )
        task = asyncio.create_task(
            pool.extract(
                extractor(),
                filename="questions.txt",
                content=b"HANG",
            )
        )
        await self._wait_for_active(pool, 1)
        started_pids = pool.active_pids
        with self.assertRaises(TimeoutError):
            await asyncio.wait_for(task, timeout=0.1)

        self.assertTrue(started_pids)
        self.assertEqual(pool.active_process_count, 0)
        self.assertEqual(pool.active_pids, ())
        reused = await asyncio.wait_for(
            pool.extract(
                extractor(),
                filename="questions.txt",
                content=b"normal",
            ),
            timeout=2.0,
        )
        self.assertEqual(reused.text, "worker result")
        self.assertEqual(pool.active_process_count, 0)

    async def test_bad_worker_protocol_is_typed_and_process_is_reaped(self) -> None:
        pool = AssessmentDocumentWorkerPool(
            max_workers=1,
            worker_command=worker_command(BAD_PROTOCOL_WORKER_SCRIPT),
        )

        with self.assertRaises(AssessmentDocumentWorkerError):
            await pool.extract(
                extractor(),
                filename="questions.txt",
                content=b"normal",
            )

        self.assertEqual(pool.active_process_count, 0)
        self.assertEqual(pool.active_pids, ())


if __name__ == "__main__":
    unittest.main()
