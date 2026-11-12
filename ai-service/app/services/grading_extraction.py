from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path

from app.clients.grading_attachment import DownloadedGradingAttachment
from app.core.config import GradingSettings
from app.services.document_extraction import (
    AssessmentDocumentExtractor,
    AssessmentDocumentInvalidError,
    AssessmentDocumentTooLargeError,
    AssessmentDocumentUnsupportedError,
    AssessmentDocumentWorkerError,
    extract_assessment_document,
)


class GradingExtractionError(RuntimeError):
    code = "GRADING_EXTRACTION_ERROR"


class GradingExtractionUnsupportedError(GradingExtractionError):
    code = "GRADING_EXTRACTION_UNSUPPORTED"


class GradingExtractionInvalidError(GradingExtractionError):
    code = "GRADING_EXTRACTION_INVALID"


class GradingExtractionTooLargeError(GradingExtractionError):
    code = "GRADING_EXTRACTION_TOO_LARGE"


class GradingExtractionWorkerError(GradingExtractionError):
    code = "GRADING_EXTRACTION_WORKER_ERROR"


@dataclass(frozen=True, slots=True)
class ExtractedGradingAttachment:
    file_id: int
    filename: str
    file_kind: str
    text: str
    warnings: tuple[str, ...]


async def extract_grading_attachments(
    attachments: list[DownloadedGradingAttachment],
    *,
    settings: GradingSettings,
) -> list[ExtractedGradingAttachment]:
    extractor = AssessmentDocumentExtractor(
        max_text_chars=settings.max_extracted_chars_per_file,
        max_pdf_pages=settings.max_pdf_pages,
        max_uncompressed_docx_bytes=min(
            settings.max_attachment_file_bytes * 5,
            100 * 1024 * 1024,
        ),
    )
    extracted: list[ExtractedGradingAttachment] = []
    total_chars = 0
    for attachment in attachments:
        extension = Path(attachment.filename).suffix.lower()
        extraction_filename = attachment.filename
        if extension == ".py":
            # The shared bounded text extractor uses extension allow-listing;
            # Python source is still plain UTF text and needs no execution.
            extraction_filename = f"{Path(attachment.filename).stem}.txt"
        try:
            document = await extract_assessment_document(
                extractor,
                filename=extraction_filename,
                content=attachment.content,
            )
        except AssessmentDocumentUnsupportedError as exc:
            raise GradingExtractionUnsupportedError(
                "Only TXT, Markdown, Python source, DOCX and text-layer PDF attachments are supported."
            ) from exc
        except AssessmentDocumentTooLargeError as exc:
            raise GradingExtractionTooLargeError(
                "An attachment exceeds the extraction safety budget."
            ) from exc
        except AssessmentDocumentInvalidError as exc:
            raise GradingExtractionInvalidError(
                "An attachment is invalid or has no extractable text."
            ) from exc
        except AssessmentDocumentWorkerError as exc:
            raise GradingExtractionWorkerError(
                "The isolated attachment extraction worker is unavailable."
            ) from exc
        total_chars += len(document.text)
        if total_chars > settings.max_extracted_chars_total:
            raise GradingExtractionTooLargeError(
                "The aggregate extracted text exceeds the grading budget."
            )
        extracted.append(
            ExtractedGradingAttachment(
                file_id=attachment.file_id,
                filename=attachment.filename,
                file_kind=document.file_kind,
                text=document.text,
                warnings=document.warnings,
            )
        )
    return extracted
