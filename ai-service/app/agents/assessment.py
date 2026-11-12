from __future__ import annotations

import asyncio
from collections.abc import Callable
from typing import Protocol

from pydantic import ValidationError

from app.clients.assessment_source import AssessmentSourceClient
from app.clients.knowledge import KnowledgeClientError, KnowledgeServiceClient
from app.contracts.assessment import (
    AssessmentModelOutput,
    AssessmentParseRequest,
    AssessmentParseResponse,
    QuestionDraft,
)
from app.core.config import (
    AssessmentParseSettings,
    AssessmentSourceSettings,
    KnowledgeServiceConfigurationError,
)
from app.prompts.assessment import (
    ASSESSMENT_PARSE_PROMPT_VERSION,
    build_assessment_parse_messages,
)
from app.providers.base import ModelProvider, ModelRequest
from app.providers.factory import create_model_provider
from app.services.document_extraction import (
    AssessmentDocumentExtractor,
    extract_assessment_document,
)


class AssessmentParseAgentError(RuntimeError):
    code = "ASSESSMENT_PARSE_AGENT_ERROR"


class AssessmentParseRequestIdMismatchError(AssessmentParseAgentError):
    code = "ASSESSMENT_PARSE_REQUEST_ID_MISMATCH"


class AssessmentParseModelOutputError(AssessmentParseAgentError):
    code = "ASSESSMENT_PARSE_MODEL_OUTPUT_INVALID"


class AssessmentParseDeadlineExceededError(AssessmentParseAgentError):
    code = "ASSESSMENT_PARSE_TOTAL_TIMEOUT"


class AssessmentSourceReader(Protocol):
    async def download(
        self,
        *,
        source_file_id: int,
        download_url: str,
        request_id: str,
    ): ...

    async def aclose(self) -> None: ...


class AssessmentKnowledgeMatcher(Protocol):
    async def match_knowledge_points(
        self,
        text: str,
        *,
        course_id: int,
        request_id: str,
    ): ...

    async def aclose(self) -> None: ...


ProviderFactory = Callable[[], ModelProvider]


class _UnavailableKnowledgeMatcher:
    async def match_knowledge_points(
        self,
        text: str,
        *,
        course_id: int,
        request_id: str,
    ):
        del text, request_id, course_id
        raise KnowledgeServiceConfigurationError(
            "Knowledge matching is unavailable because its configuration is invalid."
        )

    async def aclose(self) -> None:
        return None


class AssessmentParseAgent:
    """Turns one transient Spring file into a validated, editable question draft."""

    def __init__(
        self,
        source_client: AssessmentSourceReader,
        knowledge_matcher: AssessmentKnowledgeMatcher,
        document_extractor: AssessmentDocumentExtractor,
        *,
        provider_factory: ProviderFactory = create_model_provider,
        total_timeout_seconds: float = 75.0,
    ) -> None:
        self._source_client = source_client
        self._knowledge_matcher = knowledge_matcher
        self._document_extractor = document_extractor
        self._provider_factory = provider_factory
        self._total_timeout_seconds = total_timeout_seconds

    async def aclose(self) -> None:
        try:
            await self._source_client.aclose()
        finally:
            await self._knowledge_matcher.aclose()

    async def parse(
        self,
        request: AssessmentParseRequest,
        *,
        trace_request_id: str,
    ) -> AssessmentParseResponse:
        if request.request_id != trace_request_id:
            raise AssessmentParseRequestIdMismatchError(
                "The body request_id must match X-Request-Id."
            )

        try:
            async with asyncio.timeout(self._total_timeout_seconds):
                return await self._parse_within_deadline(
                    request,
                    trace_request_id=trace_request_id,
                )
        except TimeoutError as exc:
            raise AssessmentParseDeadlineExceededError(
                "Assessment parsing exceeded the configured total timeout."
            ) from exc

    async def _parse_within_deadline(
        self,
        request: AssessmentParseRequest,
        *,
        trace_request_id: str,
    ) -> AssessmentParseResponse:

        source = await self._source_client.download(
            source_file_id=request.source_file_id,
            download_url=str(request.download_url),
            request_id=trace_request_id,
        )
        document = await extract_assessment_document(
            self._document_extractor,
            filename=request.filename,
            content=source.content,
        )
        messages = build_assessment_parse_messages(
            document,
            preferred_language=request.preferred_language,
        )

        provider = self._provider_factory()
        try:
            model_response = await provider.generate(
                ModelRequest(
                    messages=messages,
                    prompt_version=ASSESSMENT_PARSE_PROMPT_VERSION,
                    max_tokens=8_000,
                    temperature=0.1,
                    request_id=trace_request_id,
                    capability="ASSESSMENT_PARSE",
                ),
                output_model=AssessmentModelOutput,
            )
        finally:
            await provider.aclose()

        if model_response.structured_content is None:
            raise AssessmentParseModelOutputError(
                "The model did not return the required structured assessment output."
            )
        try:
            generated = AssessmentModelOutput.model_validate(
                model_response.structured_content
            )
        except ValidationError as exc:
            raise AssessmentParseModelOutputError(
                "The model returned invalid structured assessment output."
            ) from exc

        if model_response.metadata.prompt_version != ASSESSMENT_PARSE_PROMPT_VERSION:
            raise AssessmentParseModelOutputError(
                "The model response prompt version does not match the assessment prompt."
            )
        model_version = model_response.metadata.model.strip()
        if not model_version:
            raise AssessmentParseModelOutputError(
                "The model response is missing a model version."
            )

        questions, match_warnings = await self._match_knowledge_points(
            generated.questions,
            course_id=request.course_id,
            request_id=trace_request_id,
        )
        warnings = _unique_items(
            [*document.warnings, *generated.warnings, *match_warnings]
        )
        return AssessmentParseResponse(
            request_id=request.request_id,
            title_suggestion=generated.title_suggestion,
            questions=questions,
            warnings=warnings,
            model_version=model_version,
            prompt_version=ASSESSMENT_PARSE_PROMPT_VERSION,
        )

    async def _match_knowledge_points(
        self,
        questions: list[QuestionDraft],
        *,
        course_id: int,
        request_id: str,
    ) -> tuple[list[QuestionDraft], list[str]]:
        semaphore = asyncio.Semaphore(5)

        async def match_one(question: QuestionDraft) -> tuple[QuestionDraft, str | None]:
            async with semaphore:
                try:
                    result = await self._knowledge_matcher.match_knowledge_points(
                        question.stem,
                        course_id=course_id,
                        request_id=request_id,
                    )
                except (KnowledgeClientError, KnowledgeServiceConfigurationError):
                    warning = (
                        "知识图谱匹配暂不可用，本题知识点候选为空，请教师手动确认。"
                    )
                    return question.model_copy(
                        update={
                            "knowledge_point_candidates": [],
                            "warnings": _unique_items([*question.warnings, warning]),
                        }
                    ), warning

                warning: str | None = None
                if result.requires_review_count:
                    warning = (
                        f"知识图谱将 {result.requires_review_count} 个候选标记为需要人工复核，"
                        "请教师确认知识点绑定。"
                    )
                elif result.filtered_unapproved_count:
                    warning = (
                        "知识图谱返回了未审核或非知识点候选，已安全忽略，请教师复核。"
                    )
                elif not result.candidates:
                    warning = "未匹配到已审核知识点，请教师手动选择。"
                question_warnings = question.warnings
                if warning is not None:
                    question_warnings = _unique_items([*question_warnings, warning])
                return question.model_copy(
                    update={
                        "knowledge_point_candidates": list(result.candidates),
                        "warnings": question_warnings,
                    }
                ), warning

        matched = await asyncio.gather(*(match_one(question) for question in questions))
        return (
            [question for question, _ in matched],
            _unique_items([warning for _, warning in matched if warning is not None]),
        )


def create_assessment_parse_agent() -> AssessmentParseAgent:
    settings = AssessmentSourceSettings.from_env()
    parse_settings = AssessmentParseSettings.from_env()
    try:
        knowledge_matcher: AssessmentKnowledgeMatcher = KnowledgeServiceClient()
    except KnowledgeServiceConfigurationError:
        knowledge_matcher = _UnavailableKnowledgeMatcher()
    return AssessmentParseAgent(
        AssessmentSourceClient(settings),
        knowledge_matcher,
        AssessmentDocumentExtractor(
            max_text_chars=settings.max_text_chars,
            max_pdf_pages=settings.max_pdf_pages,
            max_uncompressed_docx_bytes=min(
                settings.max_file_bytes * 5,
                100 * 1024 * 1024,
            ),
        ),
        total_timeout_seconds=parse_settings.total_timeout_seconds,
    )


def _unique_items(values: list[str] | tuple[str, ...]) -> list[str]:
    result: list[str] = []
    seen: set[str] = set()
    for value in values:
        if value not in seen:
            result.append(value)
            seen.add(value)
    return result
