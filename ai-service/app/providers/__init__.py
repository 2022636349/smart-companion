from app.providers.base import (
    EmbeddingConfigurationError,
    EmbeddingProvider,
    EmbeddingProviderError,
    EmbeddingProviderHealth,
    EmbeddingResponseError,
    EmbeddingTimeoutError,
    EmbeddingUnavailableError,
    ModelAuthenticationError,
    ModelCallMetadata,
    ModelConfigurationError,
    ModelMessage,
    ModelProvider,
    ModelProviderError,
    ModelProviderHealth,
    ModelRateLimitError,
    ModelRequest,
    ModelRequestError,
    ModelResponse,
    ModelResponseError,
    ModelServerError,
    ModelTimeoutError,
    ModelTransportError,
    StructuredOutputError,
    TokenUsage,
)


__all__ = [
    "DeepSeekProvider",
    "EmbeddingConfigurationError",
    "EmbeddingProvider",
    "EmbeddingProviderError",
    "EmbeddingProviderHealth",
    "EmbeddingResponseError",
    "EmbeddingTimeoutError",
    "EmbeddingUnavailableError",
    "OllamaEmbeddingProvider",
    "OllamaProvider",
    "create_embedding_provider",
    "ModelAuthenticationError",
    "ModelCallMetadata",
    "ModelConfigurationError",
    "ModelMessage",
    "ModelProvider",
    "ModelProviderError",
    "ModelProviderHealth",
    "ModelRateLimitError",
    "ModelRequest",
    "ModelRequestError",
    "ModelResponse",
    "ModelResponseError",
    "ModelServerError",
    "ModelTimeoutError",
    "ModelTransportError",
    "StructuredOutputError",
    "TokenUsage",
    "create_model_provider",
]


def __getattr__(name: str):
    """Keep convenient exports without creating a config/provider import cycle."""
    if name == "DeepSeekProvider":
        from app.providers.deepseek import DeepSeekProvider

        return DeepSeekProvider
    if name == "create_model_provider":
        from app.providers.factory import create_model_provider

        return create_model_provider
    if name == "create_embedding_provider":
        from app.providers.factory import create_embedding_provider

        return create_embedding_provider
    if name == "OllamaEmbeddingProvider":
        from app.providers.ollama import OllamaEmbeddingProvider

        return OllamaEmbeddingProvider
    if name == "OllamaProvider":
        from app.providers.ollama import OllamaProvider

        return OllamaProvider
    raise AttributeError(name)
