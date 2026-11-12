from __future__ import annotations

import json
from collections import deque
from pathlib import Path
from typing import Any

from knowledge_service.app.domain.graph_dto import (
    build_match_candidates,
    build_recommendations,
    build_rag_context,
    normalize_edge,
    normalize_generated_at,
    normalize_node,
    normalize_source,
)
from knowledge_service.app.repository.course_scope import valid_course_id


class JsonGraphRepository:
    def __init__(self, graph_file: str | Path, include_pending: bool = False, *, course_id: int = 1, graph_data: dict[str, Any] | None = None):
        self.course_id = valid_course_id(course_id)
        self.graph_file = Path(graph_file)
        self.include_pending = include_pending
        if graph_data is None:
            with self.graph_file.open("r", encoding="utf-8") as file:
                self.graph = json.load(file)
        else:
            self.graph = graph_data

        self.metadata = self.graph.get("metadata", {})
        if self.metadata.get("course_id", 1) != self.course_id:
            raise ValueError("graph release belongs to a different course")
        self.nodes = {
            str(node["id"]): normalize_node(node)
            for node in self.graph.get("nodes", [])
        }
        self.edges = [
            normalize_edge(edge)
            for edge in self.graph.get("edges", [])
            if edge.get("source") is not None and edge.get("target") is not None
        ]
        source_rows = self.graph.get("source_sections", self.graph.get("sources", []))
        self.source_records = [normalize_source(source) for source in source_rows]

    def _is_visible(
        self,
        item: dict[str, Any],
        *,
        approved_only: bool = False,
    ) -> bool:
        status = item.get("review_status") or "PENDING"
        if status == "APPROVED":
            return True
        return status == "PENDING" and self.include_pending and not approved_only

    def _edge_is_visible(
        self,
        edge: dict[str, Any],
        *,
        approved_only: bool = False,
    ) -> bool:
        source = self.nodes.get(edge["source_id"])
        target = self.nodes.get(edge["target_id"])
        return bool(
            source
            and target
            and self._is_visible(edge, approved_only=approved_only)
            and self._is_visible(source, approved_only=approved_only)
            and self._is_visible(target, approved_only=approved_only)
        )

    def health(self) -> dict[str, Any]:
        return {
            "status": "UP",
            "node_count": len(self.nodes),
            "edge_count": len(self.edges),
            "backend": "json",
            "graph_file": str(self.graph_file),
            "metadata": self.metadata,
            "warnings": (
                ["已启用 PENDING 草案内容，仅限本地联调"]
                if self.include_pending else []
            ),
        }

    def get_node(
        self,
        node_id: str,
        *,
        approved_only: bool = False,
    ) -> dict[str, Any] | None:
        node = self.nodes.get(str(node_id))
        return dict(node) if node and self._is_visible(node, approved_only=approved_only) else None

    def search(self, query: str, limit: int = 20) -> list[dict[str, Any]]:
        query = query.strip().casefold()
        scored: list[tuple[float, dict[str, Any]]] = []
        for node in self.nodes.values():
            if not self._is_visible(node):
                continue
            fields = {
                "name": node["name"],
                "aliases": " ".join(node["aliases"]),
                "keywords": " ".join(node["keywords"]),
                "description": node.get("description") or "",
            }
            score = 0.0
            if query == fields["name"].casefold():
                score = 1.0
            elif query in fields["name"].casefold():
                score = 0.9
            elif any(query in value.casefold() for value in fields.values()):
                score = 0.7
            if score:
                scored.append((score, dict(node)))

        scored.sort(key=lambda pair: (-pair[0], pair[1]["name"]))
        return [item for _, item in scored[:limit]]

    def match(
        self,
        text: str,
        scene: str,
        current_node_id: str | None = None,
        limit: int = 5,
    ) -> list[dict[str, Any]]:
        # Matching is a production binding operation. It never follows the
        # local GRAPH_ALLOW_PENDING switch used by exploratory graph screens.
        approved_nodes = [
            dict(node)
            for node in self.nodes.values()
            if node.get("review_status") == "APPROVED"
        ]
        context_node_ids = self._match_context_node_ids(current_node_id)
        return build_match_candidates(
            approved_nodes,
            text,
            current_node_id=current_node_id,
            context_node_ids=context_node_ids,
            limit=limit,
        )

    def recommend(
        self,
        mastery: list[dict[str, Any]],
        limit: int = 5,
    ) -> list[dict[str, Any]]:
        # Recommendations are a production personalization contract.  They
        # never follow GRAPH_ALLOW_PENDING, even in a local graph preview.
        return build_recommendations(
            list(self.nodes.values()),
            self.edges,
            mastery,
            limit=limit,
        )

    def _match_context_node_ids(self, current_node_id: str | None) -> set[str]:
        if not current_node_id:
            return set()
        current_id = str(current_node_id)
        current = self.nodes.get(current_id)
        if not current or current.get("review_status") != "APPROVED":
            return set()

        context_ids = {current_id}
        parent_id = current.get("parent_id")
        if parent_id and self.get_node(str(parent_id), approved_only=True):
            context_ids.add(str(parent_id))
        context_ids.update(
            node["id"]
            for node in self.nodes.values()
            if node.get("parent_id") == current_id
            and node.get("review_status") == "APPROVED"
        )
        for edge in self.edges:
            if not self._edge_is_visible(edge, approved_only=True):
                continue
            if edge["source_id"] == current_id:
                context_ids.add(edge["target_id"])
            elif edge["target_id"] == current_id:
                context_ids.add(edge["source_id"])
        return context_ids

    def context(
        self,
        node_id: str,
        *,
        approved_only: bool = False,
    ) -> dict[str, Any] | None:
        node = self.get_node(node_id, approved_only=approved_only)
        if node is None:
            return None

        node_id = str(node_id)
        parent = (
            self.get_node(node["parent_id"], approved_only=approved_only)
            if node.get("parent_id")
            else None
        )
        children = [
            item
            for item in self.nodes.values()
            if item.get("parent_id") == node_id
            and self._is_visible(item, approved_only=approved_only)
        ]
        # Frozen semantics: source is the prerequisite that must be mastered
        # before target can be learned.
        prerequisites = [
            self.nodes[edge["source_id"]]
            for edge in self.edges
            if edge["relation"] == "requires"
            and self._edge_is_visible(edge, approved_only=approved_only)
            and edge["target_id"] == node_id
        ]
        successors = [
            self.nodes[edge["target_id"]]
            for edge in self.edges
            if edge["relation"] == "requires"
            and self._edge_is_visible(edge, approved_only=approved_only)
            and edge["source_id"] == node_id
        ]
        return {
            "node": node,
            "course_id": self.course_id,
            "parent": parent,
            "children": sorted(children, key=lambda item: item["id"]),
            "prerequisites": sorted(prerequisites, key=lambda item: item["id"]),
            "successors": sorted(successors, key=lambda item: item["id"]),
            "source_status": "NOT_REVIEWED",
        }

    def prerequisites(
        self,
        node_id: str,
        max_depth: int = 5,
    ) -> list[dict[str, Any]] | None:
        if self.get_node(node_id) is None:
            return None
        max_depth = max(1, min(max_depth, 10))
        incoming: dict[str, list[str]] = {node_id: [] for node_id in self.nodes}
        for edge in self.edges:
            if edge["relation"] != "requires" or not self._edge_is_visible(edge):
                continue
            incoming[edge["target_id"]].append(edge["source_id"])

        seen = {str(node_id)}
        queue: deque[tuple[str, int]] = deque([(str(node_id), 0)])
        distances: dict[str, int] = {}
        while queue:
            current, distance = queue.popleft()
            if distance >= max_depth:
                continue
            for prerequisite_id in sorted(incoming.get(current, [])):
                if prerequisite_id in seen:
                    continue
                seen.add(prerequisite_id)
                distances[prerequisite_id] = distance + 1
                queue.append((prerequisite_id, distance + 1))
        return [
            dict(self.nodes[prerequisite_id])
            for prerequisite_id in sorted(
                distances,
                key=lambda item: (distances[item], item),
            )
        ]

    def sources(
        self,
        node_id: str,
        approved_only: bool = True,
    ) -> list[dict[str, Any]] | None:
        if self.get_node(node_id, approved_only=approved_only) is None:
            return None
        return sorted(
            [
                dict(source)
                for source in self.source_records
                if source["node_id"] == str(node_id)
                and self._is_visible(source, approved_only=approved_only)
                and source["rights_status"] == "ALLOWED"
            ],
            key=lambda source: source["id"],
        )

    def version(self) -> dict[str, Any]:
        return {
            "course_id": self.course_id,
            "graph_version": str(self.metadata.get("version") or "unknown"),
            "backend": "json",
            "node_count": len(self.nodes),
            "edge_count": len(self.edges),
            "approved_node_count": sum(
                node["review_status"] == "APPROVED" for node in self.nodes.values()
            ),
            "approved_edge_count": sum(
                edge["review_status"] == "APPROVED" for edge in self.edges
            ),
            "source_count": len(self.source_records),
            "generated_at": normalize_generated_at(
                self.metadata.get("updated_at") or self.metadata.get("created_at")
            ),
        }

    def rag_context(
        self,
        node_id: str,
        max_related: int = 8,
    ) -> dict[str, Any] | None:
        # Graph-RAG never uses the draft switch: every returned fact is APPROVED.
        context = self.context(node_id, approved_only=True)
        if context is None:
            return None
        max_related = max(0, min(max_related, 20))
        related: dict[str, dict[str, Any]] = {}
        for edge in self.edges:
            if edge["relation"] != "related" or not self._edge_is_visible(
                edge, approved_only=True
            ):
                continue
            related_id = None
            if edge["source_id"] == str(node_id):
                related_id = edge["target_id"]
            elif edge["target_id"] == str(node_id):
                related_id = edge["source_id"]
            if related_id is not None:
                related[related_id] = self.nodes[related_id]

        related_nodes = [related[item] for item in sorted(related)[:max_related]]
        context_node_ids = {
            item["id"]
            for item in [
                context["node"],
                *context["prerequisites"],
                *context["successors"],
                *related_nodes,
            ]
        }
        approved_sources = sorted(
            [
                dict(source)
                for source in self.source_records
                if source["node_id"] in context_node_ids
                and self._is_visible(source, approved_only=True)
                and source["rights_status"] == "ALLOWED"
            ],
            key=lambda source: (source["node_id"], source["id"]),
        )
        return build_rag_context(
            context["node"],
            context["prerequisites"],
            context["successors"],
            related_nodes,
            approved_sources,
            str(self.metadata.get("version") or "unknown"),
            course_id=self.course_id,
        )

    def course_root(self) -> str | None:
        roots = [node["id"] for node in self.nodes.values()
                 if node["level"] == 0 and node["node_type"] == "course"
                 and node["review_status"] == "APPROVED"]
        if len(roots) > 1:
            raise ValueError("course graph has multiple approved roots")
        return roots[0] if roots else None

    def subgraph(
        self,
        center_id: str,
        depth: int = 1,
        relations: set[str] | None = None,
    ) -> dict[str, Any] | None:
        if self.get_node(center_id) is None:
            return None
        depth = max(1, min(depth, 5))
        adjacency: dict[str, set[str]] = {node_id: set() for node_id in self.nodes}
        explicit_edges = [
            edge
            for edge in self.edges
            if (relations is None or edge["relation"] in relations)
            and self._edge_is_visible(edge)
        ]
        contains_edges = [
            {
                "source_id": str(node["parent_id"]),
                "target_id": node["id"],
                "relation": "contains",
                "review_status": node.get("review_status") or "PENDING",
                "evidence": [],
                "confidence": None,
            }
            for node in self.nodes.values()
            if node.get("parent_id") in self.nodes
            and (relations is None or "contains" in relations)
            and self._is_visible(node)
            and self._is_visible(self.nodes[str(node["parent_id"])])
        ]
        usable_edges = explicit_edges + contains_edges
        for edge in usable_edges:
            adjacency[edge["source_id"]].add(edge["target_id"])
            adjacency[edge["target_id"]].add(edge["source_id"])

        distances = {str(center_id): 0}
        queue = deque([str(center_id)])
        while queue:
            current = queue.popleft()
            if distances[current] >= depth:
                continue
            for neighbor in adjacency[current]:
                if neighbor not in distances:
                    distances[neighbor] = distances[current] + 1
                    queue.append(neighbor)

        selected = set(distances)
        graph_edges = sorted(
            [
                edge
                for edge in usable_edges
                if edge["source_id"] in selected and edge["target_id"] in selected
            ],
            key=lambda edge: (
                edge["source_id"],
                edge["target_id"],
                edge["relation"],
            ),
        )
        return {
            "center_id": str(center_id),
            "course_id": self.course_id,
            "nodes": [self.nodes[node_id] for node_id in sorted(selected)],
            "edges": graph_edges,
            "graph_version": str(self.metadata.get("version", "unknown")),
        }
