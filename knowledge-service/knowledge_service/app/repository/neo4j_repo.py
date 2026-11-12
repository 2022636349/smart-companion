from __future__ import annotations

import logging
import os
from collections import Counter
from time import perf_counter
from typing import Any
from knowledge_service.app.repository.course_scope import ensure_course_schema, scoped_statement, valid_course_id

from knowledge_service.app.domain.graph_dto import (
    build_match_candidates,
    build_recommendations,
    build_rag_context,
    normalize_edge,
    normalize_generated_at,
    normalize_node,
    normalize_source,
)


LOGGER = logging.getLogger("uvicorn.error")
ABSOLUTE_CONTEXT_ITEM_LIMIT = 500
ABSOLUTE_PREREQUISITE_NODE_LIMIT = 1000
ABSOLUTE_SOURCE_LIMIT = 2000
ABSOLUTE_SUBGRAPH_NODE_LIMIT = 1000
ABSOLUTE_SUBGRAPH_HOP_LIMIT = 500
ABSOLUTE_SUBGRAPH_EDGE_LIMIT = 4000
ALLOWED_NODE_RELATIONS = {"contains", "related", "requires", "supported_by"}


def _bounded_setting(name: str, default: int, maximum: int) -> int:
    return max(1, min(int(os.getenv(name, str(default))), maximum))


class GraphQueryBudgetExceeded(RuntimeError):
    """The frozen DTO cannot safely represent a truncated graph result."""


class Neo4jGraphRepository:
    def __init__(
        self,
        uri: str,
        user: str,
        password: str,
        include_pending: bool = False,
        *,
        course_id: int = 1,
        query_timeout_seconds: float | None = None,
        max_connection_pool_size: int | None = None,
        connection_timeout_seconds: float | None = None,
        candidate_limit: int | None = None,
        recommendation_depth: int | None = None,
        slow_query_ms: float | None = None,
    ):
        try:
            from neo4j import GraphDatabase, Query
        except ImportError as exc:
            raise RuntimeError("未安装 neo4j Python 驱动") from exc
        self.course_id = valid_course_id(course_id)
        self.query_timeout_seconds = query_timeout_seconds or float(
            os.getenv("GRAPH_QUERY_TIMEOUT_SECONDS", "3")
        )
        pool_size = max_connection_pool_size or int(
            os.getenv("GRAPH_NEO4J_POOL_SIZE", "20")
        )
        connection_timeout = connection_timeout_seconds or float(
            os.getenv("GRAPH_NEO4J_CONNECTION_TIMEOUT_SECONDS", "5")
        )
        self.candidate_limit = max(
            20,
            min(candidate_limit or int(os.getenv("GRAPH_QUERY_CANDIDATE_LIMIT", "500")), 5000),
        )
        self.context_item_limit = min(
            self.candidate_limit,
            _bounded_setting(
                "GRAPH_CONTEXT_ITEM_LIMIT",
                min(self.candidate_limit, 200),
                ABSOLUTE_CONTEXT_ITEM_LIMIT,
            ),
        )
        self.prerequisite_node_limit = min(
            self.candidate_limit,
            _bounded_setting(
                "GRAPH_PREREQUISITE_NODE_LIMIT",
                self.candidate_limit,
                ABSOLUTE_PREREQUISITE_NODE_LIMIT,
            ),
        )
        self.source_limit = min(
            self.candidate_limit * 4,
            _bounded_setting(
                "GRAPH_SOURCE_LIMIT",
                min(self.candidate_limit * 4, 1000),
                ABSOLUTE_SOURCE_LIMIT,
            ),
        )
        self.subgraph_node_limit = min(
            self.candidate_limit,
            _bounded_setting(
                "GRAPH_SUBGRAPH_NODE_LIMIT",
                self.candidate_limit,
                ABSOLUTE_SUBGRAPH_NODE_LIMIT,
            ),
        )
        self.subgraph_hop_limit = min(
            self.subgraph_node_limit,
            _bounded_setting(
                "GRAPH_SUBGRAPH_HOP_LIMIT",
                min(self.subgraph_node_limit, 200),
                ABSOLUTE_SUBGRAPH_HOP_LIMIT,
            ),
        )
        self.subgraph_edge_limit = min(
            self.candidate_limit * 4,
            _bounded_setting(
                "GRAPH_SUBGRAPH_EDGE_LIMIT",
                min(self.candidate_limit * 4, 2000),
                ABSOLUTE_SUBGRAPH_EDGE_LIMIT,
            ),
        )
        self.recommendation_depth = max(
            1,
            min(recommendation_depth or int(os.getenv("GRAPH_RECOMMEND_MAX_DEPTH", "10")), 10),
        )
        self.slow_query_ms = slow_query_ms or float(
            os.getenv("GRAPH_SLOW_QUERY_MS", "250")
        )
        self._query_type = Query
        self.driver = GraphDatabase.driver(
            uri,
            auth=(user, password),
            max_connection_pool_size=pool_size,
            connection_timeout=connection_timeout,
            connection_acquisition_timeout=connection_timeout,
        )
        self.include_pending = include_pending
        with self.driver.session() as session:
            ensure_course_schema(session)
            if self.course_id != 1:
                session.run(
                    "MERGE (m:GraphMetadata {course_id: $course_id, key: 'current'}) "
                    "ON CREATE SET m.version = '0.0.0', m.created_at = '1970-01-01T00:00:00Z'",
                    course_id=self.course_id,
                ).consume()

    def _run(self, session: Any, statement: str, **params: Any) -> Any:
        statement = scoped_statement(statement)
        params["course_id"] = getattr(self, "course_id", 1)
        started = perf_counter()
        query_type = getattr(self, "_query_type", None)
        query = (
            query_type(statement, timeout=self.query_timeout_seconds)
            if query_type is not None
            else statement
        )
        result = session.run(query, **params)
        duration_ms = (perf_counter() - started) * 1000
        if duration_ms >= getattr(self, "slow_query_ms", 250.0):
            LOGGER.warning(
                "graph_slow_query backend=neo4j duration_ms=%.2f timeout_seconds=%.2f",
                duration_ms,
                getattr(self, "query_timeout_seconds", 3.0),
            )
        return result

    @staticmethod
    def _bounded_layer_nodes(
        records: Any,
        *,
        layer_limit: int,
        per_frontier_limit: int,
        error_message: str,
    ) -> list[dict[str, Any]]:
        rows = list(records)
        if len(rows) > layer_limit:
            raise GraphQueryBudgetExceeded(error_message)
        per_frontier = Counter(str(row.get("frontier_id") or "") for row in rows)
        if any(count > per_frontier_limit for count in per_frontier.values()):
            raise GraphQueryBudgetExceeded(error_message)
        nodes = {
            str(node["id"]): node
            for node in (
                normalize_node(dict(row["node"])) for row in rows if row.get("node")
            )
        }
        return [nodes[node_id] for node_id in sorted(nodes)]

    @staticmethod
    def _visibility(alias: str) -> str:
        return (
            f"(coalesce({alias}.review_status, 'PENDING') = 'APPROVED' "
            f"OR ($allow_pending AND coalesce({alias}.review_status, 'PENDING') = 'PENDING'))"
        )

    @staticmethod
    def _relationship_visibility(alias: str) -> str:
        return f"coalesce({alias}.course_id, $course_id) = $course_id"

    def close(self) -> None:
        self.driver.close()

    def health(self) -> dict[str, Any]:
        with self.driver.session() as session:
            self._run(session, "RETURN 1").consume()
            node_count = self._run(
                session, "MATCH (n:KnowledgeNode) RETURN count(n) AS count"
            ).single()["count"]
            edge_count = self._run(
                session,
                """
                MATCH (:KnowledgeNode)-[r]->(:KnowledgeNode)
                WHERE type(r) IN ['REQUIRES', 'RELATED', 'SUPPORTED_BY']
                RETURN count(r) AS count
                """
            ).single()["count"]
        return {
            "status": "UP",
            "node_count": node_count,
            "edge_count": edge_count,
            "backend": "neo4j",
            "graph_file": None,
            "metadata": {},
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
        with self.driver.session() as session:
            record = self._run(
                session,
                f"""
                MATCH (n:KnowledgeNode {{id: $id}})
                WHERE {self._visibility('n')}
                RETURN n
                """,
                id=str(node_id),
                allow_pending=self.include_pending and not approved_only,
            ).single()
        return normalize_node(dict(record["n"])) if record else None

    def search(self, query: str, limit: int = 20) -> list[dict[str, Any]]:
        term = query.strip()
        with self.driver.session() as session:
            records = self._run(
                session,
                """
                MATCH (n:KnowledgeNode)
                WHERE (coalesce(n.review_status, 'PENDING') = 'APPROVED'
                       OR ($allow_pending AND coalesce(n.review_status, 'PENDING') = 'PENDING'))
                  AND (toLower(n.name) CONTAINS toLower($term)
                       OR any(alias IN coalesce(n.aliases, []) WHERE toLower(alias) CONTAINS toLower($term))
                       OR any(keyword IN coalesce(n.keywords, []) WHERE toLower(keyword) CONTAINS toLower($term))
                       OR toLower(coalesce(n.description, '')) CONTAINS toLower($term))
                RETURN n
                ORDER BY CASE WHEN toLower(n.name) = toLower($term) THEN 0 ELSE 1 END, n.name
                LIMIT $limit
                """,
                term=term,
                limit=limit,
                allow_pending=self.include_pending,
            )
            return [normalize_node(dict(record["n"])) for record in records]

    def match(
        self,
        text: str,
        scene: str,
        current_node_id: str | None = None,
        limit: int = 5,
    ) -> list[dict[str, Any]]:
        # Cypher performs a bounded lexical prefilter; the shared Python scorer
        # remains the final DTO/parity authority.
        with self.driver.session() as session:
            records = self._run(
                session,
                """
                MATCH (n:KnowledgeNode)
                WHERE coalesce(n.review_status, 'PENDING') = 'APPROVED'
                  AND n.node_type = 'knowledge_point'
                  AND (
                    n.id = $current_node_id
                    OR toLower($text) CONTAINS toLower(n.name)
                    OR any(value IN coalesce(n.aliases, [])
                           WHERE toLower($text) CONTAINS toLower(value))
                    OR any(value IN coalesce(n.keywords, [])
                           WHERE toLower($text) CONTAINS toLower(value))
                    OR any(value IN coalesce(n.learning_objectives, [])
                           WHERE toLower($text) CONTAINS toLower(value))
                    OR any(value IN coalesce(n.common_misconceptions, [])
                           WHERE toLower($text) CONTAINS toLower(value))
                    OR any(value IN coalesce(n.example_code, [])
                           WHERE toLower($text) CONTAINS toLower(value))
                  )
                RETURN n
                ORDER BY n.id
                LIMIT $candidate_limit
                """,
                text=text,
                current_node_id=str(current_node_id) if current_node_id else None,
                candidate_limit=getattr(self, "candidate_limit", 500),
            )
            approved_nodes = [normalize_node(dict(record["n"])) for record in records]

        context_node_ids = self._match_context_node_ids(
            approved_nodes, current_node_id
        )
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
        candidate_limit = getattr(self, "candidate_limit", 500)
        with self.driver.session() as session:
            # Recommendation ranking needs the complete approved prerequisite
            # closure, not merely the connected component around the student's
            # current mastery rows.  Reading that closure with a hop counter made
            # valid course graphs fail once a dependency chain crossed ten levels.
            # A single, hard-bounded scan is both cheaper and complete for graphs
            # within the public recommendation budget.
            node_records = self._run(
                session,
                """
                MATCH (n:KnowledgeNode)
                WHERE coalesce(n.review_status, 'PENDING') = 'APPROVED'
                  AND n.node_type = 'knowledge_point'
                RETURN n
                ORDER BY n.id
                LIMIT $fetch_limit
                """,
                fetch_limit=candidate_limit + 1,
            )
            initial_nodes = [normalize_node(dict(record["n"])) for record in node_records]
            if len(initial_nodes) > candidate_limit:
                raise GraphQueryBudgetExceeded(
                    "graph recommendation candidate budget exceeded"
                )
            selected_nodes = {str(node["id"]): node for node in initial_nodes}

            nodes = [selected_nodes[node_id] for node_id in sorted(selected_nodes)]
            node_ids = sorted(selected_nodes)
            edge_limit = min(candidate_limit * 4, ABSOLUTE_SUBGRAPH_EDGE_LIMIT)
            edge_records = self._run(
                session,
                """
                MATCH (source:KnowledgeNode)-[rel:REQUIRES]->(target:KnowledgeNode)
                WHERE source.id IN $node_ids AND target.id IN $node_ids
                  AND coalesce(source.review_status, 'PENDING') = 'APPROVED'
                  AND coalesce(target.review_status, 'PENDING') = 'APPROVED'
                  AND coalesce(rel.review_status, 'PENDING') = 'APPROVED'
                RETURN source.id AS source_id, target.id AS target_id,
                       toLower(type(rel)) AS relation,
                       coalesce(rel.review_status, 'PENDING') AS review_status,
                       coalesce(rel.evidence, []) AS evidence,
                       properties(rel)['confidence'] AS confidence
                ORDER BY source.id, target.id
                LIMIT $fetch_limit
                """,
                node_ids=node_ids,
                fetch_limit=edge_limit + 1,
            )
            edges = [normalize_edge(dict(record)) for record in edge_records]
            if len(edges) > edge_limit:
                raise GraphQueryBudgetExceeded(
                    "graph recommendation edge budget exceeded"
                )
        return build_recommendations(nodes, edges, mastery, limit=limit)

    def _match_context_node_ids(
        self,
        approved_nodes: list[dict[str, Any]],
        current_node_id: str | None,
    ) -> set[str]:
        if not current_node_id:
            return set()
        current_id = str(current_node_id)
        context = self.context(current_id, approved_only=True)
        if context is None:
            return set()
        context_ids = {
            str(item["id"])
            for item in [
                context["node"],
                context["parent"],
                *context["children"],
                *context["prerequisites"],
                *context["successors"],
            ]
            if item
        }
        item_limit = getattr(self, "context_item_limit", 200)
        with self.driver.session() as session:
            records = self._run(
                session,
                """
                MATCH (current:KnowledgeNode {id: $id})-[rel]-(neighbor:KnowledgeNode)
                WHERE coalesce(current.review_status, 'PENDING') = 'APPROVED'
                  AND coalesce(rel.review_status, 'PENDING') = 'APPROVED'
                  AND coalesce(neighbor.review_status, 'PENDING') = 'APPROVED'
                RETURN DISTINCT neighbor.id AS neighbor_id
                ORDER BY neighbor_id
                LIMIT $fetch_limit
                """,
                id=current_id,
                fetch_limit=item_limit + 1,
            )
            neighbor_ids = [
                str(record["neighbor_id"])
                for record in records
                if record.get("neighbor_id") is not None
            ]
            if len(neighbor_ids) > item_limit:
                raise GraphQueryBudgetExceeded("graph match context budget exceeded")
            context_ids.update(neighbor_ids)
        ordered = [current_id, *sorted(context_ids - {current_id})]
        if len(ordered) > item_limit:
            raise GraphQueryBudgetExceeded("graph match context budget exceeded")
        return set(ordered)

    def context(
        self,
        node_id: str,
        *,
        approved_only: bool = False,
    ) -> dict[str, Any] | None:
        allow_pending = self.include_pending and not approved_only
        node = self.get_node(node_id, approved_only=approved_only)
        if node is None:
            return None
        parent = (
            self.get_node(str(node["parent_id"]), approved_only=approved_only)
            if node.get("parent_id")
            else None
        )
        item_limit = getattr(self, "context_item_limit", 200)
        with self.driver.session() as session:
            children = [
                normalize_node(dict(record["node"]))
                for record in self._run(
                    session,
                    f"""
                    MATCH (child:KnowledgeNode {{parent_id: $id}})
                    WHERE {self._visibility('child')}
                    RETURN child AS node
                    ORDER BY child.id
                    LIMIT $fetch_limit
                    """,
                    id=str(node_id),
                    allow_pending=allow_pending,
                    fetch_limit=item_limit + 1,
                )
            ]
            if len(children) > item_limit:
                raise GraphQueryBudgetExceeded("graph child context budget exceeded")
            prerequisites = [
                normalize_node(dict(record["node"]))
                for record in self._run(
                    session,
                    f"""
                    MATCH (prerequisite:KnowledgeNode)-[rel:REQUIRES]->
                          (target:KnowledgeNode {{id: $id}})
                    WHERE {self._visibility('prerequisite')}
                      AND {self._visibility('target')}
                      AND {self._visibility('rel')}
                    RETURN DISTINCT prerequisite AS node
                    ORDER BY node.id
                    LIMIT $fetch_limit
                    """,
                    id=str(node_id),
                    allow_pending=allow_pending,
                    fetch_limit=item_limit + 1,
                )
            ]
            if len(prerequisites) > item_limit:
                raise GraphQueryBudgetExceeded(
                    "graph prerequisite context budget exceeded"
                )
            successors = [
                normalize_node(dict(record["node"]))
                for record in self._run(
                    session,
                    f"""
                    MATCH (source:KnowledgeNode {{id: $id}})-[rel:REQUIRES]->
                          (successor:KnowledgeNode)
                    WHERE {self._visibility('source')}
                      AND {self._visibility('successor')}
                      AND {self._visibility('rel')}
                    RETURN DISTINCT successor AS node
                    ORDER BY node.id
                    LIMIT $fetch_limit
                    """,
                    id=str(node_id),
                    allow_pending=allow_pending,
                    fetch_limit=item_limit + 1,
                )
            ]
            if len(successors) > item_limit:
                raise GraphQueryBudgetExceeded("graph successor context budget exceeded")
        return {
            "node": node,
            "course_id": self.course_id,
            "parent": parent,
            "children": children,
            "prerequisites": prerequisites,
            "successors": successors,
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
        node_limit = getattr(self, "prerequisite_node_limit", 500)
        hop_limit = min(getattr(self, "subgraph_hop_limit", 200), node_limit)
        selected_ids = {str(node_id)}
        frontier_ids = [str(node_id)]
        result: list[dict[str, Any]] = []
        with self.driver.session() as session:
            for _depth in range(max_depth):
                remaining = node_limit - len(result)
                if not frontier_ids:
                    break
                if remaining <= 0:
                    raise GraphQueryBudgetExceeded(
                        "graph prerequisite node budget exhausted"
                    )
                current_hop_limit = min(hop_limit, remaining)
                per_frontier_limit = min(
                    hop_limit,
                    max(
                        1,
                        (current_hop_limit + len(frontier_ids) - 1)
                        // len(frontier_ids),
                    ),
                )
                records = self._run(
                    session,
                    f"""
                    UNWIND $frontier_ids AS frontier_id
                    CALL (frontier_id) {{
                      MATCH (prerequisite:KnowledgeNode)-[rel:REQUIRES]->
                            (target:KnowledgeNode {{id: frontier_id}})
                      WHERE NOT (prerequisite.id IN $selected_ids)
                        AND {self._visibility('prerequisite')}
                        AND {self._visibility('target')}
                        AND {self._visibility('rel')}
                      RETURN prerequisite AS neighbor
                      ORDER BY prerequisite.id
                      LIMIT $per_frontier_fetch_limit
                    }}
                    RETURN frontier_id, neighbor AS node
                    ORDER BY frontier_id, neighbor.id
                    LIMIT $layer_fetch_limit
                    """,
                    frontier_ids=frontier_ids,
                    selected_ids=sorted(selected_ids),
                    allow_pending=self.include_pending,
                    per_frontier_fetch_limit=per_frontier_limit + 1,
                    layer_fetch_limit=current_hop_limit + 1,
                )
                next_nodes = self._bounded_layer_nodes(
                    records,
                    layer_limit=current_hop_limit,
                    per_frontier_limit=per_frontier_limit,
                    error_message="graph prerequisite traversal budget exceeded",
                )
                frontier_ids = []
                for item in next_nodes:
                    item_id = str(item["id"])
                    if item_id in selected_ids:
                        continue
                    selected_ids.add(item_id)
                    frontier_ids.append(item_id)
                    result.append(item)
        return result

    def sources(
        self,
        node_id: str,
        approved_only: bool = True,
    ) -> list[dict[str, Any]] | None:
        allow_pending = self.include_pending and not approved_only
        if self.get_node(node_id, approved_only=approved_only) is None:
            return None
        return self._sources_for_node_ids([str(node_id)], allow_pending=allow_pending)

    def _sources_for_node_ids(
        self,
        node_ids: list[str],
        *,
        allow_pending: bool,
    ) -> list[dict[str, Any]]:
        source_limit = getattr(self, "source_limit", 1000)
        with self.driver.session() as session:
            records = self._run(
                session,
                f"""
                MATCH (source:GraphSource)
                WHERE source.node_id IN $node_ids
                  AND {self._visibility('source')}
                  AND coalesce(
                    source.rights_status,
                    CASE WHEN source.review_status = 'APPROVED'
                         THEN 'ALLOWED' ELSE 'RESTRICTED' END
                  ) = 'ALLOWED'
                RETURN source
                ORDER BY CASE WHEN source.node_id = $primary_node_id THEN 0 ELSE 1 END,
                         source.node_id, source.id
                LIMIT $fetch_limit
                """,
                node_ids=node_ids,
                primary_node_id=node_ids[0] if node_ids else None,
                allow_pending=allow_pending,
                fetch_limit=source_limit + 1,
            )
            sources = [
                normalize_source(dict(record["source"])) for record in records
            ]
            if len(sources) > source_limit:
                raise GraphQueryBudgetExceeded("graph source response budget exceeded")
            return sources

    def version(self) -> dict[str, Any]:
        with self.driver.session() as session:
            metadata_record = self._run(
                session,
                "MATCH (m:GraphMetadata {key: 'current'}) RETURN m"
            ).single()
            counts = self._run(
                session,
                """
                MATCH (n:KnowledgeNode)
                WITH count(n) AS node_count,
                     sum(CASE WHEN n.review_status = 'APPROVED' THEN 1 ELSE 0 END)
                         AS approved_node_count
                OPTIONAL MATCH (:KnowledgeNode)-[r]->(:KnowledgeNode)
                WHERE type(r) IN ['REQUIRES', 'RELATED', 'SUPPORTED_BY']
                RETURN node_count, approved_node_count,
                       count(r) AS edge_count,
                       sum(CASE WHEN r.review_status = 'APPROVED' THEN 1 ELSE 0 END)
                           AS approved_edge_count
                """
            ).single()
            source_count_record = self._run(
                session,
                "MATCH (source:GraphSource) RETURN count(source) AS source_count"
            ).single()
        metadata = dict(metadata_record["m"]) if metadata_record else {}
        return {
            "graph_version": str(metadata.get("version") or "unknown"),
            "course_id": self.course_id,
            "backend": "neo4j",
            "node_count": int(counts["node_count"] or 0),
            "edge_count": int(counts["edge_count"] or 0),
            "approved_node_count": int(counts["approved_node_count"] or 0),
            "approved_edge_count": int(counts["approved_edge_count"] or 0),
            "source_count": int(source_count_record["source_count"] or 0),
            "generated_at": normalize_generated_at(
                metadata.get("updated_at") or metadata.get("created_at")
            ),
        }

    def _graph_version(self) -> str:
        with self.driver.session() as session:
            metadata_record = self._run(
                session,
                "MATCH (m:GraphMetadata {key: 'current'}) RETURN m.version AS version",
            ).single()
        return str(metadata_record["version"] if metadata_record else "unknown")

    def rag_context(
        self,
        node_id: str,
        max_related: int = 8,
    ) -> dict[str, Any] | None:
        context = self.context(node_id, approved_only=True)
        if context is None:
            return None
        max_related = max(0, min(max_related, 20))
        with self.driver.session() as session:
            records = self._run(
                session,
                f"""
                MATCH (n:KnowledgeNode {{id: $id}})-[rel:RELATED]-(related:KnowledgeNode)
                WHERE {self._visibility('n')}
                  AND {self._visibility('rel')}
                  AND {self._visibility('related')}
                RETURN DISTINCT related
                ORDER BY related.id
                LIMIT $limit
                """,
                id=str(node_id),
                limit=max_related,
                allow_pending=False,
            )
            related = [normalize_node(dict(record["related"])) for record in records]
        context_node_ids = [
            item["id"]
            for item in [
                context["node"],
                *context["prerequisites"],
                *context["successors"],
                *related,
            ]
        ]
        sources = self._sources_for_node_ids(
            context_node_ids,
            allow_pending=False,
        )
        return build_rag_context(
            context["node"],
            context["prerequisites"],
            context["successors"],
            related,
            sources,
            self._graph_version(),
            course_id=self.course_id,
        )

    def course_root(self) -> str | None:
        with self.driver.session() as session:
            rows = list(self._run(session,
                "MATCH (n:KnowledgeNode) WHERE n.level = 0 AND n.node_type = 'course' "
                "AND n.review_status = 'APPROVED' RETURN n.id AS id ORDER BY id LIMIT 2"))
        if len(rows) > 1:
            raise GraphQueryBudgetExceeded("course graph has multiple approved roots")
        return str(rows[0]["id"]) if rows else None

    def subgraph(
        self,
        center_id: str,
        depth: int = 1,
        relations: set[str] | None = None,
    ) -> dict[str, Any] | None:
        depth = max(1, min(depth, 5))
        center = self.get_node(center_id)
        if center is None:
            return None
        relation_filter = (
            sorted(
                {relation.lower() for relation in relations}
                & ALLOWED_NODE_RELATIONS
            )
            if relations is not None
            else sorted(ALLOWED_NODE_RELATIONS)
        )
        node_limit = max(1, getattr(self, "subgraph_node_limit", 500))
        hop_limit = max(
            1,
            min(getattr(self, "subgraph_hop_limit", 200), node_limit),
        )
        edge_limit = max(1, getattr(self, "subgraph_edge_limit", 2000))
        selected_nodes = {str(center_id): center}
        frontier_ids = [str(center_id)]
        with self.driver.session() as session:
            if relation_filter != []:
                for _hop in range(depth):
                    remaining = node_limit - len(selected_nodes)
                    if not frontier_ids:
                        break
                    if remaining <= 0:
                        raise GraphQueryBudgetExceeded(
                            "graph subgraph node budget exhausted"
                        )
                    current_hop_limit = min(hop_limit, remaining)
                    per_frontier_limit = min(
                        hop_limit,
                        max(
                            1,
                            (current_hop_limit + len(frontier_ids) - 1)
                            // len(frontier_ids),
                        ),
                    )
                    records = self._run(
                        session,
                        f"""
                        UNWIND $frontier_ids AS frontier_id
                        CALL (frontier_id) {{
                          MATCH (source:KnowledgeNode {{id: frontier_id}})-
                                [rel]-(neighbor:KnowledgeNode)
                          WHERE NOT (neighbor.id IN $selected_ids)
                            AND toLower(type(rel)) IN $relations
                            AND {self._visibility('source')}
                            AND {self._visibility('neighbor')}
                            AND {self._visibility('rel')}
                          RETURN neighbor
                          ORDER BY neighbor.id
                          LIMIT $per_frontier_fetch_limit
                        }}
                        RETURN frontier_id, neighbor AS node
                        ORDER BY frontier_id, neighbor.id
                        LIMIT $layer_fetch_limit
                        """,
                        frontier_ids=frontier_ids,
                        selected_ids=sorted(selected_nodes),
                        relations=relation_filter,
                        allow_pending=self.include_pending,
                        per_frontier_fetch_limit=per_frontier_limit + 1,
                        layer_fetch_limit=current_hop_limit + 1,
                    )
                    next_nodes = self._bounded_layer_nodes(
                        records,
                        layer_limit=current_hop_limit,
                        per_frontier_limit=per_frontier_limit,
                        error_message="graph subgraph node budget exceeded",
                    )
                    frontier_ids = []
                    for item in next_nodes:
                        item_id = str(item["id"])
                        if item_id in selected_nodes:
                            continue
                        selected_nodes[item_id] = item
                        frontier_ids.append(item_id)

            selected_ids = sorted(selected_nodes)
            if relation_filter == [] or len(selected_ids) == 1:
                edges: list[dict[str, Any]] = []
            else:
                edge_records = self._run(
                    session,
                    f"""
                    MATCH (source:KnowledgeNode)-[rel]->(target:KnowledgeNode)
                    WHERE source.id IN $selected_ids
                      AND target.id IN $selected_ids
                      AND ($relations IS NULL OR toLower(type(rel)) IN $relations)
                      AND {self._visibility('source')}
                      AND {self._visibility('target')}
                      AND {self._visibility('rel')}
                    RETURN DISTINCT source.id AS source_id,
                           target.id AS target_id,
                           toLower(type(rel)) AS relation,
                           coalesce(rel.review_status, 'PENDING') AS review_status,
                           coalesce(rel.evidence, []) AS evidence,
                           properties(rel)['confidence'] AS confidence
                    ORDER BY source_id, target_id, relation
                    LIMIT $fetch_limit
                    """,
                    selected_ids=selected_ids,
                    relations=relation_filter,
                    allow_pending=self.include_pending,
                    fetch_limit=edge_limit + 1,
                )
                edges = [normalize_edge(dict(record)) for record in edge_records]
                if len(edges) > edge_limit:
                    raise GraphQueryBudgetExceeded(
                        "graph subgraph edge budget exceeded"
                    )
        return {
            "center_id": str(center_id),
            "course_id": self.course_id,
            "nodes": [selected_nodes[node_id] for node_id in sorted(selected_nodes)],
            "edges": edges,
            "graph_version": self._graph_version(),
        }
