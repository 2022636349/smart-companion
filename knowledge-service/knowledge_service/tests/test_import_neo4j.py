from __future__ import annotations

from typing import Any

from neo4j import GraphDatabase

from knowledge_service.ingestion.import_neo4j import import_graph
from knowledge_service.tests.test_graph_api import _graph


class _ConsumedResult:
    def consume(self):
        return None


class _RecordingSession:
    def __init__(self, calls: list[tuple[str, dict[str, Any]]]):
        self.calls = calls

    def __enter__(self):
        return self

    def __exit__(self, *_args):
        return None

    def run(self, query: str, **params: Any):
        self.calls.append((" ".join(query.split()), params))
        return _ConsumedResult()


class _RecordingDriver:
    def __init__(self):
        self.calls: list[tuple[str, dict[str, Any]]] = []
        self.closed = False

    def session(self):
        return _RecordingSession(self.calls)

    def close(self):
        self.closed = True


def test_importer_persists_metadata_all_edge_types_and_graph_sources(monkeypatch):
    driver = _RecordingDriver()
    monkeypatch.setattr(GraphDatabase, "driver", lambda *_args, **_kwargs: driver)

    node_count, edge_count = import_graph(
        _graph(),
        uri="bolt://unused",
        user="neo4j",
        password="unused",
    )

    assert node_count == 9
    assert edge_count == 8
    assert driver.closed is True

    metadata_calls = [
        params
        for query, params in driver.calls
        if "MERGE (m:GraphMetadata" in query
    ]
    assert metadata_calls[0]["row"]["key"] == "current"
    assert metadata_calls[0]["row"]["version"] == "2.1.0-test"

    imported_relations = {
        relation
        for relation in ("REQUIRES", "RELATED", "SUPPORTED_BY")
        if any(f"MERGE (source)-[r:{relation}]" in query for query, _ in driver.calls)
    }
    assert imported_relations == {"REQUIRES", "RELATED"}

    source_calls = [
        params
        for query, params in driver.calls
        if "MERGE (source:GraphSource" in query
    ]
    assert len(source_calls) == 1
    assert {row["id"] for row in source_calls[0]["rows"]} == {
        "source-approved",
        "source-pending",
        "source-rejected",
        "source-prerequisite",
        "pending-node-source",
        "source-restricted",
    }
    assert all(
        "locator" in row
        and "review_status" in row
        and row["rights_status"] in {"ALLOWED", "RESTRICTED"}
        for row in source_calls[0]["rows"]
    )
    assert all(row["course_id"] == 1 for row in source_calls[0]["rows"])
    assert metadata_calls[0]["row"]["course_id"] == 1

    statements = {query for query, _params in driver.calls}
    assert any(
        "CREATE INDEX knowledge_node_parent_id IF NOT EXISTS" in query
        and "ON (n.parent_id)" in query
        for query in statements
    )
    assert any(
        "CREATE INDEX graph_source_node_id IF NOT EXISTS" in query
        and "ON (s.node_id)" in query
        for query in statements
    )
