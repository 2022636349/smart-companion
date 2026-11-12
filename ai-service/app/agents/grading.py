from __future__ import annotations

import asyncio
from collections.abc import Callable
from decimal import Decimal
from typing import Protocol

from pydantic import ValidationError

from app.clients.grading_attachment import GradingAttachmentClient
from app.contracts.assessment import (
    AttachmentGradeRequest,
    AttachmentGradeResponse,
    GradeModelOutput,
    GradeProviderOutput,
    SubjectiveGradeRequest,
)
from app.contracts.common import RubricItem
from app.core.config import AssessmentSourceSettings, GradingSettings
from app.prompts.grading import (
    ATTACHMENT_GRADE_PROMPT_VERSION,
    SUBJECTIVE_GRADE_PROMPT_VERSION,
    build_attachment_grade_messages,
    build_subjective_grade_messages,
    sanitize_student_material,
)
from app.providers.base import ModelProvider, ModelRequest
from app.providers.factory import create_model_provider
from app.services.grading_extraction import extract_grading_attachments


LOW_CONFIDENCE_THRESHOLD = Decimal("0.50")
LOW_CONFIDENCE_WARNING = "AI 对至少一个评分点置信度较低，必须由教师重点复核。"


class GradingAgentError(RuntimeError):
    code = "GRADING_AGENT_ERROR"


class GradingRequestIdMismatchError(GradingAgentError):
    code = "GRADING_REQUEST_ID_MISMATCH"


class GradingModelOutputError(GradingAgentError):
    code = "GRADING_MODEL_OUTPUT_INVALID"


class GradingDeadlineExceededError(GradingAgentError):
    code = "GRADING_TOTAL_TIMEOUT"


class GradingSensitiveRubricError(GradingAgentError):
    code = "GRADING_SENSITIVE_RUBRIC"


class AttachmentReader(Protocol):
    async def download_all(
        self,
        urls: list[str],
        *,
        request_id: str,
    ): ...

    async def aclose(self) -> None: ...


ProviderFactory = Callable[[], ModelProvider]


class GradingAgent:
    """Produces a validated suggestion; Spring remains final-grade authority."""

    def __init__(
        self,
        *,
        attachment_client: AttachmentReader | None = None,
        provider_factory: ProviderFactory = create_model_provider,
        settings: GradingSettings | None = None,
    ) -> None:
        self._settings = settings or GradingSettings.from_env()
        self._attachment_client = attachment_client
        self._provider_factory = provider_factory

    async def aclose(self) -> None:
        if self._attachment_client is not None:
            await self._attachment_client.aclose()

    async def suggest_subjective(
        self,
        request: SubjectiveGradeRequest,
        *,
        trace_request_id: str,
    ) -> AttachmentGradeResponse:
        self._check_request_id(request.request_id, trace_request_id)
        _validate_rubric_privacy(request.rubric_items)
        try:
            async with asyncio.timeout(self._settings.total_timeout_seconds):
                return await self._generate(
                    request_id=request.request_id,
                    rubric_items=request.rubric_items,
                    max_score=request.max_score,
                    prompt_version=SUBJECTIVE_GRADE_PROMPT_VERSION,
                    messages=build_subjective_grade_messages(
                        answer_text=request.answer_text,
                        reference_answer=request.reference_answer,
                        rubric_items=request.rubric_items,
                        max_score=request.max_score,
                    ),
                    inherited_warnings=[],
                    evidence_sources=[sanitize_student_material(request.answer_text)],
                )
        except TimeoutError as exc:
            raise GradingDeadlineExceededError(
                "Subjective grading exceeded the configured total timeout."
            ) from exc

    async def grade_attachment(
        self,
        request: AttachmentGradeRequest,
        *,
        trace_request_id: str,
    ) -> AttachmentGradeResponse:
        self._check_request_id(request.request_id, trace_request_id)
        _validate_rubric_privacy(request.rubric_items)
        if self._attachment_client is None:
            raise GradingAgentError("The attachment reader is unavailable.")
        try:
            async with asyncio.timeout(self._settings.total_timeout_seconds):
                downloaded = await self._attachment_client.download_all(
                    [str(url) for url in request.attachment_download_urls],
                    request_id=trace_request_id,
                )
                documents = await extract_grading_attachments(
                    downloaded,
                    settings=self._settings,
                )
                extraction_warnings = _unique_items(
                    [warning for document in documents for warning in document.warnings]
                )
                return await self._generate(
                    request_id=request.request_id,
                    rubric_items=request.rubric_items,
                    max_score=request.max_score,
                    prompt_version=ATTACHMENT_GRADE_PROMPT_VERSION,
                    messages=build_attachment_grade_messages(
                        attachments=documents,
                        rubric_items=request.rubric_items,
                        max_score=request.max_score,
                    ),
                    inherited_warnings=extraction_warnings,
                    evidence_sources=[
                        sanitize_student_material(document.text)
                        for document in documents
                    ],
                )
        except TimeoutError as exc:
            raise GradingDeadlineExceededError(
                "Attachment grading exceeded the configured total timeout."
            ) from exc

    async def _generate(
        self,
        *,
        request_id: str,
        rubric_items: list[RubricItem],
        max_score: float,
        prompt_version: str,
        messages,
        inherited_warnings: list[str],
        evidence_sources: list[str],
    ) -> AttachmentGradeResponse:
        model_rubric_items = _model_rubric_items(rubric_items)
        provider = self._provider_factory()
        try:
            response = await provider.generate(
                ModelRequest(
                    messages=messages,
                    prompt_version=prompt_version,
                    max_tokens=4_000,
                    temperature=0.0,
                    request_id=request_id,
                    capability=(
                        "ATTACHMENT_GRADE"
                        if prompt_version == ATTACHMENT_GRADE_PROMPT_VERSION
                        else "SUBJECTIVE_GRADE"
                    ),
                ),
                output_model=GradeProviderOutput,
            )
        finally:
            await provider.aclose()
        if response.structured_content is None:
            raise GradingModelOutputError(
                "The model did not return structured grading output."
            )
        try:
            generated = GradeModelOutput.model_validate(
                _sanitize_grade_output(response.structured_content)
            )
        except ValidationError as exc:
            raise GradingModelOutputError(
                "The model returned invalid grading output."
            ) from exc
        if response.metadata.prompt_version != prompt_version:
            raise GradingModelOutputError(
                "The model response prompt version does not match the request."
            )
        model_version = response.metadata.model.strip()
        if not model_version or len(model_version) > 200:
            raise GradingModelOutputError(
                "The model response is missing a model version."
            )
        _validate_generated_grade(
            generated,
            rubric_items=model_rubric_items,
            max_score=max_score,
            evidence_sources=evidence_sources,
        )
        warnings = _bounded_warnings(
            _unique_items([*inherited_warnings, *generated.warnings])
        )
        if any(
            Decimal(str(item.confidence)) < LOW_CONFIDENCE_THRESHOLD
            for item in generated.rubric_grades
        ):
            warnings = _bounded_warnings(
                _unique_items([*warnings, LOW_CONFIDENCE_WARNING])
            )
        return AttachmentGradeResponse(
            request_id=request_id,
            suggested_score=generated.suggested_score,
            rubric_grades=generated.rubric_grades,
            overall_comment=generated.overall_comment,
            warnings=warnings,
            model_version=model_version,
            prompt_version=prompt_version,
        )

    @staticmethod
    def _check_request_id(body_request_id: str, trace_request_id: str) -> None:
        if body_request_id != trace_request_id:
            raise GradingRequestIdMismatchError(
                "request_id must match X-Request-Id."
            )


def _validate_generated_grade(
    generated: GradeModelOutput,
    *,
    rubric_items: list[RubricItem],
    max_score: float,
    evidence_sources: list[str],
) -> None:
    expected = {item.label: item for item in rubric_items}
    observed_labels = [item.label for item in generated.rubric_grades]
    if len(observed_labels) != len(set(observed_labels)):
        raise GradingModelOutputError("The model duplicated a rubric label.")
    if set(observed_labels) != set(expected):
        raise GradingModelOutputError(
            "The model omitted or invented a rubric label."
        )
    score_total = Decimal("0")
    for grade in generated.rubric_grades:
        rubric = expected[grade.label]
        if Decimal(str(grade.max_score)) != Decimal(str(rubric.max_score)):
            raise GradingModelOutputError(
                "The model changed a rubric score boundary."
            )
        score_total += Decimal(str(grade.score))
        for evidence in grade.evidence:
            normalized_evidence = _normalize_evidence(evidence)
            if not any(
                normalized_evidence in _normalize_evidence(source)
                for source in evidence_sources
            ):
                raise GradingModelOutputError(
                    "The model returned evidence not present in student material."
                )
            if any(
                len(_normalize_evidence(source)) > 40
                and normalized_evidence == _normalize_evidence(source)
                for source in evidence_sources
            ):
                raise GradingModelOutputError(
                    "The model copied an entire student material as evidence."
                )
    suggested = Decimal(str(generated.suggested_score))
    maximum = Decimal(str(max_score))
    if score_total != suggested or suggested > maximum:
        raise GradingModelOutputError(
            "The model grading total is inconsistent or out of bounds."
        )


def create_grading_agent(*, with_attachments: bool) -> GradingAgent:
    settings = GradingSettings.from_env()
    attachment_client = None
    if with_attachments:
        attachment_client = GradingAttachmentClient(
            AssessmentSourceSettings.from_env(),
            settings,
        )
    return GradingAgent(
        attachment_client=attachment_client,
        settings=settings,
    )


def _unique_items(values: list[str]) -> list[str]:
    result: list[str] = []
    seen: set[str] = set()
    for value in values:
        if value not in seen:
            result.append(value)
            seen.add(value)
    return result


def _bounded_warnings(values: list[str]) -> list[str]:
    if len(values) <= 20:
        return values
    return [*values[:19], "其他模型或提取警告已省略，请教师完整复核作答材料。"]


def _normalize_evidence(value: str) -> str:
    return " ".join(value.split())


def _model_rubric_items(rubric_items: list[RubricItem]) -> list[RubricItem]:
    sanitized = [
        RubricItem(
            label=item.label,
            description=sanitize_student_material(item.description),
            max_score=item.max_score,
        )
        for item in rubric_items
    ]
    labels = [item.label for item in sanitized]
    if len(labels) != len(set(labels)):
        raise GradingModelOutputError(
            "Rubric labels become ambiguous after identity redaction."
        )
    return sanitized


def _validate_rubric_privacy(rubric_items: list[RubricItem]) -> None:
    if any(sanitize_student_material(item.label) != item.label for item in rubric_items):
        raise GradingSensitiveRubricError(
            "Rubric labels must not contain direct identity fields."
        )


def _sanitize_grade_output(value: dict) -> dict:
    """Redact direct identifiers from all model-authored response text."""

    copied = dict(value)
    grades = copied.get("rubric_grades")
    if isinstance(grades, list):
        sanitized_grades = []
        for grade in grades:
            if not isinstance(grade, dict):
                sanitized_grades.append(grade)
                continue
            sanitized = dict(grade)
            evidence = sanitized.get("evidence")
            if isinstance(evidence, list):
                sanitized["evidence"] = [
                    sanitize_student_material(item) if isinstance(item, str) else item
                    for item in evidence
                ]
            if isinstance(sanitized.get("comment"), str):
                sanitized["comment"] = sanitize_student_material(sanitized["comment"])
            sanitized_grades.append(sanitized)
        copied["rubric_grades"] = sanitized_grades
    if isinstance(copied.get("overall_comment"), str):
        copied["overall_comment"] = sanitize_student_material(
            copied["overall_comment"]
        )
    warnings = copied.get("warnings")
    if isinstance(warnings, list):
        copied["warnings"] = [
            sanitize_student_material(item) if isinstance(item, str) else item
            for item in warnings
        ]
    return copied
