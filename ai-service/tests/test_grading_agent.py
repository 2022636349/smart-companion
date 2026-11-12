from __future__ import annotations

import asyncio
import json
import unittest

from app.agents.grading import (
    GradingAgent,
    GradingDeadlineExceededError,
    GradingModelOutputError,
    GradingRequestIdMismatchError,
)
from app.clients.grading_attachment import DownloadedGradingAttachment
from app.contracts.assessment import (
    AttachmentGradeRequest,
    GradeProviderOutput,
    SubjectiveGradeRequest,
)
from app.core.config import GradingSettings
from app.providers.base import (
    ModelCallMetadata,
    ModelResponse,
    TokenUsage,
    StructuredOutputError,
)


def settings(*, timeout: float = 2) -> GradingSettings:
    return GradingSettings(
        total_timeout_seconds=timeout,
        attachment_timeout_seconds=1,
        max_attachment_files=5,
        max_attachment_file_bytes=1_024,
        max_attachment_total_bytes=2_048,
        max_extracted_chars_per_file=1_000,
        max_extracted_chars_total=2_000,
        max_pdf_pages=20,
    )


def subjective_request() -> SubjectiveGradeRequest:
    return SubjectiveGradeRequest.model_validate(
        {
            "request_id": "trace-subjective-grade-01",
            "submission_answer_id": 88,
            "answer_text": "函数通过参数接收输入，并使用 return 返回结果。",
            "reference_answer": "函数可以封装可复用逻辑。",
            "rubric_items": [
                {"label": "概念", "description": "说明参数", "max_score": 6.0},
                {"label": "返回值", "description": "说明返回值", "max_score": 4.0},
            ],
            "max_score": 10.0,
        }
    )


def valid_output(*, low_confidence: bool = False) -> dict:
    return {
        "suggested_score": 9.0,
        "rubric_grades": [
            {
                "label": "概念",
                "score": 5.0,
                "max_score": 6.0,
                "evidence": ["函数通过参数接收输入"],
                "confidence": 0.4 if low_confidence else 0.9,
                "comment": "概念基本正确",
            },
            {
                "label": "返回值",
                "score": 4.0,
                "max_score": 4.0,
                "evidence": ["使用 return 返回结果"],
                "confidence": 0.95,
                "comment": "说明正确",
            },
        ],
        "overall_comment": "这是供教师复核的评分建议。",
        "warnings": [],
    }


class FakeProvider:
    provider_name = "fake"
    model_name = "fake-grader"

    def __init__(self, output: dict | None = None, *, delay: float = 0) -> None:
        self.output = output
        self.delay = delay
        self.requests = []
        self.closed = False

    async def generate(self, request, *, output_model=None):
        self.requests.append((request, output_model))
        if self.delay:
            await asyncio.sleep(self.delay)
        return ModelResponse(
            text=json.dumps(self.output, ensure_ascii=False),
            structured_content=self.output,
            metadata=ModelCallMetadata(
                provider="fake",
                model="fake-grader",
                token_usage=TokenUsage(10, 10, 20),
                finish_reason="stop",
                latency_ms=1,
                provider_request_id="fake-request",
                prompt_version=request.prompt_version,
                attempts=1,
            ),
        )

    async def check_health(self):
        raise NotImplementedError

    async def aclose(self) -> None:
        self.closed = True


class FakeAttachmentClient:
    def __init__(self, attachments=None) -> None:
        self.attachments = attachments or []
        self.calls = []
        self.closed = False

    async def download_all(self, urls, *, request_id):
        self.calls.append((urls, request_id))
        return self.attachments

    async def aclose(self) -> None:
        self.closed = True


class RejectingProvider(FakeProvider):
    async def generate(self, request, *, output_model=None):
        self.requests.append((request, output_model))
        raise StructuredOutputError(
            "Provider structured output failed validation."
        )


class GradingAgentTest(unittest.IsolatedAsyncioTestCase):
    def test_provider_schema_requires_every_rubric_result_field(self) -> None:
        schema = GradeProviderOutput.model_json_schema()
        rubric_schema = schema["$defs"]["RubricGrade"]
        self.assertEqual(
            set(rubric_schema["required"]),
            {"label", "score", "max_score", "evidence", "confidence", "comment"},
        )
        self.assertEqual(
            schema["properties"]["rubric_grades"]["items"]["$ref"],
            "#/$defs/RubricGrade",
        )

    async def test_subjective_uses_versioned_provider_without_identity_or_rag(self) -> None:
        provider = FakeProvider(valid_output())
        agent = GradingAgent(
            provider_factory=lambda: provider,
            settings=settings(),
        )
        result = await agent.suggest_subjective(
            subjective_request(),
            trace_request_id="trace-subjective-grade-01",
        )
        request = provider.requests[0][0]
        prompt = "\n".join(message.content for message in request.messages)
        self.assertEqual(request.prompt_version, "subjective-grade-v1")
        self.assertIs(provider.requests[0][1], GradeProviderOutput)
        self.assertNotIn("submission_answer_id", prompt)
        self.assertNotIn("trace-subjective", prompt)
        self.assertNotIn("knowledgePoint", prompt)
        self.assertNotIn("Chroma", request.messages[-1].content)
        self.assertEqual(result.suggested_score, 9.0)
        self.assertEqual(result.model_version, "fake-grader")
        self.assertTrue(provider.closed)

    async def test_common_direct_identifiers_are_redacted_before_provider(self) -> None:
        request = subjective_request().model_copy(
            update={
                "answer_text": (
                    "姓名：张三\n学号：20260101\n邮箱 student@example.com，"
                    "手机 13800138000，身份证 11010519491231002X。"
                    "函数通过参数接收输入，并使用 return 返回结果。"
                )
            }
        )
        provider = FakeProvider(valid_output())
        agent = GradingAgent(provider_factory=lambda: provider, settings=settings())
        await agent.suggest_subjective(
            request,
            trace_request_id="trace-subjective-grade-01",
        )
        prompt = provider.requests[0][0].messages[-1].content
        for sensitive in (
            "张三",
            "20260101",
            "student@example.com",
            "13800138000",
            "11010519491231002X",
        ):
            self.assertNotIn(sensitive, prompt)
        self.assertIn("[REDACTED_", prompt)

    async def test_redacted_identity_evidence_is_validated_in_provider_fact_domain(self) -> None:
        request = SubjectiveGradeRequest.model_validate(
            {
                "request_id": "trace-subjective-grade-01",
                "submission_answer_id": 88,
                "answer_text": (
                    "姓名：张三\n邮箱 student@example.com，手机 13800138000。"
                    "函数通过参数接收输入。"
                ),
                "rubric_items": [
                    {"label": "内容", "description": "分析作答", "max_score": 10.0}
                ],
                "max_score": 10.0,
            }
        )
        output = {
            "suggested_score": 8.0,
            "rubric_grades": [
                {
                    "label": "内容",
                    "score": 8.0,
                    "max_score": 10.0,
                    "evidence": ["邮箱 [REDACTED_EMAIL]，手机 [REDACTED_PHONE]"],
                    "confidence": 0.8,
                    "comment": "供教师复核",
                }
            ],
            "overall_comment": "建议由教师确认。",
            "warnings": [],
        }
        provider = FakeProvider(output)
        agent = GradingAgent(provider_factory=lambda: provider, settings=settings())
        result = await agent.suggest_subjective(
            request,
            trace_request_id="trace-subjective-grade-01",
        )
        serialized = result.model_dump_json()
        for sensitive in ("张三", "student@example.com", "13800138000"):
            self.assertNotIn(sensitive, serialized)
        self.assertIn("[REDACTED_EMAIL]", serialized)

    async def test_model_authored_comments_and_warnings_cannot_reintroduce_identity(self) -> None:
        output = valid_output()
        output["rubric_grades"][0]["comment"] = "联系 student@example.com 复核"
        output["overall_comment"] = "学生手机 13800138000"
        output["warnings"] = ["姓名：张三"]
        provider = FakeProvider(output)
        agent = GradingAgent(provider_factory=lambda: provider, settings=settings())
        result = await agent.suggest_subjective(
            subjective_request(),
            trace_request_id="trace-subjective-grade-01",
        )
        serialized = result.model_dump_json()
        for sensitive in ("student@example.com", "13800138000", "张三"):
            self.assertNotIn(sensitive, serialized)
        self.assertIn("[REDACTED_", serialized)

    async def test_low_confidence_adds_teacher_review_warning(self) -> None:
        provider = FakeProvider(valid_output(low_confidence=True))
        agent = GradingAgent(provider_factory=lambda: provider, settings=settings())
        result = await agent.suggest_subjective(
            subjective_request(),
            trace_request_id="trace-subjective-grade-01",
        )
        self.assertTrue(any("置信度较低" in warning for warning in result.warnings))

    async def test_rejects_missing_duplicate_changed_or_invented_rubric(self) -> None:
        bad_outputs = []
        missing = valid_output()
        missing["rubric_grades"] = missing["rubric_grades"][:1]
        missing["suggested_score"] = 5.0
        bad_outputs.append(missing)
        duplicate = valid_output()
        duplicate["rubric_grades"][1]["label"] = "概念"
        bad_outputs.append(duplicate)
        changed = valid_output()
        changed["rubric_grades"][0]["max_score"] = 5.0
        bad_outputs.append(changed)
        invented = valid_output()
        invented["rubric_grades"][1]["label"] = "身份"
        bad_outputs.append(invented)
        for output in bad_outputs:
            with self.subTest(labels=[item["label"] for item in output["rubric_grades"]]):
                agent = GradingAgent(
                    provider_factory=lambda value=output: FakeProvider(value),
                    settings=settings(),
                )
                with self.assertRaises(GradingModelOutputError):
                    await agent.suggest_subjective(
                        subjective_request(),
                        trace_request_id="trace-subjective-grade-01",
                    )

    async def test_rejects_inconsistent_total_and_fabricated_or_whole_answer_evidence(self) -> None:
        inconsistent = valid_output()
        inconsistent["suggested_score"] = 10.0
        fabricated = valid_output()
        fabricated["rubric_grades"][0]["evidence"] = ["不存在的学生表述"]
        for output in (inconsistent, fabricated):
            agent = GradingAgent(
                provider_factory=lambda value=output: FakeProvider(value),
                settings=settings(),
                    )

    async def test_rejects_copying_entire_long_answer_as_evidence(self) -> None:
        request = subjective_request().model_copy(
            update={"answer_text": subjective_request().answer_text * 3}
        )
        whole = valid_output()
        whole["rubric_grades"][0]["evidence"] = [request.answer_text]
        agent = GradingAgent(
            provider_factory=lambda: FakeProvider(whole),
            settings=settings(),
        )
        with self.assertRaises(GradingModelOutputError):
            await agent.suggest_subjective(
                request,
                trace_request_id="trace-subjective-grade-01",
            )
            with self.assertRaises(GradingModelOutputError):
                await agent.suggest_subjective(
                    subjective_request(),
                    trace_request_id="trace-subjective-grade-01",
                )

    async def test_request_id_mismatch_fails_before_model(self) -> None:
        calls = 0

        def provider_factory():
            nonlocal calls
            calls += 1
            return FakeProvider(valid_output())

        agent = GradingAgent(provider_factory=provider_factory, settings=settings())
        with self.assertRaises(GradingRequestIdMismatchError):
            await agent.suggest_subjective(
                subjective_request(), trace_request_id="trace-other-grade-001"
            )
        self.assertEqual(calls, 0)

    async def test_provider_structured_output_failure_propagates_typed_and_closes(self) -> None:
        provider = RejectingProvider({})
        agent = GradingAgent(
            provider_factory=lambda: provider,
            settings=settings(),
        )
        with self.assertRaises(StructuredOutputError):
            await agent.suggest_subjective(
                subjective_request(),
                trace_request_id="trace-subjective-grade-01",
            )
        self.assertTrue(provider.closed)

    async def test_total_timeout_and_cancellation_close_provider(self) -> None:
        slow = FakeProvider(valid_output(), delay=1)
        agent = GradingAgent(
            provider_factory=lambda: slow,
            settings=settings(timeout=0.02),
        )
        with self.assertRaises(GradingDeadlineExceededError):
            await agent.suggest_subjective(
                subjective_request(),
                trace_request_id="trace-subjective-grade-01",
            )
        self.assertTrue(slow.closed)

        slow_cancel = FakeProvider(valid_output(), delay=10)
        agent = GradingAgent(
            provider_factory=lambda: slow_cancel,
            settings=settings(timeout=2),
        )
        task = asyncio.create_task(
            agent.suggest_subjective(
                subjective_request(),
                trace_request_id="trace-subjective-grade-01",
            )
        )
        await asyncio.sleep(0)
        task.cancel()
        with self.assertRaises(asyncio.CancelledError):
            await task
        self.assertTrue(slow_cancel.closed)

    async def test_attachment_is_downloaded_extracted_in_memory_and_minimally_disclosed(self) -> None:
        output = {
            "suggested_score": 10.0,
            "rubric_grades": [
                {
                    "label": "内容",
                    "score": 10.0,
                    "max_score": 10.0,
                    "evidence": ["学生附件内容"],
                    "confidence": 0.9,
                    "comment": "内容符合要求",
                }
            ],
            "overall_comment": "供教师复核",
            "warnings": [],
        }
        provider = FakeProvider(output)
        client = FakeAttachmentClient(
            [
                DownloadedGradingAttachment(
                    file_id=7,
                    filename="student-name.txt",
                    content="学生附件内容".encode(),
                    content_type="text/plain",
                )
            ]
        )
        request = AttachmentGradeRequest.model_validate(
            {
                "request_id": "trace-attachment-grade-01",
                "submission_answer_id": 88,
                "attachment_download_urls": [
                    "https://business.test/api/files/7/content"
                ],
                "rubric_items": [
                    {"label": "内容", "description": "完整内容", "max_score": 10.0}
                ],
                "max_score": 10.0,
            }
        )
        agent = GradingAgent(
            attachment_client=client,
            provider_factory=lambda: provider,
            settings=settings(),
        )
        result = await agent.grade_attachment(
            request,
            trace_request_id="trace-attachment-grade-01",
        )
        prompt = provider.requests[0][0].messages[-1].content
        self.assertEqual(result.suggested_score, 10.0)
        self.assertNotIn("student-name", prompt)
        self.assertNotIn("/api/files/7", prompt)
        self.assertNotIn("submission_answer_id", prompt)
        self.assertEqual(client.calls[0][1], "trace-attachment-grade-01")
        await agent.aclose()
        self.assertTrue(client.closed)


if __name__ == "__main__":
    unittest.main()
