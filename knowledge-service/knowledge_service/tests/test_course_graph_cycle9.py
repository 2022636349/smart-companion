from __future__ import annotations

import json
from concurrent.futures import ThreadPoolExecutor
from copy import deepcopy

import pytest
from fastapi.testclient import TestClient

from knowledge_service.app.api import graph_routes
from knowledge_service.app.domain.ingestion_dto import (
    GraphIngestionBatchUpsertRequest, GraphPublishRequest, GraphRollbackRequest,
)
from knowledge_service.app.repository.course_scope import (
    COURSE_LABEL_KEYS, empty_course_graph, ensure_course_schema, scoped_statement,
)
from knowledge_service.app.repository.json_repo import JsonGraphRepository
from knowledge_service.app.repository.neo4j_repo import Neo4jGraphRepository
from knowledge_service.app.services.graph_service import GraphService
from knowledge_service.app.services.ingestion_service import DraftDecisionInvalid, DraftNotPublishable, GraphIngestionService
from knowledge_service.main import app
from knowledge_service.operations.ingestion_validation import canonical_payload_sha256
from knowledge_service.operations.releases import verify_snapshot
from knowledge_service.tests.test_graph_ingestion_cycle7 import (
    _approve_all, _candidate_node, _candidate_source, _request, d_temp,
)


HEADERS = {"X-Internal-Token": "test-internal-token", "X-Request-Id": "cycle7-request-0001"}


def _new_course_request(course_id: int, batch_id: str = "cycle9-same-batch-0001"):
    root = _candidate_node(candidate_id="course-root", node_id="root-local", name=f"课程{course_id}")
    root.update(level=0, node_type="course", parent_id=None)
    point = _candidate_node(node_id="1.1", name=f"课程{course_id}函数")
    point["parent_id"] = "root-local"
    sources = [
        _candidate_source(candidate_id="source-root", source_id="source-root", node_id="root-local"),
        _candidate_source(node_id="1.1"),
    ]
    value = _request(batch_id=batch_id, nodes=[root, point], edges=[], sources=sources).model_dump(mode="json")
    value["base_graph_version"] = "0.0.0"
    value["payload_sha256"] = canonical_payload_sha256(GraphIngestionBatchUpsertRequest.model_validate(value))
    return GraphIngestionBatchUpsertRequest.model_validate(value)


@pytest.fixture
def course_services(d_temp, monkeypatch):
    monkeypatch.setenv("GRAPH_BACKEND", "json")
    monkeypatch.setenv("GRAPH_DRAFT_BACKEND", "file")
    monkeypatch.setenv("GRAPH_INGESTION_ROOT", str(d_temp))
    monkeypatch.setenv("INTERNAL_SERVICE_TOKEN", "test-internal-token")
    monkeypatch.setattr(graph_routes, "_course_service_cache", {})
    try:
        yield graph_routes._course_services
    finally:
        graph_routes.close_course_services()


def _publish(service, request):
    service.upsert(request)
    approved = _approve_all(service, request.batch_id)
    return service.publish(request.batch_id, GraphPublishRequest(
        request_id="cycle9-publish-same-0001", expected_revision=approved["revision"],
        target_graph_version="1.0.0",
    ))


def test_new_course_is_deterministic_empty_and_never_falls_back_to_python(course_services):
    python_before = graph_routes.graph_service.version()
    with TestClient(app) as client:
        first = client.get("/internal/graph/version", params={"course_id": 2}, headers=HEADERS)
        second = client.get("/internal/graph/version", params={"course_id": 2}, headers=HEADERS)
        assert first.status_code == 200 and first.json() == second.json()
        assert first.json()["course_id"] == 2 and first.json()["graph_version"] == "0.0.0"
        assert all(first.json()[key] == 0 for key in ("node_count", "edge_count", "source_count", "approved_node_count", "approved_edge_count"))
        for path in ("nodes/5.1.2", "rag-context/5.1.2", "sources/5.1.2", "context/5.1.2", "prerequisites/5.1.2"):
            assert client.get(f"/internal/graph/{path}", params={"course_id": 2}, headers=HEADERS).status_code == 404
        assert client.get("/internal/graph/search", params={"course_id": 2, "q": "Python"}, headers=HEADERS).json()["items"] == []
        empty = client.get("/internal/graph/subgraph", params={"course_id": 2}, headers=HEADERS).json()
        assert empty == {"course_id": 2, "center_id": "", "nodes": [], "edges": [], "graph_version": "0.0.0"}
    assert graph_routes.graph_service.version() == python_before


def test_every_nonhealth_route_requires_explicit_positive_course_id(monkeypatch):
    monkeypatch.setenv("INTERNAL_SERVICE_TOKEN", "test-internal-token")
    schema = app.openapi()
    with TestClient(app) as client:
        for path, operations in schema["paths"].items():
            if not path.startswith("/internal/graph/") or path.endswith("/health"):
                continue
            for method, operation in operations.items():
                params = operation.get("parameters", [])
                scope = next(p for p in params if p["name"] == "course_id")
                assert scope["required"] is True and scope["in"] == "query"
                concrete = path.replace("{node_id}", "1.1").replace("{batch_id}", "cycle9-batch-000001").replace("{graph_version}", "1.0.0")
                response = client.request(method.upper(), concrete, headers=HEADERS)
                assert response.status_code == 422
                assert any(error["loc"] == ["query", "course_id"] for error in response.json()["detail"]["errors"])
        for invalid in (0, -1, "not-a-course", "true"):
            assert client.get("/internal/graph/version", params={"course_id": invalid}, headers=HEADERS).status_code == 422


def test_two_courses_share_local_ids_versions_and_request_ids_without_collisions(course_services):
    graph2, service2 = course_services(2)
    graph3, service3 = course_services(3)
    request2, request3 = _new_course_request(2), _new_course_request(3)
    with ThreadPoolExecutor(max_workers=2) as pool:
        results = list(pool.map(lambda item: _publish(*item), [(service2, request2), (service3, request3)]))
    assert [result["course_id"] for result in results] == [2, 3]
    assert all(result["graph_version"] == "1.0.0" for result in results)
    assert results[0]["release_sha256"] != results[1]["release_sha256"]
    for course_id, graph, service in ((2, graph2, service2), (3, graph3, service3)):
        assert graph.get_node("1.1")["name"] == f"课程{course_id}函数"
        assert graph.rag_context("1.1")["course_id"] == course_id
        assert graph.context("1.1")["course_id"] == course_id
        assert graph.recommend_next([], 5)["course_id"] == course_id
        assert graph.subgraph(None)["center_id"] == "root-local"
        assert service.get(request2.batch_id)["course_id"] == course_id
        assert service.release_store.get("0.0.0")["version"] == "0.0.0"
    rollback = GraphRollbackRequest(request_id="cycle9-rollback-same-001", expected_current_version="1.0.0")
    reverted = service2.rollback("0.0.0", rollback)
    assert reverted["course_id"] == 2 and graph2.get_node("1.1") is None
    assert service2.rollback("0.0.0", rollback) == reverted
    assert graph3.version()["graph_version"] == "1.0.0"
    assert graph3.get_node("1.1")["name"] == "课程3函数"
    service3.rollback("0.0.0", rollback)
    assert graph3.get_node("1.1") is None


def test_drafts_pending_decisions_cursors_and_files_are_course_scoped(course_services):
    graph2, service2 = course_services(2)
    graph3, service3 = course_services(3)
    request = _new_course_request(2)
    detail, _ = service2.upsert(request)
    assert detail["course_id"] == 2 and detail["pending_count"] == 4
    assert graph2.get_node("1.1") is None and graph3.get_node("1.1") is None
    assert service3.list(status=None, limit=20, cursor=None)["items"] == []
    with pytest.raises(DraftNotPublishable):
        service2.publish(request.batch_id, GraphPublishRequest(request_id="cycle9-blocked-publish", expected_revision=1, target_graph_version="1.0.0"))
    service3.upsert(_new_course_request(3))
    first = service2.candidates(request.batch_id, candidate_type=None, decision_status=None, review_lane=None, limit=1, cursor=None)
    with pytest.raises(DraftDecisionInvalid, match="course scope"):
        service3.candidates(request.batch_id, candidate_type=None, decision_status=None, review_lane=None, limit=1, cursor=first["next_cursor"])
    service2.upsert(_new_course_request(2, "cycle9-same-batch-0002"))
    cursor = service2.list(status=None, limit=1, cursor=None)["next_cursor"]
    with pytest.raises(DraftDecisionInvalid, match="course scope"):
        service3.list(status=None, limit=1, cursor=cursor)
    assert service2.release_store.root != service3.release_store.root


def test_restart_restores_only_requested_course_and_rejects_cross_course_release(course_services):
    graph2, service2 = course_services(2)
    _publish(service2, _new_course_request(2))
    graph_routes.close_course_services()
    restored, _ = course_services(2)
    assert restored.version()["graph_version"] == "1.0.0"
    assert course_services(3)[0].version()["graph_version"] == "0.0.0"
    with pytest.raises(ValueError, match="different course"):
        restored.activate_json_release(graph_routes.graph_service.json_repo.graph_file)
    assert restored.version()["graph_version"] == "1.0.0"


def test_neo4j_scope_covers_both_endpoints_releases_drafts_leases_and_receipts():
    for label in COURSE_LABEL_KEYS:
        assert f":{label} {{course_id: $course_id}})" in scoped_statement(f"MATCH (n:{label}) RETURN n")
        assert f":{label} {{course_id: $course_id, id: $id}})" in scoped_statement(f"MERGE (n:{label} {{id: $id}}) RETURN n")
    query = scoped_statement("MATCH (a:KnowledgeNode)-[r:REQUIRES]->(b:KnowledgeNode) RETURN a,b")
    assert query.count("course_id: $course_id") == 2
    row_query = scoped_statement("UNWIND $rows AS row MERGE (n:KnowledgeNode {id: row.id}) RETURN n")
    assert "{course_id: $course_id, id: row.id}" in row_query
    class Result:
        def consume(self): pass
    class RecordingSession:
        def __init__(self): self.calls = []
        def run(self, query, **params):
            self.calls.append((getattr(query, "text", query), params))
            return Result()
    session = RecordingSession()
    repo = object.__new__(Neo4jGraphRepository)
    repo.course_id = 2
    repo._run(session, "MATCH (n:KnowledgeNode {id: $id}) RETURN n", id="1.1", course_id=99)
    assert session.calls[0][1]["course_id"] == 2
    ensure_course_schema(session)
    statements = [query for query, _ in session.calls]
    assert all(any(f"REQUIRE (n.course_id, n.{key}) IS UNIQUE" in query for query in statements) for _, key in COURSE_LABEL_KEYS.values())
    assert not any("DELETE" in query for query in statements)
    for legacy, _ in COURSE_LABEL_KEYS.values():
        drop = next(i for i, query in enumerate(statements) if f"DROP CONSTRAINT {legacy} IF EXISTS" in query)
        create = next(i for i, query in enumerate(statements) if f"CREATE CONSTRAINT {legacy}_course" in query)
        assert drop < create
