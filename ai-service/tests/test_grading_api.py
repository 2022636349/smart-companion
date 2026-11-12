from __future__ import annotations

import os
import unittest
from unittest.mock import patch

from fastapi.testclient import TestClient

from app.agents.grading import (
    GradingDeadlineExceededError,
    GradingModelOutputError,
    GradingRequestIdMismatchError,
)
from app.contracts.assessment import AttachmentGradeResponse
from app.main import app
from app.providers.base import (
    ModelRateLimitError,
    ModelServerError,
    StructuredOutputError,
)


class FakeGradingAgent:
    def __init__(self, *, error: Exception | None = None) -> None:
        self.error = error
        self.calls = []
        self.closed = False

    async def suggest_subjective(self, request, *, trace_request_id):
        self.calls.append(("subjective", request, trace_request_id))
        return await self._result(request.request_id)

    async def grade_attachment(self, request, *, trace_request_id):
        self.calls.append(("attachment", request, trace_request_id))
        return await self._result(request.request_id)

    async def _result(self, request_id):
        if self.error is not None:
            raise self.error
        return AttachmentGradeResponse.model_validate(
            {
                "request_id": request_id,
                "suggested_score": 9.0,
                "rubric_grades": [
                    {
                        "label": "内容",
                        "score": 9.0,
                        "max_score": 10.0,
                        "evidence": ["有效证据"],
                        "confidence": 0.8,
                        "comment": "供教师复核",
                    }
                ],
                "overall_comment": "AI 建议，不是最终成绩。",
                "warnings": [],
                "model_version": "fake-grader",
                "prompt_version": "subjective-grade-v1",
            }
        )

    async def aclose(self):
        self.closed = True


class GradingApiTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        os.environ["INTERNAL_SERVICE_TOKEN"] = "test-internal-token"
        cls.client = TestClient(app)

    @staticmethod
    def headers() -> dict[str, str]:
        return {
            "X-Internal-Token": "test-internal-token",
            "X-Request-Id": "trace-subjective-grade-api-01",
        }

    @staticmethod
    def subjective_payload() -> dict:
        return {
            "request_id": "trace-subjective-grade-api-01",
            "submission_answer_id": 9,
            "answer_text": "有效证据",
            "reference_answer": None,
            "rubric_items": [
                {"label": "内容", "description": "检查内容", "max_score": 10.0}
            ],
            "max_score": 10.0,
        }

    def test_both_frozen_routes_return_advisory_structure(self) -> None:
        subjective_agent = FakeGradingAgent()
        with patch(
            "app.routers.assessment.create_grading_agent",
            return_value=subjective_agent,
        ):
            response = self.client.post(
                "/internal/ai/subjective/suggest-grade",
                headers=self.headers(),
                json=self.subjective_payload(),
            )
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()["suggested_score"], 9.0)
        self.assertTrue(subjective_agent.closed)

        attachment_agent = FakeGradingAgent()
        attachment_payload = {
            key: value
            for key, value in self.subjective_payload().items()
            if key not in {"answer_text", "reference_answer"}
        }
        attachment_payload["attachment_download_urls"] = [
            "http://localhost:8080/api/files/7/content"
        ]
        with patch(
            "app.routers.assessment.create_grading_agent",
            return_value=attachment_agent,
        ):
            response = self.client.post(
                "/internal/ai/attachment/grade",
                headers=self.headers(),
                json=attachment_payload,
            )
        self.assertEqual(response.status_code, 200)
        self.assertEqual(attachment_agent.calls[0][0], "attachment")
        self.assertTrue(attachment_agent.closed)

    def test_failures_are_typed_and_explicitly_preserve_manual_grading(self) -> None:
        cases = [
            (
                GradingRequestIdMismatchError("mismatch"),
                422,
                "SUBJECTIVE_GRADING_REQUEST_ID_MISMATCH",
            ),
            (
                ModelRateLimitError("limited"),
                429,
                "SUBJECTIVE_GRADING_MODEL_RATE_LIMITED",
            ),
            (
                GradingModelOutputError("bad output"),
                502,
                "SUBJECTIVE_GRADING_MODEL_INVALID_RESPONSE",
            ),
            (
                ModelServerError("down"),
                503,
                "SUBJECTIVE_GRADING_UNAVAILABLE",
            ),
            (
                GradingDeadlineExceededError("timeout"),
                504,
                "SUBJECTIVE_GRADING_TIMEOUT",
            ),
        ]
        for error, status, code in cases:
            with self.subTest(code):
                agent = FakeGradingAgent(error=error)
                with patch(
                    "app.routers.assessment.create_grading_agent",
                    return_value=agent,
                ):
                    response = self.client.post(
                        "/internal/ai/subjective/suggest-grade",
                        headers=self.headers(),
                        json=self.subjective_payload(),
                    )
                self.assertEqual(response.status_code, status)
                detail = response.json()["detail"]
                self.assertEqual(detail["code"], code)
                if status >= 429:
                    self.assertIn("manual grading remains available", detail["message"])
                self.assertEqual(
                    detail["request_id"], "trace-subjective-grade-api-01"
                )
                self.assertTrue(agent.closed)

    def test_validation_errors_do_not_echo_answer_material(self) -> None:
        payload = self.subjective_payload()
        secret = "DO-NOT-ECHO-STUDENT-MATERIAL"
        payload["answer_text"] = secret
        payload["max_score"] = 11
        response = self.client.post(
            "/internal/ai/subjective/suggest-grade",
            headers=self.headers(),
            json=payload,
        )
        self.assertEqual(response.status_code, 422)
        self.assertNotIn(secret, response.text)

    def test_malformed_provider_warning_returns_typed_502_without_raw_value(self) -> None:
        raw_value = "987654321"
        agent = FakeGradingAgent(
            error=StructuredOutputError(
                "Provider rejected warning value " + raw_value
            )
        )
        with patch(
            "app.routers.assessment.create_grading_agent",
            return_value=agent,
        ):
            response = self.client.post(
                "/internal/ai/subjective/suggest-grade",
                headers=self.headers(),
                json=self.subjective_payload(),
            )

        self.assertEqual(response.status_code, 502)
        detail = response.json()["detail"]
        self.assertEqual(
            detail["code"],
            "SUBJECTIVE_GRADING_MODEL_INVALID_RESPONSE",
        )
        self.assertEqual(
            detail["request_id"],
            "trace-subjective-grade-api-01",
        )
        self.assertNotIn(raw_value, response.text)
        self.assertNotIn("AttributeError", response.text)
        self.assertNotIn("Traceback", response.text)
        self.assertTrue(agent.closed)


if __name__ == "__main__":
    unittest.main()
