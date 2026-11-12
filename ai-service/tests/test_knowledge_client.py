import json
import secrets
import unittest

import httpx2
from pydantic import SecretStr

from app.clients.knowledge import (
    KnowledgeInputError,
    KnowledgeRequestError,
    KnowledgeResponseError,
    KnowledgeServerError,
    KnowledgeServiceClient,
    KnowledgeTimeoutError,
)
from app.core.config import KnowledgeServiceSettings


RUNTIME_INTERNAL_TOKEN = secrets.token_urlsafe(24)


def knowledge_settings() -> KnowledgeServiceSettings:
    return KnowledgeServiceSettings(
        base_url="https://knowledge.test",
        internal_token=SecretStr(RUNTIME_INTERNAL_TOKEN),
        timeout_seconds=1.0,
        max_related=8,
    )


def graph_context(
    *,
    course_id: int = 1,
    source_status: str = "APPROVED",
    source_rights: str = "ALLOWED",
) -> dict:
    return {
        "course_id": course_id,
        "knowledge_point": {
            "id": "kp-python-functions",
            "name": "Python 函数",
            "level": 3,
            "node_type": "knowledge_point",
            "description": "函数将可复用逻辑封装在命名代码块中。",
            "learning_objectives": ["能够定义并调用函数"],
            "review_status": "APPROVED",
            "content_version": 2,
        },
        "prerequisites": [],
        "successors": [],
        "related_nodes": [],
        "sources": [
            {
                "id": "source-python-textbook-3-1",
                "node_id": "kp-python-functions",
                "source_type": "TEXTBOOK",
                "title": "Python 程序设计",
                "resource_id": 42,
                "chapter": "3.1 函数",
                "locator": "第 3 章 3.1 节",
                "excerpt": "def 语句用于定义函数。",
                "review_status": source_status,
                "rights_status": source_rights,
                "confidence": 0.98,
            }
        ],
        "graph_version": "graph-v2026.08.09",
        "context_status": "READY",
        "warnings": [],
    }


def graph_match(*statuses: str) -> dict:
    return {
        "items": [
            {
                "node": {
                    "id": f"kp-{index}",
                    "name": f"知识点 {index}",
                    "level": 3,
                    "node_type": "knowledge_point",
                    "review_status": status,
                    "content_version": 1,
                },
                "score": 0.9 - index * 0.1,
                "matched_by": ["KEYWORD"],
                "evidence": ["命中关键词"],
                "requires_review": True,
            }
            for index, status in enumerate(statuses, 1)
        ]
    }


class KnowledgeServiceClientTest(unittest.IsolatedAsyncioTestCase):
    async def test_router_search_rejects_pending_nodes(self) -> None:
        async def handler(_: httpx2.Request) -> httpx2.Response:
            return httpx2.Response(
                200,
                json={
                    "query": "函数",
                    "items": [graph_match("PENDING")["items"][0]["node"]],
                },
            )

        async with httpx2.AsyncClient(
            transport=httpx2.MockTransport(handler)
        ) as http_client:
            client = KnowledgeServiceClient(knowledge_settings(), client=http_client)
            with self.assertRaises(KnowledgeResponseError):
                await client.search_graph(
                    "函数", course_id=1, request_id="trace-router-search-01"
                )

    async def test_router_prerequisites_reject_pending_nodes(self) -> None:
        async def handler(_: httpx2.Request) -> httpx2.Response:
            return httpx2.Response(
                200,
                json={"items": [graph_match("PENDING")["items"][0]["node"]]},
            )

        async with httpx2.AsyncClient(
            transport=httpx2.MockTransport(handler)
        ) as http_client:
            client = KnowledgeServiceClient(knowledge_settings(), client=http_client)
            with self.assertRaises(KnowledgeResponseError):
                await client.get_prerequisites(
                    "kp-python-functions",
                    course_id=1, request_id="trace-router-prereq-1",
                )

    async def test_router_recommend_injects_authenticated_student_scope(self) -> None:
        observed = {}

        async def handler(request: httpx2.Request) -> httpx2.Response:
            observed["payload"] = json.loads(request.content)
            observed["request_id"] = request.headers["X-Request-Id"]
            return httpx2.Response(
                200,
                json={"items": [], "graph_version": "graph-v1", "course_id": 1},
            )

        async with httpx2.AsyncClient(
            transport=httpx2.MockTransport(handler)
        ) as http_client:
            client = KnowledgeServiceClient(knowledge_settings(), client=http_client)
            result = await client.recommend_next(
                student_id=987654321,
                mastery=[
                    {
                        "knowledge_point_id": "kp-python-functions",
                        "score": 50,
                        "confidence": 0.9,
                    }
                ],
                course_id=1, request_id="trace-router-recommend-1",
                limit=3,
            )

        self.assertEqual(observed["payload"]["student_id"], 987654321)
        self.assertEqual(observed["payload"]["limit"], 3)
        self.assertEqual(observed["request_id"], "trace-router-recommend-1")
        self.assertEqual(result.graph_version, "graph-v1")

    async def test_question_match_uses_scene_and_keeps_only_approved_nodes(self) -> None:
        observed = {}

        async def handler(request: httpx2.Request) -> httpx2.Response:
            observed["path"] = request.url.path
            observed["course_id"] = request.url.params["course_id"]
            observed["token"] = request.headers["X-Internal-Token"]
            observed["request_id"] = request.headers["X-Request-Id"]
            observed["payload"] = json.loads(request.content)
            return httpx2.Response(
                200,
                json=graph_match("APPROVED", "PENDING", "REJECTED"),
            )

        async with httpx2.AsyncClient(
            transport=httpx2.MockTransport(handler)
        ) as http_client:
            client = KnowledgeServiceClient(knowledge_settings(), client=http_client)
            matched = await client.match_knowledge_points(
                "解释 Python 函数定义",
                course_id=1, request_id="trace-match-0001",
            )

        self.assertEqual(observed["path"], "/internal/graph/match")
        self.assertEqual(observed["course_id"], "1")
        self.assertEqual(observed["token"], RUNTIME_INTERNAL_TOKEN)
        self.assertEqual(observed["request_id"], "trace-match-0001")
        self.assertEqual(observed["payload"]["scene"], "QUESTION")
        self.assertEqual(observed["payload"]["limit"], 5)
        self.assertEqual(
            [item.knowledge_point_id for item in matched.candidates], ["kp-1"]
        )
        self.assertEqual(matched.filtered_unapproved_count, 2)
        self.assertEqual(matched.requires_review_count, 1)

    async def test_same_match_text_is_scoped_independently_for_two_courses(self) -> None:
        observed_courses: list[int] = []

        async def handler(request: httpx2.Request) -> httpx2.Response:
            course_id = int(request.url.params["course_id"])
            observed_courses.append(course_id)
            payload = graph_match("APPROVED")
            payload["items"][0]["node"]["id"] = "shared-function-id"
            payload["items"][0]["node"]["name"] = (
                "Python 函数" if course_id == 1 else "数学函数"
            )
            return httpx2.Response(200, json=payload)

        async with httpx2.AsyncClient(
            transport=httpx2.MockTransport(handler)
        ) as http_client:
            client = KnowledgeServiceClient(knowledge_settings(), client=http_client)
            python_match = await client.match_knowledge_points(
                "函数", course_id=1, request_id="trace-match-course-1"
            )
            math_match = await client.match_knowledge_points(
                "函数", course_id=2, request_id="trace-match-course-2"
            )

        self.assertEqual(observed_courses, [1, 2])
        self.assertEqual(
            python_match.candidates[0].knowledge_point_id,
            math_match.candidates[0].knowledge_point_id,
        )
        self.assertNotEqual(
            python_match.candidates[0].name,
            math_match.candidates[0].name,
        )

    async def test_rag_context_rejects_cross_course_response(self) -> None:
        async def handler(_: httpx2.Request) -> httpx2.Response:
            return httpx2.Response(200, json=graph_context(course_id=2))

        async with httpx2.AsyncClient(
            transport=httpx2.MockTransport(handler)
        ) as http_client:
            client = KnowledgeServiceClient(knowledge_settings(), client=http_client)
            with self.assertRaises(KnowledgeResponseError):
                await client.get_rag_context(
                    "kp-python-functions",
                    course_id=1,
                    request_id="trace-cross-course-rag",
                )

    async def test_recommendation_rejects_cross_course_response(self) -> None:
        async def handler(_: httpx2.Request) -> httpx2.Response:
            return httpx2.Response(
                200,
                json={"items": [], "graph_version": "graph-v1", "course_id": 2},
            )

        async with httpx2.AsyncClient(
            transport=httpx2.MockTransport(handler)
        ) as http_client:
            client = KnowledgeServiceClient(knowledge_settings(), client=http_client)
            with self.assertRaises(KnowledgeResponseError):
                await client.recommend_next(
                    course_id=1,
                    student_id=987654321,
                    mastery=[
                        {
                            "knowledge_point_id": "kp-python-functions",
                            "score": 50,
                            "confidence": 0.9,
                        }
                    ],
                    request_id="trace-cross-course-recommendation",
                )

    async def test_invalid_match_response_is_typed(self) -> None:
        async def handler(_: httpx2.Request) -> httpx2.Response:
            return httpx2.Response(200, json={"items": [{"node": {}}]})

        async with httpx2.AsyncClient(
            transport=httpx2.MockTransport(handler)
        ) as http_client:
            client = KnowledgeServiceClient(knowledge_settings(), client=http_client)
            with self.assertRaises(KnowledgeResponseError):
                await client.match_knowledge_points(
                    "Python 函数",
                    course_id=1, request_id="trace-match-0002",
                )

    async def test_invalid_local_identifier_is_rejected_before_http(self) -> None:
        calls = 0

        async def handler(_: httpx2.Request) -> httpx2.Response:
            nonlocal calls
            calls += 1
            raise AssertionError("HTTP must not be called for an invalid local id.")

        async with httpx2.AsyncClient(
            transport=httpx2.MockTransport(handler)
        ) as http_client:
            client = KnowledgeServiceClient(
                knowledge_settings(), client=http_client
            )
            with self.assertRaises(KnowledgeInputError):
                await client.get_rag_context(
                    "../kp-python-functions",
                    course_id=1, request_id="trace-request-0000",
                )

        self.assertEqual(calls, 0)

    async def test_fetches_approved_context_with_internal_headers_and_request_id(
        self,
    ) -> None:
        observed = {}

        async def handler(request: httpx2.Request) -> httpx2.Response:
            observed["path"] = request.url.path
            observed["query"] = request.url.query
            observed["token"] = request.headers["X-Internal-Token"]
            observed["request_id"] = request.headers["X-Request-Id"]
            return httpx2.Response(200, json=graph_context())

        async with httpx2.AsyncClient(
            transport=httpx2.MockTransport(handler)
        ) as http_client:
            client = KnowledgeServiceClient(
                knowledge_settings(), client=http_client
            )
            context = await client.get_rag_context(
                "kp-python-functions",
                course_id=1, request_id="trace-request-0001",
            )

        self.assertEqual(
            observed["path"],
            "/internal/graph/rag-context/kp-python-functions",
        )
        self.assertEqual(observed["query"], b"max_related=8&course_id=1")
        self.assertEqual(observed["token"], RUNTIME_INTERNAL_TOKEN)
        self.assertEqual(observed["request_id"], "trace-request-0001")
        self.assertEqual(context.context_status, "READY")
        self.assertEqual(context.sources[0].review_status, "APPROVED")

    async def test_pending_source_is_rejected_before_agent_use(self) -> None:
        async def handler(_: httpx2.Request) -> httpx2.Response:
            return httpx2.Response(
                200,
                json=graph_context(source_status="PENDING"),
            )

        async with httpx2.AsyncClient(
            transport=httpx2.MockTransport(handler)
        ) as http_client:
            client = KnowledgeServiceClient(
                knowledge_settings(), client=http_client
            )
            with self.assertRaises(KnowledgeResponseError):
                await client.get_rag_context(
                    "kp-python-functions",
                    course_id=1, request_id="trace-request-0002",
                )

    async def test_restricted_source_is_rejected_before_agent_use(self) -> None:
        async def handler(_: httpx2.Request) -> httpx2.Response:
            return httpx2.Response(
                200,
                json=graph_context(source_rights="RESTRICTED"),
            )

        async with httpx2.AsyncClient(
            transport=httpx2.MockTransport(handler)
        ) as http_client:
            client = KnowledgeServiceClient(
                knowledge_settings(), client=http_client
            )
            with self.assertRaises(KnowledgeResponseError):
                await client.get_rag_context(
                    "kp-python-functions",
                    course_id=1, request_id="trace-request-source-rights",
                )

    async def test_graph_server_failure_is_typed_without_raw_body(self) -> None:
        async def handler(_: httpx2.Request) -> httpx2.Response:
            return httpx2.Response(
                503,
                json={"detail": {"message": RUNTIME_INTERNAL_TOKEN}},
            )

        async with httpx2.AsyncClient(
            transport=httpx2.MockTransport(handler)
        ) as http_client:
            client = KnowledgeServiceClient(
                knowledge_settings(), client=http_client
            )
            with self.assertRaises(KnowledgeServerError) as raised:
                await client.get_rag_context(
                    "kp-python-functions",
                    course_id=1, request_id="trace-request-0003",
                )

        self.assertEqual(raised.exception.status_code, 503)
        self.assertNotIn(RUNTIME_INTERNAL_TOKEN, str(raised.exception))

    async def test_downstream_request_rejection_remains_distinct(self) -> None:
        async def handler(_: httpx2.Request) -> httpx2.Response:
            return httpx2.Response(422)

        async with httpx2.AsyncClient(
            transport=httpx2.MockTransport(handler)
        ) as http_client:
            client = KnowledgeServiceClient(
                knowledge_settings(), client=http_client
            )
            with self.assertRaises(KnowledgeRequestError) as raised:
                await client.get_rag_context(
                    "kp-python-functions",
                    course_id=1, request_id="trace-request-0005",
                )

        self.assertEqual(raised.exception.status_code, 422)

    async def test_graph_timeout_is_typed(self) -> None:
        async def handler(request: httpx2.Request) -> httpx2.Response:
            raise httpx2.ReadTimeout("mock timeout", request=request)

        async with httpx2.AsyncClient(
            transport=httpx2.MockTransport(handler)
        ) as http_client:
            client = KnowledgeServiceClient(
                knowledge_settings(), client=http_client
            )
            with self.assertRaises(KnowledgeTimeoutError):
                await client.get_rag_context(
                    "kp-python-functions",
                    course_id=1, request_id="trace-request-0004",
                )

    def test_internal_token_is_redacted_from_settings_repr(self) -> None:
        self.assertNotIn(RUNTIME_INTERNAL_TOKEN, repr(knowledge_settings()))


if __name__ == "__main__":
    unittest.main()
