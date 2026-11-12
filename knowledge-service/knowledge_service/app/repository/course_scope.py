"""Course-scoped persistence primitives shared by all Neo4j repositories.

Only application-authored Cypher reaches this module. Every graph node pattern
is stamped with the trusted service course, including release/lease/receipt
patterns, so an omitted predicate cannot silently query the Python course.
"""
from __future__ import annotations

import re
from typing import Any


COURSE_LABEL_KEYS = {
    "KnowledgeNode": ("knowledge_node_id", "id"),
    "GraphSource": ("graph_source_id", "id"),
    "GraphMetadata": ("graph_metadata_key", "key"),
    "GraphRelease": ("graph_release_version", "version"),
    "GraphReleaseNode": ("graph_release_node_key", "release_key"),
    "GraphReleaseSource": ("graph_release_source_key", "release_key"),
    "GraphActivationLease": ("graph_activation_lease_key", "key"),
    "GraphOperationReceipt": ("graph_operation_receipt_key", "receipt_key"),
    "GraphDraftBatch": ("graph_draft_batch_id", "batch_id"),
}
_LABELS = (*COURSE_LABEL_KEYS, "InactiveKnowledgeNode", "InactiveGraphSource")
_LABEL_PART = "|".join(re.escape(label) for label in sorted(_LABELS, key=len, reverse=True))
_PATTERN = re.compile(r"(:(?:" + _LABEL_PART + r"))\s*(\{|\))")
_ROW_PATTERN = re.compile(
    r"(:(?:" + _LABEL_PART + r"))\s*\{\s*"
    r"(?P<key>[A-Za-z_][A-Za-z0-9_]*)\s*:\s*row\.(?P=key)\s*\}"
)


def valid_course_id(course_id: int) -> int:
    if isinstance(course_id, bool) or not isinstance(course_id, int) or course_id < 1:
        raise ValueError("course_id must be a positive integer")
    return course_id


def scoped_statement(statement: str) -> str:
    if statement.lstrip().upper().startswith(("CREATE CONSTRAINT", "CREATE INDEX", "DROP ")):
        return statement
    statement = _ROW_PATTERN.sub(
        lambda match: f"{match.group(1)} {{course_id: $course_id, {match.group('key')}: row.{match.group('key')}}}",
        statement,
    )
    return _PATTERN.sub(
        lambda match: (
            f"{match.group(1)} {{course_id: $course_id, "
            if match.group(2) == "{"
            else f"{match.group(1)} {{course_id: $course_id}})"
        ),
        statement,
    ).replace("{course_id: $course_id, course_id: $course_id, ", "{course_id: $course_id, ")


def ensure_course_schema(session: Any) -> None:
    """Idempotently tag pre-C9 records as course 1, preserving their content.

    New compound constraints are installed before old global constraints are
    removed. No nodes, relationships, or manual review decisions are deleted.
    """
    for label in _LABELS:
        session.run(
            f"MATCH (n:{label}) WHERE n.course_id IS NULL SET n.course_id = 1"
        ).consume()
    # Active/staged relationships are always between nodes in one course; tag
    # legacy relationships before scoped services begin serving course 1.
    session.run(
        "MATCH (a)-[r]->(b) WHERE a.course_id = b.course_id AND r.course_id IS NULL "
        "SET r.course_id = a.course_id"
    ).consume()
    for label, (legacy_name, key) in COURSE_LABEL_KEYS.items():
        session.run(f"DROP CONSTRAINT {legacy_name} IF EXISTS").consume()
        session.run(
            f"CREATE CONSTRAINT {legacy_name}_course IF NOT EXISTS "
            f"FOR (n:{label}) REQUIRE (n.course_id, n.{key}) IS UNIQUE"
        ).consume()
    session.run(
        "CREATE INDEX graph_draft_course_status IF NOT EXISTS "
        "FOR (b:GraphDraftBatch) ON (b.course_id, b.status)"
    ).consume()
    session.run(
        "CREATE INDEX knowledge_node_course_name IF NOT EXISTS "
        "FOR (n:KnowledgeNode) ON (n.course_id, n.name)"
    ).consume()
    session.run(
        "CREATE INDEX knowledge_node_course_parent IF NOT EXISTS "
        "FOR (n:KnowledgeNode) ON (n.course_id, n.parent_id)"
    ).consume()
    session.run(
        "CREATE INDEX graph_source_course_node IF NOT EXISTS "
        "FOR (s:GraphSource) ON (s.course_id, s.node_id)"
    ).consume()


def empty_course_graph(course_id: int) -> dict[str, Any]:
    course_id = valid_course_id(course_id)
    if course_id == 1:
        raise ValueError("the existing Python course must retain its graph")
    return {
        "metadata": {
            "name": f"Course {course_id}",
            "course_id": course_id,
            "version": "0.0.0",
            "node_count": 0,
            "edge_count": 0,
            "source_count": 0,
            "created_at": "1970-01-01T00:00:00Z",
            "updated_at": "1970-01-01T00:00:00Z",
            "requires_semantics": "source_is_prerequisite_of_target",
        },
        "nodes": [],
        "edges": [],
        "source_sections": [],
    }


def is_empty_course_graph(graph: dict[str, Any]) -> bool:
    metadata = graph.get("metadata") or {}
    course_id = metadata.get("course_id")
    return (
        isinstance(course_id, int)
        and not isinstance(course_id, bool)
        and course_id > 1
        and graph == empty_course_graph(course_id)
    )
