from __future__ import annotations

import re
from dataclasses import dataclass
from urllib.parse import SplitResult, unquote, urlsplit

import httpx2

from app.core.config import AssessmentSourceSettings, GradingSettings


_FILE_PATH_PATTERN = re.compile(r"/api/files/(?P<file_id>[1-9][0-9]*)/content$")
_FILENAME_STAR_PATTERN = re.compile(
    r"(?:^|;)\s*filename\*\s*=\s*UTF-8''(?P<value>[^;]+)",
    re.IGNORECASE,
)
_FILENAME_PATTERN = re.compile(
    r'(?:^|;)\s*filename\s*=\s*(?:"(?P<quoted>[^"]+)"|(?P<plain>[^;]+))',
    re.IGNORECASE,
)


class GradingAttachmentError(RuntimeError):
    code = "GRADING_ATTACHMENT_ERROR"

    def __init__(self, message: str, *, status_code: int | None = None) -> None:
        super().__init__(message)
        self.status_code = status_code


class GradingAttachmentInputError(GradingAttachmentError):
    code = "GRADING_ATTACHMENT_INPUT_ERROR"


class GradingAttachmentNotFoundError(GradingAttachmentError):
    code = "GRADING_ATTACHMENT_NOT_FOUND"


class GradingAttachmentTooLargeError(GradingAttachmentError):
    code = "GRADING_ATTACHMENT_TOO_LARGE"


class GradingAttachmentAuthenticationError(GradingAttachmentError):
    code = "GRADING_ATTACHMENT_AUTHENTICATION_ERROR"


class GradingAttachmentTimeoutError(GradingAttachmentError):
    code = "GRADING_ATTACHMENT_TIMEOUT"


class GradingAttachmentTransportError(GradingAttachmentError):
    code = "GRADING_ATTACHMENT_TRANSPORT_ERROR"


class GradingAttachmentServerError(GradingAttachmentError):
    code = "GRADING_ATTACHMENT_SERVER_ERROR"


class GradingAttachmentRequestError(GradingAttachmentError):
    code = "GRADING_ATTACHMENT_REQUEST_ERROR"


@dataclass(frozen=True, slots=True)
class DownloadedGradingAttachment:
    file_id: int
    filename: str
    content: bytes
    content_type: str | None


class GradingAttachmentClient:
    """Downloads task-bound Spring attachments without redirects or URL trust."""

    def __init__(
        self,
        source_settings: AssessmentSourceSettings | None = None,
        grading_settings: GradingSettings | None = None,
        *,
        client: httpx2.AsyncClient | None = None,
    ) -> None:
        self._source_settings = source_settings or AssessmentSourceSettings.from_env()
        self._grading_settings = grading_settings or GradingSettings.from_env()
        self._owns_client = client is None
        self._client = client or httpx2.AsyncClient(
            timeout=self._grading_settings.attachment_timeout_seconds,
            trust_env=False,
            follow_redirects=False,
        )

    async def aclose(self) -> None:
        if self._owns_client:
            await self._client.aclose()

    async def download_all(
        self,
        urls: list[str],
        *,
        request_id: str,
    ) -> list[DownloadedGradingAttachment]:
        if not 1 <= len(urls) <= self._grading_settings.max_attachment_files:
            raise GradingAttachmentInputError(
                "The attachment count exceeds the configured grading limit."
            )
        attachments: list[DownloadedGradingAttachment] = []
        total_bytes = 0
        for url in urls:
            file_id = self._validate_url(url)
            remaining_total_bytes = (
                self._grading_settings.max_attachment_total_bytes - total_bytes
            )
            if remaining_total_bytes <= 0:
                raise GradingAttachmentTooLargeError(
                    "The aggregate attachment payload exceeds the grading limit."
                )
            attachment = await self._download_one(
                file_id=file_id,
                download_url=url,
                request_id=request_id,
                remaining_total_bytes=remaining_total_bytes,
            )
            total_bytes += len(attachment.content)
            if total_bytes > self._grading_settings.max_attachment_total_bytes:
                raise GradingAttachmentTooLargeError(
                    "The aggregate attachment payload exceeds the grading limit."
                )
            attachments.append(attachment)
        return attachments

    async def _download_one(
        self,
        *,
        file_id: int,
        download_url: str,
        request_id: str,
        remaining_total_bytes: int,
    ) -> DownloadedGradingAttachment:
        headers = {
            "X-Internal-Token": self._source_settings.internal_token.get_secret_value(),
            "X-Request-Id": request_id,
            "Accept": "application/octet-stream",
        }
        try:
            async with self._client.stream(
                "GET",
                download_url,
                headers=headers,
                follow_redirects=False,
            ) as response:
                self._raise_for_status(response.status_code)
                declared_size = _content_length(response.headers.get("Content-Length"))
                effective_limit = min(
                    self._grading_settings.max_attachment_file_bytes,
                    remaining_total_bytes,
                )
                if declared_size is not None and declared_size > effective_limit:
                    raise GradingAttachmentTooLargeError(
                        "An attachment exceeds the configured per-file limit.",
                        status_code=response.status_code,
                    )
                filename = _safe_filename(
                    response.headers.get("Content-Disposition"),
                    file_id=file_id,
                )
                content = bytearray()
                async for chunk in response.aiter_bytes():
                    content.extend(chunk)
                    if len(content) > effective_limit:
                        raise GradingAttachmentTooLargeError(
                            "An attachment exceeds the configured per-file limit.",
                            status_code=response.status_code,
                        )
                if not content:
                    raise GradingAttachmentInputError(
                        "An attachment is empty.", status_code=response.status_code
                    )
                content_type = response.headers.get("Content-Type")
        except GradingAttachmentError:
            raise
        except httpx2.TimeoutException as exc:
            raise GradingAttachmentTimeoutError(
                "The Spring attachment download timed out."
            ) from exc
        except httpx2.TransportError as exc:
            raise GradingAttachmentTransportError(
                "The Spring attachment download failed."
            ) from exc
        return DownloadedGradingAttachment(
            file_id=file_id,
            filename=filename,
            content=bytes(content),
            content_type=content_type.split(";", 1)[0].strip().lower()
            if content_type
            else None,
        )

    def _validate_url(self, download_url: str) -> int:
        base = urlsplit(self._source_settings.base_url)
        target = urlsplit(download_url)
        base_path = base.path.rstrip("/")
        match = _FILE_PATH_PATTERN.fullmatch(target.path[len(base_path) :]) \
            if target.path.startswith(base_path) else None
        if (
            not _same_origin(base, target)
            or match is None
            or target.query
            or target.fragment
            or target.username is not None
            or target.password is not None
        ):
            raise GradingAttachmentInputError(
                "Attachment URLs must use the configured Spring file endpoint."
            )
        return int(match.group("file_id"))

    @staticmethod
    def _raise_for_status(status_code: int) -> None:
        if 200 <= status_code < 300:
            return
        if status_code == 404:
            raise GradingAttachmentNotFoundError(
                "The task-bound attachment was not found.", status_code=status_code
            )
        if status_code == 413:
            raise GradingAttachmentTooLargeError(
                "Spring rejected an oversized attachment.", status_code=status_code
            )
        if status_code in {401, 403}:
            raise GradingAttachmentAuthenticationError(
                "Spring rejected attachment task authorization.", status_code=status_code
            )
        if 500 <= status_code <= 599:
            raise GradingAttachmentServerError(
                "Spring failed while reading an attachment.", status_code=status_code
            )
        raise GradingAttachmentRequestError(
            "Spring rejected the attachment request.", status_code=status_code
        )


def _safe_filename(value: str | None, *, file_id: int) -> str:
    if not value:
        raise GradingAttachmentInputError(
            "Spring did not provide a task attachment filename."
        )
    star = _FILENAME_STAR_PATTERN.search(value)
    if star:
        try:
            filename = unquote(
                star.group("value"), encoding="utf-8", errors="strict"
            )
        except (UnicodeError, ValueError) as exc:
            raise GradingAttachmentInputError(
                "Spring returned an invalid attachment filename encoding."
            ) from exc
    else:
        regular = _FILENAME_PATTERN.search(value)
        if regular is None:
            raise GradingAttachmentInputError(
                "Spring returned an invalid attachment disposition."
            )
        filename = regular.group("quoted") or regular.group("plain") or ""
    filename = filename.strip().replace("\\", "/").rsplit("/", 1)[-1]
    if (
        not filename
        or filename in {".", ".."}
        or len(filename) > 255
        or any(ord(character) < 32 for character in filename)
    ):
        raise GradingAttachmentInputError(
            "Spring returned an unsafe attachment filename."
        )
    return filename


def _content_length(value: str | None) -> int | None:
    if value is None:
        return None
    try:
        parsed = int(value)
    except ValueError:
        return None
    return parsed if parsed >= 0 else None


def _default_port(parts: SplitResult) -> int | None:
    if parts.port is not None:
        return parts.port
    if parts.scheme.lower() == "https":
        return 443
    if parts.scheme.lower() == "http":
        return 80
    return None


def _same_origin(left: SplitResult, right: SplitResult) -> bool:
    return (
        left.scheme.lower() == right.scheme.lower()
        and (left.hostname or "").lower() == (right.hostname or "").lower()
        and _default_port(left) == _default_port(right)
    )
