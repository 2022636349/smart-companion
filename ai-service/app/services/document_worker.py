from __future__ import annotations

import json
import struct
import sys
from pathlib import Path
from typing import BinaryIO


# The parent launches this exact file with -I. Add only the known ai-service
# root; do not consume PYTHONPATH, the current directory, or user site paths.
AI_SERVICE_ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(AI_SERVICE_ROOT))

from app.services.document_extraction import (  # noqa: E402
    WORKER_CONTENT_MAX_BYTES,
    WORKER_REQUEST_HEADER_MAX_BYTES,
    WORKER_RESPONSE_MAX_BYTES,
    AssessmentDocumentExtractor,
    AssessmentDocumentInvalidError,
    AssessmentDocumentTooLargeError,
    AssessmentDocumentUnsupportedError,
)


def _read_exact(stream: BinaryIO, length: int) -> bytes:
    result = bytearray()
    while len(result) < length:
        chunk = stream.read(min(64 * 1024, length - len(result)))
        if not chunk:
            raise ValueError("Unexpected end of worker request.")
        result.extend(chunk)
    return bytes(result)


def _bounded_int(
    value: object,
    *,
    minimum: int,
    maximum: int,
) -> int:
    if type(value) is not int or not minimum <= value <= maximum:
        raise ValueError("Worker request integer is outside its safety range.")
    return value


def _read_request() -> tuple[AssessmentDocumentExtractor, str, bytes]:
    stdin = sys.stdin.buffer
    header_length = struct.unpack(">I", _read_exact(stdin, 4))[0]
    if not 1 <= header_length <= WORKER_REQUEST_HEADER_MAX_BYTES:
        raise ValueError("Worker request header is outside its safety limit.")
    header = json.loads(_read_exact(stdin, header_length).decode("utf-8"))
    expected_keys = {
        "filename",
        "content_length",
        "max_text_chars",
        "max_pdf_pages",
        "max_uncompressed_docx_bytes",
    }
    if not isinstance(header, dict) or set(header) != expected_keys:
        raise ValueError("Worker request header shape is invalid.")

    filename = header["filename"]
    if (
        not isinstance(filename, str)
        or not 1 <= len(filename) <= 1_024
        or any(ord(character) < 32 for character in filename)
    ):
        raise ValueError("Worker filename is invalid.")
    content_length = _bounded_int(
        header["content_length"],
        minimum=1,
        maximum=WORKER_CONTENT_MAX_BYTES,
    )
    max_text_chars = _bounded_int(
        header["max_text_chars"],
        minimum=1_000,
        maximum=200_000,
    )
    max_pdf_pages = _bounded_int(
        header["max_pdf_pages"],
        minimum=1,
        maximum=1_000,
    )
    max_uncompressed_docx_bytes = _bounded_int(
        header["max_uncompressed_docx_bytes"],
        minimum=1_024,
        maximum=100 * 1024 * 1024,
    )
    content = _read_exact(stdin, content_length)
    if stdin.read(1):
        raise ValueError("Worker request contains trailing data.")
    return (
        AssessmentDocumentExtractor(
            max_text_chars=max_text_chars,
            max_pdf_pages=max_pdf_pages,
            max_uncompressed_docx_bytes=max_uncompressed_docx_bytes,
        ),
        filename,
        content,
    )


def _encode_response(response: dict[str, object]) -> bytes:
    payload = json.dumps(
        response,
        ensure_ascii=False,
        separators=(",", ":"),
    ).encode("utf-8")
    if len(payload) > WORKER_RESPONSE_MAX_BYTES:
        payload = json.dumps(
            {"ok": False, "error_type": "TOO_LARGE"},
            separators=(",", ":"),
        ).encode("utf-8")
    return payload


def _write_response(response: dict[str, object]) -> None:
    payload = _encode_response(response)
    stdout = sys.stdout.buffer
    stdout.write(struct.pack(">I", len(payload)))
    stdout.write(payload)
    stdout.flush()


def main() -> int:
    try:
        extractor, filename, content = _read_request()
        result = extractor.extract(filename=filename, content=content)
        response: dict[str, object] = {
            "ok": True,
            "text": result.text,
            "file_kind": result.file_kind,
            "warnings": list(result.warnings),
            "line_pages": list(result.line_pages),
        }
    except AssessmentDocumentUnsupportedError:
        response = {"ok": False, "error_type": "UNSUPPORTED"}
    except AssessmentDocumentTooLargeError:
        response = {"ok": False, "error_type": "TOO_LARGE"}
    except AssessmentDocumentInvalidError:
        response = {"ok": False, "error_type": "INVALID"}
    except Exception:
        # Never serialize exception messages, source bytes, paths, or traces.
        response = {"ok": False, "error_type": "INTERNAL"}
    _write_response(response)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
