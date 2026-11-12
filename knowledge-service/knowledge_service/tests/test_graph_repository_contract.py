from __future__ import annotations

import json
import re
from pathlib import Path
from typing import Any

import pytest

from knowledge_service.app.domain.graph_dto import (
    GraphMatchResponse,
    GraphNodeListResponse,
    GraphRecommendationResponse,
    GraphRagContextResponse,
    GraphSourceResponse,
    GraphSubgraphResponse,
    GraphVersionResponse,
    normalize_source,
)
from knowledge_service.app.repository.json_repo import JsonGraphRepository
from knowledge_service.app.repository.neo4j_repo import (
    GraphQueryBudgetExceeded,
    Neo4jGraphRepository,
)
from knowledge_service.app.services.graph_service import GraphService
from knowledge_service.tests.test_graph_api import _graph


class _FakeResult:
    def __init__(self, records: list[dict[str, Any]]):
        self.records = records

    def __iter__(self):
        return iter(self.records)

    def single(self):
        return self.records[0] if self.records else None

    def consume(self):
        return None


class _FakeSession:
    def __init__(self, graph: dict[str, Any], queries: list[tuple[str, dict[str, Any]]]):
        self.graph = graph
        self.queries = queries
        self.nodes = {node["id"]: node for node in graph["nodes"]}

    def __enter__(self):
        return self

    def __exit__(self, *_args):
        return None

    def run(self, query: str, **params: Any):
        query = getattr(query, "text", query)
        compact = " ".join(query.split())
        # The fake interprets the pre-C9 lexical subset; preserve the original
        # scoped query in evidence, then remove only the fixed course predicate.
        compact = re.sub(r"\{course_id: \$course_id, ", "{", compact)
        compact = compact.replace(" {course_id: $course_id}", "")
        self.queries.append((compact, params))
        if "size($mastery_ids)" in compact:
            mastery_ids = set(params["mastery_ids"])
            if mastery_ids:
                node_ids = mastery_ids
            else:
                targets = {
                    edge["target"]
                    for edge in self.graph["edges"]
                    if edge["relation"] == "REQUIRES"
                    and edge["review_status"] == "APPROVED"
                }
                node_ids = {
                    node_id
                    for node_id, node in self.nodes.items()
                    if node["review_status"] == "APPROVED"
                    and node.get("node_type") == "knowledge_point"
                    and node_id not in targets
                }
            rows = [
                {"n": self.nodes[node_id]}
                for node_id in sorted(node_ids)
                if node_id in self.nodes
                and self.nodes[node_id]["review_status"] == "APPROVED"
            ]
            return _FakeResult(rows[: params["fetch_limit"]])
        if (
            "MATCH (n:KnowledgeNode)" in compact
            and "RETURN n ORDER BY n.id" in compact
        ):
            rows = [
                {"n": node}
                for node in sorted(self.nodes.values(), key=lambda item: item["id"])
                if node["review_status"] == "APPROVED"
                and (
                    "n.node_type = 'knowledge_point'" not in compact
                    or node.get("node_type") == "knowledge_point"
                )
            ]
            fetch_limit = params.get("fetch_limit", params.get("candidate_limit"))
            return _FakeResult(rows if fetch_limit is None else rows[:fetch_limit])
        if "MATCH (source:KnowledgeNode)-[rel:REQUIRES]->(target:KnowledgeNode)" in compact:
            rows = [
                {
                    "source_id": edge["source"],
                    "target_id": edge["target"],
                    "relation": edge["relation"].lower(),
                    "review_status": edge["review_status"],
                    "evidence": edge["evidence"],
                    "confidence": edge["confidence"],
                }
                for edge in self.graph["edges"]
                if edge["relation"] == "REQUIRES"
                and edge["review_status"] == "APPROVED"
                and self.nodes[edge["source"]]["review_status"] == "APPROVED"
                and self.nodes[edge["target"]]["review_status"] == "APPROVED"
            ]
            return _FakeResult(rows[: params["fetch_limit"]])
        if "MATCH (outside:KnowledgeNode)-[rel:REQUIRES]->" in compact:
            selected = set(params["node_ids"])
            outside_ids = [
                edge["source"]
                for edge in self.graph["edges"]
                if edge["relation"] == "REQUIRES"
                and edge["review_status"] == "APPROVED"
                and edge["target"] in selected
                and edge["source"] not in selected
                and self.nodes[edge["source"]]["review_status"] == "APPROVED"
            ]
            return _FakeResult(
                [{"outside_id": node_id} for node_id in sorted(set(outside_ids))[:1]]
            )
        if (
            "UNWIND $frontier_ids AS frontier_id" in compact
            and "(source:KnowledgeNode {id: frontier_id})-" in compact
            and "rel:REQUIRES" in compact
            and "(neighbor:KnowledgeNode)" in compact
        ):
            selected = set(params["selected_ids"])
            rows: list[dict[str, Any]] = []
            for frontier_id in sorted(params["frontier_ids"]):
                neighbor_ids = set()
                for edge in self.graph["edges"]:
                    if (
                        edge["relation"] != "REQUIRES"
                        or edge["review_status"] != "APPROVED"
                    ):
                        continue
                    neighbor_id = None
                    if edge["source"] == frontier_id:
                        neighbor_id = edge["target"]
                    elif edge["target"] == frontier_id:
                        neighbor_id = edge["source"]
                    if (
                        neighbor_id is not None
                        and neighbor_id not in selected
                        and self.nodes[frontier_id]["review_status"] == "APPROVED"
                        and self.nodes[neighbor_id]["review_status"] == "APPROVED"
                    ):
                        neighbor_ids.add(neighbor_id)
                rows.extend(
                    {"frontier_id": frontier_id, "node": self.nodes[node_id]}
                    for node_id in sorted(neighbor_ids)[
                        : params["per_frontier_fetch_limit"]
                    ]
                )
            return _FakeResult(rows[: params["layer_fetch_limit"]])
        if (
            "UNWIND $frontier_ids AS frontier_id" in compact
            and "MATCH (prerequisite:KnowledgeNode)-[rel:REQUIRES]->" in compact
        ):
            selected = set(params["selected_ids"])
            rows = []
            for frontier_id in sorted(params["frontier_ids"]):
                node_ids = {
                    edge["source"]
                    for edge in self.graph["edges"]
                    if edge["target"] == frontier_id
                    and edge["relation"] == "REQUIRES"
                    and edge["review_status"] == "APPROVED"
                    and edge["source"] not in selected
                    and self.nodes[frontier_id]["review_status"] == "APPROVED"
                    and self.nodes[edge["source"]]["review_status"] == "APPROVED"
                }
                rows.extend(
                    {"frontier_id": frontier_id, "node": self.nodes[node_id]}
                    for node_id in sorted(node_ids)[
                        : params["per_frontier_fetch_limit"]
                    ]
                )
            return _FakeResult(rows[: params["layer_fetch_limit"]])
        if (
            "UNWIND $frontier_ids AS frontier_id" in compact
            and "[rel]-(neighbor:KnowledgeNode)" in compact
        ):
            selected = set(params["selected_ids"])
            relations = params["relations"]
            allow_pending = params["allow_pending"]
            graph_edges = list(self.graph["edges"]) + [
                {
                    "source": node["parent_id"],
                    "target": node["id"],
                    "relation": "CONTAINS",
                    "review_status": node["review_status"],
                }
                for node in self.graph["nodes"]
                if node.get("parent_id") in self.nodes
            ]
            rows = []
            for frontier_id in sorted(params["frontier_ids"]):
                neighbor_ids = set()
                for edge in graph_edges:
                    if edge["relation"].lower() not in relations:
                        continue
                    if edge["review_status"] != "APPROVED" and not (
                        allow_pending and edge["review_status"] == "PENDING"
                    ):
                        continue
                    neighbor_id = None
                    if edge["source"] == frontier_id:
                        neighbor_id = edge["target"]
                    elif edge["target"] == frontier_id:
                        neighbor_id = edge["source"]
                    if neighbor_id is None or neighbor_id in selected:
                        continue
                    neighbor_status = self.nodes[neighbor_id]["review_status"]
                    source_status = self.nodes[frontier_id]["review_status"]
                    if (
                        source_status == "APPROVED"
                        or (allow_pending and source_status == "PENDING")
                    ) and (
                        neighbor_status == "APPROVED"
                        or (allow_pending and neighbor_status == "PENDING")
                    ):
                        neighbor_ids.add(neighbor_id)
                rows.extend(
                    {"frontier_id": frontier_id, "node": self.nodes[node_id]}
                    for node_id in sorted(neighbor_ids)[
                        : params["per_frontier_fetch_limit"]
                    ]
                )
            return _FakeResult(rows[: params["layer_fetch_limit"]])
        if "RETURN DISTINCT neighbor.id AS neighbor_id" in compact:
            current_id = params["id"]
            neighbor_ids = set()
            for edge in self.graph["edges"]:
                if edge["review_status"] != "APPROVED":
                    continue
                if edge["source"] == current_id:
                    neighbor_ids.add(edge["target"])
                elif edge["target"] == current_id:
                    neighbor_ids.add(edge["source"])
            rows = [
                {"neighbor_id": node_id}
                for node_id in sorted(neighbor_ids)
                if self.nodes[node_id]["review_status"] == "APPROVED"
            ]
            return _FakeResult(rows[: params["fetch_limit"]])
        if "MATCH (child:KnowledgeNode {parent_id: $id})" in compact:
            rows = [
                {"node": node}
                for node in sorted(self.nodes.values(), key=lambda item: item["id"])
                if node.get("parent_id") == params["id"]
                and node["review_status"] == "APPROVED"
            ]
            return _FakeResult(rows[: params["fetch_limit"]])
        if (
            "MATCH (prerequisite:KnowledgeNode)-[rel:REQUIRES]->" in compact
            and "target.id IN $frontier_ids" not in compact
        ):
            rows = [
                {"node": self.nodes[edge["source"]]}
                for edge in self.graph["edges"]
                if edge["target"] == params["id"]
                and edge["relation"] == "REQUIRES"
                and edge["review_status"] == "APPROVED"
                and self.nodes[edge["source"]]["review_status"] == "APPROVED"
            ]
            return _FakeResult(
                sorted(rows, key=lambda row: row["node"]["id"])[
                    : params["fetch_limit"]
                ]
            )
        if "(source:KnowledgeNode {id: $id})-[rel:REQUIRES]->" in compact:
            rows = [
                {"node": self.nodes[edge["target"]]}
                for edge in self.graph["edges"]
                if edge["source"] == params["id"]
                and edge["relation"] == "REQUIRES"
                and edge["review_status"] == "APPROVED"
                and self.nodes[edge["target"]]["review_status"] == "APPROVED"
            ]
            return _FakeResult(
                sorted(rows, key=lambda row: row["node"]["id"])[
                    : params["fetch_limit"]
                ]
            )
        if "target.id IN $frontier_ids" in compact:
            selected = set(params["selected_ids"])
            frontier = set(params["frontier_ids"])
            node_ids = {
                edge["source"]
                for edge in self.graph["edges"]
                if edge["target"] in frontier
                and edge["relation"] == "REQUIRES"
                and edge["review_status"] == "APPROVED"
                and edge["source"] not in selected
                and self.nodes[edge["source"]]["review_status"] == "APPROVED"
            }
            return _FakeResult(
                [{"node": self.nodes[node_id]} for node_id in sorted(node_ids)][
                    : params["fetch_limit"]
                ]
            )
        if "MATCH (source:KnowledgeNode)-[rel]-(neighbor:KnowledgeNode)" in compact:
            frontier = set(params["frontier_ids"])
            selected = set(params["selected_ids"])
            relations = params["relations"]
            graph_edges = list(self.graph["edges"]) + [
                {
                    "source": node["parent_id"],
                    "target": node["id"],
                    "relation": "CONTAINS",
                    "review_status": node["review_status"],
                }
                for node in self.graph["nodes"]
                if node.get("parent_id") in self.nodes
            ]
            neighbor_ids = set()
            for edge in graph_edges:
                if edge["review_status"] != "APPROVED":
                    continue
                if relations is not None and edge["relation"].lower() not in relations:
                    continue
                neighbor_id = None
                if edge["source"] in frontier:
                    neighbor_id = edge["target"]
                elif edge["target"] in frontier:
                    neighbor_id = edge["source"]
                if (
                    neighbor_id is not None
                    and neighbor_id not in selected
                    and self.nodes[neighbor_id]["review_status"] == "APPROVED"
                ):
                    neighbor_ids.add(neighbor_id)
            return _FakeResult(
                [{"node": self.nodes[node_id]} for node_id in sorted(neighbor_ids)][
                    : params["fetch_limit"]
                ]
            )
        if "MATCH (source:KnowledgeNode)-[rel]->(target:KnowledgeNode)" in compact:
            selected = set(params["selected_ids"])
            relations = params["relations"]
            graph_edges = list(self.graph["edges"]) + [
                {
                    "source": node["parent_id"],
                    "target": node["id"],
                    "relation": "CONTAINS",
                    "review_status": node["review_status"],
                    "evidence": [],
                    "confidence": None,
                }
                for node in self.graph["nodes"]
                if node.get("parent_id") in self.nodes
            ]
            rows = [
                {
                    "source_id": edge["source"],
                    "target_id": edge["target"],
                    "relation": edge["relation"].lower(),
                    "review_status": edge["review_status"],
                    "evidence": edge.get("evidence", []),
                    "confidence": edge.get("confidence"),
                }
                for edge in graph_edges
                if edge["source"] in selected
                and edge["target"] in selected
                and edge["review_status"] == "APPROVED"
                and (relations is None or edge["relation"].lower() in relations)
            ]
            rows.sort(key=lambda row: (row["source_id"], row["target_id"], row["relation"]))
            return _FakeResult(rows[: params["fetch_limit"]])
        if "neighbor_ids" in compact:
            return _FakeResult(
                [
                    {
                        "neighbor_ids": [
                            "pre-1",
                            "successor",
                            "related-a",
                            "related-b",
                        ]
                    }
                ]
            )
        if "collect(DISTINCT child)" in compact:
            return _FakeResult(
                [
                    {
                        "n": self.nodes["center"],
                        "parent": self.nodes["root"],
                        "children": [],
                        "prerequisites": [self.nodes["pre-1"]],
                        "successors": [self.nodes["successor"]],
                    }
                ]
            )
        if "RETURN prerequisite AS node" in compact:
            rows = [
                {"node": self.nodes["pre-1"], "depth": 1},
                {"node": self.nodes["pre-2"], "depth": 2},
            ]
            max_depth = int(compact.split("*1..")[1].split("]")[0])
            return _FakeResult([row for row in rows if row["depth"] <= max_depth])
        if "MATCH (source:GraphSource" in compact and "RETURN source" in compact:
            rows = [
                {"source": source}
                for source in self.graph["source_sections"]
                if source["node_id"] in params["node_ids"]
                and source.get(
                    "rights_status",
                    "ALLOWED" if source["review_status"] == "APPROVED" else "RESTRICTED",
                ) == "ALLOWED"
                and (
                    source["review_status"] == "APPROVED"
                    or (
                        params["allow_pending"]
                        and source["review_status"] == "PENDING"
                    )
                )
            ]
            return _FakeResult(
                sorted(rows, key=lambda row: row["source"]["id"])[
                    : params["fetch_limit"]
                ]
            )
        if "RETURN DISTINCT related" in compact:
            related = [self.nodes["related-a"], self.nodes["related-b"]]
            return _FakeResult(
                [{"related": node} for node in related[: params["limit"]]]
            )
        if "RETURN m.version AS version" in compact:
            return _FakeResult([{"version": self.graph["metadata"]["version"]}])
        if "MATCH (m:GraphMetadata" in compact:
            return _FakeResult([{"m": {"key": "current", **self.graph["metadata"]}}])
        if "approved_node_count" in compact:
            return _FakeResult(
                [
                    {
                        "node_count": 9,
                        "edge_count": 8,
                        "approved_node_count": 7,
                        "approved_edge_count": 7,
                    }
                ]
            )
        if "RETURN count(source) AS source_count" in compact:
            return _FakeResult([{"source_count": 6}])
        if "RETURN n" in compact and "KnowledgeNode" in compact:
            node = self.nodes.get(params["id"])
            if node is None or node["review_status"] == "REJECTED":
                return _FakeResult([])
            if node["review_status"] == "PENDING" and not params["allow_pending"]:
                return _FakeResult([])
            return _FakeResult([{"n": node}])
        raise AssertionError(f"unexpected fake Neo4j query: {compact}")


class _FakeDriver:
    def __init__(self, graph: dict[str, Any]):
        self.graph = graph
        self.queries: list[tuple[str, dict[str, Any]]] = []

    def session(self):
        return _FakeSession(self.graph, self.queries)

    def close(self):
        return None


def _write_graph(tmp_path: Path) -> Path:
    graph_file = tmp_path / "contract-graph.json"
    graph_file.write_text(json.dumps(_graph(), ensure_ascii=False), encoding="utf-8")
    return graph_file


def _fake_neo4j_repository() -> Neo4jGraphRepository:
    repository = object.__new__(Neo4jGraphRepository)
    repository.course_id = 1
    repository.driver = _FakeDriver(_graph())
    repository.include_pending = False
    return repository


def test_json_and_neo4j_return_the_same_v21_dtos(tmp_path: Path):
    json_repository = JsonGraphRepository(_write_graph(tmp_path), include_pending=False)
    neo4j_repository = _fake_neo4j_repository()

    json_prerequisites = json_repository.prerequisites("center", max_depth=2)
    neo4j_prerequisites = neo4j_repository.prerequisites("center", max_depth=2)
    assert GraphNodeListResponse(items=json_prerequisites or []).model_dump() == (
        GraphNodeListResponse(items=neo4j_prerequisites or []).model_dump()
    )

    json_sources = json_repository.sources("center")
    neo4j_sources = neo4j_repository.sources("center")
    assert GraphSourceResponse(items=json_sources or []).model_dump() == (
        GraphSourceResponse(items=neo4j_sources or []).model_dump()
    )

    json_rag = json_repository.rag_context("center", max_related=1)
    neo4j_rag = neo4j_repository.rag_context("center", max_related=1)
    assert json_rag is not None and neo4j_rag is not None
    assert GraphRagContextResponse.model_validate(json_rag).model_dump() == (
        GraphRagContextResponse.model_validate(neo4j_rag).model_dump()
    )

    json_version = GraphVersionResponse.model_validate(json_repository.version()).model_dump()
    neo4j_version = GraphVersionResponse.model_validate(neo4j_repository.version()).model_dump()
    assert {key: value for key, value in json_version.items() if key != "backend"} == {
        key: value for key, value in neo4j_version.items() if key != "backend"
    }
    assert json_version["backend"] == "json"
    assert neo4j_version["backend"] == "neo4j"

    json_match = json_repository.match(
        "请用核心概念和共享关键词解题",
        "QUESTION",
        current_node_id="center",
        limit=3,
    )
    neo4j_match = neo4j_repository.match(
        "请用核心概念和共享关键词解题",
        "QUESTION",
        current_node_id="center",
        limit=3,
    )
    assert GraphMatchResponse(items=json_match).model_dump() == (
        GraphMatchResponse(items=neo4j_match).model_dump()
    )

    mastery = [
        {"knowledge_point_id": "pre-2", "score": 90, "confidence": 0.8},
        {"knowledge_point_id": "pre-1", "score": 55, "confidence": 0.7},
        {"knowledge_point_id": "center", "score": 90, "confidence": 0.9},
    ]
    json_recommendation = {
        "course_id": 1,
        "items": json_repository.recommend(mastery, limit=5),
        "graph_version": json_repository.version()["graph_version"],
    }
    neo4j_recommendation = {
        "course_id": 1,
        "items": neo4j_repository.recommend(mastery, limit=5),
        "graph_version": neo4j_repository.version()["graph_version"],
    }
    assert GraphRecommendationResponse.model_validate(
        json_recommendation
    ).model_dump() == GraphRecommendationResponse.model_validate(
        neo4j_recommendation
    ).model_dump()

    json_subgraph = json_repository.subgraph(
        "center", depth=2, relations={"requires"}
    )
    neo4j_subgraph = neo4j_repository.subgraph(
        "center", depth=2, relations={"requires"}
    )
    assert json_subgraph is not None and neo4j_subgraph is not None
    assert GraphSubgraphResponse.model_validate(json_subgraph).model_dump() == (
        GraphSubgraphResponse.model_validate(neo4j_subgraph).model_dump()
    )


def test_demo_switch_includes_pending_but_never_rejected_or_rag_drafts(tmp_path: Path):
    repository = JsonGraphRepository(_write_graph(tmp_path), include_pending=True)

    assert repository.get_node("pending") is not None
    assert repository.get_node("rejected") is None
    assert {item["id"] for item in repository.prerequisites("center", 2) or []} == {
        "pre-1",
        "pre-2",
        "pending",
    }
    assert {item["id"] for item in repository.sources("center", False) or []} == {
        "source-approved",
    }

    rag = repository.rag_context("center", max_related=20)
    assert rag is not None
    returned = (
        rag["prerequisites"]
        + rag["successors"]
        + rag["related_nodes"]
        + rag["sources"]
    )
    assert all(item["review_status"] == "APPROVED" for item in returned)

    assert repository.match("绝密别名", "QUESTION") == []
    assert repository.match("拒绝别名", "QUESTION") == []


def test_legacy_source_section_shape_normalizes_to_graph_source():
    source = normalize_source(
        {
            "book_id": "book-1",
            "book_name": "Python教材",
            "section_title": "变量与赋值",
            "knowledge_id": "2.1.2",
            "audit_result": "APPROVED",
        }
    )
    assert source["id"].startswith("source-section-")
    assert source["node_id"] == "2.1.2"
    assert source["source_type"] == "TEXTBOOK"
    assert source["title"] == "Python教材"
    assert source["chapter"] == source["locator"] == "变量与赋值"
    assert source["review_status"] == "APPROVED"
    assert source["rights_status"] == "ALLOWED"


def test_normalize_source_rejects_unknown_rights_status():
    with pytest.raises(ValueError, match="rights_status"):
        normalize_source(
            {
                "id": "source-invalid-rights",
                "node_id": "2.1.2",
                "source_type": "TEXTBOOK",
                "title": "Python教材",
                "locator": "第 2 章",
                "review_status": "APPROVED",
                "rights_status": "PENDING",
            }
        )


def test_neo4j_match_and_recommend_queries_have_hard_candidate_bounds():
    repository = _fake_neo4j_repository()
    repository.candidate_limit = 64
    repository.recommendation_depth = 6

    repository.match("核心概念", "QUESTION", current_node_id="center", limit=3)
    repository.recommend(
        [{"knowledge_point_id": "center", "score": 55, "confidence": 0.8}],
        limit=5,
    )

    calls = repository.driver.queries
    match_query, match_params = next(
        (query, params)
        for query, params in calls
        if "toLower($text) CONTAINS" in query
    )
    recommend_query, recommend_params = next(
        (query, params)
        for query, params in calls
        if "n.node_type = 'knowledge_point'" in query
        and "toLower($text) CONTAINS" not in query
        and "fetch_limit" in params
    )
    edge_query, edge_params = next(
        (query, params)
        for query, params in calls
        if "source.id IN $node_ids" in query
    )
    assert "LIMIT $candidate_limit" in match_query
    assert match_params["candidate_limit"] == 64
    assert "REQUIRES*1.." not in recommend_query
    assert "LIMIT $fetch_limit" in recommend_query
    assert recommend_params["fetch_limit"] == 65
    assert "UNWIND $frontier_ids" not in recommend_query
    assert "LIMIT $fetch_limit" in edge_query
    assert edge_params["fetch_limit"] == 257


def _high_fanout_cycle_graph() -> dict[str, Any]:
    template = _graph()["nodes"][3]

    def node(node_id: str) -> dict[str, Any]:
        return {**template, "id": node_id, "name": node_id, "parent_id": None}

    node_ids = ["center", *[f"n{index:02d}" for index in range(10)], "x0", "x1"]
    edges = [
        {
            "source": "center",
            "target": f"n{index:02d}",
            "relation": "RELATED",
            "review_status": "APPROVED",
            "evidence": [],
            "confidence": 1.0,
        }
        for index in range(10)
    ]
    edges.extend(
        [
            {
                "source": "n00",
                "target": "n01",
                "relation": "RELATED",
                "review_status": "APPROVED",
                "evidence": [],
                "confidence": 1.0,
            },
            {
                "source": "n01",
                "target": "center",
                "relation": "RELATED",
                "review_status": "APPROVED",
                "evidence": [],
                "confidence": 1.0,
            },
            {
                "source": "n00",
                "target": "x0",
                "relation": "RELATED",
                "review_status": "APPROVED",
                "evidence": [],
                "confidence": 1.0,
            },
            {
                "source": "x0",
                "target": "x1",
                "relation": "RELATED",
                "review_status": "APPROVED",
                "evidence": [],
                "confidence": 1.0,
            },
        ]
    )
    return {
        "metadata": {**_graph()["metadata"], "version": "bounded-test"},
        "nodes": [node(node_id) for node_id in node_ids],
        "edges": edges,
        "source_sections": [],
    }


def _requires_chain_graph() -> dict[str, Any]:
    template = _graph()["nodes"][3]
    node_ids = ["p2", "p1", "p0", "center"]
    nodes = [
        {**template, "id": node_id, "name": node_id, "parent_id": None}
        for node_id in node_ids
    ]
    edges = [
        {
            "source": source,
            "target": target,
            "relation": "REQUIRES",
            "review_status": "APPROVED",
            "evidence": [],
            "confidence": 1.0,
        }
        for source, target in (("p2", "p1"), ("p1", "p0"), ("p0", "center"))
    ]
    return {
        "metadata": {**_graph()["metadata"], "version": "chain-test"},
        "nodes": nodes,
        "edges": edges,
        "source_sections": [],
    }


def test_neo4j_subgraph_uses_bounded_direct_hop_bfs_on_high_fanout_cycle():
    repository = object.__new__(Neo4jGraphRepository)
    repository.course_id = 1
    repository.driver = _FakeDriver(_high_fanout_cycle_graph())
    repository.include_pending = False
    repository.subgraph_node_limit = 6
    repository.subgraph_hop_limit = 3
    repository.subgraph_edge_limit = 6

    with pytest.raises(GraphQueryBudgetExceeded, match="node budget"):
        repository.subgraph("center", depth=5, relations={"related"})

    bfs_calls = [
        (query, params)
        for query, params in repository.driver.queries
        if "UNWIND $frontier_ids AS frontier_id" in query
        and "[rel]-(neighbor:KnowledgeNode)" in query
    ]
    assert bfs_calls
    assert all(
        "*1.." not in query and "collect(" not in query
        for query, _ in bfs_calls
    )
    assert all(len(params["frontier_ids"]) <= 6 for _, params in bfs_calls)
    assert all(params["per_frontier_fetch_limit"] <= 4 for _, params in bfs_calls)
    assert all(params["layer_fetch_limit"] <= 4 for _, params in bfs_calls)
    assert all(len(params["selected_ids"]) <= 6 for _, params in bfs_calls)
    assert all(
        "UNWIND $frontier_ids AS frontier_id CALL (frontier_id) {"
        in " ".join(query.split())
        for query, _ in bfs_calls
    )
    assert all(
        "$course_id AS course_id" not in " ".join(query.split())
        for query, _ in bfs_calls
    )

    complete = object.__new__(Neo4jGraphRepository)
    complete.course_id = 1
    complete.driver = _FakeDriver(_high_fanout_cycle_graph())
    complete.include_pending = False
    complete.subgraph_node_limit = 20
    complete.subgraph_hop_limit = 20
    complete.subgraph_edge_limit = 30
    first = complete.subgraph("center", depth=5, relations={"related"})
    second = complete.subgraph("center", depth=5, relations={"related"})
    assert first == second
    assert first is not None
    assert len({node["id"] for node in first["nodes"]}) == len(first["nodes"]) == 13
    assert {"center", "n00", "n09", "x0", "x1"} <= {
        node["id"] for node in first["nodes"]
    }
    assert len(first["edges"]) == 14


def test_neo4j_context_prerequisite_and_source_results_have_query_limits():
    repository = _fake_neo4j_repository()
    repository.context_item_limit = 7
    repository.prerequisite_node_limit = 8
    repository.subgraph_hop_limit = 3
    repository.source_limit = 9

    repository.context("center")
    repository.prerequisites("center", max_depth=5)
    repository.sources("center")

    calls = repository.driver.queries
    context_calls = [
        (query, params)
        for query, params in calls
        if any(
            marker in query
            for marker in (
                "MATCH (child:KnowledgeNode {parent_id: $id})",
                "(target:KnowledgeNode {id: $id})",
                "(source:KnowledgeNode {id: $id})-[rel:REQUIRES]->",
            )
        )
    ]
    prerequisite_calls = [
        (query, params)
        for query, params in calls
        if "UNWIND $frontier_ids AS frontier_id" in query
        and "MATCH (prerequisite:KnowledgeNode)-[rel:REQUIRES]->" in query
    ]
    source_call = next(
        (query, params)
        for query, params in calls
        if "MATCH (source:GraphSource)" in query
    )
    assert len(context_calls) == 3
    assert all("LIMIT $fetch_limit" in query for query, _ in context_calls)
    assert all(params["fetch_limit"] == 8 for _, params in context_calls)
    assert prerequisite_calls
    assert all(
        "*1.." not in query
        and "collect(" not in query
        and "LIMIT $per_frontier_fetch_limit" in query
        and "LIMIT $layer_fetch_limit" in query
        for query, _ in prerequisite_calls
    )
    assert all(
        "UNWIND $frontier_ids AS frontier_id CALL (frontier_id) {"
        in " ".join(query.split())
        for query, _ in prerequisite_calls
    )
    assert all(
        "$course_id AS course_id" not in " ".join(query.split())
        for query, _ in prerequisite_calls
    )
    assert all(
        params["per_frontier_fetch_limit"] <= 4
        and params["layer_fetch_limit"] <= 4
        for _, params in prerequisite_calls
    )
    assert "LIMIT $fetch_limit" in source_call[0]
    assert source_call[1]["fetch_limit"] == 10


def test_exact_node_cap_fails_closed_before_a_deeper_layer_is_omitted():
    graph = _requires_chain_graph()

    subgraph_repository = object.__new__(Neo4jGraphRepository)
    subgraph_repository.course_id = 1
    subgraph_repository.driver = _FakeDriver(graph)
    subgraph_repository.include_pending = False
    subgraph_repository.subgraph_node_limit = 3
    subgraph_repository.subgraph_hop_limit = 2
    subgraph_repository.subgraph_edge_limit = 20
    with pytest.raises(GraphQueryBudgetExceeded, match="node budget exhausted"):
        subgraph_repository.subgraph("center", depth=5, relations={"requires"})

    prerequisite_repository = object.__new__(Neo4jGraphRepository)
    prerequisite_repository.course_id = 1
    prerequisite_repository.driver = _FakeDriver(graph)
    prerequisite_repository.include_pending = False
    prerequisite_repository.prerequisite_node_limit = 2
    prerequisite_repository.subgraph_hop_limit = 2
    with pytest.raises(GraphQueryBudgetExceeded, match="node budget exhausted"):
        prerequisite_repository.prerequisites("center", max_depth=5)

    recommend_repository = object.__new__(Neo4jGraphRepository)
    recommend_repository.course_id = 1
    recommend_repository.driver = _FakeDriver(graph)
    recommend_repository.include_pending = False
    recommend_repository.candidate_limit = 3
    recommend_repository.subgraph_hop_limit = 2
    recommend_repository.recommendation_depth = 5
    with pytest.raises(GraphQueryBudgetExceeded, match="candidate budget exceeded"):
        recommend_repository.recommend(
            [{"knowledge_point_id": "center", "score": 55, "confidence": 0.9}],
            limit=5,
        )


def test_high_fanout_prerequisite_and_recommendation_layers_fail_closed():
    graph = _high_fanout_cycle_graph()
    graph["edges"] = [
        {
            "source": f"n{index:02d}",
            "target": "center",
            "relation": "REQUIRES",
            "review_status": "APPROVED",
            "evidence": [],
            "confidence": 1.0,
        }
        for index in range(10)
    ]
    graph["edges"].extend(
        [
            {
                "source": "n00",
                "target": "n01",
                "relation": "REQUIRES",
                "review_status": "APPROVED",
                "evidence": [],
                "confidence": 1.0,
            },
            {
                "source": "n01",
                "target": "n00",
                "relation": "REQUIRES",
                "review_status": "APPROVED",
                "evidence": [],
                "confidence": 1.0,
            },
        ]
    )

    prerequisite_repository = object.__new__(Neo4jGraphRepository)
    prerequisite_repository.course_id = 1
    prerequisite_repository.driver = _FakeDriver(graph)
    prerequisite_repository.include_pending = False
    prerequisite_repository.prerequisite_node_limit = 3
    prerequisite_repository.subgraph_hop_limit = 3
    with pytest.raises(GraphQueryBudgetExceeded, match="traversal budget"):
        prerequisite_repository.prerequisites("center", max_depth=2)

    recommend_repository = object.__new__(Neo4jGraphRepository)
    recommend_repository.course_id = 1
    recommend_repository.driver = _FakeDriver(graph)
    recommend_repository.include_pending = False
    recommend_repository.candidate_limit = 4
    recommend_repository.subgraph_hop_limit = 3
    recommend_repository.recommendation_depth = 2
    with pytest.raises(GraphQueryBudgetExceeded, match="candidate budget exceeded"):
        recommend_repository.recommend(
            [{"knowledge_point_id": "center", "score": 55, "confidence": 0.9}],
            limit=3,
        )


def test_recommendation_supports_valid_prerequisite_chains_deeper_than_ten_hops():
    graph = _requires_chain_graph()
    template = graph["nodes"][0]
    node_ids = [*[f"p{index}" for index in range(12, -1, -1)], "center"]
    graph["nodes"] = [
        {**template, "id": node_id, "name": node_id, "parent_id": None}
        for node_id in node_ids
    ]
    graph["edges"] = [
        {
            "source": source,
            "target": target,
            "relation": "REQUIRES",
            "review_status": "APPROVED",
            "evidence": [],
            "confidence": 1.0,
        }
        for source, target in zip(node_ids, node_ids[1:])
    ]
    repository = object.__new__(Neo4jGraphRepository)
    repository.course_id = 1
    repository.driver = _FakeDriver(graph)
    repository.include_pending = False
    repository.candidate_limit = 64

    recommendations = repository.recommend(
        [{"knowledge_point_id": "center", "score": 55, "confidence": 0.9}],
        limit=5,
    )

    assert [item["node"]["id"] for item in recommendations] == ["p12"]
    assert "已审核先修链" in recommendations[0]["evidence"][0]


def test_subgraph_induced_edges_have_a_hard_budget():
    repository = object.__new__(Neo4jGraphRepository)
    repository.course_id = 1
    repository.driver = _FakeDriver(_high_fanout_cycle_graph())
    repository.include_pending = False
    repository.subgraph_node_limit = 20
    repository.subgraph_hop_limit = 20
    repository.subgraph_edge_limit = 3

    with pytest.raises(GraphQueryBudgetExceeded, match="edge budget"):
        repository.subgraph("center", depth=5, relations={"related"})

    edge_query, params = next(
        (query, params)
        for query, params in repository.driver.queries
        if "MATCH (source:KnowledgeNode)-[rel]->(target:KnowledgeNode)" in query
    )
    assert "LIMIT $fetch_limit" in edge_query
    assert params["fetch_limit"] == 4


def test_budget_failure_never_falls_back_to_unbounded_json():
    class _Neo4jBudgetRepository:
        def subgraph(self, *_args: Any, **_kwargs: Any):
            raise GraphQueryBudgetExceeded("bounded failure")

    class _JsonFallbackRepository:
        course_id = 1
        called = False

        def subgraph(self, *_args: Any, **_kwargs: Any):
            self.called = True
            return {"unsafe": True}

    json_repository = _JsonFallbackRepository()
    service = GraphService(json_repository, _Neo4jBudgetRepository())  # type: ignore[arg-type]
    service.backend_preference = "auto"

    with pytest.raises(GraphQueryBudgetExceeded, match="bounded failure"):
        service.subgraph("center", depth=5)
    assert json_repository.called is False
