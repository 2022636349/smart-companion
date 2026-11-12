from __future__ import annotations

import collections
import hashlib
import json
from dataclasses import asdict, dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any
from knowledge_service.app.repository.course_scope import is_empty_course_graph


@dataclass(frozen=True)
class QualityThresholds:
    min_approved_node_ratio: float = 0.95
    min_approved_source_coverage: float = 1.0
    max_approved_knowledge_point_isolated: int = 0
    max_dangling_references: int = 0
    max_duplicate_items: int = 0
    max_self_loops: int = 0
    max_requires_cycles: int = 0


def load_graph(path: str | Path) -> dict[str, Any]:
    with Path(path).open("r", encoding="utf-8-sig") as handle:
        graph = json.load(handle)
    if not isinstance(graph.get("nodes"), list) or not isinstance(graph.get("edges"), list):
        raise ValueError("graph JSON must contain nodes and edges arrays")
    return graph


def sha256_file(path: str | Path) -> str:
    digest = hashlib.sha256()
    with Path(path).open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _cycles(node_ids: set[str], edges: list[dict[str, Any]]) -> list[list[str]]:
    adjacency = {node_id: [] for node_id in node_ids}
    for edge in edges:
        source = str(edge.get("source"))
        target = str(edge.get("target"))
        if source in adjacency and target in adjacency:
            adjacency[source].append(target)
    state: dict[str, int] = {}
    stack: list[str] = []
    found: list[list[str]] = []

    def visit(node_id: str) -> None:
        state[node_id] = 1
        stack.append(node_id)
        for target in sorted(adjacency[node_id]):
            if state.get(target, 0) == 0:
                visit(target)
            elif state.get(target) == 1:
                cycle = stack[stack.index(target) :] + [target]
                if cycle not in found:
                    found.append(cycle)
        stack.pop()
        state[node_id] = 2

    for node_id in sorted(node_ids):
        if state.get(node_id, 0) == 0:
            visit(node_id)
    return found


def quality_report(
    graph: dict[str, Any],
    *,
    graph_sha256: str | None = None,
    thresholds: QualityThresholds | None = None,
    generated_at: str | None = None,
) -> dict[str, Any]:
    limits = thresholds or QualityThresholds()
    metadata = dict(graph.get("metadata") or {})
    nodes = [item for item in graph.get("nodes", []) if isinstance(item, dict)]
    edges = [item for item in graph.get("edges", []) if isinstance(item, dict)]
    sources = [
        item
        for item in graph.get("source_sections", graph.get("sources", []))
        if isinstance(item, dict)
    ]

    node_id_rows = [str(node.get("id")) for node in nodes]
    node_ids = set(node_id_rows)
    edge_keys = [
        (str(edge.get("source")), str(edge.get("target")), str(edge.get("relation")))
        for edge in edges
    ]
    source_ids = [str(source.get("id")) for source in sources]
    source_node_ids = {
        str(source.get("node_id", source.get("knowledge_id"))) for source in sources
    }
    missing_parents = [
        {"node_id": str(node.get("id")), "parent_id": str(node.get("parent_id"))}
        for node in nodes
        if node.get("parent_id") not in (None, "")
        and str(node.get("parent_id")) not in node_ids
    ]
    dangling_edges = [
        edge
        for edge in edges
        if str(edge.get("source")) not in node_ids
        or str(edge.get("target")) not in node_ids
    ]
    dangling_sources = sorted(source_node_ids - node_ids)
    self_loops = [
        edge for edge in edges if str(edge.get("source")) == str(edge.get("target"))
    ]
    requires_edges = [
        edge
        for edge in edges
        if str(edge.get("relation", "")).upper() == "REQUIRES"
        and edge.get("review_status", "PENDING") == "APPROVED"
    ]
    cycles = _cycles(node_ids, requires_edges)

    approved_nodes = [node for node in nodes if node.get("review_status") == "APPROVED"]
    course_roots = [
        node for node in approved_nodes
        if node.get("node_type") == "course" and int(node.get("level") or 0) == 0
        and node.get("parent_id") in (None, "")
    ]
    approved_node_ids = {str(node.get("id")) for node in approved_nodes}
    approved_source_node_ids = {
        str(source.get("node_id", source.get("knowledge_id")))
        for source in sources
        if source.get("review_status", source.get("audit_result")) == "APPROVED"
    }
    covered_approved_nodes = approved_node_ids & approved_source_node_ids
    approved_node_ratio = len(approved_nodes) / len(nodes) if nodes else 0.0
    approved_source_coverage = (
        len(covered_approved_nodes) / len(approved_nodes) if approved_nodes else 0.0
    )
    # 0.0.0 is a deterministic, fact-free bootstrap/rollback state, never a
    # published teaching graph. Nonempty releases keep every existing gate.
    if is_empty_course_graph(graph):
        approved_node_ratio = approved_source_coverage = 1.0

    degree = collections.Counter()
    for edge in requires_edges:
        degree[str(edge.get("source"))] += 1
        degree[str(edge.get("target"))] += 1
    isolated = [
        {"id": str(node.get("id")), "name": node.get("name"), "level": node.get("level")}
        for node in nodes
        if degree[str(node.get("id"))] == 0
    ]
    isolated_approved_knowledge_points = [
        item
        for item in isolated
        if item["id"] in approved_node_ids and int(item.get("level") or 0) == 3
    ]
    # REQUIRES coverage is not graph reachability: a textbook concept may have
    # a complete reviewed hierarchy and source before semantic edges are known.
    # Do not force authors/models to invent prerequisites to satisfy a quota.
    approved_by_id = {str(node['id']): node for node in approved_nodes}
    root_ids = {str(node['id']) for node in course_roots}

    def has_approved_parent_chain(node_id: str) -> bool:
        seen: set[str] = set()
        current = approved_by_id.get(node_id)
        while current is not None and str(current['id']) not in seen:
            current_id = str(current['id'])
            seen.add(current_id)
            if current_id in root_ids:
                return True
            parent = approved_by_id.get(str(current.get('parent_id')))
            if parent is None or int(parent.get('level', -1)) >= int(current.get('level', -1)):
                return False
            current = parent
        return False

    semantic_ids = {
        str(edge[key]) for edge in edges for key in ('source', 'target')
        if str(edge.get('relation', '')).upper() in {'REQUIRES', 'RELATED'}
        and edge.get('review_status') == 'APPROVED'
        and str(edge.get('source')) in approved_node_ids
        and str(edge.get('target')) in approved_node_ids
    }
    knowledge_points = [node for node in approved_nodes
                        if node.get('node_type') == 'knowledge_point'
                        or (node.get('node_type') is None and int(node.get('level', -1)) == 3)]
    truly_isolated = [node for node in knowledge_points
                      if not has_approved_parent_chain(str(node['id']))
                      and str(node['id']) not in semantic_ids]
    incomplete_chains = [str(node['id']) for node in approved_nodes
                         if not has_approved_parent_chain(str(node['id']))]
    semantic_coverage_warnings = [str(node['id']) for node in knowledge_points
                                  if has_approved_parent_chain(str(node['id']))
                                  and str(node['id']) not in semantic_ids]

    duplicate_count = (
        len(node_id_rows) - len(node_ids)
        + len(edge_keys) - len(set(edge_keys))
        + len(source_ids) - len(set(source_ids))
    )
    dangling_count = len(missing_parents) + len(dangling_edges) + len(dangling_sources)
    checks = {
        "metadata_counts_match": (
            metadata.get("node_count") == len(nodes)
            and metadata.get("edge_count") == len(edges)
        ),
        "approved_node_ratio": approved_node_ratio >= limits.min_approved_node_ratio,
        "approved_source_coverage": (
            approved_source_coverage >= limits.min_approved_source_coverage
        ),
        "approved_knowledge_point_isolation": (
            len(truly_isolated)
            <= limits.max_approved_knowledge_point_isolated
        ),
        "approved_parent_chains": not incomplete_chains,
        "dangling_references": dangling_count <= limits.max_dangling_references,
        "duplicate_items": duplicate_count <= limits.max_duplicate_items,
        "self_loops": len(self_loops) <= limits.max_self_loops,
        "requires_cycles": len(cycles) <= limits.max_requires_cycles,
        "single_course_root": is_empty_course_graph(graph) or len(course_roots) == 1,
    }
    status_counts = lambda rows: dict(  # noqa: E731 - compact deterministic helper
        sorted(
            collections.Counter(
                str(row.get("review_status", row.get("audit_result", "UNKNOWN"))).upper()
                for row in rows
            ).items()
        )
    )
    release_decision = "PUBLISH" if all(checks.values()) else "BLOCK"
    return {
        "schema_version": "graph-quality-v1",
        "graph_version": str(metadata.get("version") or "unknown"),
        "graph_sha256": graph_sha256,
        "generated_at": generated_at
        or datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        "thresholds": asdict(limits),
        "counts": {
            "nodes": len(nodes),
            "edges": len(edges),
            "sources": len(sources),
            "node_review_statuses": status_counts(nodes),
            "edge_review_statuses": status_counts(edges),
            "source_review_statuses": status_counts(sources),
        },
        "coverage": {
            "approved_node_ratio": round(approved_node_ratio, 6),
            "approved_source_node_count": len(covered_approved_nodes),
            "approved_source_coverage": round(approved_source_coverage, 6),
        },
        "issues": {
            "isolated_from_approved_requires": isolated,
            "isolated_approved_knowledge_points": isolated_approved_knowledge_points,
            "missing_parents": missing_parents,
            "missing_edge_endpoints": dangling_edges,
            "missing_source_nodes": dangling_sources,
            "duplicate_node_id_count": len(node_id_rows) - len(node_ids),
            "duplicate_edge_count": len(edge_keys) - len(set(edge_keys)),
            "duplicate_source_id_count": len(source_ids) - len(set(source_ids)),
            "self_loops": self_loops,
            "requires_cycles": cycles,
            "truly_isolated_approved_knowledge_points": [str(node['id']) for node in truly_isolated],
            "incomplete_approved_parent_chains": incomplete_chains,
        },
        "warnings": {"knowledge_points_without_semantic_edges": semantic_coverage_warnings,
                     "course_root_reuse": metadata.get('course_root_reuse', [])},
        "checks": checks,
        "release_decision": release_decision,
        "passed": release_decision == "PUBLISH",
    }


def report_for_file(
    graph_file: str | Path, thresholds: QualityThresholds | None = None
) -> dict[str, Any]:
    path = Path(graph_file).resolve()
    return quality_report(
        load_graph(path), graph_sha256=sha256_file(path), thresholds=thresholds
    )
