from __future__ import annotations

import re
from dataclasses import dataclass
from typing import Any, Literal, Protocol, runtime_checkable

from pydantic import BaseModel


MessageRole = Literal["system", "user", "assistant"]
DependencyStatus = Literal["UP", "DOWN", "DEGRADED"]


@dataclass(frozen=True, slots=True)
class ModelMessage:
    role: MessageRole
    content: str

    def __post_init__(self) -> None:
        if self.role not in {"system", "user", "assistant"}:
            raise ValueError(f"Unsupported model message role: {self.role}")
        if not isinstance(self.content, str) or not self.content:
            raise ValueError("Model message content must not be empty.")


@dataclass(frozen=True, slots=True)
class ModelRequest:
    messages: tuple[ModelMessage, ...]
    prompt_version: str
    max_tokens: int | None = None
    temperature: float | None = None
    anonymous_user_id: str | None = None
    request_id: str = "telemetry-untracked"
    capability: str = "MODEL_GENERATION"
    reasoning_mode: Literal["provider_default", "disabled"] = "provider_default"

    def __post_init__(self) -> None:
        object.__setattr__(self, "messages", tuple(self.messages))
        if not self.messages:
            raise ValueError("At least one model message is required.")
        if any(not isinstance(message, ModelMessage) for message in self.messages):
            raise ValueError("Every model message must be a ModelMessage.")
        if not isinstance(self.prompt_version, str) or not self.prompt_version:
            raise ValueError("A prompt version is required for every model call.")
        if not isinstance(self.request_id, str) or not 16 <= len(self.request_id) <= 64:
            raise ValueError("request_id must be 16..64 characters for telemetry.")
        if (
            not isinstance(self.capability, str)
            or not self.capability
            or len(self.capability) > 60
        ):
            raise ValueError("capability must be 1..60 characters.")
        if self.reasoning_mode not in {"provider_default", "disabled"}:
            raise ValueError("reasoning_mode must be provider_default or disabled.")
        if self.max_tokens is not None and (
            type(self.max_tokens) is not int or self.max_tokens <= 0
        ):
            raise ValueError("max_tokens must be positive.")
        if self.temperature is not None and (
            isinstance(self.temperature, bool)
            or not isinstance(self.temperature, (int, float))
            or not 0 <= self.temperature <= 2
        ):
            raise ValueError("temperature must be between 0 and 2.")
        if self.anonymous_user_id is not None:
            value = self.anonymous_user_id
            if not isinstance(value, str) or not re.fullmatch(
                r"anon_[0-9a-f]{64}", value
            ):
                raise ValueError(
                    "anonymous_user_id must be an opaque anon_ prefixed 64-character "
                    "lowercase hexadecimal HMAC digest."
                )


@dataclass(frozen=True, slots=True)
class TokenUsage:
    prompt_tokens: int
    completion_tokens: int
    total_tokens: int


@dataclass(frozen=True, slots=True)
class ModelCallMetadata:
    provider: str
    model: str
    token_usage: TokenUsage
    finish_reason: str
    latency_ms: int
    provider_request_id: str | None
    prompt_version: str
    attempts: int


@dataclass(frozen=True, slots=True)
class ModelResponse:
    text: str
    structured_content: dict[str, Any] | None
    metadata: ModelCallMetadata


@dataclass(frozen=True, slots=True)
class ModelProviderHealth:
    provider_status: DependencyStatus
    model_status: DependencyStatus
    latency_ms: int
    provider_detail: str | None = None
    model_detail: str | None = None
    model_version: str | None = None


class ModelProviderError(RuntimeError):
    """Sanitized provider failure safe to expose in health diagnostics."""

    code = "MODEL_PROVIDER_ERROR"
    retryable = False

    def __init__(
        self,
        message: str,
        *,
        status_code: int | None = None,
        provider_request_id: str | None = None,
        attempts: int = 1,
    ) -> None:
        super().__init__(message)
        self.status_code = status_code
        self.provider_request_id = provider_request_id
        self.attempts = attempts


class ModelConfigurationError(ModelProviderError):
    code = "MODEL_CONFIGURATION_ERROR"


class ModelAuthenticationError(ModelProviderError):
    code = "MODEL_AUTHENTICATION_ERROR"


class ModelRateLimitError(ModelProviderError):
    code = "MODEL_RATE_LIMIT_ERROR"
    retryable = True


class ModelServerError(ModelProviderError):
    code = "MODEL_SERVER_ERROR"
    retryable = True


class ModelTimeoutError(ModelProviderError):
    code = "MODEL_TIMEOUT_ERROR"
    retryable = True


class ModelTransportError(ModelProviderError):
    code = "MODEL_TRANSPORT_ERROR"
    retryable = True


class ModelRequestError(ModelProviderError):
    code = "MODEL_REQUEST_ERROR"


class ModelRoutingUnavailableError(ModelRequestError):
    code = "MODEL_ROUTING_UNAVAILABLE"


class ModelResponseError(ModelProviderError):
    code = "MODEL_RESPONSE_ERROR"


class StructuredOutputError(ModelResponseError):
    code = "MODEL_STRUCTURED_OUTPUT_ERROR"


@dataclass(frozen=True, slots=True)
class EmbeddingProviderHealth:
    status: DependencyStatus
    latency_ms: int
    model_version: str | None = None
    detail: str | None = None


class EmbeddingProviderError(RuntimeError):
    """Sanitized embedding failure; memory endpoints map subclasses to HTTP."""

    code = "EMBEDDING_PROVIDER_ERROR"


class EmbeddingConfigurationError(EmbeddingProviderError):
    code = "EMBEDDING_CONFIGURATION_ERROR"


class EmbeddingUnavailableError(EmbeddingProviderError):
    code = "EMBEDDING_UNAVAILABLE"


class EmbeddingTimeoutError(EmbeddingProviderError):
    code = "EMBEDDING_TIMEOUT"


class EmbeddingResponseError(EmbeddingProviderError):
    code = "EMBEDDING_RESPONSE_ERROR"


@runtime_checkable
class EmbeddingProvider(Protocol):
    """Independent embedding boundary used only by learning-memory indexing."""

    @property
    def provider_name(self) -> str: ...

    @property
    def model_name(self) -> str: ...

    async def embed(self, texts: tuple[str, ...]) -> list[list[float]]: ...

    async def check_health(self) -> EmbeddingProviderHealth: ...

    async def aclose(self) -> None: ...


@runtime_checkable
class ModelProvider(Protocol):
    """Vendor-neutral generation boundary used by all AI agents."""

    @property
    def provider_name(self) -> str: ...

    @property
    def model_name(self) -> str: ...

    async def generate(
        self,
        request: ModelRequest,
        *,
        output_model: type[BaseModel] | None = None,
    ) -> ModelResponse: ...

    async def check_health(self) -> ModelProviderHealth: ...

    async def aclose(self) -> None: ...
