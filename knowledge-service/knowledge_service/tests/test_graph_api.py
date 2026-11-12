from __future__ import annotations

import json
import os
from pathlib import Path

import pytest


os.environ["GRAPH_BACKEND"] = "json"
os.environ["GRAPH_FILE"] = "knowledge_graph_v2.json"
os.environ["INTERNAL_SERVICE_TOKEN"] = "test-internal-token"
os.environ["GRAPH_ALLOW_PENDING"] = "false"

from fastapi.testclient import TestClient  # noqa: E402

from knowledge_service.app.api import graph_routes  # noqa: E402
from knowledge_service.app.repository.json_repo import JsonGraphRepository  # noqa: E402
from knowledge_service.app.repository.neo4j_repo import (  # noqa: E402
    GraphQueryBudgetExceeded,
)
from knowledge_service.app.services.graph_service import GraphService  # noqa: E402
from knowledge_service.main import app  # noqa: E402


INTERNAL_HEADERS = {
    "X-Internal-Token": "test-internal-token",
    "X-Request-Id": "test-request-001",
}


def _node(
    node_id: str,
    name: str,
    review_status: str = "APPROVED",
    *,
    parent_id: str | None = None,
    description: str | None = "已审核定义",
    level: int = 3,
    aliases: list[str] | None = None,
    keywords: list[str] | None = None,
) -> dict:
    return {
        "id": node_id,
        "name": name,
        "level": level,
        "node_type": "course" if level == 0 else "knowledge_point",
        "parent_id": parent_id,
        "description": description,
        "aliases": aliases or [],
        "keywords": keywords or [name],
        "difficulty": "BASIC" if level else None,
        "importance": 3 if level else None,
        "estimated_minutes": 15 if level else None,
        "learning_objectives": [f"掌握{name}"] if description else [],
        "common_misconceptions": [],
        "example_code": [],
        "review_status": review_status,
        "content_version": 1,
    }


def _edge(
    source: str,
    target: str,
    relation: str = "REQUIRES",
    review_status: str = "APPROVED",
) -> dict:
    return {
        "source": source,
        "target": target,
        "relation": relation,
        "review_status": review_status,
        "evidence": [f"{source}->{target}"],
        "confidence": 1.0,
    }


def _graph() -> dict:
    nodes = [
        _node("root", "Python", level=0),
        _node("pre-2", "二级先修"),
        _node("pre-1", "直接先修"),
        _node(
            "center",
            "中心知识点",
            parent_id="root",
            aliases=["核心概念"],
            keywords=["中心词", "共享关键词"],
        ),
        _node("successor", "后续知识点", description=None),
        _node("related-a", "相关甲", keywords=["共享关键词"]),
        _node("related-b", "相关乙", keywords=["共享关键词"]),
        _node(
            "pending",
            "Pending Secret",
            "PENDING",
            aliases=["绝密别名"],
        ),
        _node(
            "rejected",
            "Rejected Secret",
            "REJECTED",
            aliases=["拒绝别名"],
        ),
    ]
    edges = [
        _edge("pre-2", "pre-1"),
        _edge("pre-1", "center"),
        _edge("pending", "center"),
        _edge("center", "successor"),
        _edge("center", "related-a", "RELATED"),
        _edge("center", "related-b", "RELATED"),
        _edge("center", "rejected", "RELATED"),
        _edge("pre-2", "center", review_status="PENDING"),
    ]
    return {
        "metadata": {
            "name": "contract fixture",
            "version": "2.1.0-test",
            "node_count": len(nodes),
            "edge_count": len(edges),
            "created_at": "2026-08-01",
            "updated_at": "2026-08-09T12:00:00+08:00",
            "requires_semantics": "source_is_prerequisite_of_target",
        },
        "nodes": nodes,
        "edges": edges,
        "source_sections": [
            {
                "id": "source-approved",
                "node_id": "center",
                "source_type": "TEXTBOOK",
                "title": "Python程序设计",
                "chapter": "第1章",
                "page_from": 10,
                "page_to": 11,
                "locator": "第1章/中心知识点",
                "review_status": "APPROVED",
                "rights_status": "ALLOWED",
                "confidence": 0.95,
            },
            {
                "id": "source-pending",
                "node_id": "center",
                "source_type": "TEXTBOOK",
                "title": "待审核教材",
                "locator": "待审核章节",
                "review_status": "PENDING",
                "rights_status": "RESTRICTED",
            },
            {
                "id": "source-rejected",
                "node_id": "center",
                "source_type": "TEXTBOOK",
                "title": "已拒绝教材",
                "locator": "拒绝章节",
                "review_status": "REJECTED",
                "rights_status": "RESTRICTED",
            },
            {
                "id": "source-prerequisite",
                "node_id": "pre-1",
                "source_type": "TEXTBOOK",
                "title": "先修教材",
                "locator": "先修章节",
                "review_status": "APPROVED",
                "rights_status": "ALLOWED",
            },
            {
                "id": "pending-node-source",
                "node_id": "pending",
                "source_type": "TEXTBOOK",
                "title": "不应召回",
                "locator": "草稿节点来源",
                "review_status": "APPROVED",
                "rights_status": "ALLOWED",
            },
            {
                "id": "source-restricted",
                "node_id": "center",
                "source_type": "TEXTBOOK",
                "title": "未获授权教材",
                "locator": "受限章节",
                "review_status": "APPROVED",
                "rights_status": "RESTRICTED",
            },
        ],
    }


@pytest.fixture()
def client(tmp_path: Path, monkeypatch: pytest.MonkeyPatch):
    graph_file = tmp_path / "graph.json"
    graph_file.write_text(json.dumps(_graph(), ensure_ascii=False), encoding="utf-8")
    service = GraphService(JsonGraphRepository(graph_file, include_pending=False))
    monkeypatch.setattr(graph_routes, "graph_service", service)
    monkeypatch.setattr(graph_routes, "_course_service_cache", {})
    with TestClient(app) as test_client:
        test_client.params = {"course_id": 1}
        yield test_client


def test_public_health_is_available_without_internal_headers(client: TestClient):
    response = client.get("/health")
    assert response.status_code == 200
    assert response.json()["status"] == "UP"
    assert response.json()["service"] == "knowledge-service"
    assert response.json()["timestamp"].endswith("Z")
    assert response.headers["X-Request-Id"].startswith("req-")


def test_internal_routes_require_shared_token_and_request_id(client: TestClient):
    missing_token = client.get(
        "/internal/graph/version", headers={"X-Request-Id": "test-request-002"}
    )
    assert missing_token.status_code == 401
    assert missing_token.json()["detail"]["code"] == "INTERNAL_UNAUTHORIZED"

    missing_request_id = client.get(
        "/internal/graph/version",
        headers={"X-Internal-Token": "test-internal-token"},
    )
    assert missing_request_id.status_code == 422
    assert missing_request_id.json()["detail"]["code"] == "VALIDATION_ERROR"


def test_internal_health_and_version_report_repository_counts(client: TestClient):
    health = client.get("/internal/graph/health", headers=INTERNAL_HEADERS)
    assert health.status_code == 200
    assert health.json()["node_count"] == 9
    assert health.json()["edge_count"] == 8
    assert health.json()["backend"] == "json"

    version = client.get("/internal/graph/version", headers=INTERNAL_HEADERS)
    assert version.status_code == 200
    assert version.json() == {
        "course_id": 1,
        "graph_version": "2.1.0-test",
        "backend": "json",
        "node_count": 9,
        "edge_count": 8,
        "approved_node_count": 7,
        "approved_edge_count": 7,
            "source_count": 6,
        "generated_at": "2026-08-09T04:00:00Z",
    }


def test_formal_node_and_search_paths_hide_pending_and_rejected(client: TestClient):
    node = client.get("/internal/graph/nodes/center", headers=INTERNAL_HEADERS)
    assert node.status_code == 200
    assert node.json()["review_status"] == "APPROVED"

    for hidden_id in ("pending", "rejected"):
        hidden = client.get(
            f"/internal/graph/nodes/{hidden_id}", headers=INTERNAL_HEADERS
        )
        assert hidden.status_code == 404

    search = client.get(
        "/internal/graph/search",
        params={"q": "Secret", "limit": 5},
        headers=INTERNAL_HEADERS,
    )
    assert search.status_code == 200
    assert search.json()["items"] == []


def test_match_uses_reviewed_names_aliases_keywords_and_stable_request_id(
    client: TestClient,
):
    alias_match = client.post(
        "/internal/graph/match",
        json={
            "text": "请使用核心概念分析下面这道题。",
            "scene": "QUESTION",
            "limit": 5,
        },
        headers=INTERNAL_HEADERS,
    )
    assert alias_match.status_code == 200
    assert alias_match.headers["X-Request-Id"] == INTERNAL_HEADERS["X-Request-Id"]
    assert [item["node"]["id"] for item in alias_match.json()["items"]] == [
        "center"
    ]
    candidate = alias_match.json()["items"][0]
    assert candidate["node"]["review_status"] == "APPROVED"
    assert candidate["matched_by"] == ["ALIAS"]
    assert candidate["score"] == 0.76
    assert candidate["requires_review"] is True
    assert candidate["evidence"] == ["别名命中：核心概念"]

    stable_tie = client.post(
        "/internal/graph/match",
        json={"text": "共享关键词", "scene": "RESOURCE", "limit": 2},
        headers=INTERNAL_HEADERS,
    )
    assert stable_tie.status_code == 200
    assert [item["node"]["id"] for item in stable_tie.json()["items"]] == [
        "center",
        "related-a",
    ]
    assert all(item["score"] == 0.92 for item in stable_tie.json()["items"])
    assert all(item["requires_review"] for item in stable_tie.json()["items"])


def test_match_context_is_bounded_reviewable_and_never_leaks_drafts(
    client: TestClient,
):
    context_only = client.post(
        "/internal/graph/match",
        json={
            "text": "请分析下面的通用题干",
            "scene": "QUESTION",
            "current_node_id": "center",
            "limit": 20,
        },
        headers=INTERNAL_HEADERS,
    )
    assert context_only.status_code == 200
    assert [item["node"]["id"] for item in context_only.json()["items"]] == [
        "center"
    ]
    assert context_only.json()["items"][0]["matched_by"] == ["GRAPH_CONTEXT"]
    assert context_only.json()["items"][0]["score"] == 0.42
    assert context_only.json()["items"][0]["requires_review"] is True

    for text in ("Pending Secret", "绝密别名", "Rejected Secret", "拒绝别名"):
        hidden = client.post(
            "/internal/graph/match",
            json={"text": text, "scene": "QUESTION"},
            headers=INTERNAL_HEADERS,
        )
        assert hidden.status_code == 200
        assert hidden.json()["items"] == []


def test_recommend_next_is_explainable_stable_and_never_leaks_pending_graph_data(
    client: TestClient,
):
    response = client.post(
        "/internal/graph/recommend-next",
        json={
            "student_id": 42,
            "mastery": [
                {"knowledge_point_id": "pre-2", "score": 91, "confidence": 0.8},
                {"knowledge_point_id": "pre-1", "score": 52, "confidence": 0.7},
                {"knowledge_point_id": "center", "score": 90, "confidence": 0.9},
                # Equal confidence duplicates retain the conservative score.
                {"knowledge_point_id": "pre-1", "score": 58, "confidence": 0.7},
                {"knowledge_point_id": "pending", "score": 0, "confidence": 1},
            ],
            "limit": 5,
        },
        headers=INTERNAL_HEADERS,
    )
    assert response.status_code == 200
    assert response.headers["X-Request-Id"] == INTERNAL_HEADERS["X-Request-Id"]
    payload = response.json()
    assert payload["graph_version"] == "2.1.0-test"
    assert [item["node"]["id"] for item in payload["items"]] == [
        "pre-1",
        "successor",
    ]
    assert [item["priority"] for item in payload["items"]] == [1, 2]
    assert all(item["reason"] and item["evidence"] for item in payload["items"])
    assert all(item["node"]["review_status"] == "APPROVED" for item in payload["items"])
    assert any("score=52.00" in item for item in payload["items"][0]["evidence"])


def test_recommend_next_unlocks_approved_prerequisites_and_handles_empty_mastery(
    client: TestClient,
):
    locked = client.post(
        "/internal/graph/recommend-next",
        json={
            "student_id": 42,
            "mastery": [
                {"knowledge_point_id": "center", "score": 40, "confidence": 0.9}
            ],
        },
        headers=INTERNAL_HEADERS,
    )
    assert locked.status_code == 200
    assert [item["node"]["id"] for item in locked.json()["items"]] == ["pre-2"]
    assert locked.json()["items"][0]["evidence"] == [
        "已审核先修链：pre-2 → pre-1 → center"
    ]

    initial = client.post(
        "/internal/graph/recommend-next",
        json={"student_id": 42, "mastery": [], "limit": 20},
        headers=INTERNAL_HEADERS,
    )
    assert initial.status_code == 200
    initial_ids = [item["node"]["id"] for item in initial.json()["items"]]
    assert initial_ids == sorted(initial_ids)
    assert "pending" not in initial_ids and "rejected" not in initial_ids
    assert "pre-2" in initial_ids


def test_recommend_next_maps_repository_failure_to_contract_service_unavailable(
    client: TestClient, monkeypatch: pytest.MonkeyPatch
):
    def unavailable(*_args, **_kwargs):
        raise RuntimeError("backend down")

    monkeypatch.setattr(graph_routes.graph_service, "recommend_next", unavailable)
    response = client.post(
        "/internal/graph/recommend-next",
        json={"student_id": 42, "mastery": []},
        headers=INTERNAL_HEADERS,
    )
    assert response.status_code == 503
    assert response.json()["detail"]["code"] == "GRAPH_SERVICE_UNAVAILABLE"


@pytest.mark.parametrize(
    "payload",
    [
        {"text": "", "scene": "QUESTION"},
        {"text": "x" * 30001, "scene": "QUESTION"},
        {"text": "题目", "scene": "UNKNOWN"},
        {"text": "题目", "scene": "QUESTION", "limit": 21},
    ],
)
def test_match_rejects_unsafe_or_out_of_contract_input(
    client: TestClient, payload: dict
):
    response = client.post(
        "/internal/graph/match", json=payload, headers=INTERNAL_HEADERS
    )
    assert response.status_code == 422
    assert response.json()["detail"]["code"] == "VALIDATION_ERROR"


def test_validation_errors_do_not_echo_rejected_input_or_context(client: TestClient):
    secret = "evidence-secret-should-never-be-reflected"
    response = client.post(
        "/internal/graph/match",
        json={"text": [secret], "scene": "QUESTION"},
        headers=INTERNAL_HEADERS,
    )

    assert response.status_code == 422
    body = response.json()
    assert secret not in response.text
    assert all(set(error) <= {"loc", "type", "msg"} for error in body["detail"]["errors"])


@pytest.mark.parametrize(
    "payload",
    [
        {"student_id": 42, "mastery": [{"knowledge_point_id": "center", "score": 101, "confidence": 1}]},
        {"student_id": 42, "mastery": [{"knowledge_point_id": "center", "score": 50, "confidence": -0.1}]},
        {"student_id": 42, "mastery": [], "limit": 21},
        {"mastery": []},
    ],
)
def test_recommend_next_rejects_out_of_contract_input(client: TestClient, payload: dict):
    response = client.post(
        "/internal/graph/recommend-next", json=payload, headers=INTERNAL_HEADERS
    )
    assert response.status_code == 422
    assert response.json()["detail"]["code"] == "VALIDATION_ERROR"


def test_prerequisites_preserve_direction_depth_and_review_filter(client: TestClient):
    direct = client.get(
        "/internal/graph/prerequisites/center",
        params={"max_depth": 1},
        headers=INTERNAL_HEADERS,
    )
    assert direct.status_code == 200
    assert [item["id"] for item in direct.json()["items"]] == ["pre-1"]

    chain = client.get(
        "/internal/graph/prerequisites/center",
        params={"max_depth": 2},
        headers=INTERNAL_HEADERS,
    )
    assert [item["id"] for item in chain.json()["items"]] == ["pre-1", "pre-2"]
    assert all(item["review_status"] == "APPROVED" for item in chain.json()["items"])


def test_sources_are_traceable_and_cannot_disable_production_filter(client: TestClient):
    default = client.get(
        "/internal/graph/sources/center", headers=INTERNAL_HEADERS
    )
    assert default.status_code == 200
    assert [item["id"] for item in default.json()["items"]] == ["source-approved"]
    assert default.json()["items"][0]["locator"] == "第1章/中心知识点"
    assert default.json()["items"][0]["rights_status"] == "ALLOWED"

    requested_all = client.get(
        "/internal/graph/sources/center",
        params={"approved_only": False},
        headers=INTERNAL_HEADERS,
    )
    assert [item["id"] for item in requested_all.json()["items"]] == [
        "source-approved"
    ]

    hidden_node = client.get(
        "/internal/graph/sources/pending",
        params={"approved_only": False},
        headers=INTERNAL_HEADERS,
    )
    assert hidden_node.status_code == 404


def test_rag_context_is_approved_bounded_traceable_and_statused(client: TestClient):
    response = client.get(
        "/internal/graph/rag-context/center",
        params={"max_related": 1},
        headers=INTERNAL_HEADERS,
    )
    assert response.status_code == 200
    payload = response.json()
    assert payload["knowledge_point"]["id"] == "center"
    assert [item["id"] for item in payload["prerequisites"]] == ["pre-1"]
    assert [item["id"] for item in payload["successors"]] == ["successor"]
    assert [item["id"] for item in payload["related_nodes"]] == ["related-a"]
    assert [item["id"] for item in payload["sources"]] == [
        "source-approved",
        "source-prerequisite",
    ]
    assert payload["graph_version"] == "2.1.0-test"
    assert payload["context_status"] == "READY"
    assert payload["warnings"] == []
    assert all(
        item["review_status"] == "APPROVED"
        for key in ("prerequisites", "successors", "related_nodes", "sources")
        for item in payload[key]
    )

    insufficient = client.get(
        "/internal/graph/rag-context/successor", headers=INTERNAL_HEADERS
    )
    assert insufficient.status_code == 200
    assert insufficient.json()["context_status"] == "INSUFFICIENT"
    assert len(insufficient.json()["warnings"]) == 2


def test_subgraph_and_errors_apply_limits_and_not_found_behavior(client: TestClient):
    subgraph = client.get(
        "/internal/graph/subgraph",
        params={"center_id": "root", "depth": 1, "relations": "contains"},
        headers=INTERNAL_HEADERS,
    )
    assert subgraph.status_code == 200
    assert {node["id"] for node in subgraph.json()["nodes"]} == {"root", "center"}
    assert {edge["relation"] for edge in subgraph.json()["edges"]} == {"contains"}

    missing = client.get(
        "/internal/graph/rag-context/not-found", headers=INTERNAL_HEADERS
    )
    assert missing.status_code == 404
    assert missing.json()["detail"]["code"] == "GRAPH_NODE_NOT_FOUND"

    invalid = client.get(
        "/internal/graph/rag-context/center",
        params={"max_related": 21},
        headers=INTERNAL_HEADERS,
    )
    assert invalid.status_code == 422
    assert invalid.json()["detail"]["code"] == "VALIDATION_ERROR"

    invalid_depth = client.get(
        "/internal/graph/prerequisites/center",
        params={"max_depth": 11},
        headers=INTERNAL_HEADERS,
    )
    assert invalid_depth.status_code == 422
    assert invalid_depth.json()["detail"]["code"] == "VALIDATION_ERROR"


def test_graph_budget_exhaustion_is_typed_503_without_partial_dto(
    client: TestClient, monkeypatch: pytest.MonkeyPatch
):
    def exhausted(*_args, **_kwargs):
        raise GraphQueryBudgetExceeded("test budget")

    monkeypatch.setattr(graph_routes.graph_service, "subgraph", exhausted)
    response = client.get(
        "/internal/graph/subgraph",
        params={"center_id": "center", "depth": 5},
        headers=INTERNAL_HEADERS,
    )

    assert response.status_code == 503
    assert response.json()["detail"]["code"] == "GRAPH_SERVICE_UNAVAILABLE"


def test_fastapi_schema_exposes_the_frozen_v24_graph_operations():
    schema = app.openapi()
    expected = {
        "/internal/graph/prerequisites/{node_id}": "internalGetPrerequisites",
        "/internal/graph/sources/{node_id}": "internalGetGraphSources",
        "/internal/graph/version": "internalGetGraphVersion",
        "/internal/graph/rag-context/{node_id}": "internalGetGraphRagContext",
        "/internal/graph/match": "internalMatchKnowledgePoints",
        "/internal/graph/recommend-next": "internalRecommendNext",
    }
    for path, operation_id in expected.items():
        method = "post" if path.endswith(("/match", "/recommend-next")) else "get"
        assert schema["paths"][path][method]["operationId"] == operation_id
    required_rag_fields = set(
        schema["components"]["schemas"]["GraphRagContextResponse"]["required"]
    )
    assert required_rag_fields == {
        "course_id",
        "knowledge_point",
        "prerequisites",
        "successors",
        "related_nodes",
        "sources",
        "graph_version",
        "context_status",
        "warnings",
    }
    assert set(schema["components"]["schemas"]["GraphMatchRequest"]["required"]) == {
        "text",
        "scene",
    }
    assert set(
        schema["components"]["schemas"]["GraphMatchCandidate"]["required"]
    ) == {"node", "score", "matched_by", "requires_review"}
    assert set(
        schema["components"]["schemas"]["GraphRecommendationResponse"]["required"]
    ) == {"items", "graph_version", "course_id"}
