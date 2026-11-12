from __future__ import annotations

import os
import unittest
from unittest.mock import patch

from fastapi.testclient import TestClient

from app.agents.assessment import (
    AssessmentParseDeadlineExceededError,
    AssessmentParseRequestIdMismatchError,
)
from app.contracts.assessment import AssessmentParseResponse
from app.core.config import AssessmentParseConfigurationError
from app.main import app
from app.providers.base import (
    ModelRateLimitError,
    ModelResponseError,
    ModelTimeoutError,
)
from app.services.document_extraction import (
    AssessmentDocumentUnsupportedError,
    AssessmentDocumentWorkerError,
)
from tests.assessment_fixtures import five_question_model_output


class FakeAssessmentAgent:
    def __init__(self, *, error: Exception | None = None) -> None:
        self.error = error
        self.calls = []
        self.closed = False

    async def parse(self, request, *, trace_request_id):
        self.calls.append((request, trace_request_id))
        if self.error is not None:
            raise self.error
        output = five_question_model_output()
        for question in output["questions"]:
            question["knowledge_point_candidates"] = [
                {
                    "knowledge_point_id": "kp-python-functions",
                    "name": "Python 函数",
                    "score": 0.9,
                }
            ]
        return AssessmentParseResponse.model_validate(
            {
                "request_id": request.request_id,
                **output,
                "model_version": "mock-assessment-model",
                "prompt_version": "assessment-parse-v1",
            }
        )

    async def aclose(self) -> None:
        self.closed = True


class AssessmentApiTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        os.environ["INTERNAL_SERVICE_TOKEN"] = "test-internal-token"
        cls.client = TestClient(app)

    @staticmethod
    def headers() -> dict[str, str]:
        return {
            "X-Internal-Token": "test-internal-token",
            "X-Request-Id": "trace-assessment-0001",
        }

    @staticmethod
    def payload() -> dict:
        return {
            "request_id": "trace-assessment-0001",
            "source_file_id": 7,
            "filename": "questions.docx",
            "download_url": "http://localhost:8080/api/files/7/content",
            "course_id": 99,
            "preferred_language": "zh-CN",
        }

    def test_success_returns_five_types_and_same_request_id(self) -> None:
        agent = FakeAssessmentAgent()
        with patch(
            "app.routers.assessment.create_assessment_parse_agent",
            return_value=agent,
        ):
            response = self.client.post(
                "/internal/ai/assessment/parse",
                headers=self.headers(),
                json=self.payload(),
            )

        self.assertEqual(response.status_code, 200)
        body = response.json()
        self.assertEqual(body["request_id"], "trace-assessment-0001")
        self.assertEqual(response.headers["X-Request-Id"], "trace-assessment-0001")
        self.assertEqual(len(body["questions"]), 5)
        self.assertEqual(
            {question["question_type"] for question in body["questions"]},
            {
                "SINGLE_CHOICE",
                "MULTIPLE_CHOICE",
                "SUBJECTIVE",
                "PROGRAMMING",
                "ATTACHMENT",
            },
        )
        self.assertTrue(agent.closed)

    def test_typed_failures_preserve_request_id(self) -> None:
        cases = [
            (
                AssessmentParseRequestIdMismatchError("mismatch"),
                422,
                "ASSESSMENT_PARSE_REQUEST_ID_MISMATCH",
            ),
            (
                AssessmentDocumentUnsupportedError("unsupported"),
                422,
                "ASSESSMENT_PARSE_UNSUPPORTED_FILE_TYPE",
            ),
            (
                ModelRateLimitError("limited", status_code=429),
                429,
                "ASSESSMENT_PARSE_MODEL_RATE_LIMITED",
            ),
            (
                ModelTimeoutError("timeout"),
                504,
                "ASSESSMENT_PARSE_MODEL_TIMEOUT",
            ),
            (
                AssessmentParseDeadlineExceededError("total timeout"),
                504,
                "ASSESSMENT_PARSE_TOTAL_TIMEOUT",
            ),
            (
                ModelResponseError("bad response"),
                502,
                "ASSESSMENT_PARSE_MODEL_INVALID_RESPONSE",
            ),
            (
                AssessmentParseConfigurationError("bad config"),
                503,
                "ASSESSMENT_PARSE_CONFIGURATION_INVALID",
            ),
            (
                AssessmentDocumentWorkerError("bad worker protocol"),
                503,
                "ASSESSMENT_PARSE_WORKER_UNAVAILABLE",
            ),
        ]
        for error, expected_status, expected_code in cases:
            with self.subTest(expected_code):
                agent = FakeAssessmentAgent(error=error)
                with patch(
                    "app.routers.assessment.create_assessment_parse_agent",
                    return_value=agent,
                ):
                    response = self.client.post(
                        "/internal/ai/assessment/parse",
                        headers=self.headers(),
                        json=self.payload(),
                    )

                self.assertEqual(response.status_code, expected_status)
                detail = response.json()["detail"]
                self.assertEqual(detail["code"], expected_code)
                self.assertEqual(detail["request_id"], "trace-assessment-0001")
                self.assertEqual(
                    response.headers["X-Request-Id"], "trace-assessment-0001"
                )
                self.assertTrue(agent.closed)

    def test_internal_headers_remain_required(self) -> None:
        response = self.client.post(
            "/internal/ai/assessment/parse",
            headers={"X-Internal-Token": "test-internal-token"},
            json=self.payload(),
        )

        self.assertEqual(response.status_code, 422)
        self.assertEqual(response.json()["detail"]["code"], "VALIDATION_ERROR")


if __name__ == "__main__":
    unittest.main()
