from __future__ import annotations

import unittest
from unittest.mock import patch

from app.clients.grading_attachment import DownloadedGradingAttachment
from app.core.config import GradingSettings
from app.services.document_extraction import AssessmentDocumentTooLargeError
from app.services.document_extraction import ExtractedAssessmentDocument
from app.services.grading_extraction import (
    GradingExtractionTooLargeError,
    extract_grading_attachments,
)


def settings() -> GradingSettings:
    return GradingSettings(
        total_timeout_seconds=2,
        attachment_timeout_seconds=1,
        max_attachment_files=5,
        max_attachment_file_bytes=1_024,
        max_attachment_total_bytes=2_048,
        max_extracted_chars_per_file=20,
        max_extracted_chars_total=25,
        max_pdf_pages=10,
    )


class GradingExtractionTest(unittest.IsolatedAsyncioTestCase):
    async def test_aggregate_text_budget_is_enforced(self) -> None:
        attachments = [
            DownloadedGradingAttachment(
                file_id=index,
                filename=f"{index}.txt",
                content=("x" * 15).encode(),
                content_type="text/plain",
            )
            for index in (1, 2)
        ]
        with patch(
            "app.services.grading_extraction.extract_assessment_document",
            side_effect=[
                ExtractedAssessmentDocument(text="x" * 15, file_kind="TEXT", warnings=()),
                ExtractedAssessmentDocument(text="x" * 15, file_kind="TEXT", warnings=()),
            ],
        ):
            with self.assertRaises(GradingExtractionTooLargeError):
                await extract_grading_attachments(attachments, settings=settings())

    async def test_decompression_bomb_failure_is_typed_and_fail_closed(self) -> None:
        attachment = DownloadedGradingAttachment(
            file_id=1,
            filename="bomb.docx",
            content=b"PK malicious compressed body",
            content_type="application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        )
        with patch(
            "app.services.grading_extraction.extract_assessment_document",
            side_effect=AssessmentDocumentTooLargeError("bomb"),
        ):
            with self.assertRaises(GradingExtractionTooLargeError):
                await extract_grading_attachments([attachment], settings=settings())

    async def test_python_source_is_extracted_as_text_without_execution(self) -> None:
        attachment = DownloadedGradingAttachment(
            file_id=1,
            filename="solution.py",
            content=b"print('student answer')",
            content_type="text/x-python",
        )
        with patch(
            "app.services.grading_extraction.extract_assessment_document",
            return_value=ExtractedAssessmentDocument(
                text="print('student answer')",
                file_kind="TEXT",
                warnings=(),
            ),
        ) as extract:
            result = await extract_grading_attachments([attachment], settings=settings())
        self.assertTrue(extract.call_args.kwargs["filename"].endswith(".txt"))
        self.assertEqual(result[0].filename, "solution.py")


if __name__ == "__main__":
    unittest.main()
