from __future__ import annotations

from dataclasses import dataclass
from urllib.parse import SplitResult, urlsplit

import httpx2

from app.core.config import AssessmentSourceSettings


class AssessmentSourceError(RuntimeError):
    """Sanitized Spring source-file failure safe for typed translation."""

    code = "ASSESSMENT_SOURCE_ERROR"

    def __init__(self, message: str, *, status_code: int | None = None) -> None:
        super().__init__(message)
        self.status_code = status_code


class AssessmentSourceInputError(AssessmentSourceError):
    code = "ASSESSMENT_SOURCE_INPUT_ERROR"


class AssessmentSourceNotFoundError(AssessmentSourceError):
    code = "ASSESSMENT_SOURCE_NOT_FOUND"


class AssessmentSourceTooLargeError(AssessmentSourceError):
    code = "ASSESSMENT_SOURCE_TOO_LARGE"


class AssessmentSourceAuthenticationError(AssessmentSourceError):
    code = "ASSESSMENT_SOURCE_AUTHENTICATION_ERROR"


class AssessmentSourceTimeoutError(AssessmentSourceError):
    code = "ASSESSMENT_SOURCE_TIMEOUT_ERROR"


class AssessmentSourceTransportError(AssessmentSourceError):
    code = "ASSESSMENT_SOURCE_TRANSPORT_ERROR"


class AssessmentSourceServerError(AssessmentSourceError):
    code = "ASSESSMENT_SOURCE_SERVER_ERROR"


class AssessmentSourceRequestError(AssessmentSourceError):
    code = "ASSESSMENT_SOURCE_REQUEST_ERROR"


@dataclass(frozen=True, slots=True)
class DownloadedAssessmentSource:
    content: bytes
    content_type: str | None


class AssessmentSourceClient:
    """Downloads one assessment file from Spring without following redirects."""

    def __init__(
        self,
        settings: AssessmentSourceSettings | None = None,
        *,
        client: httpx2.AsyncClient | None = None,
    ) -> None:
        self._settings = settings or AssessmentSourceSettings.from_env()
        self._owns_client = client is None
        self._client = client or httpx2.AsyncClient(
            timeout=self._settings.timeout_seconds,
            trust_env=False,
            follow_redirects=False,
        )

    async def __aenter__(self) -> "AssessmentSourceClient":
        return self

    async def __aexit__(self, *_: object) -> None:
        await self.aclose()

    async def aclose(self) -> None:
        if self._owns_client:
            await self._client.aclose()

    async def download(
        self,
        *,
        source_file_id: int,
        download_url: str,
        request_id: str,
    ) -> DownloadedAssessmentSource:
        self._validate_download_url(source_file_id, download_url)
        headers = {
            "X-Internal-Token": self._settings.internal_token.get_secret_value(),
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
                if (
                    declared_size is not None
                    and declared_size > self._settings.max_file_bytes
                ):
                    raise AssessmentSourceTooLargeError(
                        "The assessment source exceeds the configured file-size limit.",
                        status_code=response.status_code,
                    )

                content = bytearray()
                async for chunk in response.aiter_bytes():
                    content.extend(chunk)
                    if len(content) > self._settings.max_file_bytes:
                        raise AssessmentSourceTooLargeError(
                            "The assessment source exceeds the configured file-size limit.",
                            status_code=response.status_code,
                        )
                if not content:
                    raise AssessmentSourceInputError(
                        "The assessment source file is empty.",
                        status_code=response.status_code,
                    )
                content_type = response.headers.get("Content-Type")
        except AssessmentSourceError:
            raise
        except httpx2.TimeoutException as exc:
            raise AssessmentSourceTimeoutError(
                "The Spring assessment source download timed out."
            ) from exc
        except httpx2.TransportError as exc:
            raise AssessmentSourceTransportError(
                "The Spring assessment source could not be reached."
            ) from exc

        return DownloadedAssessmentSource(
            content=bytes(content),
            content_type=content_type.split(";", 1)[0].strip().lower()
            if content_type
            else None,
        )

    def _validate_download_url(self, source_file_id: int, download_url: str) -> None:
        base = urlsplit(self._settings.base_url)
        target = urlsplit(download_url)
        expected_path = (
            f"{base.path.rstrip('/')}/api/files/{source_file_id}/content"
        )
        if (
            not _same_origin(base, target)
            or target.path != expected_path
            or target.query
            or target.fragment
            or target.username is not None
            or target.password is not None
        ):
            raise AssessmentSourceInputError(
                "download_url must be the configured Spring internal file endpoint."
            )

    @staticmethod
    def _raise_for_status(status_code: int) -> None:
        if 200 <= status_code < 300:
            return
        if status_code == 404:
            raise AssessmentSourceNotFoundError(
                "The assessment source file was not found.", status_code=status_code
            )
        if status_code == 413:
            raise AssessmentSourceTooLargeError(
                "The assessment source exceeds the Spring file-size limit.",
                status_code=status_code,
            )
        if status_code in {401, 403}:
            raise AssessmentSourceAuthenticationError(
                "Spring rejected internal file authentication.",
                status_code=status_code,
            )
        if 500 <= status_code <= 599:
            raise AssessmentSourceServerError(
                "Spring returned a server error while reading the assessment source.",
                status_code=status_code,
            )
        raise AssessmentSourceRequestError(
            "Spring rejected the assessment source request.", status_code=status_code
        )


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


def _content_length(value: str | None) -> int | None:
    if value is None:
        return None
    try:
        parsed = int(value)
    except ValueError:
        return None
    return parsed if parsed >= 0 else None
