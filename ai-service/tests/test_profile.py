import os
import unittest
from unittest.mock import patch

from fastapi.testclient import TestClient

from app.agents.profile import ProfileModelOutputError, ProfileSummaryAgent
from app.contracts.profile import ProfileProviderOutput, ProfileSummarizeRequest, ProfileSummarizeResponse
from app.main import app
from app.prompts.profile import PROFILE_SUMMARY_PROMPT_VERSION
from app.providers.base import ModelCallMetadata, ModelResponse, TokenUsage


def payload() -> dict:
    return {
        "request_id": "profile-request-0001",
        "course_id": 1,
        "user_id": 987654321,
        "previous_summary": "姓名：张三 邮箱 zhang@example.test",
        "evidence": [
            {
                "source_type": "ASSESSMENT",
                "source_id": 7654321,
                "knowledge_point_id": "kp-python-functions",
                "content": "姓名：张三\n对默认参数顺序仍容易混淆。",
            }
        ],
    }


def output(*, kp: str = "kp-python-functions") -> dict:
    return {
        "summary": "姓名：张三；需要继续练习函数参数。",
        "cognitive_labels": [
            {"knowledge_point_id": kp, "status": "WEAK", "confidence": 0.8, "evidence": "默认参数顺序混淆"}
        ],
        "memory_candidates": [
            {
                "memory_type": "MISCONCEPTION",
                "knowledge_point_id": kp,
                "title": "函数参数",
                "content": "学生对默认参数顺序容易混淆。",
                "confidence": 0.8,
                "editable": True,
                "deletable": True,
            }
        ],
        "resource_suggestions": [
            {"knowledge_point_id": kp, "reason": "需要参数练习", "preferred_resource_types": ["CODE", "EXERCISE"]}
        ],
    }


class FakeProvider:
    provider_name = "fake"
    model_name = "fake-summary-v1"

    def __init__(self, content: dict | None = None) -> None:
        self.content = content or output()
        self.requests = []
        self.closed = False

    async def generate(self, request, *, output_model=None):
        self.requests.append((request, output_model))
        return ModelResponse(
            text="json",
            structured_content=self.content,
            metadata=ModelCallMetadata(
                provider="fake",
                model=self.model_name,
                token_usage=TokenUsage(prompt_tokens=1, completion_tokens=1, total_tokens=2),
                finish_reason="stop",
                latency_ms=1,
                provider_request_id=None,
                prompt_version=PROFILE_SUMMARY_PROMPT_VERSION,
                attempts=1,
            ),
        )

    async def aclose(self):
        self.closed = True


class ProfileAgentTest(unittest.IsolatedAsyncioTestCase):
    def test_provider_summary_length_boundary(self) -> None:
        at_limit = output()
        at_limit["summary"] = "x" * 10_000
        self.assertEqual(len(ProfileProviderOutput.model_validate(at_limit).summary), 10_000)

        over_limit = output()
        over_limit["summary"] = "x" * 10_001
        with self.assertRaises(ValueError):
            ProfileProviderOutput.model_validate(over_limit)

    async def test_strict_candidates_and_minimum_disclosure(self) -> None:
        provider = FakeProvider()
        agent = ProfileSummaryAgent(provider_factory=lambda: provider)
        response = await agent.summarize(ProfileSummarizeRequest.model_validate(payload()), trace_request_id="profile-request-0001")
        prompt = "\n".join(message.content for message in provider.requests[0][0].messages)
        self.assertNotIn("987654321", prompt)
        self.assertNotIn("7654321", prompt)
        self.assertNotIn("zhang@example.test", prompt)
        self.assertNotIn("张三", prompt)
        self.assertEqual(provider.requests[0][1], ProfileProviderOutput)
        self.assertNotIn("张三", response.summary)
        self.assertTrue(response.memory_candidates)
        self.assertTrue(response.resource_suggestions)
        self.assertTrue(provider.closed)

    async def test_invented_knowledge_point_is_rejected(self) -> None:
        provider = FakeProvider(output(kp="invented-kp"))
        with self.assertRaises(ProfileModelOutputError):
            await ProfileSummaryAgent(provider_factory=lambda: provider).summarize(
                ProfileSummarizeRequest.model_validate(payload()),
                trace_request_id="profile-request-0001",
            )

    def test_evidence_count_and_single_item_boundaries(self) -> None:
        base = payload()
        base["evidence"] = [
            {"source_type": "TUTOR", "source_id": i, "knowledge_point_id": "kp", "content": "x" * 4_000}
            for i in range(1, 51)
        ]
        self.assertEqual(len(ProfileSummarizeRequest.model_validate(base).evidence), 50)
        base["evidence"].append(base["evidence"][0])
        with self.assertRaises(ValueError):
            ProfileSummarizeRequest.model_validate(base)
        one = payload()
        one["evidence"][0]["content"] = "x" * 4_001
        with self.assertRaises(ValueError):
            ProfileSummarizeRequest.model_validate(one)


class FakeProfileAgent:
    async def summarize(self, request, *, trace_request_id):
        return ProfileSummarizeResponse(
            course_id=request.course_id,
            request_id=request.request_id,
            **output(),
            model_version="fake-summary-v1",
            prompt_version=PROFILE_SUMMARY_PROMPT_VERSION,
        )


class ProfileApiTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        os.environ["INTERNAL_SERVICE_TOKEN"] = "test-internal-token"
        cls.client = TestClient(app)

    def test_success_contract_shape(self) -> None:
        with patch("app.routers.profile.ProfileSummaryAgent", return_value=FakeProfileAgent()):
            response = self.client.post(
                "/internal/ai/profile/summarize",
                headers={"X-Internal-Token": "test-internal-token", "X-Request-Id": "profile-request-0001"},
                json=payload(),
            )
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()["course_id"], 1)
        self.assertIn("memory_candidates", response.json())
        self.assertIn("resource_suggestions", response.json())


if __name__ == "__main__":
    unittest.main()
