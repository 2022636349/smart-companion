from __future__ import annotations
from knowledge_service.operations.root_reuse import reusable_course_root

import hashlib
import json
import unicodedata
from collections import defaultdict, deque
from typing import Any, Iterable

from knowledge_service.app.domain.ingestion_dto import (
    GraphDraftEdge,
    GraphDraftNode,
    GraphDraftSource,
    GraphIngestionBatchUpsertRequest,
    GraphThresholdProfile,
)


MAX_BATCH_BYTES = 2 * 1024 * 1024
MAX_NODES = 200
MAX_EDGES = 500
MAX_SOURCES = 500

_SERVER_MANAGED_FIELDS = {
    "decision_status",
    "review_lane",
    "decision_comment",
    "decided_by",
    "decided_at",
}


def _canonical_candidate(value: dict[str, Any]) -> dict[str, Any]:
    return {
        key: item
        for key, item in value.items()
        if key not in _SERVER_MANAGED_FIELDS
    }


def canonical_batch_payload(request: GraphIngestionBatchUpsertRequest) -> dict[str, Any]:
    value = request.model_dump(mode="json")
    return {
        "base_graph_version": value["base_graph_version"],
        "source": value["source"],
        "threshold_profile": value["threshold_profile"],
        "models": value["models"],
        "prompts": value["prompts"],
        "nodes": [_canonical_candidate(item) for item in value["nodes"]],
        "edges": [_canonical_candidate(item) for item in value["edges"]],
        "sources": [_canonical_candidate(item) for item in value["sources"]],
    }


def canonical_payload_bytes(request: GraphIngestionBatchUpsertRequest) -> bytes:
    return json.dumps(
        canonical_batch_payload(request),
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")


def canonical_payload_sha256(request: GraphIngestionBatchUpsertRequest) -> str:
    return hashlib.sha256(canonical_payload_bytes(request)).hexdigest()


def _normalized_identity(value: Any) -> str:
    return " ".join(
        unicodedata.normalize("NFKC", str(value or "")).casefold().split()
    )


def _has_graph_rag_fact(candidate: dict[str, Any]) -> bool:
    """Return whether a knowledge-point candidate contains usable learning facts."""
    for field in (
        "description",
        "learning_objectives",
        "common_misconceptions",
        "example_code",
    ):
        value = candidate.get(field)
        if isinstance(value, str) and value.strip():
            return True
        if isinstance(value, (list, tuple)) and any(
            isinstance(item, str) and item.strip() for item in value
        ):
            return True
    return False


def _issue(
    code: str,
    severity: str,
    candidate_type: str,
    candidate_id: str | None,
    message: str,
) -> dict[str, Any]:
    return {
        "code": code,
        "severity": severity,
        "candidate_type": candidate_type,
        "candidate_id": candidate_id,
        "message": message,
    }


def _duplicates(values: Iterable[tuple[str, str]]) -> dict[str, list[str]]:
    grouped: dict[str, list[str]] = defaultdict(list)
    for identity, candidate_id in values:
        grouped[identity].append(candidate_id)
    return {
        identity: candidate_ids
        for identity, candidate_ids in grouped.items()
        if len(candidate_ids) > 1
    }


def _requires_cycle_edges(
    node_ids: set[str], edges: Iterable[tuple[str, str]]
) -> set[tuple[str, str]]:
    adjacency: dict[str, set[str]] = {node_id: set() for node_id in node_ids}
    indegree = {node_id: 0 for node_id in node_ids}
    usable: set[tuple[str, str]] = set()
    for source_id, target_id in edges:
        if source_id not in node_ids or target_id not in node_ids:
            continue
        edge = (source_id, target_id)
        if edge in usable:
            continue
        usable.add(edge)
        adjacency[source_id].add(target_id)
        indegree[target_id] += 1
    queue = deque(sorted(node_id for node_id, degree in indegree.items() if degree == 0))
    visited: set[str] = set()
    while queue:
        current = queue.popleft()
        visited.add(current)
        for target_id in sorted(adjacency[current]):
            indegree[target_id] -= 1
            if indegree[target_id] == 0:
                queue.append(target_id)
    if len(visited) == len(node_ids):
        return set()
    cyclic_nodes = node_ids - visited
    return {
        edge for edge in usable if edge[0] in cyclic_nodes and edge[1] in cyclic_nodes
    }


def validate_draft_candidates(
    base_graph: dict[str, Any],
    nodes: list[dict[str, Any]],
    edges: list[dict[str, Any]],
    sources: list[dict[str, Any]],
    *,
    payload_size: int | None = None,
) -> list[dict[str, Any]]:
    """Validate the active (not human-rejected) draft candidate set.

    Pydantic enforces field-level schema before this function runs. These checks
    enforce cross-row and graph invariants against the immutable base release.
    """

    issues: list[dict[str, Any]] = []
    if payload_size is not None and payload_size > MAX_BATCH_BYTES:
        issues.append(
            _issue(
                "GRAPH_DRAFT_BUDGET_EXCEEDED",
                "ERROR",
                "BATCH",
                None,
                f"规范化候选载荷 {payload_size} bytes 超过 {MAX_BATCH_BYTES} bytes",
            )
        )
    for label, count, maximum in (
        ("nodes", len(nodes), MAX_NODES),
        ("edges", len(edges), MAX_EDGES),
        ("sources", len(sources), MAX_SOURCES),
    ):
        if count > maximum:
            issues.append(
                _issue(
                    "GRAPH_DRAFT_BUDGET_EXCEEDED",
                    "ERROR",
                    "BATCH",
                    None,
                    f"{label} 数量 {count} 超过上限 {maximum}",
                )
            )

    candidate_id_rows = [
        *((str(item["candidate_id"]), "NODE") for item in nodes),
        *((str(item["candidate_id"]), "EDGE") for item in edges),
        *((str(item["candidate_id"]), "SOURCE") for item in sources),
    ]
    for candidate_id, kinds in _duplicates(
        (candidate_id, kind) for candidate_id, kind in candidate_id_rows
    ).items():
        issues.append(
            _issue(
                "GRAPH_DRAFT_DUPLICATE_CANDIDATE_ID",
                "ERROR",
                "BATCH",
                candidate_id,
                f"candidate_id 在多个候选中重复：{', '.join(sorted(kinds))}",
            )
        )

    base_nodes = {
        str(item["id"]): item
        for item in base_graph.get("nodes", [])
        if item.get("review_status") == "APPROVED"
    }
    base_names = {
        _normalized_identity(item.get("name")): str(item["id"])
        for item in base_nodes.values()
    }
    base_sources = {
        str(item.get("id"))
        for item in base_graph.get(
            "source_sections", base_graph.get("sources", [])
        )
        if item.get("review_status") == "APPROVED" and item.get("id")
    }
    base_edges = {
        (
            str(item.get("source", item.get("source_id"))),
            str(item.get("target", item.get("target_id"))),
            str(item.get("relation") or "REQUIRES").upper(),
        )
        for item in base_graph.get("edges", [])
        if item.get("review_status") == "APPROVED"
    }

    for node_id, candidate_ids in _duplicates(
        (str(item["id"]), str(item["candidate_id"])) for item in nodes
    ).items():
        for candidate_id in candidate_ids:
            issues.append(
                _issue(
                    "GRAPH_DRAFT_DUPLICATE_NODE_ID",
                    "ERROR",
                    "NODE",
                    candidate_id,
                    f"候选节点 ID 重复：{node_id}",
                )
            )
    for name, candidate_ids in _duplicates(
        (_normalized_identity(item.get("name")), str(item["candidate_id"]))
        for item in nodes
    ).items():
        for candidate_id in candidate_ids:
            issues.append(
                _issue(
                    "GRAPH_DRAFT_DUPLICATE_NODE_NAME",
                    "ERROR",
                    "NODE",
                    candidate_id,
                    f"候选节点名称重复：{name}",
                )
            )

    candidate_node_ids = {str(item["id"]) for item in nodes}
    available_node_ids = set(base_nodes) | candidate_node_ids
    root_reuse = reusable_course_root(base_graph, nodes)
    for item in nodes:
        candidate_id = str(item["candidate_id"])
        node_id = str(item["id"])
        normalized_name = _normalized_identity(item.get("name"))
        if item.get("rights_status") != "ALLOWED":
            issues.append(
                _issue(
                    "GRAPH_RIGHTS_NOT_ALLOWED",
                    "ERROR",
                    "NODE",
                    candidate_id,
                    "候选节点版权状态必须经人工确认变更为 ALLOWED 后才能发布",
                )
            )
        if item.get("node_type") == "knowledge_point" and not _has_graph_rag_fact(item):
            issues.append(
                _issue(
                    "GRAPH_DRAFT_KNOWLEDGE_FACT_REQUIRED",
                    "ERROR",
                    "NODE",
                    candidate_id,
                    "knowledge_point 至少需要 description、learning_objectives、common_misconceptions 或 example_code 中的一项，才能提供 Graph-RAG 学习事实",
                )
            )
        reuses_root = root_reuse is not None and item is root_reuse[0]
        if node_id in base_nodes and not (reuses_root and node_id == str(root_reuse[1]['id'])):
            issues.append(
                _issue(
                    "GRAPH_DRAFT_EXISTING_NODE_ID",
                    "ERROR",
                    "NODE",
                    candidate_id,
                    f"节点 ID 已存在于基准发布物：{node_id}",
                )
            )
        if normalized_name in base_names and not (
            reuses_root and base_names[normalized_name] == str(root_reuse[1]['id'])
        ):
            issues.append(
                _issue(
                    "GRAPH_DRAFT_EXISTING_NODE_NAME",
                    "ERROR",
                    "NODE",
                    candidate_id,
                    f"节点名称与基准节点 {base_names[normalized_name]} 重复",
                )
            )
        parent_id = item.get("parent_id")
        if parent_id is not None and str(parent_id) not in available_node_ids:
            issues.append(
                _issue(
                    "GRAPH_DRAFT_PARENT_NOT_FOUND",
                    "ERROR",
                    "NODE",
                    candidate_id,
                    f"父节点不存在或未审核：{parent_id}",
                )
            )

    for identity, candidate_ids in _duplicates(
        (
            "\x1f".join(
                (
                    str(item["source_id"]),
                    str(item["target_id"]),
                    str(item["relation"]).upper(),
                )
            ),
            str(item["candidate_id"]),
        )
        for item in edges
    ).items():
        for candidate_id in candidate_ids:
            issues.append(
                _issue(
                    "GRAPH_DRAFT_DUPLICATE_EDGE",
                    "ERROR",
                    "EDGE",
                    candidate_id,
                    f"候选关系重复：{identity.replace(chr(31), ' -> ')}",
                )
            )

    candidate_requires_by_id: dict[str, tuple[str, str]] = {}
    for item in edges:
        candidate_id = str(item["candidate_id"])
        source_id = str(item["source_id"])
        target_id = str(item["target_id"])
        relation = str(item["relation"]).upper()
        if source_id == target_id:
            issues.append(
                _issue(
                    "GRAPH_DRAFT_SELF_LOOP",
                    "ERROR",
                    "EDGE",
                    candidate_id,
                    f"关系不能自环：{source_id}",
                )
            )
        for endpoint_name, endpoint_id in (
            ("source", source_id),
            ("target", target_id),
        ):
            if endpoint_id not in available_node_ids:
                issues.append(
                    _issue(
                        "GRAPH_DRAFT_EDGE_ENDPOINT_NOT_FOUND",
                        "ERROR",
                        "EDGE",
                        candidate_id,
                        f"{endpoint_name} 端点不存在或未审核：{endpoint_id}",
                    )
                )
        if (source_id, target_id, relation) in base_edges:
            issues.append(
                _issue(
                    "GRAPH_DRAFT_EXISTING_EDGE",
                    "ERROR",
                    "EDGE",
                    candidate_id,
                    "关系已存在于基准发布物",
                )
            )
        if relation == "REQUIRES":
            candidate_requires_by_id[candidate_id] = (source_id, target_id)

    base_requires = {
        (source_id, target_id)
        for source_id, target_id, relation in base_edges
        if relation == "REQUIRES"
    }
    cycle_edges = _requires_cycle_edges(
        available_node_ids,
        [*base_requires, *candidate_requires_by_id.values()],
    )
    if cycle_edges:
        for candidate_id, edge in candidate_requires_by_id.items():
            if edge in cycle_edges:
                issues.append(
                    _issue(
                        "GRAPH_DRAFT_REQUIRES_CYCLE",
                        "ERROR",
                        "EDGE",
                        candidate_id,
                        f"REQUIRES 会形成有向环：{edge[0]} -> {edge[1]}",
                    )
                )

    for source_id, candidate_ids in _duplicates(
        (str(item["id"]), str(item["candidate_id"])) for item in sources
    ).items():
        for candidate_id in candidate_ids:
            issues.append(
                _issue(
                    "GRAPH_DRAFT_DUPLICATE_SOURCE_ID",
                    "ERROR",
                    "SOURCE",
                    candidate_id,
                    f"候选来源 ID 重复：{source_id}",
                )
            )
    source_node_ids: set[str] = set()
    for item in sources:
        candidate_id = str(item["candidate_id"])
        source_id = str(item["id"])
        node_id = str(item["node_id"])
        if item.get("rights_status") != "ALLOWED":
            issues.append(
                _issue(
                    "GRAPH_RIGHTS_NOT_ALLOWED",
                    "ERROR",
                    "SOURCE",
                    candidate_id,
                    "候选来源版权状态必须经人工确认变更为 ALLOWED 后才能发布",
                )
            )
        if source_id in base_sources:
            issues.append(
                _issue(
                    "GRAPH_DRAFT_EXISTING_SOURCE_ID",
                    "ERROR",
                    "SOURCE",
                    candidate_id,
                    f"来源 ID 已存在于基准发布物：{source_id}",
                )
            )
        if node_id not in available_node_ids:
            issues.append(
                _issue(
                    "GRAPH_DRAFT_SOURCE_NODE_NOT_FOUND",
                    "ERROR",
                    "SOURCE",
                    candidate_id,
                    f"来源对应节点不存在或未审核：{node_id}",
                )
            )
        source_node_ids.add(node_id)

    for item in nodes:
        node_id = str(item["id"])
        if node_id not in source_node_ids:
            issues.append(
                _issue(
                    "GRAPH_DRAFT_NODE_SOURCE_REQUIRED",
                    "ERROR",
                    "NODE",
                    str(item["candidate_id"]),
                    f"新节点缺少同批可追溯来源：{node_id}",
                )
            )

    return sorted(
        issues,
        key=lambda item: (
            {"ERROR": 0, "WARNING": 1, "INFO": 2}[item["severity"]],
            item["candidate_type"],
            item.get("candidate_id") or "",
            item["code"],
        ),
    )


def candidate_review_lane(
    candidate: GraphDraftNode | GraphDraftEdge | GraphDraftSource,
    profile: GraphThresholdProfile,
    *,
    has_error: bool,
) -> str:
    if has_error:
        return "REJECT_RECOMMENDED"
    confidence = min(candidate.extractor_confidence, candidate.reviewer_confidence)
    if confidence < profile.reject_below:
        return "REJECT_RECOMMENDED"
    if isinstance(candidate, GraphDraftNode):
        fast_track = profile.node_fast_track_min
    elif isinstance(candidate, GraphDraftEdge):
        fast_track = profile.edge_fast_track_min
    else:
        fast_track = profile.source_fast_track_min
    return "FAST_TRACK" if confidence >= fast_track else "STANDARD"
