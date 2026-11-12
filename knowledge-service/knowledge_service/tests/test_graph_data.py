from __future__ import annotations

import csv
import json
from collections import defaultdict, deque
from pathlib import Path

from jsonschema import Draft7Validator

from knowledge_service.app.repository.json_repo import JsonGraphRepository
from tools.audit_knowledge_graph import audit


ROOT = Path(__file__).resolve().parents[2]


def read_csv(name: str) -> list[dict[str, str]]:
    with (ROOT / name).open("r", encoding="utf-8-sig", newline="") as handle:
        return list(csv.DictReader(handle))


def load_graph() -> dict:
    return json.loads((ROOT / "knowledge_graph_v2.json").read_text(encoding="utf-8"))


def canonical_audit_locator(value: str) -> str:
    return value.replace("（第", "，第").replace("页）", "页")


def test_v2_graph_validates_against_whole_document_schema():
    graph = load_graph()
    schema = json.loads((ROOT / "knowledge_graph.schema.json").read_text(encoding="utf-8"))
    errors = sorted(Draft7Validator(schema).iter_errors(graph), key=lambda item: list(item.path))
    assert errors == []
    assert graph["metadata"]["node_count"] == len(graph["nodes"]) == 150
    assert graph["metadata"]["edge_count"] == len(graph["edges"]) == 119
    assert graph["metadata"]["requires_semantics"] == "source_is_prerequisite_of_target"

    audit_result = audit(graph)
    assert audit_result["passed"] is True
    assert audit_result["summary"]["source_node_count"] == len(
        {source["node_id"] for source in graph["source_sections"]}
    )
    assert audit_result["summary"]["source_item_count"] == len(
        graph["source_sections"]
    )
    assert audit_result["summary"]["source_review_statuses"] == {
        "APPROVED": len(graph["source_sections"])
    }


def test_graph_has_no_dangling_references_duplicates_or_requires_cycles():
    graph = load_graph()
    node_ids = [node["id"] for node in graph["nodes"]]
    assert len(node_ids) == len(set(node_ids))
    id_set = set(node_ids)
    assert all(node["parent_id"] is None or node["parent_id"] in id_set for node in graph["nodes"])

    pairs = [(edge["source"], edge["target"]) for edge in graph["edges"]]
    assert len(pairs) == len(set(pairs))
    assert all(source in id_set and target in id_set and source != target for source, target in pairs)

    outgoing: dict[str, list[str]] = defaultdict(list)
    indegree = {node_id: 0 for node_id in id_set}
    for source, target in pairs:
        outgoing[source].append(target)
        indegree[target] += 1
    queue = deque(node_id for node_id, degree in indegree.items() if degree == 0)
    visited = 0
    while queue:
        current = queue.popleft()
        visited += 1
        for target in outgoing[current]:
            indegree[target] -= 1
            if indegree[target] == 0:
                queue.append(target)
    assert visited == len(id_set)


def test_review_csvs_and_published_snapshot_have_matching_approvals():
    graph = load_graph()
    names = {node["id"]: node["name"] for node in graph["nodes"]}

    books_path = ROOT / "books.csv"
    match_path = ROOT / "knowledge_match_audit.csv"
    assert books_path.read_bytes().startswith(b"\xef\xbb\xbf")
    assert match_path.read_bytes().startswith(b"\xef\xbb\xbf")

    books = read_csv("books.csv")
    matches = read_csv("knowledge_match_audit.csv")
    prerequisites = read_csv("prerequisite_audit.csv")
    sources = read_csv("source_section_audit.csv")
    assert len(books) == 17
    assert {book["rights_status"] for book in books} <= {
        "PENDING",
        "ALLOWED",
        "RESTRICTED",
    }
    assert len(matches) == 100
    assert all(names[row["knowledge_id"]] == row["knowledge_name"] for row in matches)
    assert len(prerequisites) == len(graph["edges"]) == 119
    assert {row["audit_result"] for row in prerequisites} == {
        "PENDING",
        "APPROVED",
        "REJECTED",
    }
    assert {row["audit_result"] for row in sources} == {"PENDING", "APPROVED"}

    edge_by_pair = {
        (edge["source"], edge["target"]): edge for edge in graph["edges"]
    }
    assert set(edge_by_pair) == {
        (row["prerequisite_knowledge_id"], row["target_knowledge_id"])
        for row in prerequisites
    }
    for row in prerequisites:
        edge = edge_by_pair[
            (row["prerequisite_knowledge_id"], row["target_knowledge_id"])
        ]
        assert edge["review_status"] == row["audit_result"]

    published_sources = {source["id"]: source for source in graph["source_sections"]}
    approved_source_rows = [
        row for row in sources if row["audit_result"] == "APPROVED"
    ]
    assert set(published_sources) == {
        f"source-section-{row['audit_id']}" for row in approved_source_rows
    }
    for row in approved_source_rows:
        source = published_sources[f"source-section-{row['audit_id']}"]
        assert source["node_id"] == row["knowledge_id"]
        assert source["title"] == row["book_name"]
        assert source["locator"] == canonical_audit_locator(row["section_title"])
        assert source["review_status"] == row["audit_result"] == "APPROVED"
        assert source.get("excerpt") in (None, "")

    books_by_id = {book["book_id"]: book for book in books}
    assert all(
        books_by_id[row["book_id"]]["rights_status"] == "RESTRICTED"
        for row in approved_source_rows
    )

    schema = json.loads((ROOT / "knowledge_graph.schema.json").read_text(encoding="utf-8"))
    book_validator = Draft7Validator(schema["definitions"]["book"])
    source_validator = Draft7Validator(schema["definitions"]["source_section"])
    assert all(not list(book_validator.iter_errors(row)) for row in books)
    assert all(not list(source_validator.iter_errors(row)) for row in sources)
    assert all(
        not list(source_validator.iter_errors(source))
        for source in graph["source_sections"]
    )


def test_real_graph_rag_returns_only_approved_content_and_rejects_pending_center():
    repository = JsonGraphRepository(ROOT / "knowledge_graph_v2.json")
    approved_source = repository.source_records[0]
    rag = repository.rag_context(approved_source["node_id"], max_related=20)
    assert rag is not None
    assert rag["knowledge_point"]["review_status"] == "APPROVED"
    assert rag["context_status"] == "READY"
    assert rag["graph_version"] == repository.metadata["version"]
    assert rag["sources"]
    returned = (
        rag["prerequisites"]
        + rag["successors"]
        + rag["related_nodes"]
        + rag["sources"]
    )
    assert all(item["review_status"] == "APPROVED" for item in returned)

    pending_node_id = next(
        node["id"]
        for node in repository.nodes.values()
        if node["review_status"] == "PENDING"
    )
    assert repository.get_node(pending_node_id) is None
    assert repository.rag_context(pending_node_id) is None
    assert repository.subgraph(pending_node_id, depth=1) is None


def test_real_graph_question_match_is_stable_and_review_filtered():
    repository = JsonGraphRepository(ROOT / "knowledge_graph_v2.json")
    text = "请编写for循环配合range计算1到5的和"
    first = repository.match(text, "QUESTION", limit=5)
    second = repository.match(text, "QUESTION", limit=5)

    assert first == second
    assert first
    assert first[0]["node"]["id"] == "3.2.2"
    assert "KEYWORD" in first[0]["matched_by"]
    assert all(item["node"]["review_status"] == "APPROVED" for item in first)

    pending = next(
        node
        for node in repository.nodes.values()
        if node["review_status"] == "PENDING" and node.get("aliases")
    )
    hidden_match = repository.match(pending["aliases"][0], "QUESTION", limit=20)
    assert pending["id"] not in {item["node"]["id"] for item in hidden_match}
