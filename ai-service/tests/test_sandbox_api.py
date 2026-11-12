from __future__ import annotations

import unittest
import os
from unittest.mock import AsyncMock, patch

from fastapi.testclient import TestClient

from app.contracts.sandbox import (
    SANDBOX_TEST_PAYLOAD_MAX_BYTES,
    SANDBOX_TEST_TEXT_MAX_CHARS,
    SandboxRunResponse,
    SandboxStatus,
)
from app.main import app
from app.sandbox.runner import create_sandbox_runner
from app.sandbox.runner import (
    SandboxBusyError,
    SandboxConfigurationError,
    SandboxTimeoutError,
)


TOKEN = "test-internal-token"
REQUEST_ID = "sandbox-api-request-0001"


def payload() -> dict:
    return {
        "request_id": REQUEST_ID,
        "execution_mode": "GRADING",
        "language": "PYTHON_3_11",
        "source_code": "print(input())",
        "test_cases": [
            {
                "test_case_id": 1,
                "input_data": "secret-hidden-input\n",
                "expected_output": "secret-hidden-output\n",
                "score": 1,
                "visibility": "HIDDEN",
                "compare_mode": "EXACT",
            }
        ],
        "limits": {"time_ms": 500, "memory_mb": 64, "output_kb": 8},
    }


def headers(request_id: str = REQUEST_ID) -> dict[str, str]:
    return {"X-Internal-Token": TOKEN, "X-Request-Id": request_id}


class SandboxApiTests(unittest.TestCase):
    def setUp(self) -> None:
        self.previous_token = os.environ.get("INTERNAL_SERVICE_TOKEN")
        os.environ["INTERNAL_SERVICE_TOKEN"] = TOKEN
        self.client = TestClient(app)

    def tearDown(self) -> None:
        if self.previous_token is None:
            os.environ.pop("INTERNAL_SERVICE_TOKEN", None)
        else:
            os.environ["INTERNAL_SERVICE_TOKEN"] = self.previous_token

    def test_request_id_mismatch_and_authentication(self) -> None:
        mismatch = self.client.post(
            "/internal/code/run",
            json=payload(),
            headers=headers("different-request-0001"),
        )
        self.assertEqual(mismatch.status_code, 422)
        self.assertEqual(mismatch.json()["detail"]["code"], "SANDBOX_REQUEST_ID_MISMATCH")
        unauthorized = self.client.post(
            "/internal/code/run",
            json=payload(),
            headers={"X-Request-Id": REQUEST_ID},
        )
        self.assertEqual(unauthorized.status_code, 401)

    def test_validation_error_never_echoes_hidden_test_values(self) -> None:
        invalid = payload()
        invalid["test_cases"][0]["visibility"] = "NOT_A_VISIBILITY"
        response = self.client.post(
            "/internal/code/run", json=invalid, headers=headers()
        )
        self.assertEqual(response.status_code, 422)
        self.assertNotIn("secret-hidden-input", response.text)
        self.assertNotIn("secret-hidden-output", response.text)
        self.assertNotIn('"input"', response.text)

    def test_test_case_text_and_combined_payload_are_bounded(self) -> None:
        too_long = payload()
        too_long["test_cases"][0]["input_data"] = "x" * (
            SANDBOX_TEST_TEXT_MAX_CHARS + 1
        )
        response = self.client.post(
            "/internal/code/run", json=too_long, headers=headers()
        )
        self.assertEqual(response.status_code, 422)
        self.assertNotIn("x" * 128, response.text)

        oversized_total = payload()
        per_case = SANDBOX_TEST_PAYLOAD_MAX_BYTES // 100 // 2 + 1
        oversized_total["test_cases"] = [
            {
                **oversized_total["test_cases"][0],
                "test_case_id": index + 1,
                "input_data": "输" * per_case,
                "expected_output": "出" * per_case,
            }
            for index in range(100)
        ]
        response = self.client.post(
            "/internal/code/run", json=oversized_total, headers=headers()
        )
        self.assertEqual(response.status_code, 422)
        self.assertNotIn("输" * 128, response.text)

    def test_runtime_failures_are_typed_not_fake_success(self) -> None:
        cases = (
            (SandboxBusyError(), 429, "SANDBOX_CAPACITY_EXHAUSTED"),
            (SandboxConfigurationError(), 503, "SANDBOX_UNAVAILABLE"),
            (SandboxTimeoutError(), 504, "SANDBOX_TOTAL_TIMEOUT"),
        )
        for error, status_code, code in cases:
            runner = AsyncMock()
            runner.run.side_effect = error
            with patch("app.routers.sandbox.create_sandbox_runner", return_value=runner):
                response = self.client.post(
                    "/internal/code/run", json=payload(), headers=headers()
                )
            self.assertEqual(response.status_code, status_code)
            self.assertEqual(response.json()["detail"]["code"], code)
            self.assertNotIn("secret-hidden-input", response.text)
            self.assertNotIn("secret-hidden-output", response.text)

    def test_public_and_grading_requests_use_separate_capacity_pools(self) -> None:
        public = create_sandbox_runner("PUBLIC_RUN")
        grading = create_sandbox_runner("GRADING")

        self.assertIsNot(public, grading)
        self.assertIs(public, create_sandbox_runner("PUBLIC_RUN"))
        self.assertIs(grading, create_sandbox_runner("GRADING"))

    def test_success_shape_and_hidden_fields_are_absent(self) -> None:
        runner = AsyncMock()
        runner.run.return_value = SandboxRunResponse(
            request_id=REQUEST_ID,
            status=SandboxStatus.WRONG_ANSWER,
            achieved_score=0,
            total_score=1,
            passed_count=0,
            total_count=1,
            test_results=[
                {
                    "test_case_id": 1,
                    "status": "WRONG_ANSWER",
                    "score": 0,
                    "max_score": 1,
                    "time_ms": 2,
                    "memory_kb": 0,
                    "visibility": "HIDDEN",
                    "actual_output": "secret-hidden-input",
                    "expected_output": "secret-hidden-output",
                    "error_message": "secret-error",
                }
            ],
        )
        with patch("app.routers.sandbox.create_sandbox_runner", return_value=runner):
            response = self.client.post(
                "/internal/code/run", json=payload(), headers=headers()
            )
        self.assertEqual(response.status_code, 200)
        body = response.json()
        self.assertEqual(body["request_id"], REQUEST_ID)
        hidden = body["test_results"][0]
        self.assertNotIn("actual_output", hidden)
        self.assertNotIn("expected_output", hidden)
        self.assertNotIn("error_message", hidden)

    def test_code_explanation_is_deterministic_and_never_echoes_raw_values(self) -> None:
        response = self.client.post(
            "/internal/ai/code/explain-result",
            json={
                "request_id": REQUEST_ID,
                "question_id": 1,
                "knowledge_point_ids": ["kp-1"],
                "source_code": "print('source-secret')",
                "failures": [
                    {
                        "position": 1,
                        "status": "WRONG_ANSWER",
                        "public_input": "public-input-secret",
                        "public_expected_output": "public-expected-secret",
                        "actual_output": "actual-secret",
                        "error_message": "trace-secret",
                    }
                ],
            },
            headers=headers(),
        )
        self.assertEqual(response.status_code, 200)
        body = response.json()
        self.assertEqual(body["model_version"], "deterministic-no-model-v1")
        self.assertIn("WRONG_ANSWER", body["feedback"])
        for secret in (
            "source-secret",
            "public-input-secret",
            "public-expected-secret",
            "actual-secret",
            "trace-secret",
        ):
            self.assertNotIn(secret, response.text)

    def test_code_explanation_does_not_echo_unknown_status(self) -> None:
        response = self.client.post(
            "/internal/ai/code/explain-result",
            json={
                "request_id": REQUEST_ID,
                "question_id": 1,
                "knowledge_point_ids": [],
                "source_code": "print(1)",
                "failures": [
                    {"position": 1, "status": "status-secret-not-an-enum"}
                ],
            },
            headers=headers(),
        )
        self.assertEqual(response.status_code, 200)
        self.assertIn("UNKNOWN", response.json()["feedback"])
        self.assertNotIn("status-secret", response.text)


if __name__ == "__main__":
    unittest.main()
