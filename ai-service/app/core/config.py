from __future__ import annotations

import os
from dataclasses import dataclass
from urllib.parse import urlsplit

from pydantic import SecretStr

from app.providers.base import ModelConfigurationError


def _read_float(name: str, default: float, *, minimum: float, maximum: float) -> float:
    raw = os.getenv(name)
    if raw is None or not raw.strip():
        return default
    try:
        value = float(raw)
    except ValueError as exc:
        raise ModelConfigurationError(f"{name} must be a number.") from exc
    if not minimum <= value <= maximum:
        raise ModelConfigurationError(
            f"{name} must be between {minimum:g} and {maximum:g}."
        )
    return value


def _read_int(name: str, default: int, *, minimum: int, maximum: int) -> int:
    raw = os.getenv(name)
    if raw is None or not raw.strip():
        return default
    try:
        value = int(raw)
    except ValueError as exc:
        raise ModelConfigurationError(f"{name} must be an integer.") from exc
    if not minimum <= value <= maximum:
        raise ModelConfigurationError(
            f"{name} must be between {minimum} and {maximum}."
        )
    return value


def _validate_base_url(value: str) -> str:
    normalized = value.rstrip("/")
    parsed = urlsplit(normalized)
    if (
        parsed.scheme not in {"http", "https"}
        or not parsed.hostname
        or parsed.username is not None
        or parsed.password is not None
        or parsed.query
        or parsed.fragment
    ):
        raise ModelConfigurationError(
            "DEEPSEEK_BASE_URL must be an http(s) URL without credentials, query, or fragment."
        )
    if parsed.scheme != "https" and parsed.hostname not in {
        "localhost",
        "127.0.0.1",
        "::1",
    }:
        raise ModelConfigurationError(
            "DEEPSEEK_BASE_URL must use HTTPS unless it targets localhost."
        )
    return normalized


def _validate_internal_service_base_url(name: str, value: str) -> str:
    normalized = value.rstrip("/")
    parsed = urlsplit(normalized)
    if (
        parsed.scheme not in {"http", "https"}
        or not parsed.hostname
        or parsed.username is not None
        or parsed.password is not None
        or parsed.query
        or parsed.fragment
    ):
        raise KnowledgeServiceConfigurationError(
            f"{name} must be an http(s) URL without credentials, query, or fragment."
        )
    return normalized


def _validate_ollama_base_url(value: str) -> str:
    """Accept only an explicitly allow-listed local or deployment endpoint."""
    normalized = value.rstrip("/")
    parsed = urlsplit(normalized)
    if (
        parsed.scheme not in {"http", "https"}
        or not parsed.hostname
        or parsed.username is not None
        or parsed.password is not None
        or parsed.query
        or parsed.fragment
    ):
        raise ModelConfigurationError(
            "OLLAMA_BASE_URL must be an http(s) URL without credentials, query, or fragment."
        )
    allowed = {
        item.strip().casefold()
        for item in os.getenv(
            "OLLAMA_ALLOWED_HOSTS", "localhost,127.0.0.1,::1"
        ).split(",")
        if item.strip()
    }
    if not allowed or (parsed.hostname or "").casefold() not in allowed:
        raise ModelConfigurationError(
            "OLLAMA_BASE_URL host must be listed in OLLAMA_ALLOWED_HOSTS."
        )
    return normalized


class KnowledgeServiceConfigurationError(ValueError):
    """Invalid knowledge-service client configuration without credential values."""


class AssessmentSourceConfigurationError(ValueError):
    """Invalid Spring file-source client configuration."""


class AssessmentParseConfigurationError(ValueError):
    """Invalid assessment parse orchestration configuration."""


class GradingConfigurationError(ValueError):
    """Invalid subjective or attachment grading configuration."""


class MemoryConfigurationError(ValueError):
    """Invalid memory-only embedding or Chroma configuration."""


class GraphIngestionConfigurationError(ValueError):
    """Invalid transient graph extraction/review orchestration configuration."""


class TutorRouterConfigurationError(ValueError):
    """Invalid bounded tutor Router configuration."""


@dataclass(frozen=True, slots=True)
class DeepSeekSettings:
    api_key: SecretStr
    base_url: str
    chat_model: str
    timeout_seconds: float
    total_timeout_seconds: float
    max_retries: int
    retry_base_seconds: float
    retry_max_seconds: float

    @classmethod
    def from_env(cls) -> DeepSeekSettings:
        api_key = os.getenv("DEEPSEEK_API_KEY", "").strip()
        chat_model = os.getenv("DEEPSEEK_CHAT_MODEL", "").strip()
        missing = [
            name
            for name, value in (
                ("DEEPSEEK_API_KEY", api_key),
                ("DEEPSEEK_CHAT_MODEL", chat_model),
            )
            if not value
        ]
        if missing:
            raise ModelConfigurationError(
                "Missing required environment variables: " + ", ".join(missing) + "."
            )

        base_url = _validate_base_url(
            os.getenv("DEEPSEEK_BASE_URL", "https://api.deepseek.com").strip()
        )
        timeout_seconds = _read_float(
            "DEEPSEEK_TIMEOUT_SECONDS", 60.0, minimum=0.1, maximum=300.0
        )
        total_timeout_seconds = _read_float(
            "DEEPSEEK_TOTAL_TIMEOUT_SECONDS", 110.0, minimum=0.1, maximum=120.0
        )
        max_retries = _read_int("DEEPSEEK_MAX_RETRIES", 2, minimum=0, maximum=5)
        retry_base_seconds = _read_float(
            "DEEPSEEK_RETRY_BASE_SECONDS", 0.25, minimum=0.0, maximum=10.0
        )
        retry_max_seconds = _read_float(
            "DEEPSEEK_RETRY_MAX_SECONDS", 2.0, minimum=0.0, maximum=60.0
        )
        if retry_max_seconds < retry_base_seconds:
            raise ModelConfigurationError(
                "DEEPSEEK_RETRY_MAX_SECONDS must be greater than or equal to "
                "DEEPSEEK_RETRY_BASE_SECONDS."
            )
        return cls(
            api_key=SecretStr(api_key), base_url=base_url, chat_model=chat_model,
            timeout_seconds=timeout_seconds, total_timeout_seconds=total_timeout_seconds,
            max_retries=max_retries, retry_base_seconds=retry_base_seconds,
            retry_max_seconds=retry_max_seconds,
        )


@dataclass(frozen=True, slots=True)
class OllamaGenerationSettings:
    base_url: str
    chat_model: str
    timeout_seconds: float
    total_timeout_seconds: float
    max_retries: int
    retry_base_seconds: float
    retry_max_seconds: float

    @classmethod
    def from_env(cls) -> "OllamaGenerationSettings":
        chat_model = os.getenv("OLLAMA_CHAT_MODEL", "").strip()
        if not chat_model:
            raise ModelConfigurationError("Missing required environment variable: OLLAMA_CHAT_MODEL.")
        base_url = _validate_ollama_base_url(
            os.getenv("OLLAMA_BASE_URL", "http://localhost:11434").strip()
        )
        timeout_seconds = _read_float(
            "OLLAMA_TIMEOUT_SECONDS", 30.0, minimum=0.1, maximum=300.0
        )
        total_timeout_seconds = _read_float(
            "OLLAMA_TOTAL_TIMEOUT_SECONDS", 50.0, minimum=0.1, maximum=60.0
        )
        max_retries = _read_int("OLLAMA_MAX_RETRIES", 1, minimum=0, maximum=5)
        retry_base_seconds = _read_float(
            "OLLAMA_RETRY_BASE_SECONDS", 0.25, minimum=0.0, maximum=10.0
        )
        retry_max_seconds = _read_float(
            "OLLAMA_RETRY_MAX_SECONDS", 1.0, minimum=0.0, maximum=60.0
        )
        if retry_max_seconds < retry_base_seconds:
            raise ModelConfigurationError(
                "OLLAMA_RETRY_MAX_SECONDS must be greater than or equal to OLLAMA_RETRY_BASE_SECONDS."
            )
        return cls(
            base_url=base_url,
            chat_model=chat_model,
            timeout_seconds=timeout_seconds,
            total_timeout_seconds=total_timeout_seconds,
            max_retries=max_retries,
            retry_base_seconds=retry_base_seconds,
            retry_max_seconds=retry_max_seconds,
        )


@dataclass(frozen=True, slots=True)
class TelemetrySettings:
    base_url: str
    internal_token: SecretStr
    timeout_seconds: float
    max_retries: int
    retry_base_seconds: float
    pricing_version: str
    currency: str
    input_microunits_per_million: int
    output_microunits_per_million: int

    @classmethod
    def from_env(cls) -> "TelemetrySettings":
        base_url = _validate_internal_service_base_url(
            "BUSINESS_SERVICE_INTERNAL_BASE_URL",
            os.getenv("BUSINESS_SERVICE_INTERNAL_BASE_URL", "http://localhost:8080").strip(),
        )
        internal_token = os.getenv(
            "INTERNAL_SERVICE_TOKEN", "local-development-internal-token-2026"
        )
        if not internal_token:
            raise ModelConfigurationError("INTERNAL_SERVICE_TOKEN must not be empty.")
        pricing_version = os.getenv("MODEL_PRICING_VERSION", "unconfigured-v1").strip()
        currency = os.getenv("MODEL_PRICING_CURRENCY", "USD").strip().upper()
        if not pricing_version or len(pricing_version) > 40:
            raise ModelConfigurationError("MODEL_PRICING_VERSION must be 1..40 characters.")
        if len(currency) != 3 or not currency.isalpha():
            raise ModelConfigurationError("MODEL_PRICING_CURRENCY must be a three-letter code.")
        return cls(
            base_url=base_url,
            internal_token=SecretStr(internal_token),
            timeout_seconds=_read_float(
                "MODEL_TELEMETRY_TIMEOUT_SECONDS", 3.0, minimum=0.1, maximum=10.0
            ),
            max_retries=_read_int("MODEL_TELEMETRY_MAX_RETRIES", 2, minimum=0, maximum=3),
            retry_base_seconds=_read_float(
                "MODEL_TELEMETRY_RETRY_BASE_SECONDS", 0.1, minimum=0.0, maximum=2.0
            ),
            pricing_version=pricing_version,
            currency=currency,
            input_microunits_per_million=_read_int(
                "MODEL_INPUT_MICROUNITS_PER_MILLION", 0, minimum=0, maximum=10**12
            ),
            output_microunits_per_million=_read_int(
                "MODEL_OUTPUT_MICROUNITS_PER_MILLION", 0, minimum=0, maximum=10**12
            ),
        )


@dataclass(frozen=True, slots=True)
class AIServiceSettings:
    generation_provider: str
    memory_embedding_provider: str

    @classmethod
    def from_env(cls) -> AIServiceSettings:
        return cls(
            generation_provider=os.getenv("AI_MODEL_PROVIDER", "deepseek").strip().lower(),
            memory_embedding_provider=os.getenv(
                "MEMORY_EMBEDDING_PROVIDER", "disabled"
            ).strip().lower(),
        )


@dataclass(frozen=True, slots=True)
class MemorySettings:
    embedding_provider: str
    embedding_model: str
    ollama_base_url: str
    embedding_timeout_seconds: float
    total_timeout_seconds: float
    chroma_persist_dir: str

    @classmethod
    def from_env(cls) -> "MemorySettings":
        provider = os.getenv("MEMORY_EMBEDDING_PROVIDER", "disabled").strip().lower()
        model = os.getenv("MEMORY_EMBEDDING_MODEL", "").strip()
        if provider not in {"disabled", "ollama"}:
            raise MemoryConfigurationError(
                "MEMORY_EMBEDDING_PROVIDER must be disabled or ollama."
            )
        if provider == "ollama" and not model:
            raise MemoryConfigurationError(
                "MEMORY_EMBEDDING_MODEL is required when Ollama embedding is enabled."
            )
        try:
            base_url = _validate_ollama_base_url(
                os.getenv("OLLAMA_BASE_URL", "http://localhost:11434").strip()
            )
            embedding_timeout = _read_float(
                "MEMORY_EMBEDDING_TIMEOUT_SECONDS", 10.0, minimum=0.1, maximum=60.0
            )
            total_timeout = _read_float(
                "MEMORY_TOTAL_TIMEOUT_SECONDS", 15.0, minimum=0.5, maximum=80.0
            )
        except (KnowledgeServiceConfigurationError, ModelConfigurationError) as exc:
            raise MemoryConfigurationError(str(exc)) from exc
        persist_dir = os.getenv(
            "CHROMA_PERSIST_DIR", "D:\\CodexEnvs\\EducationAgent\\chroma"
        ).strip()
        if not persist_dir:
            raise MemoryConfigurationError("CHROMA_PERSIST_DIR must not be empty.")
        normalized = os.path.abspath(persist_dir)
        if os.name == "nt" and not normalized.upper().startswith("D:\\"):
            raise MemoryConfigurationError("CHROMA_PERSIST_DIR must be located on D:.")
        return cls(
            embedding_provider=provider,
            embedding_model=model,
            ollama_base_url=base_url,
            embedding_timeout_seconds=embedding_timeout,
            total_timeout_seconds=total_timeout,
            chroma_persist_dir=normalized,
        )


@dataclass(frozen=True, slots=True)
class KnowledgeServiceSettings:
    base_url: str
    internal_token: SecretStr
    timeout_seconds: float
    max_related: int
    match_limit: int = 5

    @classmethod
    def from_env(cls) -> KnowledgeServiceSettings:
        base_url = _validate_internal_service_base_url(
            "KNOWLEDGE_SERVICE_BASE_URL",
            os.getenv("KNOWLEDGE_SERVICE_BASE_URL", "http://localhost:8001").strip(),
        )
        internal_token = os.getenv(
            "INTERNAL_SERVICE_TOKEN", "local-development-internal-token-2026"
        )
        if not internal_token:
            raise KnowledgeServiceConfigurationError(
                "INTERNAL_SERVICE_TOKEN must not be empty."
            )
        try:
            timeout_seconds = _read_float(
                "KNOWLEDGE_SERVICE_TIMEOUT_SECONDS",
                5.0,
                minimum=0.1,
                maximum=60.0,
            )
            max_related = _read_int(
                "KNOWLEDGE_SERVICE_MAX_RELATED", 8, minimum=0, maximum=20
            )
            match_limit = _read_int(
                "KNOWLEDGE_SERVICE_MATCH_LIMIT", 5, minimum=1, maximum=20
            )
        except ModelConfigurationError as exc:
            raise KnowledgeServiceConfigurationError(str(exc)) from exc
        return cls(
            base_url=base_url,
            internal_token=SecretStr(internal_token),
            timeout_seconds=timeout_seconds,
            max_related=max_related,
            match_limit=match_limit,
        )


@dataclass(frozen=True, slots=True)
class AssessmentSourceSettings:
    base_url: str
    internal_token: SecretStr
    timeout_seconds: float
    max_file_bytes: int
    max_text_chars: int
    max_pdf_pages: int

    @classmethod
    def from_env(cls) -> "AssessmentSourceSettings":
        raw_base_url = os.getenv(
            "BUSINESS_SERVICE_INTERNAL_BASE_URL", "http://localhost:8080"
        ).strip()
        try:
            base_url = _validate_internal_service_base_url(
                "BUSINESS_SERVICE_INTERNAL_BASE_URL", raw_base_url
            )
        except KnowledgeServiceConfigurationError as exc:
            raise AssessmentSourceConfigurationError(str(exc)) from exc

        internal_token = os.getenv(
            "INTERNAL_SERVICE_TOKEN", "local-development-internal-token-2026"
        )
        if not internal_token:
            raise AssessmentSourceConfigurationError(
                "INTERNAL_SERVICE_TOKEN must not be empty."
            )
        try:
            timeout_seconds = _read_float(
                "ASSESSMENT_SOURCE_TIMEOUT_SECONDS",
                10.0,
                minimum=0.1,
                maximum=60.0,
            )
            max_file_bytes = _read_int(
                "ASSESSMENT_SOURCE_MAX_FILE_BYTES",
                50 * 1024 * 1024,
                minimum=1024,
                maximum=50 * 1024 * 1024,
            )
            max_text_chars = _read_int(
                "ASSESSMENT_SOURCE_MAX_TEXT_CHARS",
                60_000,
                minimum=1_000,
                maximum=200_000,
            )
            max_pdf_pages = _read_int(
                "ASSESSMENT_SOURCE_MAX_PDF_PAGES",
                200,
                minimum=1,
                maximum=1_000,
            )
        except ModelConfigurationError as exc:
            raise AssessmentSourceConfigurationError(str(exc)) from exc

        return cls(
            base_url=base_url,
            internal_token=SecretStr(internal_token),
            timeout_seconds=timeout_seconds,
            max_file_bytes=max_file_bytes,
            max_text_chars=max_text_chars,
            max_pdf_pages=max_pdf_pages,
        )


@dataclass(frozen=True, slots=True)
class AssessmentParseSettings:
    total_timeout_seconds: float

    @classmethod
    def from_env(cls) -> "AssessmentParseSettings":
        try:
            total_timeout_seconds = _read_float(
                "ASSESSMENT_PARSE_TOTAL_TIMEOUT_SECONDS",
                75.0,
                minimum=1.0,
                maximum=80.0,
            )
        except ModelConfigurationError as exc:
            raise AssessmentParseConfigurationError(str(exc)) from exc
        return cls(total_timeout_seconds=total_timeout_seconds)


@dataclass(frozen=True, slots=True)
class GraphIngestionSettings:
    total_timeout_seconds: float
    max_candidates: int

    @classmethod
    def from_env(cls) -> "GraphIngestionSettings":
        try:
            total_timeout_seconds = _read_float(
                "GRAPH_INGESTION_TOTAL_TIMEOUT_SECONDS",
                220.0,
                minimum=1.0,
                maximum=240.0,
            )
            max_candidates = _read_int(
                "GRAPH_INGESTION_MAX_CANDIDATES",
                300,
                minimum=1,
                maximum=300,
            )
        except ModelConfigurationError as exc:
            raise GraphIngestionConfigurationError(str(exc)) from exc
        return cls(
            total_timeout_seconds=total_timeout_seconds,
            max_candidates=max_candidates,
        )


@dataclass(frozen=True, slots=True)
class TutorRouterSettings:
    max_steps: int
    tool_timeout_seconds: float
    total_timeout_seconds: float

    @classmethod
    def from_env(cls) -> "TutorRouterSettings":
        try:
            max_steps = _read_int(
                "TUTOR_ROUTER_MAX_STEPS", 2, minimum=1, maximum=2
            )
            tool_timeout_seconds = _read_float(
                "TUTOR_ROUTER_TOOL_TIMEOUT_SECONDS",
                5.0,
                minimum=0.1,
                maximum=15.0,
            )
            total_timeout_seconds = _read_float(
                "TUTOR_ROUTER_TOTAL_TIMEOUT_SECONDS",
                30.0,
                minimum=1.0,
                maximum=60.0,
            )
        except ModelConfigurationError as exc:
            raise TutorRouterConfigurationError(str(exc)) from exc
        if total_timeout_seconds < tool_timeout_seconds:
            raise TutorRouterConfigurationError(
                "TUTOR_ROUTER_TOTAL_TIMEOUT_SECONDS must be greater than or equal "
                "to TUTOR_ROUTER_TOOL_TIMEOUT_SECONDS."
            )
        return cls(
            max_steps=max_steps,
            tool_timeout_seconds=tool_timeout_seconds,
            total_timeout_seconds=total_timeout_seconds,
        )


@dataclass(frozen=True, slots=True)
class TutorGenerationSettings:
    """Shared deadline for all answer-only attempts after Router execution."""

    total_timeout_seconds: float

    @classmethod
    def from_env(cls) -> "TutorGenerationSettings":
        return cls(
            total_timeout_seconds=_read_float(
                "TUTOR_GENERATION_TOTAL_TIMEOUT_SECONDS",
                110.0,
                minimum=1.0,
                maximum=115.0,
            )
        )


@dataclass(frozen=True, slots=True)
class GradingSettings:
    total_timeout_seconds: float
    attachment_timeout_seconds: float
    max_attachment_files: int
    max_attachment_file_bytes: int
    max_attachment_total_bytes: int
    max_extracted_chars_per_file: int
    max_extracted_chars_total: int
    max_pdf_pages: int

    @classmethod
    def from_env(cls) -> "GradingSettings":
        try:
            total_timeout_seconds = _read_float(
                "GRADING_TOTAL_TIMEOUT_SECONDS",
                75.0,
                minimum=1.0,
                maximum=80.0,
            )
            attachment_timeout_seconds = _read_float(
                "GRADING_ATTACHMENT_TIMEOUT_SECONDS",
                10.0,
                minimum=0.1,
                maximum=60.0,
            )
            max_attachment_files = _read_int(
                "GRADING_ATTACHMENT_MAX_FILES",
                5,
                minimum=1,
                maximum=5,
            )
            max_attachment_file_bytes = _read_int(
                "GRADING_ATTACHMENT_MAX_FILE_BYTES",
                20 * 1024 * 1024,
                minimum=1024,
                maximum=50 * 1024 * 1024,
            )
            max_attachment_total_bytes = _read_int(
                "GRADING_ATTACHMENT_MAX_TOTAL_BYTES",
                50 * 1024 * 1024,
                minimum=1024,
                maximum=100 * 1024 * 1024,
            )
            max_extracted_chars_per_file = _read_int(
                "GRADING_ATTACHMENT_MAX_TEXT_CHARS_PER_FILE",
                40_000,
                minimum=1_000,
                maximum=100_000,
            )
            max_extracted_chars_total = _read_int(
                "GRADING_ATTACHMENT_MAX_TEXT_CHARS_TOTAL",
                60_000,
                minimum=1_000,
                maximum=200_000,
            )
            max_pdf_pages = _read_int(
                "GRADING_ATTACHMENT_MAX_PDF_PAGES",
                100,
                minimum=1,
                maximum=500,
            )
        except ModelConfigurationError as exc:
            raise GradingConfigurationError(str(exc)) from exc
        if max_attachment_total_bytes < max_attachment_file_bytes:
            raise GradingConfigurationError(
                "GRADING_ATTACHMENT_MAX_TOTAL_BYTES must be greater than or equal "
                "to GRADING_ATTACHMENT_MAX_FILE_BYTES."
            )
        if max_extracted_chars_total < max_extracted_chars_per_file:
            raise GradingConfigurationError(
                "GRADING_ATTACHMENT_MAX_TEXT_CHARS_TOTAL must be greater than or "
                "equal to GRADING_ATTACHMENT_MAX_TEXT_CHARS_PER_FILE."
            )
        return cls(
            total_timeout_seconds=total_timeout_seconds,
            attachment_timeout_seconds=attachment_timeout_seconds,
            max_attachment_files=max_attachment_files,
            max_attachment_file_bytes=max_attachment_file_bytes,
            max_attachment_total_bytes=max_attachment_total_bytes,
            max_extracted_chars_per_file=max_extracted_chars_per_file,
            max_extracted_chars_total=max_extracted_chars_total,
            max_pdf_pages=max_pdf_pages,
        )
