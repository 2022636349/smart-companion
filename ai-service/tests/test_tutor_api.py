import os
import unittest
from unittest.mock import patch

from fastapi.testclient import TestClient

from app.agents.tutor import TutorGraphContextInsufficientError
from app.clients.knowledge import (
    KnowledgeInputError,
    KnowledgeRequestError,
    KnowledgeServerError,
)
from app.contracts.tutor import TutorChatRequest, TutorChatResponse
from app.main import app
from app.providers.base import ModelServerError


class FakeTutorAgent:
    def __init__(self, *, error: Exception | None = None) -> None:
        self.error = error
        self.calls = []
        self.closed = False

    async def chat(self, request, *, trace_request_id):
        self.calls.append((request, trace_request_id))
        if self.error is not None:
            raise self.error
        return TutorChatResponse.model_validate(
            {
                "request_id": request.request_id,
                "course_id": request.course_id,
                "knowledge_point_id": request.knowledge_point_id,
                "answer": "使用 def 语句定义函数。",
                "blocks": [
                    {
                        "type": "markdown",
                        "content": "`def name(args):` 用于定义函数。",
                    }
                ],
                "activities": [],
                "teaching_plan": {
                    "goal": "基于已审核图谱讲解函数。",
                    "modalities": ["EXPLANATION"],
                    "specialist_agents": ["EXPLANATION"],
                },
                "specialist_trace": [
                    {
                        "agent": "EXPLANATION",
                        "status": "SUCCEEDED",
                        "duration_ms": 1,
                        "artifact_count": 0,
                    }
                ],
                "sources": [
                    {
                        "id": "source-python-textbook-3-1",
                        "node_id": "kp-python-functions",
                        "source_type": "TEXTBOOK",
                        "title": "Python 程序设计",
                        "resource_id": 42,
                        "locator": "第 3 章 3.1 节",
                        "excerpt": "def 语句用于定义函数。",
                        "review_status": "APPROVED",
                        "rights_status": "ALLOWED",
                        "reviewed": True,
                    }
                ],
                "cognitive_labels": [],
                "memory_ids_used": [],
                "memory_index_epoch": None,
                "memory_rebuild_required": False,
                "agent_trace": {
                    "requested_mode": "FIXED",
                    "effective_mode": "FIXED",
                    "tool_invocations": [],
                    "total_steps": 0,
                },
                "model_version": "mock-tutor-model-v2",
                "prompt_version": "tutor-graph-rag-v4",
            }
        )

    async def aclose(self) -> None:
        self.closed = True


class TutorApiTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        os.environ["INTERNAL_SERVICE_TOKEN"] = "test-internal-token"
        cls.client = TestClient(app)

    @staticmethod
    def headers() -> dict[str, str]:
        return {
            "X-Internal-Token": "test-internal-token",
            "X-Request-Id": "trace-request-0001",
        }

    @staticmethod
    def payload() -> dict:
        return {
            "request_id": "trace-request-0001",
            "course_id": 1,
            "course_name": "Python程序设计",
            "enabled_activity_types": ["EXPLAIN", "QUIZ", "CODE", "VISUAL"],
            "user_id": 1,
            "session_id": 2,
            "knowledge_point_id": "kp-python-functions",
            "message": "请解释函数定义语法。",
            "history": [],
            "student_context": {
                "entry_source": "GRAPH",
                "personalization_enabled": False,
            },
            "teaching_mode": "EXPLAIN",
        }

    def test_success_returns_complete_spring_client_shape(self) -> None:
        agent = FakeTutorAgent()
        with patch("app.routers.tutor.create_tutor_agent", return_value=agent):
            response = self.client.post(
                "/internal/ai/tutor/chat",
                headers=self.headers(),
                json=self.payload(),
            )

        self.assertEqual(response.status_code, 200)
        body = response.json()
        self.assertEqual(body["request_id"], "trace-request-0001")
        self.assertEqual(body["course_id"], 1)
        for field in (
            "answer",
            "blocks",
            "sources",
            "cognitive_labels",
            "memory_ids_used",
            "memory_index_epoch",
            "memory_rebuild_required",
            "agent_trace",
            "model_version",
            "prompt_version",
        ):
            self.assertIn(field, body)
        self.assertTrue(body["blocks"])
        self.assertTrue(body["sources"])
        self.assertIsNone(body["memory_index_epoch"])
        self.assertFalse(body["memory_rebuild_required"])
        self.assertEqual(body["sources"][0]["review_status"], "APPROVED")
        self.assertEqual(body["sources"][0]["rights_status"], "ALLOWED")
        self.assertIs(body["sources"][0]["reviewed"], True)
        self.assertEqual(agent.calls[0][1], "trace-request-0001")
        self.assertTrue(agent.closed)
        self.assertEqual(response.headers["X-Request-Id"], "trace-request-0001")

        required = set(
            self.client.app.openapi()["components"]["schemas"]["TutorChatResponse"]["required"]
        )
        self.assertIn("memory_index_epoch", required)
        self.assertIn("memory_rebuild_required", required)
        self.assertIn("agent_trace", required)
        schemas = self.client.app.openapi()["components"]["schemas"]
        self.assertIn("rights_status", schemas["TutorSource"]["required"])
        self.assertEqual(
            schemas["TutorSource"]["properties"]["rights_status"]["const"],
            "ALLOWED",
        )
        request_properties = schemas["TutorChatRequest"]["properties"]
        self.assertTrue(
            {"course_id", "course_name", "enabled_activity_types"}.issubset(
                schemas["TutorChatRequest"]["required"]
            )
        )
        self.assertTrue(request_properties["enabled_activity_types"]["uniqueItems"])
        self.assertIn("agent_mode", request_properties)
        self.assertIn("mastery_context", request_properties)
        self.assertEqual(
            set(schemas["TutorAgentTrace"]["required"]),
            {"requested_mode", "effective_mode", "tool_invocations", "total_steps"},
        )

    def test_history_accepts_an_assistant_answer_at_response_limit(self) -> None:
        payload = self.payload()
        payload["history"] = [{"role": "assistant", "content": "答" * 20_000}]
        agent = FakeTutorAgent()

        with patch("app.routers.tutor.create_tutor_agent", return_value=agent):
            response = self.client.post(
                "/internal/ai/tutor/chat",
                headers=self.headers(),
                json=payload,
            )

        self.assertEqual(response.status_code, 200)
        self.assertEqual(len(agent.calls[0][0].history[0].content), 20_000)

    def test_history_total_prompt_budget_is_exactly_40000_characters(self) -> None:
        payload = self.payload()
        payload["history"] = [
            {"role": "user", "content": "问" * 20_000},
            {"role": "assistant", "content": "答" * 20_000},
        ]

        request = TutorChatRequest.model_validate(payload)

        self.assertEqual(sum(len(item.content) for item in request.history), 40_000)

    def test_history_over_prompt_budget_is_rejected_before_agent_creation(self) -> None:
        payload = self.payload()
        payload["history"] = [
            {"role": "user", "content": "问" * 20_000},
            {"role": "assistant", "content": "答" * 20_000},
            {"role": "user", "content": "?"},
        ]

        with patch("app.routers.tutor.create_tutor_agent") as factory:
            response = self.client.post(
                "/internal/ai/tutor/chat",
                headers=self.headers(),
                json=payload,
            )

        self.assertEqual(response.status_code, 422)
        self.assertEqual(response.json()["detail"]["code"], "VALIDATION_ERROR")
        factory.assert_not_called()

    def test_insufficient_graph_has_explicit_typed_response(self) -> None:
        agent = FakeTutorAgent(
            error=TutorGraphContextInsufficientError(
                ["知识点缺少已审核的可追溯来源"]
            )
        )
        with patch("app.routers.tutor.create_tutor_agent", return_value=agent):
            response = self.client.post(
                "/internal/ai/tutor/chat",
                headers=self.headers(),
                json=self.payload(),
            )

        self.assertEqual(response.status_code, 424)
        self.assertEqual(
            response.json()["detail"]["code"],
            "TUTOR_GRAPH_CONTEXT_INSUFFICIENT",
        )
        self.assertTrue(agent.closed)

    def test_graph_failure_is_translated_to_typed_503(self) -> None:
        agent = FakeTutorAgent(
            error=KnowledgeServerError("mock graph failure", status_code=503)
        )
        with patch("app.routers.tutor.create_tutor_agent", return_value=agent):
            response = self.client.post(
                "/internal/ai/tutor/chat",
                headers=self.headers(),
                json=self.payload(),
            )

        self.assertEqual(response.status_code, 503)
        self.assertEqual(
            response.json()["detail"]["code"],
            "TUTOR_GRAPH_SERVICE_UNAVAILABLE",
        )

    def test_local_knowledge_point_error_is_translated_to_typed_422(self) -> None:
        agent = FakeTutorAgent(
            error=KnowledgeInputError("mock invalid knowledge point")
        )
        with patch("app.routers.tutor.create_tutor_agent", return_value=agent):
            response = self.client.post(
                "/internal/ai/tutor/chat",
                headers=self.headers(),
                json=self.payload(),
            )

        self.assertEqual(response.status_code, 422)
        self.assertEqual(
            response.json()["detail"]["code"],
            "TUTOR_KNOWLEDGE_POINT_ID_INVALID",
        )

    def test_downstream_graph_rejection_remains_typed_502(self) -> None:
        agent = FakeTutorAgent(
            error=KnowledgeRequestError("mock downstream rejection", status_code=422)
        )
        with patch("app.routers.tutor.create_tutor_agent", return_value=agent):
            response = self.client.post(
                "/internal/ai/tutor/chat",
                headers=self.headers(),
                json=self.payload(),
            )

        self.assertEqual(response.status_code, 502)
        self.assertEqual(
            response.json()["detail"]["code"],
            "TUTOR_GRAPH_SERVICE_REJECTED_REQUEST",
        )

    def test_model_failure_is_translated_to_typed_503(self) -> None:
        agent = FakeTutorAgent(
            error=ModelServerError("mock model failure", status_code=503)
        )
        with patch("app.routers.tutor.create_tutor_agent", return_value=agent):
            response = self.client.post(
                "/internal/ai/tutor/chat",
                headers=self.headers(),
                json=self.payload(),
            )

        self.assertEqual(response.status_code, 503)
        self.assertEqual(
            response.json()["detail"]["code"],
            "TUTOR_MODEL_UNAVAILABLE",
        )


if __name__ == "__main__":
    unittest.main()
