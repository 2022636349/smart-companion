from __future__ import annotations

import csv
import json
from collections import defaultdict, deque
from pathlib import Path

from knowledge_service.app.repository.json_repo import JsonGraphRepository
from knowledge_service.app.services.graph_service import GraphService


ROOT = Path(__file__).resolve().parents[2]
GRAPH_FILE = ROOT / "knowledge_graph_v2.json"
PENDING_EVIDENCE_GAPS = {"6.2.2", "6.2.3", "6.4.3"}
CORE_RECOMMENDATION_SPINE = [
    "1.3.2",
    "1.4.1",
    "1.4.2",
    "2.1.1",
    "2.1.4",
    "2.2.2",
    "3.1.1",
    "3.1.2",
    "3.2.1",
    "3.2.3",
    "5.1.1",
    "5.1.2",
    "4.2.1",
    "3.4.1",
    "3.4.2",
    "7.1.1",
    "7.1.2",
    "7.1.3",
]


def load_graph() -> dict:
    return json.loads(GRAPH_FILE.read_text(encoding="utf-8"))


def read_csv(name: str) -> list[dict[str, str]]:
    with (ROOT / name).open("r", encoding="utf-8-sig", newline="") as handle:
        return list(csv.DictReader(handle))


def test_cycle_five_release_counts_sources_and_content_quality():
    graph = load_graph()
    approved_nodes = [node for node in graph["nodes"] if node["review_status"] == "APPROVED"]
    approved_points = [node for node in approved_nodes if node["node_type"] == "knowledge_point"]
    pending_nodes = {node["id"] for node in graph["nodes"] if node["review_status"] == "PENDING"}
    approved_sources = [
        source for source in graph["source_sections"] if source["review_status"] == "APPROVED"
    ]

    assert graph["metadata"]["version"] == "2.4.0-c5-gc.1"
    assert graph["metadata"]["published_batch"] == "C5-GC-01-B03"
    assert graph["metadata"]["approved_node_count"] == len(approved_nodes) == 147
    assert len(approved_points) == 102
    assert pending_nodes == PENDING_EVIDENCE_GAPS
    assert graph["metadata"]["approved_edge_count"] == 106
    assert graph["metadata"]["source_count"] == len(approved_sources) == 147
    assert {source["node_id"] for source in approved_sources} == {
        node["id"] for node in approved_nodes
    }
    assert all(source.get("excerpt") in (None, "") for source in approved_sources)

    for node in approved_points:
        assert node["description"]
        assert len(node["aliases"]) >= 2
        assert len(node["keywords"]) >= 4
        assert len(node["learning_objectives"]) >= 2
        assert len(node["common_misconceptions"]) >= 2
        assert node["example_code"]
        assert node["importance"] is not None
        assert node["estimated_minutes"] is not None
        for example in node["example_code"]:
            compile(example, f"cycle5-example-{node['id']}", "exec")

    source_audits = read_csv("source_section_audit.csv")
    cycle_five_rows = [row for row in source_audits if row["audit_operator"] == "C5-GC-01"]
    assert len(cycle_five_rows) == 96
    assert all(row["audit_result"] == "APPROVED" for row in cycle_five_rows)
    assert PENDING_EVIDENCE_GAPS.isdisjoint({row["knowledge_id"] for row in cycle_five_rows})


def test_cycle_five_prerequisites_are_approved_knowledge_facts_and_core_is_connected():
    graph = load_graph()
    nodes = {node["id"]: node for node in graph["nodes"]}
    status_counts: dict[str, int] = defaultdict(int)
    approved_pairs: set[tuple[str, str]] = set()
    for edge in graph["edges"]:
        status_counts[edge["review_status"]] += 1
        if edge["review_status"] != "APPROVED":
            continue
        approved_pairs.add((edge["source"], edge["target"]))
        assert edge["relation"] == "REQUIRES"
        assert nodes[edge["source"]]["review_status"] == "APPROVED"
        assert nodes[edge["target"]]["review_status"] == "APPROVED"
        assert nodes[edge["source"]]["node_type"] == "knowledge_point"
        assert nodes[edge["target"]]["node_type"] == "knowledge_point"
        assert edge["evidence"]
        assert edge["confidence"] is not None

    assert dict(status_counts) == {"APPROVED": 106, "REJECTED": 10, "PENDING": 3}
    assert set(zip(CORE_RECOMMENDATION_SPINE, CORE_RECOMMENDATION_SPINE[1:])) <= approved_pairs

    outgoing: dict[str, list[str]] = defaultdict(list)
    for source, target in approved_pairs:
        outgoing[source].append(target)
    start = CORE_RECOMMENDATION_SPINE[0]
    reachable = {start}
    queue = deque([start])
    while queue:
        current = queue.popleft()
        for target in outgoing[current]:
            if target not in reachable:
                reachable.add(target)
                queue.append(target)
    assert set(CORE_RECOMMENDATION_SPINE) <= reachable


def test_cycle_five_strict_match_and_recommendation_regression(monkeypatch):
    repository = JsonGraphRepository(GRAPH_FILE, include_pending=False)
    cases = {
        "使用列表推导式筛选偶数并生成平方列表": "4.2.4",
        "for name score in scores.items字典遍历键值对": "4.5.3",
        "可变参数": "5.2.3",
        "路径处理": "7.2.3",
        "JSON序列化": "7.3.2",
        "random.seed和randint生成可复现伪随机数": "8.2.2",
    }
    for text, expected_node_id in cases.items():
        matches = repository.match(text, "QUESTION", limit=5)
        assert matches
        assert matches[0]["node"]["id"] == expected_node_id
        assert matches[0]["node"]["review_status"] == "APPROVED"
        assert matches[0]["requires_review"] is False

    for node_id in PENDING_EVIDENCE_GAPS:
        node = repository.nodes[node_id]
        hidden = repository.match(node["aliases"][0], "QUESTION", limit=20)
        assert node_id not in {item["node"]["id"] for item in hidden}

    approved_requires = [
        edge
        for edge in repository.edges
        if edge["relation"] == "requires" and edge["review_status"] == "APPROVED"
    ]
    incoming: dict[str, set[str]] = defaultdict(set)
    for edge in approved_requires:
        incoming[edge["target_id"]].add(edge["source_id"])

    target_id = "7.1.1"
    ancestors: set[str] = set()
    queue = deque([target_id])
    while queue:
        current = queue.popleft()
        for prerequisite_id in incoming[current]:
            if prerequisite_id not in ancestors:
                ancestors.add(prerequisite_id)
                queue.append(prerequisite_id)
    mastery = [
        {"knowledge_point_id": node_id, "score": 90, "confidence": 0.9}
        for node_id in sorted(ancestors)
    ]
    mastery.append({"knowledge_point_id": target_id, "score": 45, "confidence": 0.9})
    monkeypatch.setenv("GRAPH_BACKEND", "json")
    recommendation = GraphService(repository).recommend_next(mastery, limit=10)
    assert recommendation["graph_version"] == "2.4.0-c5-gc.1"
    assert recommendation["items"]
    target_recommendation = next(
        item for item in recommendation["items"] if item["node"]["id"] == target_id
    )
    assert target_recommendation["evidence"]
    assert all(item["node"]["review_status"] == "APPROVED" for item in recommendation["items"])


def test_cycle_five_progress_records_three_bounded_batches_and_evidence_gaps():
    progress = json.loads((ROOT / "content-progress.json").read_text(encoding="utf-8"))
    cycle_five = [
        item for item in progress["batch_history"] if item["batch_id"].startswith("C5-GC-01-")
    ]
    assert [item["node_count"] for item in cycle_five] == [25, 24, 25]
    assert all(20 <= item["node_count"] <= 30 for item in cycle_five)
    assert sum(item["approved_knowledge_point_count"] for item in cycle_five) == 71
    assert sum(item.get("pending_knowledge_point_count", 0) for item in cycle_five) == 3
    joined_notes = "\n".join(progress["notes"])
    assert all(node_id in joined_notes for node_id in PENDING_EVIDENCE_GAPS)
    assert progress["approved_node_count"] == 147
    assert progress["approved_edge_count"] == 106
    assert progress["approved_source_count"] == 147
