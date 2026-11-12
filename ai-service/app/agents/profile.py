from __future__ import annotations

from collections.abc import Callable

from pydantic import ValidationError

from app.contracts.profile import (
    ProfileProviderOutput,
    ProfileSummarizeRequest,
    ProfileSummarizeResponse,
)
from app.prompts.grading import sanitize_student_material
from app.prompts.profile import PROFILE_SUMMARY_PROMPT_VERSION, build_profile_messages
from app.providers.base import ModelProvider, ModelRequest
from app.providers.factory import create_model_provider


class ProfileRequestIdMismatchError(ValueError):
    pass


class ProfileModelOutputError(ValueError):
    pass


ProviderFactory = Callable[[], ModelProvider]


class ProfileSummaryAgent:
    def __init__(self, provider_factory: ProviderFactory = create_model_provider) -> None:
        self._provider_factory = provider_factory

    async def summarize(
        self, request: ProfileSummarizeRequest, *, trace_request_id: str
    ) -> ProfileSummarizeResponse:
        if request.request_id != trace_request_id:
            raise ProfileRequestIdMismatchError(
                "request_id must match X-Request-Id."
            )
        provider = self._provider_factory()
        try:
            response = await provider.generate(
                ModelRequest(
                    messages=build_profile_messages(request),
                    prompt_version=PROFILE_SUMMARY_PROMPT_VERSION,
                    max_tokens=4_000,
                    temperature=0.1,
                    request_id=trace_request_id,
                    capability="PROFILE_SUMMARIZE",
                ),
                output_model=ProfileProviderOutput,
            )
        finally:
            await provider.aclose()
        if response.structured_content is None:
            raise ProfileModelOutputError("The model omitted structured profile output.")
        try:
            generated = ProfileProviderOutput.model_validate(
                _sanitize_profile_output(response.structured_content)
            )
        except ValidationError as exc:
            raise ProfileModelOutputError("The model returned invalid profile output.") from exc
        if response.metadata.prompt_version != PROFILE_SUMMARY_PROMPT_VERSION:
            raise ProfileModelOutputError("The model returned the wrong prompt version.")
        allowed_kps = {item.knowledge_point_id for item in request.evidence}
        if any(item.knowledge_point_id not in allowed_kps for item in generated.cognitive_labels):
            raise ProfileModelOutputError("A cognitive label invented a knowledge point.")
        if any(
            item.knowledge_point_id is not None and item.knowledge_point_id not in allowed_kps
            for item in generated.memory_candidates
        ):
            raise ProfileModelOutputError("A memory candidate invented a knowledge point.")
        if any(item.knowledge_point_id not in allowed_kps for item in generated.resource_suggestions):
            raise ProfileModelOutputError("A resource suggestion invented a knowledge point.")
        model_version = response.metadata.model.strip()
        if not model_version or len(model_version) > 200:
            raise ProfileModelOutputError("The model version is missing.")
        return ProfileSummarizeResponse(
            course_id=request.course_id,
            request_id=request.request_id,
            **generated.model_dump(),
            model_version=model_version,
            prompt_version=PROFILE_SUMMARY_PROMPT_VERSION,
        )


def _sanitize_profile_output(value: dict) -> dict:
    copied = dict(value)
    if isinstance(copied.get("summary"), str):
        copied["summary"] = sanitize_student_material(copied["summary"])
    labels = copied.get("cognitive_labels")
    if isinstance(labels, list):
        copied["cognitive_labels"] = [
            {
                **item,
                "evidence": sanitize_student_material(item["evidence"])
                if isinstance(item, dict) and isinstance(item.get("evidence"), str)
                else item.get("evidence") if isinstance(item, dict) else None,
            }
            if isinstance(item, dict)
            else item
            for item in labels
        ]
    candidates = copied.get("memory_candidates")
    if isinstance(candidates, list):
        copied["memory_candidates"] = [
            {
                **item,
                "title": sanitize_student_material(item["title"])
                if isinstance(item, dict) and isinstance(item.get("title"), str)
                else item.get("title") if isinstance(item, dict) else None,
                "content": sanitize_student_material(item["content"])
                if isinstance(item, dict) and isinstance(item.get("content"), str)
                else item.get("content") if isinstance(item, dict) else None,
            }
            if isinstance(item, dict)
            else item
            for item in candidates
        ]
    suggestions = copied.get("resource_suggestions")
    if isinstance(suggestions, list):
        copied["resource_suggestions"] = [
            {
                **item,
                "reason": sanitize_student_material(item["reason"])
                if isinstance(item, dict) and isinstance(item.get("reason"), str)
                else item.get("reason") if isinstance(item, dict) else None,
            }
            if isinstance(item, dict)
            else item
            for item in suggestions
        ]
    return copied
