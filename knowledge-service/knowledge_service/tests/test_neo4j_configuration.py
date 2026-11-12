from __future__ import annotations

from typing import Any

from neo4j import GraphDatabase

from knowledge_service.app.repository.neo4j_repo import Neo4jGraphRepository


class _Driver:
    def session(self):
        return _Session()

    def close(self):
        return None


class _Result:
    def consume(self):
        return None


class _Session:
    def __enter__(self):
        return self

    def __exit__(self, *_args):
        return None

    def __init__(self):
        self.query: Any = None

    def run(self, query: Any, **_params: Any):
        self.query = query
        return _Result()


def test_neo4j_driver_pool_and_query_timeout_are_explicit(monkeypatch):
    captured: dict[str, Any] = {}

    def driver(*_args: Any, **kwargs: Any):
        captured.update(kwargs)
        return _Driver()

    monkeypatch.setattr(GraphDatabase, "driver", driver)
    repository = Neo4jGraphRepository(
        "bolt://unused",
        "neo4j",
        "secret",
        query_timeout_seconds=2.5,
        max_connection_pool_size=17,
        connection_timeout_seconds=4.0,
        candidate_limit=80,
    )
    session = _Session()
    repository._run(session, "RETURN $value", value=1)

    assert captured["max_connection_pool_size"] == 17
    assert captured["connection_timeout"] == 4.0
    assert captured["connection_acquisition_timeout"] == 4.0
    assert session.query.text == "RETURN $value"
    assert session.query.timeout == 2.5
    assert repository.candidate_limit == 80


def test_graph_response_budgets_have_absolute_upper_bounds(monkeypatch):
    monkeypatch.setattr(GraphDatabase, "driver", lambda *_args, **_kwargs: _Driver())
    for name in (
        "GRAPH_CONTEXT_ITEM_LIMIT",
        "GRAPH_PREREQUISITE_NODE_LIMIT",
        "GRAPH_SOURCE_LIMIT",
        "GRAPH_SUBGRAPH_NODE_LIMIT",
        "GRAPH_SUBGRAPH_HOP_LIMIT",
        "GRAPH_SUBGRAPH_EDGE_LIMIT",
    ):
        monkeypatch.setenv(name, "999999")

    repository = Neo4jGraphRepository(
        "bolt://unused",
        "neo4j",
        "secret",
        candidate_limit=5000,
    )

    assert repository.context_item_limit == 500
    assert repository.prerequisite_node_limit == 1000
    assert repository.source_limit == 2000
    assert repository.subgraph_node_limit == 1000
    assert repository.subgraph_hop_limit == 500
    assert repository.subgraph_edge_limit == 4000
