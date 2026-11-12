"""Structural audit for the Python knowledge graph.

This script never modifies graph data. It exits with code 1 when structural
errors are found, and reports data-quality warnings separately.
"""

from __future__ import annotations

import argparse
import collections
import json
from pathlib import Path
from typing import Any


def load_graph(path: Path) -> dict[str, Any]:
    with path.open("r", encoding="utf-8-sig") as file:
        return json.load(file)


def find_directed_cycles(node_ids: set[str], edges: list[dict[str, Any]]) -> list[list[str]]:
    adjacency = {node_id: [] for node_id in node_ids}
    for edge in edges:
        source = str(edge.get("source"))
        target = str(edge.get("target"))
        if source in adjacency and target in adjacency:
            adjacency[source].append(target)

    state: dict[str, int] = {}
    stack: list[str] = []
    cycles: list[list[str]] = []

    def visit(node_id: str) -> None:
        state[node_id] = 1
        stack.append(node_id)
        for target in adjacency[node_id]:
            if state.get(target, 0) == 0:
                visit(target)
            elif state.get(target) == 1:
                start = stack.index(target)
                cycle = stack[start:] + [target]
                if cycle not in cycles:
                    cycles.append(cycle)
        stack.pop()
        state[node_id] = 2

    for node_id in node_ids:
        if state.get(node_id, 0) == 0:
            visit(node_id)
    return cycles


def audit(graph: dict[str, Any]) -> dict[str, Any]:
    metadata = graph.get("metadata", {})
    nodes = graph.get("nodes", [])
    edges = graph.get("edges", [])
    node_ids = {str(node.get("id")) for node in nodes}

    name_to_ids: dict[str, list[str]] = collections.defaultdict(list)
    for node in nodes:
        name_to_ids[str(node.get("name", "")).strip()].append(str(node.get("id")))

    missing_parents = [
        {"node_id": str(node.get("id")), "parent_id": str(node.get("parent_id"))}
        for node in nodes
        if node.get("parent_id") not in (None, "") and str(node.get("parent_id")) not in node_ids
    ]
    missing_endpoints = [
        edge
        for edge in edges
        if str(edge.get("source")) not in node_ids or str(edge.get("target")) not in node_ids
    ]
    edge_keys = [
        (str(edge.get("source")), str(edge.get("target")), str(edge.get("relation")))
        for edge in edges
    ]
    self_loops = [edge for edge in edges if str(edge.get("source")) == str(edge.get("target"))]
    duplicate_names = {name: ids for name, ids in name_to_ids.items() if name and len(ids) > 1}
    cycles = find_directed_cycles(node_ids, edges)

    degree = collections.Counter()
    for source, target, _ in edge_keys:
        degree[source] += 1
        degree[target] += 1
    isolated_from_explicit_edges = [
        {"id": str(node.get("id")), "name": node.get("name"), "level": node.get("level")}
        for node in nodes
        if degree[str(node.get("id"))] == 0
    ]

    legacy_source_items = [
        source
        for node in nodes
        for source in node.get("sources", [])
        if isinstance(source, dict)
    ]
    top_level_sources = [
        source
        for source in graph.get("source_sections", graph.get("sources", []))
        if isinstance(source, dict)
    ]
    source_items = legacy_source_items + top_level_sources
    source_node_ids = {
        str(source.get("node_id", source.get("knowledge_id")))
        for source in source_items
        if source.get("node_id", source.get("knowledge_id")) not in (None, "")
    }
    source_ids = [
        str(source["id"])
        for source in top_level_sources
        if source.get("id") not in (None, "")
    ]
    missing_source_nodes = sorted(source_node_ids - node_ids)

    structural_errors = {
        "missing_parents": missing_parents,
        "missing_edge_endpoints": missing_endpoints,
        "self_loops": self_loops,
        "duplicate_edge_count": len(edge_keys) - len(set(edge_keys)),
        "duplicate_names": duplicate_names,
        "directed_cycles": cycles,
        "missing_source_nodes": missing_source_nodes,
        "duplicate_source_id_count": len(source_ids) - len(set(source_ids)),
    }

    warnings = []
    if metadata.get("node_count") != len(nodes):
        warnings.append(
            f"metadata.node_count={metadata.get('node_count')} does not match actual {len(nodes)}"
        )
    if metadata.get("edge_count") != len(edges):
        warnings.append(
            f"metadata.edge_count={metadata.get('edge_count')} does not match actual {len(edges)}"
        )
    if isolated_from_explicit_edges:
        warnings.append(
            f"{len(isolated_from_explicit_edges)} nodes have no explicit prerequisite edge"
        )
    if source_node_ids and len(source_node_ids) < len(nodes):
        warnings.append(
            f"source coverage is {len(source_node_ids)}/{len(nodes)} nodes "
            f"({len(source_node_ids) / len(nodes):.1%})"
        )

    has_structural_errors = any(
        value if isinstance(value, int) else bool(value)
        for value in structural_errors.values()
    )

    return {
        "summary": {
            "node_count": len(nodes),
            "edge_count": len(edges),
            "levels": dict(sorted(collections.Counter(node.get("level") for node in nodes).items())),
            "relations": dict(collections.Counter(edge.get("relation") for edge in edges)),
            "source_node_count": len(source_node_ids),
            "source_item_count": len(source_items),
            "source_review_statuses": dict(
                collections.Counter(
                    source.get("review_status", source.get("audit_result", "UNKNOWN"))
                    for source in source_items
                )
            ),
            "explicit_edge_isolated_count": len(isolated_from_explicit_edges),
        },
        "structural_errors": structural_errors,
        "warnings": warnings,
        "isolated_from_explicit_edges": isolated_from_explicit_edges,
        "passed": not has_structural_errors,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description="Audit a knowledge graph JSON file without modifying it")
    parser.add_argument(
        "graph_file",
        nargs="?",
        default="knowledge_graph.json",
        help="Path to knowledge_graph.json or knowledge_graph_with_sources.json",
    )
    args = parser.parse_args()

    graph_path = Path(args.graph_file).expanduser().resolve()
    result = audit(load_graph(graph_path))
    print(json.dumps({"graph_file": str(graph_path), **result}, ensure_ascii=False, indent=2))
    return 0 if result["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())

