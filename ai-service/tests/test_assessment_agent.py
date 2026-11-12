from __future__ import annotations

import asyncio
import unittest

from app.agents.assessment import (
    AssessmentParseDeadlineExceededError,
    AssessmentParseAgent,
    AssessmentParseModelOutputError,
    AssessmentParseRequestIdMismatchError,
)
from app.clients.assessment_source import DownloadedAssessmentSource
from app.clients.knowledge import ApprovedKnowledgeMatch, KnowledgeServerError
from app.contracts.assessment import AssessmentParseRequest
from app.contracts.common import KnowledgePointCandidate
from app.prompts.assessment import ASSESSMENT_PARSE_PROMPT_VERSION
from app.providers.base import (
    ModelCallMetadata,
    ModelResponse,
    TokenUsage,
)
from app.services.document_extraction import AssessmentDocumentExtractor
from tests.assessment_fixtures import five_question_model_output


class FakeSourceClient:
    def __init__(self) -> None:
        self.calls = []
        self.closed = False

    async def download(self, **kwargs):
        self.calls.append(kwargs)
        return DownloadedAssessmentSource(
            content="第一题：Python 中用于定义函数的关键字是？".encode(),
            content_type="text/plain",
        )

    async def aclose(self) -> None:
        self.closed = True


class SlowSourceClient(FakeSourceClient):
    async def download(self, **kwargs):
        self.calls.append(kwargs)
        await asyncio.sleep(0.1)
        raise AssertionError("The total parse deadline should cancel the download.")


class FakeKnowledgeMatcher:
    def __init__(
        self,
        *,
        error: Exception | None = None,
        filtered_unapproved_count: int = 0,
        requires_review_count: int = 0,
    ) -> None:
        self.error = error
        self.filtered_unapproved_count = filtered_unapproved_count
        self.requires_review_count = requires_review_count
        self.calls = []
        self.closed = False

    async def match_knowledge_points(self, text, *, course_id, request_id):
        self.calls.append((text, course_id, request_id))
        if self.error is not None:
            raise self.error
        return ApprovedKnowledgeMatch(
            candidates=(
                KnowledgePointCandidate(
                    knowledge_point_id="kp-python-functions",
                    name="Python 函数",
                    score=0.94,
                ),
            ),
            filtered_unapproved_count=self.filtered_unapproved_count,
            requires_review_count=self.requires_review_count,
        )

    async def aclose(self) -> None:
        self.closed = True


class FakeProvider:
    provider_name = "fake"
    model_name = "mock-assessment-model"

    def __init__(
        self,
        *,
        structured_content: dict | None = None,
        prompt_version: str = ASSESSMENT_PARSE_PROMPT_VERSION,
    ) -> None:
        self.structured_content = structured_content
        self.prompt_version = prompt_version
        self.requests = []
        self.output_models = []
        self.closed = False

    async def generate(self, request, *, output_model=None):
        self.requests.append(request)
        self.output_models.append(output_model)
        return ModelResponse(
            text="{}",
            structured_content=self.structured_content,
            metadata=ModelCallMetadata(
                provider="fake",
                model=self.model_name,
                token_usage=TokenUsage(10, 20, 30),
                finish_reason="stop",
                latency_ms=5,
                provider_request_id="provider-request-1",
                prompt_version=self.prompt_version,
                attempts=1,
            ),
        )

    async def aclose(self) -> None:
        self.closed = True


def request() -> AssessmentParseRequest:
    return AssessmentParseRequest.model_validate(
        {
            "request_id": "trace-assessment-0001",
            "source_file_id": 7,
            "filename": "teacher-name-questions.txt",
            "download_url": "https://business.test/api/files/7/content",
            "course_id": 99,
            "preferred_language": "zh-CN",
        }
    )


def extractor() -> AssessmentDocumentExtractor:
    return AssessmentDocumentExtractor(
        max_text_chars=60_000,
        max_pdf_pages=200,
        max_uncompressed_docx_bytes=100 * 1024 * 1024,
    )


class AssessmentParseAgentTest(unittest.IsolatedAsyncioTestCase):
    async def test_parses_five_types_and_replaces_model_graph_ids(self) -> None:
        source = FakeSourceClient()
        matcher = FakeKnowledgeMatcher()
        provider = FakeProvider(structured_content=five_question_model_output())
        agent = AssessmentParseAgent(
            source,
            matcher,
            extractor(),
            provider_factory=lambda: provider,
        )

        response = await agent.parse(
            request(), trace_request_id="trace-assessment-0001"
        )
        await agent.aclose()

        self.assertEqual(len(response.questions), 5)
        self.assertEqual(
            {question.question_type.value for question in response.questions},
            {
                "SINGLE_CHOICE",
                "MULTIPLE_CHOICE",
                "SUBJECTIVE",
                "PROGRAMMING",
                "ATTACHMENT",
            },
        )
        self.assertTrue(
            all(
                question.knowledge_point_candidates[0].knowledge_point_id
                == "kp-python-functions"
                for question in response.questions
            )
        )
        self.assertNotIn(
            "hallucinated-id", response.model_dump_json()
        )
        self.assertEqual(len(matcher.calls), 5)
        self.assertTrue(
            all(
                call[1:] == (99, "trace-assessment-0001")
                for call in matcher.calls
            )
        )
        prompt = "\n".join(
            message.content for message in provider.requests[0].messages
        )
        self.assertIn("用于定义函数", prompt)
        self.assertNotIn("trace-assessment-0001", prompt)
        self.assertNotIn("teacher-name-questions.txt", prompt)
        self.assertNotIn("/api/files/7/content", prompt)
        self.assertNotIn('"course_id":99', prompt)
        self.assertEqual(
            provider.requests[0].prompt_version,
            ASSESSMENT_PARSE_PROMPT_VERSION,
        )
        self.assertTrue(source.closed)
        self.assertTrue(matcher.closed)
        self.assertTrue(provider.closed)

    async def test_graph_unavailable_returns_empty_candidates_and_warning(self) -> None:
        source = FakeSourceClient()
        matcher = FakeKnowledgeMatcher(
            error=KnowledgeServerError("mock graph down", status_code=503)
        )
        provider = FakeProvider(structured_content=five_question_model_output())
        agent = AssessmentParseAgent(
            source,
            matcher,
            extractor(),
            provider_factory=lambda: provider,
        )

        response = await agent.parse(
            request(), trace_request_id="trace-assessment-0001"
        )

        self.assertTrue(
            all(not question.knowledge_point_candidates for question in response.questions)
        )
        self.assertTrue(
            all(
                any("知识图谱匹配暂不可用" in item for item in question.warnings)
                for question in response.questions
            )
        )
        self.assertTrue(any("知识图谱匹配暂不可用" in item for item in response.warnings))

    async def test_filtered_unapproved_match_is_never_returned_and_warns(self) -> None:
        source = FakeSourceClient()
        matcher = FakeKnowledgeMatcher(filtered_unapproved_count=2)
        provider = FakeProvider(structured_content=five_question_model_output())
        agent = AssessmentParseAgent(
            source,
            matcher,
            extractor(),
            provider_factory=lambda: provider,
        )

        response = await agent.parse(
            request(), trace_request_id="trace-assessment-0001"
        )

        self.assertNotIn("PENDING", response.model_dump_json())
        self.assertNotIn("REJECTED", response.model_dump_json())
        self.assertTrue(any("未审核或非知识点候选" in item for item in response.warnings))

    async def test_requires_review_candidate_is_kept_but_warns_teacher(self) -> None:
        source = FakeSourceClient()
        matcher = FakeKnowledgeMatcher(requires_review_count=1)
        provider = FakeProvider(structured_content=five_question_model_output())
        agent = AssessmentParseAgent(
            source,
            matcher,
            extractor(),
            provider_factory=lambda: provider,
        )

        response = await agent.parse(
            request(), trace_request_id="trace-assessment-0001"
        )

        self.assertTrue(
            all(question.knowledge_point_candidates for question in response.questions)
        )
        self.assertTrue(any("需要人工复核" in item for item in response.warnings))
        self.assertTrue(
            all(
                any("需要人工复核" in item for item in question.warnings)
                for question in response.questions
            )
        )

    async def test_request_id_mismatch_stops_before_download_and_model(self) -> None:
        source = FakeSourceClient()
        matcher = FakeKnowledgeMatcher()
        provider = FakeProvider(structured_content=five_question_model_output())
        agent = AssessmentParseAgent(
            source,
            matcher,
            extractor(),
            provider_factory=lambda: provider,
        )

        with self.assertRaises(AssessmentParseRequestIdMismatchError):
            await agent.parse(request(), trace_request_id="different-trace-0001")

        self.assertFalse(source.calls)
        self.assertFalse(provider.requests)
        self.assertFalse(matcher.calls)

    async def test_invalid_model_shape_is_rejected_before_graph_match(self) -> None:
        source = FakeSourceClient()
        matcher = FakeKnowledgeMatcher()
        provider = FakeProvider(
            structured_content={
                "title_suggestion": "bad",
                "questions": [],
                "warnings": [],
            }
        )
        agent = AssessmentParseAgent(
            source,
            matcher,
            extractor(),
            provider_factory=lambda: provider,
        )

        with self.assertRaises(AssessmentParseModelOutputError):
            await agent.parse(request(), trace_request_id="trace-assessment-0001")

        self.assertFalse(matcher.calls)
        self.assertTrue(provider.closed)

    async def test_total_deadline_bounds_download_model_and_matching(self) -> None:
        source = SlowSourceClient()
        matcher = FakeKnowledgeMatcher()
        provider = FakeProvider(structured_content=five_question_model_output())
        agent = AssessmentParseAgent(
            source,
            matcher,
            extractor(),
            provider_factory=lambda: provider,
            total_timeout_seconds=0.001,
        )

        with self.assertRaises(AssessmentParseDeadlineExceededError):
            await agent.parse(request(), trace_request_id="trace-assessment-0001")

        self.assertFalse(provider.requests)
        self.assertFalse(matcher.calls)


if __name__ == "__main__":
    unittest.main()
