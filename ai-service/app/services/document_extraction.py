from __future__ import annotations

import asyncio
import io
import json
import os
import re
import struct
import subprocess
import sys
import threading
import zipfile
from contextlib import contextmanager
from dataclasses import dataclass
from pathlib import Path, PurePath
from typing import Any, Iterator, Mapping, Sequence
from xml.etree import ElementTree

from pypdf import filters as pypdf_filters
from pypdf import PdfReader
from pypdf.errors import LimitReachedError, PyPdfError
from pypdf.generic import ArrayObject, DictionaryObject, NameObject


PDF_STREAM_MAX_OUTPUT_BYTES = 8 * 1024 * 1024
PDF_DOCUMENT_MAX_DECODED_BYTES = 32 * 1024 * 1024
PDF_ZLIB_RECOVERY_MAX_INPUT_BYTES = 128 * 1024
DOCUMENT_EXTRACTION_MAX_WORKERS = 2
WORKER_REQUEST_HEADER_MAX_BYTES = 16 * 1024
WORKER_CONTENT_MAX_BYTES = 50 * 1024 * 1024
WORKER_RESPONSE_MAX_BYTES = 2 * 1024 * 1024
WORKER_TERMINATE_GRACE_SECONDS = 2.0


class _PdfSafetyLimitExceeded(BaseException):
    """Circuit breaker that pypdf's broad Exception handlers cannot hide."""


def _configure_pypdf_decoder_limits() -> None:
    output_limit_names = (
        "MAX_DECLARED_STREAM_LENGTH",
        "MAX_ARRAY_BASED_STREAM_OUTPUT_LENGTH",
        "JBIG2_MAX_OUTPUT_LENGTH",
        "LZW_MAX_OUTPUT_LENGTH",
        "RUN_LENGTH_MAX_OUTPUT_LENGTH",
        "ZLIB_MAX_OUTPUT_LENGTH",
        "FLATE_MAX_BUFFER_SIZE",
    )
    for name in output_limit_names:
        setattr(
            pypdf_filters,
            name,
            min(getattr(pypdf_filters, name), PDF_STREAM_MAX_OUTPUT_BYTES),
        )
    pypdf_filters.ZLIB_MAX_RECOVERY_INPUT_LENGTH = min(
        pypdf_filters.ZLIB_MAX_RECOVERY_INPUT_LENGTH,
        PDF_ZLIB_RECOVERY_MAX_INPUT_BYTES,
    )


@dataclass(slots=True)
class _PdfDecodeBudget:
    consumed_bytes: int = 0

    def consume(self, size: int) -> None:
        if size > PDF_STREAM_MAX_OUTPUT_BYTES:
            raise _PdfSafetyLimitExceeded(
                "A decoded PDF stream exceeds the assessment safety limit."
            )
        if self.consumed_bytes + size > PDF_DOCUMENT_MAX_DECODED_BYTES:
            raise _PdfSafetyLimitExceeded(
                "The cumulative decoded PDF streams exceed the assessment safety limit."
            )
        self.consumed_bytes += size


_PDF_DECODE_BUDGET_LOCAL = threading.local()
_ORIGINAL_DECODE_STREAM_DATA = pypdf_filters.decode_stream_data


def _bounded_decode_stream_data(stream: Any) -> bytes:
    try:
        filter_value = stream.get("/Filter", ())
        if hasattr(filter_value, "get_object"):
            filter_value = filter_value.get_object()
        filter_values = (
            filter_value
            if isinstance(filter_value, (list, tuple))
            else (filter_value,)
        )
        if any(
            str(value) in {"/JBIG2Decode", "/JBIG2"}
            for value in filter_values
        ):
            raise _PdfSafetyLimitExceeded(
                "JBIG2 decoding is not allowed during assessment text extraction."
            )
        data = _ORIGINAL_DECODE_STREAM_DATA(stream)
    except LimitReachedError as exc:
        raise _PdfSafetyLimitExceeded(
            "A PDF decoder reached its configured safety limit."
        ) from exc
    if len(data) > PDF_STREAM_MAX_OUTPUT_BYTES:
        raise _PdfSafetyLimitExceeded(
            "A decoded PDF stream exceeds the assessment safety limit."
        )
    budget = getattr(_PDF_DECODE_BUDGET_LOCAL, "budget", None)
    if budget is not None:
        budget.consume(len(data))
    return data


_configure_pypdf_decoder_limits()
pypdf_filters.decode_stream_data = _bounded_decode_stream_data


@contextmanager
def _pdf_decode_budget() -> Iterator[None]:
    previous = getattr(_PDF_DECODE_BUDGET_LOCAL, "budget", None)
    _PDF_DECODE_BUDGET_LOCAL.budget = _PdfDecodeBudget()
    try:
        yield
    finally:
        if previous is None:
            delattr(_PDF_DECODE_BUDGET_LOCAL, "budget")
        else:
            _PDF_DECODE_BUDGET_LOCAL.budget = previous


class AssessmentDocumentError(ValueError):
    code = "ASSESSMENT_DOCUMENT_ERROR"


class AssessmentDocumentUnsupportedError(AssessmentDocumentError):
    code = "ASSESSMENT_DOCUMENT_UNSUPPORTED"


class AssessmentDocumentInvalidError(AssessmentDocumentError):
    code = "ASSESSMENT_DOCUMENT_INVALID"


class AssessmentDocumentTooLargeError(AssessmentDocumentError):
    code = "ASSESSMENT_DOCUMENT_TOO_LARGE"


class AssessmentDocumentWorkerError(AssessmentDocumentError):
    code = "ASSESSMENT_DOCUMENT_WORKER_UNAVAILABLE"


class _PdfTextBudgetExceeded(BaseException):
    """Immediate page-text circuit breaker for pypdf visitor callbacks."""


@dataclass(slots=True)
class _PdfTextBudget:
    remaining_chars: int
    observed_chars: int = 0

    def observe(self, text: str, *_: Any) -> None:
        self.observed_chars += len(text)
        if self.observed_chars > self.remaining_chars:
            raise _PdfTextBudgetExceeded


@dataclass(frozen=True, slots=True)
class ExtractedAssessmentDocument:
    text: str
    file_kind: str
    warnings: tuple[str, ...] = ()
    line_pages: tuple[int | None, ...] = ()


class AssessmentDocumentExtractor:
    """Extracts a bounded amount of text in memory without persistent chunks."""

    TEXT_EXTENSIONS = frozenset({".txt", ".md"})

    def __init__(
        self,
        *,
        max_text_chars: int,
        max_pdf_pages: int,
        max_uncompressed_docx_bytes: int,
    ) -> None:
        self._max_text_chars = max_text_chars
        self._max_pdf_pages = max_pdf_pages
        self._max_uncompressed_docx_bytes = max_uncompressed_docx_bytes

    def extract(self, *, filename: str, content: bytes) -> ExtractedAssessmentDocument:
        extension = PurePath(filename).suffix.lower()
        line_pages: tuple[int | None, ...] = ()
        if extension in self.TEXT_EXTENSIONS:
            text = self._extract_text(content)
            file_kind = "TEXT"
            warnings: tuple[str, ...] = ()
        elif extension == ".docx":
            text = self._extract_docx(content)
            file_kind = "DOCX"
            warnings = ()
        elif extension == ".pdf":
            text, warnings, line_pages = self._extract_pdf(content)
            file_kind = "PDF"
        else:
            raise AssessmentDocumentUnsupportedError(
                "Only .txt, .md, .docx and .pdf assessment sources are supported."
            )

        normalized = text if file_kind == 'PDF' else _normalize_text(text)
        if not normalized:
            raise AssessmentDocumentInvalidError(
                "The assessment source contains no extractable text."
            )
        if len(normalized) > self._max_text_chars:
            raise AssessmentDocumentTooLargeError(
                "The extracted assessment text exceeds the configured prompt limit."
            )
        return ExtractedAssessmentDocument(
            text=normalized,
            file_kind=file_kind,
            warnings=warnings,
            line_pages=line_pages,
        )

    @staticmethod
    def _extract_text(content: bytes) -> str:
        if content.startswith(b"%PDF-") or content.startswith(b"PK\x03\x04"):
            raise AssessmentDocumentInvalidError(
                "The assessment source content does not match its text extension."
            )
        for encoding in ("utf-8-sig", "utf-16", "gb18030"):
            try:
                decoded = content.decode(encoding)
            except UnicodeError:
                continue
            if _looks_like_text(decoded):
                return decoded
        raise AssessmentDocumentInvalidError(
            "The assessment text encoding is unsupported or the file is binary."
        )

    def _extract_docx(self, content: bytes) -> str:
        if not content.startswith(b"PK"):
            raise AssessmentDocumentInvalidError(
                "The assessment source is not a valid DOCX container."
            )
        try:
            with zipfile.ZipFile(io.BytesIO(content)) as document:
                total_uncompressed = sum(item.file_size for item in document.infolist())
                if total_uncompressed > self._max_uncompressed_docx_bytes:
                    raise AssessmentDocumentTooLargeError(
                        "The expanded DOCX exceeds the configured safety limit."
                    )
                try:
                    document_info = document.getinfo("word/document.xml")
                except KeyError as exc:
                    raise AssessmentDocumentInvalidError(
                        "The DOCX document body is missing."
                    ) from exc
                if document_info.flag_bits & 0x1:
                    raise AssessmentDocumentInvalidError(
                        "Encrypted DOCX assessment sources are not supported."
                    )
                xml_body = document.read(document_info)
        except AssessmentDocumentError:
            raise
        except (OSError, ValueError, zipfile.BadZipFile) as exc:
            raise AssessmentDocumentInvalidError(
                "The assessment source is not a readable DOCX file."
            ) from exc

        try:
            root = ElementTree.fromstring(xml_body)
        except ElementTree.ParseError as exc:
            raise AssessmentDocumentInvalidError(
                "The DOCX document body is malformed."
            ) from exc

        namespace = "{http://schemas.openxmlformats.org/wordprocessingml/2006/main}"
        paragraphs: list[str] = []
        for paragraph in root.iter(f"{namespace}p"):
            parts: list[str] = []
            for item in paragraph.iter():
                if item.tag == f"{namespace}t" and item.text:
                    parts.append(item.text)
                elif item.tag == f"{namespace}tab":
                    parts.append("\t")
                elif item.tag in {f"{namespace}br", f"{namespace}cr"}:
                    parts.append("\n")
            value = "".join(parts).strip()
            if value:
                paragraphs.append(value)
        return "\n".join(paragraphs)

    def _extract_pdf(self, content: bytes) -> tuple[str, tuple[str, ...], tuple[int | None, ...]]:
        if not content.startswith(b"%PDF-"):
            raise AssessmentDocumentInvalidError(
                "The assessment source is not a valid PDF file."
            )
        try:
            with _pdf_decode_budget():
                reader = PdfReader(io.BytesIO(content), strict=False)
                if reader.is_encrypted:
                    raise AssessmentDocumentInvalidError(
                        "Encrypted PDF assessment sources are not supported."
                    )
                if len(reader.pages) > self._max_pdf_pages:
                    raise AssessmentDocumentTooLargeError(
                        "The assessment PDF exceeds the configured page limit."
                    )
                page_text: list[str] = []
                line_pages: list[int | None] = []
                empty_pages = 0
                remaining_chars = self._max_text_chars
                for physical_page, page in enumerate(reader.pages, start=1):
                    _resolve_pdf_font_differences(page)
                    separator_chars = 2 if page_text else 0
                    page_budget = _PdfTextBudget(
                        max(remaining_chars - separator_chars, 0)
                    )
                    try:
                        extracted = page.extract_text(
                            visitor_text=page_budget.observe
                        ) or ""
                    except _PdfTextBudgetExceeded as exc:
                        raise AssessmentDocumentTooLargeError(
                            "The extracted PDF text exceeds the configured prompt limit."
                        ) from exc
                    extracted = _normalize_text(extracted)
                    if extracted:
                        required_chars = separator_chars + len(extracted)
                        if required_chars > remaining_chars:
                            raise AssessmentDocumentTooLargeError(
                                "The extracted PDF text exceeds the configured prompt limit."
                            )
                        if page_text:
                            line_pages.append(None)  # blank separator, not a physical page
                        line_pages.extend([physical_page] * len(extracted.splitlines()))
                        page_text.append(extracted)
                        remaining_chars -= required_chars
                    else:
                        empty_pages += 1
        except AssessmentDocumentError:
            raise
        except (_PdfSafetyLimitExceeded, LimitReachedError) as exc:
            raise AssessmentDocumentTooLargeError(
                "The expanded PDF exceeds the configured safety limit."
            ) from exc
        except (PyPdfError, OSError, TypeError, ValueError) as exc:
            raise AssessmentDocumentInvalidError(
                "The assessment source is not a readable PDF file."
            ) from exc

        warnings: list[str] = []
        if empty_pages:
            warnings.append(
                f"PDF 中有 {empty_pages} 页未提取到文本，图片或扫描页需要教师复核。"
            )
        if not page_text:
            raise AssessmentDocumentInvalidError('PDF has no extractable text; scanned PDF requires OCR, which is not available.')
        return "\n\n".join(page_text), tuple(warnings), tuple(line_pages)


def _resolve_pdf_font_differences(page: Any) -> None:
    """Resolve valid indirect glyph names before pypdf builds its character map.

    pypdf 6.15 iterates Differences without dereferencing each entry, leaving
    IndirectObject values in its string encoding map. Resolve only this known
    PDF structure, in memory; retain the normal bounded decoder and text visitor.
    """
    resources = page.get('/Resources')
    if resources is None:
        return
    resources = resources.get_object()
    if not isinstance(resources, DictionaryObject):
        return
    fonts = resources.get('/Font')
    if fonts is None:
        return
    fonts = fonts.get_object()
    if not isinstance(fonts, DictionaryObject):
        return
    for font in fonts.values():
        font = font.get_object()
        if not isinstance(font, DictionaryObject):
            continue
        encoding = font.get('/Encoding')
        if encoding is None:
            continue
        encoding = encoding.get_object()
        if not isinstance(encoding, DictionaryObject):
            continue
        differences = encoding.get('/Differences')
        if differences is None:
            continue
        differences = differences.get_object()
        if isinstance(differences, ArrayObject):
            if len(differences) > 4096:
                raise AssessmentDocumentTooLargeError('PDF font differences exceed the safety limit.')
            encoding[NameObject('/Differences')] = ArrayObject([value.get_object() for value in differences])


def _worker_environment() -> dict[str, str]:
    environment = {
        "PYTHONIOENCODING": "utf-8",
        "PYTHONUTF8": "1",
        "LANG": "C.UTF-8",
    }
    for name in ("SYSTEMROOT", "WINDIR", "PATH", "PATHEXT", "COMSPEC"):
        value = os.environ.get(name)
        if value:
            environment[name] = value
    return environment


async def _terminate_and_reap(process: asyncio.subprocess.Process) -> None:
    if process.returncode is None:
        try:
            process.terminate()
        except ProcessLookupError:
            pass
        try:
            await asyncio.wait_for(
                process.wait(), timeout=WORKER_TERMINATE_GRACE_SECONDS
            )
        except TimeoutError:
            if process.returncode is None:
                try:
                    process.kill()
                except ProcessLookupError:
                    pass
            # Deliberately no final timeout: the caller must not release its
            # capacity slot until the OS confirms that the child was reaped.
            await process.wait()
    else:
        await process.wait()


async def _cleanup_uninterruptibly(
    process: asyncio.subprocess.Process,
) -> asyncio.CancelledError | None:
    cleanup_task = asyncio.create_task(_terminate_and_reap(process))
    pending_cancellation: asyncio.CancelledError | None = None
    while True:
        try:
            await asyncio.shield(cleanup_task)
            break
        except asyncio.CancelledError as exc:
            pending_cancellation = exc
            continue
    cleanup_task.result()
    return pending_cancellation


async def _finish_cancelled_spawn(
    spawn_task: asyncio.Task[asyncio.subprocess.Process],
) -> asyncio.subprocess.Process | None:
    while True:
        try:
            return await asyncio.shield(spawn_task)
        except asyncio.CancelledError:
            continue
        except OSError:
            return None


async def _read_worker_frame(
    reader: asyncio.StreamReader,
    *,
    max_payload_bytes: int,
) -> bytes:
    try:
        prefix = await reader.readexactly(4)
        payload_length = struct.unpack(">I", prefix)[0]
        if payload_length == 0 or payload_length > max_payload_bytes:
            raise AssessmentDocumentWorkerError(
                "The document worker returned an invalid response length."
            )
        return await reader.readexactly(payload_length)
    except asyncio.IncompleteReadError as exc:
        raise AssessmentDocumentWorkerError(
            "The document worker returned an incomplete response."
        ) from exc


def _decode_worker_response(
    payload: bytes,
    *,
    extractor: AssessmentDocumentExtractor,
) -> ExtractedAssessmentDocument:
    try:
        response = json.loads(payload.decode("utf-8"))
    except (UnicodeError, json.JSONDecodeError) as exc:
        raise AssessmentDocumentWorkerError(
            "The document worker returned an invalid response."
        ) from exc
    if not isinstance(response, dict) or type(response.get("ok")) is not bool:
        raise AssessmentDocumentWorkerError(
            "The document worker returned an invalid response."
        )
    if response["ok"] is False:
        error_type = response.get("error_type")
        if error_type == "UNSUPPORTED":
            raise AssessmentDocumentUnsupportedError(
                "The assessment source type is unsupported."
            )
        if error_type == "TOO_LARGE":
            raise AssessmentDocumentTooLargeError(
                "The assessment source exceeds a parsing safety limit."
            )
        if error_type == "INVALID":
            raise AssessmentDocumentInvalidError(
                "The assessment source is invalid or has no extractable text."
            )
        raise AssessmentDocumentWorkerError(
            "The document worker failed before producing a safe result."
        )

    text = response.get("text")
    file_kind = response.get("file_kind")
    warnings = response.get("warnings")
    line_pages = response.get('line_pages', [])
    if (
        not isinstance(text, str)
        or not text
        or len(text) > extractor._max_text_chars
        or file_kind not in {"TEXT", "DOCX", "PDF"}
        or not isinstance(warnings, list)
        or len(warnings) > extractor._max_pdf_pages
        or any(
            not isinstance(item, str) or len(item) > 1_000 for item in warnings
        )
        or not isinstance(line_pages, list)
        or (bool(line_pages) and len(line_pages) != len(text.splitlines()))
        or any(p is not None and (type(p) is not int or not 1 <= p <= extractor._max_pdf_pages) for p in line_pages)
    ):
        raise AssessmentDocumentWorkerError(
            "The document worker returned a result outside the configured bounds."
        )
    return ExtractedAssessmentDocument(
        text=text,
        file_kind=file_kind,
        warnings=tuple(warnings),
        line_pages=tuple(line_pages),
    )


class AssessmentDocumentWorkerPool:
    """Runs bounded extraction in at most two disposable child processes."""

    def __init__(
        self,
        *,
        max_workers: int = DOCUMENT_EXTRACTION_MAX_WORKERS,
        worker_command: Sequence[str] | None = None,
        working_directory: str | None = None,
        environment: Mapping[str, str] | None = None,
    ) -> None:
        if not 1 <= max_workers <= DOCUMENT_EXTRACTION_MAX_WORKERS:
            raise ValueError(
                "max_workers must stay within the global document extraction limit."
            )
        service_root = Path(__file__).resolve().parents[2]
        worker_path = Path(__file__).with_name("document_worker.py")
        self._max_workers = max_workers
        self._slots = threading.BoundedSemaphore(max_workers)
        self._worker_command = tuple(
            worker_command
            or (
                sys.executable,
                "-I",
                str(worker_path),
            )
        )
        self._working_directory = working_directory or str(service_root)
        self._environment = dict(environment or _worker_environment())
        self._active_processes: set[asyncio.subprocess.Process] = set()
        self._active_lock = threading.Lock()

    @property
    def active_process_count(self) -> int:
        with self._active_lock:
            return len(self._active_processes)

    @property
    def active_pids(self) -> tuple[int, ...]:
        with self._active_lock:
            return tuple(
                process.pid
                for process in self._active_processes
                if process.pid is not None
            )

    async def _acquire_slot(self) -> None:
        while not self._slots.acquire(blocking=False):
            await asyncio.sleep(0.01)

    def _register(self, process: asyncio.subprocess.Process) -> None:
        with self._active_lock:
            self._active_processes.add(process)

    def _release_reaped(
        self,
        process: asyncio.subprocess.Process | None,
    ) -> None:
        if process is not None:
            if process.returncode is None:
                return
            with self._active_lock:
                self._active_processes.discard(process)
        self._slots.release()

    async def extract(
        self,
        extractor: AssessmentDocumentExtractor,
        *,
        filename: str,
        content: bytes,
    ) -> ExtractedAssessmentDocument:
        if len(content) > WORKER_CONTENT_MAX_BYTES:
            raise AssessmentDocumentTooLargeError(
                "The assessment source exceeds the worker protocol limit."
            )
        await self._acquire_slot()
        process: asyncio.subprocess.Process | None = None
        try:
            creation_flags = (
                getattr(subprocess, "CREATE_NO_WINDOW", 0)
                if os.name == "nt"
                else 0
            )
            spawn_task = asyncio.create_task(
                asyncio.create_subprocess_exec(
                    *self._worker_command,
                    stdin=asyncio.subprocess.PIPE,
                    stdout=asyncio.subprocess.PIPE,
                    stderr=asyncio.subprocess.DEVNULL,
                    cwd=self._working_directory,
                    env=self._environment,
                    creationflags=creation_flags,
                )
            )
            try:
                process = await asyncio.shield(spawn_task)
            except asyncio.CancelledError:
                process = await _finish_cancelled_spawn(spawn_task)
                if process is not None:
                    self._register(process)
                    await _cleanup_uninterruptibly(process)
                raise
            except OSError as exc:
                raise AssessmentDocumentWorkerError(
                    "The document worker could not be started."
                ) from exc

            self._register(process)
            try:
                return await self._exchange(
                    process,
                    extractor=extractor,
                    filename=filename,
                    content=content,
                )
            except asyncio.CancelledError:
                await _cleanup_uninterruptibly(process)
                raise
            except (
                AssessmentDocumentUnsupportedError,
                AssessmentDocumentInvalidError,
                AssessmentDocumentTooLargeError,
                AssessmentDocumentWorkerError,
            ):
                pending_cancellation = await _cleanup_uninterruptibly(process)
                if pending_cancellation is not None:
                    raise pending_cancellation
                raise
            except (OSError, ValueError, TypeError) as exc:
                pending_cancellation = await _cleanup_uninterruptibly(process)
                if pending_cancellation is not None:
                    raise pending_cancellation
                raise AssessmentDocumentWorkerError(
                    "The document worker protocol failed."
                ) from exc
        finally:
            # A live or unreaped process intentionally keeps its slot forever;
            # this is fail-closed and prevents exceeding max_workers.
            if process is None or process.returncode is not None:
                self._release_reaped(process)

    async def _exchange(
        self,
        process: asyncio.subprocess.Process,
        *,
        extractor: AssessmentDocumentExtractor,
        filename: str,
        content: bytes,
    ) -> ExtractedAssessmentDocument:
        if process.stdin is None or process.stdout is None:
            raise AssessmentDocumentWorkerError(
                "The document worker pipes are unavailable."
            )
        header = json.dumps(
            {
                "filename": filename,
                "content_length": len(content),
                "max_text_chars": extractor._max_text_chars,
                "max_pdf_pages": extractor._max_pdf_pages,
                "max_uncompressed_docx_bytes": extractor._max_uncompressed_docx_bytes,
            },
            ensure_ascii=False,
            separators=(",", ":"),
        ).encode("utf-8")
        if len(header) == 0 or len(header) > WORKER_REQUEST_HEADER_MAX_BYTES:
            raise AssessmentDocumentInvalidError(
                "The assessment source metadata exceeds the worker protocol limit."
            )
        process.stdin.write(struct.pack(">I", len(header)))
        process.stdin.write(header)
        await process.stdin.drain()
        process.stdin.write(content)
        await process.stdin.drain()
        process.stdin.close()
        try:
            await process.stdin.wait_closed()
        except (BrokenPipeError, ConnectionResetError):
            pass

        payload = await _read_worker_frame(
            process.stdout,
            max_payload_bytes=WORKER_RESPONSE_MAX_BYTES,
        )
        return_code = await process.wait()
        if return_code != 0:
            raise AssessmentDocumentWorkerError(
                "The document worker exited without a valid result."
            )
        return _decode_worker_response(payload, extractor=extractor)


_DOCUMENT_WORKER_POOL = AssessmentDocumentWorkerPool()


async def extract_assessment_document(
    extractor: AssessmentDocumentExtractor,
    *,
    filename: str,
    content: bytes,
) -> ExtractedAssessmentDocument:
    return await _DOCUMENT_WORKER_POOL.extract(
        extractor,
        filename=filename,
        content=content,
    )


def _looks_like_text(value: str) -> bool:
    if not value:
        return False
    disallowed_controls = sum(
        1
        for character in value
        if ord(character) < 32 and character not in {"\t", "\n", "\r", "\f"}
    )
    return "\x00" not in value and disallowed_controls <= max(1, len(value) // 100)


def _normalize_text(value: str) -> str:
    normalized = value.replace("\r\n", "\n").replace("\r", "\n")
    normalized = "\n".join(line.rstrip() for line in normalized.split("\n"))
    normalized = re.sub(r"\n{4,}", "\n\n\n", normalized)
    return normalized.strip()
