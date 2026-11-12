import os
import unittest
from unittest.mock import patch

from fastapi.testclient import TestClient

from app.agents.graph_ingestion import GraphReviewerOutputError
from app.contracts.graph_ingestion import GraphExtractReviewResponse
from app.main import app


class FakeAgent:
    def __init__(self, *, error=None) -> None:
        self.error = error
        self.calls = []
        self.closed = False

    async def extract_and_review(self, request, *, trace_request_id):
        self.calls.append((request, trace_request_id))
        if self.error:
            raise self.error
        return GraphExtractReviewResponse.model_validate(
            {
                "request_id": request.request_id,
                "course_id": request.course_id,
                "job_id": request.job_id,
                "nodes": [
                    {
                        "candidate_id": "node-function",
                        "id": "kp-function",
                        "name": "函数定义",
                        "level": 0,
                        "node_type": "course",
                        "parent_id": None,
                        "aliases": [],
                        "keywords": ["def"],
                        "learning_objectives": [],
                        "common_misconceptions": [],
                        "example_code": [],
                        "rights_status": "ALLOWED",
                        "evidence": ["函数使用 def 定义。"],
                        "extractor_confidence": 0.96,
                        "reviewer_confidence": 0.97,
                        "review_status": "PENDING",
                        "decision_status": "PENDING",
                        "review_lane": "FAST_TRACK",
                    }
                ],
                "edges": [],
                "sources": [
                    {
                        "candidate_id": "source-function",
                        "id": "source-function-v1",
                        "node_id": "kp-function",
                        "source_type": request.source.source_type,
                        "title": "Python 程序设计",
                        "resource_id": request.source.resource_id,
                        "file_id": request.source_file_id,
                        "locator": "lines 1",
                        "excerpt": "函数使用 def 定义。",
                        "rights_status": "ALLOWED",
                        "extractor_confidence": 0.99,
                        "reviewer_confidence": 0.99,
                        "review_status": "PENDING",
                        "decision_status": "PENDING",
                        "review_lane": "FAST_TRACK",
                    }
                ],
                "warnings": [],
                "models": {
                    "extractor": {"provider": "mock", "model": "mock-extractor"},
                    "reviewer": {"provider": "mock", "model": "mock-reviewer"},
                },
                "prompts": {
                    "extractor": {
                        "version": "graph-extractor-v2",
                        "sha256": "a" * 64,
                    },
                    "reviewer": {
                        "version": "graph-reviewer-v1",
                        "sha256": "b" * 64,
                    },
                },
            }
        )

    async def aclose(self):
        self.closed = True


class GraphIngestionApiTest(unittest.TestCase):
    def setUp(self):
        self.client = TestClient(app)

    @staticmethod
    def headers(request_id="graph-request-0001"):
        return {
            "X-Internal-Token": os.getenv(
                "INTERNAL_SERVICE_TOKEN", "local-development-internal-token-2026"
            ),
            "X-Request-Id": request_id,
        }

    @staticmethod
    def payload():
        return {
            "request_id": "graph-request-0001",
            "course_id": 1,
            "course_name": "Python程序设计",
            "job_id": 7,
            "source_file_id": 42,
            "filename": "chapter.txt",
            "download_url": "http://localhost:8080/api/files/42/content",
            "source": {
                "source_type": "TEXTBOOK",
                "title": "Python 程序设计",
                "file_id": 42,
                "copyright_status": "AUTHORIZED",
            },
            "preferred_language": "zh-CN",
        }

    def test_success_exposes_drafts_and_closes_agent(self):
        agent = FakeAgent()
        with patch(
            "app.routers.graph_ingestion.create_graph_ingestion_agent",
            return_value=agent,
        ):
            response = self.client.post(
                "/internal/ai/graph/extract-review",
                headers=self.headers(),
                json=self.payload(),
            )
        self.assertEqual(response.status_code, 200)
        body = response.json()
        self.assertEqual(body["course_id"], 1)
        self.assertEqual(body["sources"][0]["file_id"], 42)
        self.assertEqual(body["nodes"][0]["review_status"], "PENDING")
        self.assertEqual(body["models"]["reviewer"]["provider"], "mock")
        self.assertTrue(agent.closed)

    def test_reviewer_invalid_response_is_typed_and_sanitized(self):
        agent = FakeAgent(error=GraphReviewerOutputError("raw provider content"))
        with patch(
            "app.routers.graph_ingestion.create_graph_ingestion_agent",
            return_value=agent,
        ):
            response = self.client.post(
                "/internal/ai/graph/extract-review",
                headers=self.headers(),
                json=self.payload(),
            )
        self.assertEqual(response.status_code, 502)
        self.assertEqual(
            response.json()["detail"]["code"],
            "GRAPH_REVIEWER_INVALID_RESPONSE",
        )
        self.assertNotIn("raw provider content", response.text)

    def test_contract_rejects_mismatched_source_file_identity(self):
        payload = self.payload()
        payload["source"]["file_id"] = 99
        response = self.client.post(
            "/internal/ai/graph/extract-review",
            headers=self.headers(),
            json=payload,
        )
        self.assertEqual(response.status_code, 422)
        self.assertEqual(response.json()["detail"]["code"], "VALIDATION_ERROR")

    def test_teaching_resource_upload_with_file_only_is_accepted(self):
        agent = FakeAgent()
        payload = self.payload()
        payload["source"]["source_type"] = "TEACHING_RESOURCE"
        payload["source"].pop("resource_id", None)
        payload["source"].pop("file_id", None)
        with patch(
            "app.routers.graph_ingestion.create_graph_ingestion_agent",
            return_value=agent,
        ):
            response = self.client.post(
                "/internal/ai/graph/extract-review",
                headers=self.headers(),
                json=payload,
            )

        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()["sources"][0]["source_type"], "TEACHING_RESOURCE")
        self.assertIsNone(response.json()["sources"][0]["resource_id"])
        self.assertEqual(response.json()["sources"][0]["file_id"], 42)

    def test_fastapi_schema_uses_frozen_operation_and_models(self):
        schema = app.openapi()
        operation = schema["paths"]["/internal/ai/graph/extract-review"]["post"]
        self.assertEqual(operation["operationId"], "extractAndReviewGraphCandidates")
        self.assertEqual(
            operation["requestBody"]["content"]["application/json"]["schema"]["$ref"],
            "#/components/schemas/GraphExtractReviewRequest",
        )
        self.assertEqual(
            operation["responses"]["200"]["content"]["application/json"]["schema"]["$ref"],
            "#/components/schemas/GraphExtractReviewResponse",
        )
        components = schema["components"]["schemas"]
        self.assertTrue(
            {"course_id", "course_name"}.issubset(
                components["GraphExtractReviewRequest"]["required"]
            )
        )
        self.assertEqual(
            set(components["GraphExtractReviewResponse"]["required"]),
            {"request_id", "job_id", "nodes", "edges", "sources", "warnings", "models", "prompts", "course_id"},
        )
        self.assertEqual(components["GraphExtractReviewResponse"]["properties"]["nodes"]["minItems"], 1)
        self.assertEqual(components["GraphExtractReviewResponse"]["properties"]["sources"]["minItems"], 1)
        source_schema = components["GraphDraftSource"]
        self.assertIn("rights_status", source_schema["required"])
        self.assertEqual(
            source_schema["properties"]["rights_status"]["enum"],
            ["ALLOWED", "RESTRICTED"],
        )
        copyright_schema = components["GraphExtractSource"]["properties"]["copyright_status"]
        self.assertEqual(
            copyright_schema["anyOf"][0]["enum"],
            ["CONFIRMED", "AUTHORIZED", "PUBLIC_DOMAIN", "UNKNOWN"],
        )


if __name__ == "__main__":
    unittest.main()
