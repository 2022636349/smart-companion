from __future__ import annotations

import os

import pytest

from knowledge_service.app.repository.neo4j_repo import Neo4jGraphRepository


pytestmark = pytest.mark.skipif(
    os.getenv("RUN_NEO4J_INTEGRATION") != "1",
    reason="set RUN_NEO4J_INTEGRATION=1 when the local Neo4j Compose service is running",
)


@pytest.fixture(scope="module")
def repository():
    repo = Neo4jGraphRepository(
        os.getenv("NEO4J_URI", "bolt://localhost:17687"),
        os.getenv("NEO4J_USER", "neo4j"),
        os.getenv("NEO4J_PASSWORD", "change-me-now"),
        include_pending=False,
    )
    try:
        yield repo
    finally:
        repo.close()


def test_live_neo4j_cycle5_release_matches_the_approved_json_contract(repository):
    health = repository.health()
    assert health["node_count"] == 150
    assert health["edge_count"] == 119

    results = repository.search("变量", limit=5)
    assert 1 <= len(results) <= 5
    assert all(item["id"] and item["review_status"] == "APPROVED" for item in results)

    context = repository.context("3.1.2")
    assert context is not None
    assert {item["id"] for item in context["prerequisites"]} == {"3.1.1"}
    assert {item["id"] for item in context["successors"]} == {"3.1.3", "3.2.1"}

    prerequisites = repository.prerequisites("3.1.2", max_depth=2)
    assert prerequisites is not None
    assert [item["id"] for item in prerequisites] == ["3.1.1", "2.2.2", "2.2.3"]

    sources = repository.sources("3.1.2")
    assert sources is not None and sources
    assert all(source["review_status"] == "APPROVED" for source in sources)

    rag = repository.rag_context("3.1.2", max_related=8)
    assert rag is not None
    assert rag["context_status"] == "READY"
    assert rag["graph_version"] == "2.4.0-c5-gc.1"
    assert rag["sources"]
    assert all(
        item["review_status"] == "APPROVED"
        for key in ("prerequisites", "successors", "related_nodes", "sources")
        for item in rag[key]
    )

    version = repository.version()
    assert version["graph_version"] == "2.4.0-c5-gc.1"
    assert version["node_count"] == 150
    assert version["edge_count"] == 119
    assert version["approved_node_count"] == 147
    assert version["approved_edge_count"] == 106
    assert version["source_count"] == 147

    subgraph = repository.subgraph("3.1.2", depth=1, relations={"requires"})
    assert subgraph is not None
    assert {node["id"] for node in subgraph["nodes"]} == {
        "3.1.1",
        "3.1.2",
        "3.1.3",
        "3.2.1",
    }
    assert {edge["relation"] for edge in subgraph["edges"]} == {"requires"}
    assert subgraph["graph_version"] == "2.4.0-c5-gc.1"

    empty_relations = repository.subgraph("3.1.2", depth=1, relations=set())
    assert empty_relations is not None
    assert len(empty_relations["nodes"]) == 1
    assert empty_relations["edges"] == []

    recommendations = repository.recommend([], limit=5)
    assert recommendations
    assert all(item["node"]["review_status"] == "APPROVED" for item in recommendations)
    assert all(item["reason"] and item["evidence"] for item in recommendations)

    repository.include_pending = True
    try:
        assert repository.get_node("0") is not None
        root_context = repository.rag_context("0")
        assert root_context is not None
        assert root_context["knowledge_point"]["id"] == "0"
        assert root_context["knowledge_point"]["review_status"] == "APPROVED"
        assert root_context["graph_version"] == "2.4.0-c5-gc.1"
    finally:
        repository.include_pending = False
