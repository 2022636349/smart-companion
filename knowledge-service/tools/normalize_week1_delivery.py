"""Normalize the two C-team week-one deliveries into the frozen V2 contract.

The transformation is deliberately idempotent. It does not fabricate human
approval: generated review rows are marked PENDING and keep reviewer/time empty.
"""

from __future__ import annotations

import csv
import json
import re
from pathlib import Path
from typing import Any, Iterable


ROOT = Path(__file__).resolve().parents[1]
GRAPH_PATH = ROOT / "knowledge_graph_v2.json"


def as_string_list(value: Any) -> list[str]:
    if value is None:
        return []
    if isinstance(value, list):
        return [str(item).strip() for item in value if str(item).strip()]
    text = str(value).strip()
    return [text] if text else []


def node_type(level: int) -> str:
    return {0: "course", 1: "module", 2: "chapter", 3: "knowledge_point"}.get(
        level, "knowledge_point"
    )


def read_csv(path: Path) -> list[dict[str, str]]:
    raw = path.read_bytes()
    for encoding in ("utf-8-sig", "utf-8", "gb18030"):
        try:
            text = raw.decode(encoding)
            return list(csv.DictReader(text.splitlines()))
        except UnicodeDecodeError:
            continue
    raise UnicodeError(f"无法识别 CSV 编码: {path}")


def write_csv(path: Path, fieldnames: list[str], rows: Iterable[dict[str, Any]]) -> None:
    with path.open("w", encoding="utf-8-sig", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=fieldnames, extrasaction="ignore")
        writer.writeheader()
        writer.writerows(rows)


def normalize_graph() -> dict[str, Any]:
    graph = json.loads(GRAPH_PATH.read_text(encoding="utf-8"))
    metadata = dict(graph.get("metadata") or {})
    already_frozen = metadata.get("requires_semantics") == "source_is_prerequisite_of_target"

    nodes: list[dict[str, Any]] = []
    for source in graph.get("nodes", []):
        level = int(source.get("level", 3))
        nodes.append(
            {
                "id": str(source["id"]),
                "name": str(source.get("name", "")).strip(),
                "level": level,
                "node_type": source.get("node_type") or node_type(level),
                "parent_id": None if source.get("parent_id") is None else str(source["parent_id"]),
                "description": source.get("description") or None,
                "aliases": as_string_list(source.get("aliases")),
                "keywords": as_string_list(source.get("keywords")),
                "difficulty": source.get("difficulty") or None,
                "importance": source.get("importance"),
                "estimated_minutes": source.get("estimated_minutes"),
                "learning_objectives": as_string_list(source.get("learning_objectives")),
                "common_misconceptions": as_string_list(source.get("common_misconceptions")),
                "example_code": as_string_list(source.get("example_code")),
                "review_status": source.get("review_status") or "PENDING",
                "content_version": int(source.get("content_version") or 1),
                "rights_status": source.get("rights_status") or "PENDING",
                "reviewed_by": source.get("reviewed_by"),
                "reviewed_at": source.get("reviewed_at"),
            }
        )

    edges: list[dict[str, Any]] = []
    for source in graph.get("edges", []):
        old_source = str(source["source"])
        old_target = str(source["target"])
        # Original delivery used dependent -> prerequisite. The frozen contract
        # requires prerequisite(source) -> dependent(target).
        edge_source, edge_target = (
            (old_source, old_target) if already_frozen else (old_target, old_source)
        )
        edges.append(
            {
                "source": edge_source,
                "target": edge_target,
                "relation": str(source.get("relation") or "REQUIRES").upper(),
                "review_status": source.get("review_status") or "PENDING",
                "evidence": as_string_list(source.get("evidence")),
                "confidence": source.get("confidence"),
            }
        )

    metadata.update(
        {
            "version": "2.0.0-draft.1",
            "node_count": len(nodes),
            "edge_count": len(edges),
            "updated_at": "2026-08-02",
            "requires_semantics": "source_is_prerequisite_of_target",
        }
    )
    normalized = {"metadata": metadata, "nodes": nodes, "edges": edges}
    GRAPH_PATH.write_text(
        json.dumps(normalized, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    return normalized


def normalize_books() -> list[dict[str, str]]:
    path = ROOT / "books.csv"
    rows = read_csv(path)
    for row in rows:
        row["rights_status"] = row.get("rights_status") or "PENDING"
    fields = [
        "book_id",
        "book_name",
        "publisher",
        "author",
        "publish_year",
        "catalog_status",
        "rights_status",
    ]
    write_csv(path, fields, rows)
    return rows


def normalize_match_audit(graph: dict[str, Any]) -> None:
    path = ROOT / "knowledge_match_audit.csv"
    rows = read_csv(path)
    names = {node["id"]: node["name"] for node in graph["nodes"]}
    for row in rows:
        if row.get("knowledge_id") in names:
            row["knowledge_name"] = names[row["knowledge_id"]]
    fields = [
        "audit_id",
        "book_id",
        "book_name",
        "knowledge_id",
        "knowledge_name",
        "match_level",
        "audit_result",
        "audit_operator",
        "audit_time",
        "remark",
    ]
    write_csv(path, fields, rows)


def build_prerequisite_review_queue(graph: dict[str, Any]) -> None:
    path = ROOT / "prerequisite_audit.csv"
    fields = [
        "audit_id",
        "target_knowledge_id",
        "prerequisite_knowledge_id",
        "audit_result",
        "audit_operator",
        "audit_time",
        "remark",
    ]
    rows = [
        {
            "audit_id": index,
            "target_knowledge_id": edge["target"],
            "prerequisite_knowledge_id": edge["source"],
            "audit_result": "PENDING",
            "audit_operator": "",
            "audit_time": "",
            "remark": "由V2 REQUIRES关系生成，待人工复核",
        }
        for index, edge in enumerate(graph["edges"], start=1)
        if edge["relation"] == "REQUIRES"
    ]
    write_csv(path, fields, rows)


def compact_title(value: str) -> str:
    return re.sub(r"[\s（）()·—_，,]", "", value).lower()


def build_source_review_queue(books: list[dict[str, str]]) -> None:
    path = ROOT / "source_section_audit.csv"
    fields = [
        "audit_id",
        "book_id",
        "book_name",
        "section_title",
        "knowledge_id",
        "audit_result",
        "audit_operator",
        "audit_time",
        "remark",
    ]
    candidates = read_csv(ROOT / "high_confidence.csv")
    normalized_books = [(book, compact_title(book["book_name"])) for book in books]
    rows: list[dict[str, Any]] = []
    for index, candidate in enumerate(candidates[:100], start=1):
        title_key = compact_title(candidate.get("book_title", ""))
        matches = [book for book, key in normalized_books if title_key and title_key in key]
        book = matches[0] if len(matches) == 1 else None
        section = (
            candidate.get("subsection_title")
            or candidate.get("section_title")
            or candidate.get("chapter_title")
            or candidate.get("query_text")
            or ""
        )
        rows.append(
            {
                "audit_id": index,
                "book_id": book["book_id"] if book else "",
                "book_name": book["book_name"] if book else candidate.get("book_title", ""),
                "section_title": section,
                "knowledge_id": candidate.get("matched_node_id", ""),
                "audit_result": "PENDING",
                "audit_operator": "",
                "audit_time": "",
                "remark": (
                    f"自动匹配候选，score={candidate.get('similarity_score', '')}；"
                    "书目无法唯一对应时 book_id 留空"
                ),
            }
        )
    write_csv(path, fields, rows)


def main() -> None:
    graph = normalize_graph()
    books = normalize_books()
    normalize_match_audit(graph)
    build_prerequisite_review_queue(graph)
    build_source_review_queue(books)
    print(
        f"已规范化：{len(graph['nodes'])} 节点、{len(graph['edges'])} 条 REQUIRES、"
        f"{len(books)} 本教材。"
    )


if __name__ == "__main__":
    main()
