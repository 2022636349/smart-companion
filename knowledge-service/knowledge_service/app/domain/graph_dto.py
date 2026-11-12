from __future__ import annotations

import hashlib
import re
import unicodedata
from datetime import date, datetime, timezone
from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, Field


class HealthResponse(BaseModel):
    status: Literal["UP"]
    service: str
    timestamp: str


class GraphHealthResponse(BaseModel):
    status: Literal["UP", "DEGRADED"]
    node_count: int
    edge_count: int
    backend: Literal["json", "neo4j"]
    graph_file: str | None = None
    metadata: dict[str, Any] = Field(default_factory=dict)
    warnings: list[str] = Field(default_factory=list)


class GraphNode(BaseModel):
    model_config = ConfigDict(extra="ignore")

    id: str = Field(min_length=1, max_length=64)
    name: str = Field(min_length=1, max_length=200)
    level: int
    node_type: Literal["course", "module", "chapter", "section", "knowledge_point"]
    parent_id: str | None = None
    description: str | None = None
    aliases: list[str] = Field(default_factory=list)
    keywords: list[str] = Field(default_factory=list)
    difficulty: str | None = None
    importance: int | None = Field(default=None, ge=1, le=5)
    estimated_minutes: int | None = Field(default=None, ge=1)
    learning_objectives: list[str] = Field(default_factory=list)
    common_misconceptions: list[str] = Field(default_factory=list)
    example_code: list[str] = Field(default_factory=list)
    review_status: Literal["PENDING", "APPROVED", "REJECTED"]
    content_version: int = Field(ge=1)


class GraphEdge(BaseModel):
    source_id: str = Field(min_length=1, max_length=64)
    target_id: str = Field(min_length=1, max_length=64)
    relation: Literal["contains", "requires", "related", "supported_by"]
    review_status: Literal["PENDING", "APPROVED", "REJECTED"]
    evidence: list[str] = Field(default_factory=list)
    confidence: float | None = Field(default=None, ge=0, le=1)


class GraphSearchResponse(BaseModel):
    items: list[GraphNode]
    query: str


class GraphContextResponse(BaseModel):
    course_id: int = Field(ge=1)
    node: GraphNode
    parent: GraphNode | None
    children: list[GraphNode]
    prerequisites: list[GraphNode]
    successors: list[GraphNode]
    source_status: str = "NOT_REVIEWED"


class GraphSubgraphResponse(BaseModel):
    course_id: int = Field(ge=1)
    center_id: str
    nodes: list[GraphNode]
    edges: list[GraphEdge]
    graph_version: str


class GraphMatchRequest(BaseModel):
    text: str = Field(min_length=1, max_length=30000)
    scene: Literal["QUESTION", "RESOURCE", "TEXTBOOK", "TUTOR"]
    current_node_id: str | None = None
    limit: int = Field(default=5, ge=1, le=20)


class GraphMatchCandidate(BaseModel):
    node: GraphNode
    score: float = Field(ge=0, le=1)
    matched_by: list[
        Literal["NAME", "ALIAS", "KEYWORD", "FULLTEXT", "GRAPH_CONTEXT"]
    ]
    evidence: list[str] = Field(default_factory=list)
    requires_review: bool


class GraphMatchResponse(BaseModel):
    items: list[GraphMatchCandidate]


class MasteryInput(BaseModel):
    knowledge_point_id: str = Field(min_length=1, max_length=64)
    score: float = Field(ge=0, le=100)
    confidence: float = Field(ge=0, le=1)


class GraphRecommendRequest(BaseModel):
    student_id: int
    mastery: list[MasteryInput] = Field(default_factory=list, max_length=500)
    limit: int = Field(default=5, ge=1, le=20)


class GraphRecommendation(BaseModel):
    node: GraphNode
    reason: str = Field(min_length=1)
    priority: int = Field(ge=1)
    evidence: list[str] = Field(min_length=1, max_length=8)


class GraphRecommendationResponse(BaseModel):
    course_id: int = Field(ge=1)
    items: list[GraphRecommendation]
    graph_version: str = Field(min_length=1)


class GraphNodeListResponse(BaseModel):
    items: list[GraphNode]


class GraphSource(BaseModel):
    id: str
    node_id: str = Field(min_length=1, max_length=64)
    source_type: Literal["TEXTBOOK", "TEACHING_RESOURCE"]
    title: str
    resource_id: int | None = None
    file_id: int | None = None
    chapter: str | None = None
    page_from: int | None = Field(default=None, ge=1)
    page_to: int | None = Field(default=None, ge=1)
    locator: str
    excerpt: str | None = None
    review_status: Literal["PENDING", "APPROVED", "REJECTED"]
    # A GraphSource is a canonical, published provenance record.  Callers must
    # never be allowed to silently treat unknown copyright as usable evidence.
    rights_status: Literal["ALLOWED", "RESTRICTED"]
    confidence: float | None = Field(default=None, ge=0, le=1)


class GraphSourceResponse(BaseModel):
    items: list[GraphSource]


class GraphRagContextResponse(BaseModel):
    course_id: int = Field(ge=1)
    knowledge_point: GraphNode
    prerequisites: list[GraphNode]
    successors: list[GraphNode]
    related_nodes: list[GraphNode]
    sources: list[GraphSource]
    graph_version: str
    context_status: Literal["READY", "INSUFFICIENT"]
    warnings: list[str]


class GraphVersionResponse(BaseModel):
    course_id: int = Field(ge=1)
    graph_version: str
    backend: Literal["json", "neo4j"]
    node_count: int = Field(ge=0)
    edge_count: int = Field(ge=0)
    approved_node_count: int = Field(ge=0)
    approved_edge_count: int = Field(ge=0)
    source_count: int = Field(default=0, ge=0)
    generated_at: datetime


def _list_value(value: Any) -> list[str]:
    if value is None:
        return []
    if isinstance(value, list):
        return [str(item) for item in value if item is not None]
    text = str(value).strip()
    return [text] if text else []


def _optional_int(value: Any) -> int | None:
    if value is None or value == "":
        return None
    return int(value)


def _optional_float(value: Any) -> float | None:
    if value is None or value == "":
        return None
    return float(value)


def node_type_for_level(level: int) -> str:
    return {
        0: "course",
        1: "module",
        2: "chapter",
        3: "knowledge_point",
    }.get(level, "knowledge_point")


def normalize_node(node: dict[str, Any]) -> dict[str, Any]:
    """Convert the source JSON/Neo4j shape to the API's GraphNode shape."""
    level = int(node.get("level", 3))
    result: dict[str, Any] = {
        "id": str(node["id"]),
        "name": str(node.get("name", "")),
        "level": level,
        "node_type": node.get("node_type") or node_type_for_level(level),
        "parent_id": node.get("parent_id"),
        "description": node.get("description"),
        "aliases": _list_value(node.get("aliases")),
        "keywords": _list_value(node.get("keywords")),
        "difficulty": node.get("difficulty"),
        "importance": node.get("importance"),
        "estimated_minutes": node.get("estimated_minutes"),
        "learning_objectives": _list_value(node.get("learning_objectives")),
        "common_misconceptions": _list_value(node.get("common_misconceptions")),
        "example_code": _list_value(node.get("example_code")),
        "review_status": node.get("review_status") or "PENDING",
        "content_version": int(node.get("content_version") or 1),
    }
    if "rights_status" in node:
        result["rights_status"] = node["rights_status"]
    return result


def normalize_edge(edge: dict[str, Any]) -> dict[str, Any]:
    source_id = edge.get("source_id", edge.get("source"))
    target_id = edge.get("target_id", edge.get("target"))
    return {
        "source_id": str(source_id),
        "target_id": str(target_id),
        "relation": str(edge.get("relation", "REQUIRES")).lower(),
        "review_status": edge.get("review_status") or "PENDING",
        "evidence": _list_value(edge.get("evidence")),
        "confidence": edge.get("confidence"),
    }


def normalize_source(source: dict[str, Any]) -> dict[str, Any]:
    """Normalize canonical GraphSource rows and the pre-V2.1 audit shape."""
    node_id = source.get("node_id", source.get("knowledge_id"))
    title = source.get("title", source.get("book_name"))
    chapter = source.get("chapter", source.get("section_title"))
    locator = source.get("locator") or chapter
    if node_id is None or title is None or locator is None:
        raise ValueError("图谱来源必须包含 node_id、title 和 locator")

    source_id = source.get("id")
    if not source_id:
        identity = "\x1f".join(
            [
                str(node_id),
                str(source.get("book_id") or title),
                str(locator),
            ]
        )
        digest = hashlib.sha256(identity.encode("utf-8")).hexdigest()[:20]
        source_id = f"source-section-{digest}"

    source_type = str(source.get("source_type") or "TEXTBOOK").upper()
    review_status = source.get("review_status", source.get("audit_result")) or "PENDING"
    # Historical graph files predate source-level copyright metadata. They are
    # already immutable APPROVED releases, so normalizing them records the
    # explicit legacy migration decision as ALLOWED; all new C7 draft sources
    # carry rights_status at ingestion and release time. Any non-approved
    # legacy/audit source remains fail-closed RESTRICTED.
    rights_status = source.get("rights_status")
    if rights_status is None:
        rights_status = "ALLOWED" if str(review_status).upper() == "APPROVED" else "RESTRICTED"
    rights_status = str(rights_status).upper()
    if rights_status not in {"ALLOWED", "RESTRICTED"}:
        raise ValueError("图谱来源 rights_status 必须是 ALLOWED 或 RESTRICTED")
    return {
        "id": str(source_id),
        "node_id": str(node_id),
        "source_type": source_type,
        "title": str(title),
        "resource_id": _optional_int(source.get("resource_id")),
        "file_id": _optional_int(source.get("file_id")),
        "chapter": str(chapter) if chapter not in (None, "") else None,
        "page_from": _optional_int(source.get("page_from")),
        "page_to": _optional_int(source.get("page_to")),
        "locator": str(locator),
        "excerpt": source.get("excerpt") or None,
        "review_status": str(review_status).upper(),
        "rights_status": rights_status,
        "confidence": _optional_float(source.get("confidence")),
    }


def normalize_generated_at(value: Any) -> str:
    """Return a deterministic UTC RFC 3339 timestamp for version responses."""
    if isinstance(value, datetime):
        parsed = value
    elif isinstance(value, date):
        parsed = datetime.combine(value, datetime.min.time())
    else:
        text = str(value or "").strip()
        if not text:
            return "1970-01-01T00:00:00Z"
        try:
            parsed = datetime.fromisoformat(text.replace("Z", "+00:00"))
        except ValueError:
            return "1970-01-01T00:00:00Z"
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=timezone.utc)
    return parsed.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")


def build_rag_context(
    knowledge_point: dict[str, Any],
    prerequisites: list[dict[str, Any]],
    successors: list[dict[str, Any]],
    related_nodes: list[dict[str, Any]],
    sources: list[dict[str, Any]],
    graph_version: str,
    *,
    course_id: int,
) -> dict[str, Any]:
    """Build the shared JSON/Neo4j Graph-RAG response and readiness signal."""
    warnings: list[str] = []
    has_facts = bool(
        (knowledge_point.get("description") or "").strip()
        or knowledge_point.get("learning_objectives")
        or knowledge_point.get("common_misconceptions")
        or knowledge_point.get("example_code")
    )
    if not has_facts:
        warnings.append("知识点缺少已审核的定义、目标、误区或示例事实")
    has_knowledge_point_source = any(
        source.get("node_id") == knowledge_point.get("id") for source in sources
    )
    if not has_knowledge_point_source:
        warnings.append("知识点缺少已审核的可追溯来源")
    return {
        "knowledge_point": knowledge_point,
        "course_id": course_id,
        "prerequisites": prerequisites,
        "successors": successors,
        "related_nodes": related_nodes,
        "sources": sources,
        "graph_version": graph_version,
        "context_status": (
            "READY" if has_facts and has_knowledge_point_source else "INSUFFICIENT"
        ),
        "warnings": warnings,
    }


_MATCHED_BY_ORDER = {
    "NAME": 0,
    "ALIAS": 1,
    "KEYWORD": 2,
    "FULLTEXT": 3,
    "GRAPH_CONTEXT": 4,
}
_FACT_SPLIT_PATTERN = re.compile(r"[\n\r，。；;、]+")
_ASCII_WORD_PATTERN = re.compile(r"^[a-z0-9_+().-]+$")


def _normalize_match_text(value: Any) -> str:
    text = unicodedata.normalize("NFKC", str(value or "")).casefold()
    return " ".join(text.split())


def _contains_match_term(text: str, term: str) -> bool:
    """Match code identifiers as words and Chinese phrases as substrings."""
    normalized_term = _normalize_match_text(term)
    if not normalized_term:
        return False
    if _ASCII_WORD_PATTERN.fullmatch(normalized_term):
        pattern = rf"(?<![a-z0-9_]){re.escape(normalized_term)}(?![a-z0-9_])"
        return re.search(pattern, text) is not None
    return normalized_term in text


def _short_evidence(label: str, value: Any) -> str:
    compact = " ".join(str(value or "").split())
    if len(compact) > 96:
        compact = compact[:93] + "..."
    return f"{label}：{compact}"


def _fact_fragments(node: dict[str, Any]) -> list[str]:
    values: list[str] = []
    if node.get("description"):
        values.extend(_FACT_SPLIT_PATTERN.split(str(node["description"])))
    for field in (
        "learning_objectives",
        "common_misconceptions",
        "example_code",
    ):
        values.extend(_list_value(node.get(field)))
    return [
        fragment.strip()
        for fragment in values
        if 4 <= len(_normalize_match_text(fragment)) <= 160
    ]


def build_match_candidates(
    nodes: list[dict[str, Any]],
    text: str,
    *,
    current_node_id: str | None,
    context_node_ids: set[str] | None,
    limit: int,
) -> list[dict[str, Any]]:
    """Build stable, reviewable matches shared by JSON and Neo4j repositories.

    Repositories must still pass only APPROVED nodes. This function repeats that
    guard so a draft-mode caller cannot accidentally leak PENDING/REJECTED data.
    """
    normalized_text = _normalize_match_text(text)
    current_id = str(current_node_id) if current_node_id else None
    context_ids = {str(item) for item in (context_node_ids or set())}
    candidates: list[dict[str, Any]] = []

    for original in nodes:
        if (
            original.get("review_status") != "APPROVED"
            or original.get("node_type") != "knowledge_point"
        ):
            continue
        node = dict(original)
        matched_by: set[str] = set()
        evidence: list[str] = []
        weights: list[float] = []

        name = str(node.get("name") or "").strip()
        if name and _contains_match_term(normalized_text, name):
            matched_by.add("NAME")
            evidence.append(_short_evidence("名称命中", name))
            weights.append(
                1.0 if normalized_text == _normalize_match_text(name) else 0.80
            )

        for alias in node.get("aliases") or []:
            if _contains_match_term(normalized_text, alias):
                matched_by.add("ALIAS")
                evidence.append(_short_evidence("别名命中", alias))
                weights.append(
                    0.96
                    if normalized_text == _normalize_match_text(alias)
                    else 0.76
                )

        for keyword in node.get("keywords") or []:
            if _contains_match_term(normalized_text, keyword):
                matched_by.add("KEYWORD")
                evidence.append(_short_evidence("关键词命中", keyword))
                weights.append(
                    0.92
                    if normalized_text == _normalize_match_text(keyword)
                    else 0.62
                )

        for fragment in _fact_fragments(node):
            if _contains_match_term(normalized_text, fragment):
                matched_by.add("FULLTEXT")
                evidence.append(_short_evidence("审核事实命中", fragment))
                weights.append(0.45)

        node_id = str(node["id"])
        has_lexical_match = bool(weights)
        lexical_hit_count = len(evidence)
        context_bonus = 0.0
        if current_id and node_id == current_id:
            matched_by.add("GRAPH_CONTEXT")
            evidence.append(_short_evidence("当前知识点", current_id))
            if has_lexical_match:
                context_bonus = 0.08
            else:
                weights.append(0.42)
        elif has_lexical_match and node_id in context_ids:
            matched_by.add("GRAPH_CONTEXT")
            evidence.append(_short_evidence("关联当前知识点", current_id))
            context_bonus = 0.04

        if not weights:
            continue
        base_score = max(weights)
        supporting_hits = max(0, lexical_hit_count - 1)
        score = min(
            1.0,
            base_score + min(0.16, supporting_hits * 0.04) + context_bonus,
        )
        candidates.append(
            {
                "node": node,
                "score": round(score, 4),
                "matched_by": sorted(
                    matched_by, key=lambda item: _MATCHED_BY_ORDER[item]
                ),
                "evidence": evidence[:8],
                "requires_review": score < 0.82,
            }
        )

    candidates.sort(
        key=lambda item: (
            -item["score"],
            -int(item["node"].get("level") or 0),
            str(item["node"]["id"]),
        )
    )
    # Similar top scores are ambiguous even when each individual match is strong.
    for index, candidate in enumerate(candidates):
        neighbor_scores = []
        if index:
            neighbor_scores.append(candidates[index - 1]["score"])
        if index + 1 < len(candidates):
            neighbor_scores.append(candidates[index + 1]["score"])
        if (
            candidate["score"] < 0.98
            and any(abs(candidate["score"] - score) <= 0.05 for score in neighbor_scores)
        ):
            candidate["requires_review"] = True
    return candidates[: max(1, min(limit, 20))]


_MASTERED_SCORE = 85.0


def build_recommendations(
    nodes: list[dict[str, Any]],
    edges: list[dict[str, Any]],
    mastery: list[dict[str, Any]],
    *,
    limit: int,
) -> list[dict[str, Any]]:
    """Build deterministic, approved-only next-step recommendations.

    This function deliberately receives no student identifier: C only evaluates the
    aggregate mastery facts sent by B and must not query student-owned data.  Both
    repositories invoke the same function so JSON and Neo4j have identical
    filtering, prerequisite direction, and ordering semantics.
    """
    approved = {
        str(node["id"]): dict(node)
        for node in nodes
        if node.get("review_status") == "APPROVED"
        and node.get("node_type") == "knowledge_point"
    }
    prerequisites: dict[str, set[str]] = {node_id: set() for node_id in approved}
    successors: dict[str, set[str]] = {node_id: set() for node_id in approved}
    for edge in edges:
        if edge.get("relation") != "requires" or edge.get("review_status") != "APPROVED":
            continue
        source_id = str(edge.get("source_id", ""))
        target_id = str(edge.get("target_id", ""))
        if source_id in approved and target_id in approved:
            prerequisites[target_id].add(source_id)
            successors[source_id].add(target_id)

    # Duplicate caller rows are resolved deterministically.  Prefer the most
    # confident observation; for equal confidence keep the lower score so a
    # duplicate cannot accidentally unlock a prerequisite.
    mastery_by_id: dict[str, tuple[float, float]] = {}
    for item in mastery:
        node_id = str(item.get("knowledge_point_id", ""))
        if node_id not in approved:
            continue
        score = float(item["score"])
        confidence = float(item["confidence"])
        existing = mastery_by_id.get(node_id)
        if existing is None or (confidence, -score) > (existing[1], -existing[0]):
            mastery_by_id[node_id] = (score, confidence)

    def is_mastered(node_id: str) -> bool:
        return mastery_by_id.get(node_id, (0.0, 0.0))[0] >= _MASTERED_SCORE

    def is_available(node_id: str) -> bool:
        return not is_mastered(node_id) and all(
            is_mastered(prerequisite_id)
            for prerequisite_id in prerequisites[node_id]
        )

    def blockers(node_id: str) -> list[tuple[str, list[str]]]:
        """Return nearest available approved prerequisites and their evidence paths."""
        results: dict[str, list[str]] = {}

        def visit(current_id: str, path: list[str], seen: set[str]) -> None:
            for prerequisite_id in sorted(prerequisites[current_id]):
                if prerequisite_id in seen or is_mastered(prerequisite_id):
                    continue
                next_path = [prerequisite_id, *path]
                if is_available(prerequisite_id):
                    existing = results.get(prerequisite_id)
                    if existing is None or tuple(next_path) < tuple(existing):
                        results[prerequisite_id] = next_path
                else:
                    visit(prerequisite_id, next_path, { *seen, prerequisite_id })

        visit(node_id, [node_id], {node_id})
        return [(candidate_id, results[candidate_id]) for candidate_id in sorted(results)]

    candidates: dict[str, dict[str, Any]] = {}

    def add_candidate(
        node_id: str,
        *,
        rank: int,
        score: float,
        confidence: float,
        reason: str,
        evidence: str,
    ) -> None:
        key = (rank, round(score, 4), -round(confidence, 4), node_id)
        existing = candidates.get(node_id)
        if existing is None:
            candidates[node_id] = {
                "node": approved[node_id],
                "key": key,
                "reason": reason,
                "evidence": {evidence},
            }
            return
        existing["evidence"].add(evidence)
        if key < existing["key"]:
            existing["key"] = key
            existing["reason"] = reason

    def add_blockers(target_id: str) -> None:
        for blocker_id, path in blockers(target_id):
            path_text = " → ".join(path)
            add_candidate(
                blocker_id,
                rank=0,
                score=mastery_by_id.get(blocker_id, (0.0, 0.0))[0],
                confidence=mastery_by_id.get(blocker_id, (0.0, 0.0))[1],
                reason=(
                    f"{approved[target_id]['name']} 尚未解锁，建议先学习"
                    f"已审核先修 {approved[blocker_id]['name']}。"
                ),
                evidence=f"已审核先修链：{path_text}",
            )

    for node_id in sorted(mastery_by_id):
        score, confidence = mastery_by_id[node_id]
        if score < _MASTERED_SCORE:
            if is_available(node_id):
                level = "薄弱" if score < 60 else "学习中"
                add_candidate(
                    node_id,
                    rank=1 if score < 60 else 2,
                    score=score,
                    confidence=confidence,
                    reason=f"{approved[node_id]['name']} 当前为{level}，建议优先巩固。",
                    evidence=(
                        f"掌握度证据：score={score:.2f}，"
                        f"confidence={confidence:.2f}；已审核先修均已满足"
                    ),
                )
            else:
                add_blockers(node_id)
            continue

        for successor_id in sorted(successors[node_id]):
            if is_mastered(successor_id):
                continue
            if is_available(successor_id):
                add_candidate(
                    successor_id,
                    rank=3,
                    score=mastery_by_id.get(successor_id, (0.0, 0.0))[0],
                    confidence=confidence,
                    reason=(
                        f"已掌握 {approved[node_id]['name']}，可以继续学习"
                        f" {approved[successor_id]['name']}。"
                    ),
                    evidence=(
                        f"已审核关系：{node_id} → {successor_id}；"
                        f"前置掌握度={score:.2f}"
                    ),
                )
            else:
                add_blockers(successor_id)

    # With no usable evidence, expose only entry points whose approved
    # prerequisites are already satisfied (normally roots).  A mastered graph
    # intentionally returns an empty list instead of inventing a next step.
    if not mastery_by_id:
        for node_id in sorted(approved):
            if is_available(node_id):
                add_candidate(
                    node_id,
                    rank=4,
                    score=0.0,
                    confidence=0.0,
                    reason=f"{approved[node_id]['name']} 是当前可开始的已审核知识点。",
                    evidence="已审核先修条件为空或均已满足",
                )

    ordered = sorted(candidates.values(), key=lambda item: item["key"])
    result: list[dict[str, Any]] = []
    for priority, candidate in enumerate(ordered[: max(1, min(limit, 20))], start=1):
        result.append(
            {
                "node": candidate["node"],
                "reason": candidate["reason"],
                "priority": priority,
                "evidence": sorted(candidate["evidence"])[:8],
            }
        )
    return result
