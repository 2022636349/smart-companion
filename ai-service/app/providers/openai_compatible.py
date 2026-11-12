from __future__ import annotations

from typing import Any

import httpx2
from pydantic import BaseModel

from app.core.animation_config import AnimationModelSettings
from app.providers.base import ModelRequest, ModelRequestError, ModelRoutingUnavailableError
from app.providers.chat_completions import ChatCompletionsProvider


class OpenAICompatibleProvider(ChatCompletionsProvider):
    """Separate endpoint/key/model, using the same validated Chat Completions transport."""

    provider_name = 'openai_compatible'
    provider_label = 'OpenAI-compatible provider'

    def __init__(self, settings: AnimationModelSettings, **kwargs) -> None:
        super().__init__(settings, **kwargs)

    def _request_error(self, response: httpx2.Response, *, attempts: int) -> ModelRequestError:
        # Gateways may encode missing upstream capacity as a client error.
        # Recognize the typed signal only; never expose their raw error text.
        try:
            body = response.json()
        except (ValueError, TypeError):
            body = None
        error = body.get('error') if isinstance(body, dict) else None
        if (response.status_code == 400 and isinstance(error, dict)
                and error.get('type') == 'routing_strategy_resource_unavailable'):
            return ModelRoutingUnavailableError(
                'The model gateway has no available channel for the selected route.',
                status_code=response.status_code,
                provider_request_id=self._provider_request_id(response),
                attempts=attempts,
            )
        return super()._request_error(response, attempts=attempts)

    def _build_payload(self, request: ModelRequest, *, output_model: type[BaseModel] | None) -> dict[str, Any]:
        payload = super()._build_payload(request, output_model=output_model)
        # DeepSeek's user_id is not part of this adapter's wire contract.
        payload.pop('user_id', None)
        if not self._settings.send_temperature:
            payload.pop('temperature', None)
        if self._settings.token_limit_field == 'max_completion_tokens' and 'max_tokens' in payload:
            payload['max_completion_tokens'] = payload.pop('max_tokens')
        if output_model is not None:
            if self._settings.response_format == 'text':
                # Only the API formatting hint is omitted. Local JSON/schema checks remain mandatory.
                payload.pop('response_format', None)
            elif self._settings.response_format == 'json_schema':
                payload['response_format'] = {'type': 'json_schema', 'json_schema': {
                    'name': output_model.__name__, 'schema': output_model.model_json_schema(),
                }}
        return payload
